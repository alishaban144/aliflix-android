package com.aliflix.app.party

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import com.aliflix.app.BuildConfig
import com.aliflix.app.model.*
import com.aliflix.app.model.MediaType
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

internal data class PartyState(val room: String = "", val invite: String = "", val self: String = "", val phase: String = "", val data: JSONObject? = null, val error: String? = null) {
    val host get() = self.isNotBlank() && data?.optString("host") == self
    val active get() = room.isNotBlank() && data?.optBoolean("ended") != true
    val content get() = data?.optJSONObject("content")
    val members get() = data?.optJSONArray("members")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    fun can(action: String) = host || members.firstOrNull { it.optString("id") == self }?.optJSONObject("rights")?.optBoolean(action) == true
    val label get() = error ?: phase.replaceFirstChar { it.uppercase() }.replace('-', ' ')
}

/** Uses elapsedRealtime locally; wall-clock changes on a phone cannot move the room clock. */
internal class PartyClock {
    private val samples = ArrayDeque<Pair<Double, Double>>()
    var offset = 0.0; private set
    val ready get() = samples.isNotEmpty()
    fun sample(sent: Double, received: Double, server: Double) {
        val rtt = received - sent
        if (!rtt.isFinite() || rtt !in 0.0..5000.0 || !server.isFinite()) return
        samples.addLast(rtt to (server - (sent + received) / 2))
        while (samples.size > 12) samples.removeFirst()
        val best = samples.sortedBy { it.first }.take(5).map { it.second }.sorted()
        offset = best[best.size / 2]
    }
    fun serverNow(local: Long = SystemClock.elapsedRealtime()) = local + offset
    fun reset() { samples.clear(); offset = 0.0 }
}

internal object PartyProtocol {
    fun content(selection: PlaybackSelection) = JSONObject().put("id", selection.media.id).put("type", selection.media.type.routeName)
        .put("season", selection.seasonNumber ?: 1).put("episode", selection.episodeNumber ?: 1)
        .put("title", selection.media.title).put("poster", selection.media.posterPath.orEmpty())
        .put("language", selection.media.originalLanguage).put("year", selection.media.year).put("imdbId", selection.media.imdbId.orEmpty()).put("genres", org.json.JSONArray(selection.media.genres.take(12)))
    fun matches(content: JSONObject?, selection: PlaybackSelection?) = content != null && selection != null &&
        content.optInt("id") == selection.media.id && content.optString("type") == selection.media.type.routeName &&
        (selection.media.type == MediaType.MOVIE || (content.optInt("season") == (selection.seasonNumber ?: 1) && content.optInt("episode") == (selection.episodeNumber ?: 1)))
    fun selection(content: JSONObject, preferences: PlaybackPreferences): PlaybackSelection {
        val media = Media(content.getInt("id"), MediaType.from(content.getString("type")), content.getString("title"),
            imdbId = content.optString("imdbId").takeIf { it.isNotBlank() }, genres = content.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            posterPath = content.optString("poster").takeIf { it.startsWith('/') }, originalLanguage = content.optString("language"), year = content.optString("year"))
        return PlaybackSelection(media, content.optInt("season", 1), content.optInt("episode", 1), source = preferences.sourceFor(media))
    }
    fun target(data: JSONObject, serverNow: Double): Long = (data.optDouble("position") +
        if (data.optBoolean("playing")) (serverNow - data.optDouble("at")).coerceAtLeast(0.0) else 0.0).toLong().coerceIn(0, 604800000)
    fun correction(drift: Long, sinceSeek: Long): Float = when {
        abs(drift) <= 250 -> 1f
        abs(drift) > 1800 && sinceSeek >= 6000 -> 0f
        drift > 0 -> 1.03f
        else -> .97f
    }
}

internal class WatchPartyClient private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("watch-party", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS).build()
    private val _state = MutableStateFlow(PartyState())
    val state: StateFlow<PartyState> = _state.asStateFlow()
    val clock = PartyClock()
    private val identityLock = Mutex()
    private var navigation = 0L
    private var token = prefs.getString("token", "").orEmpty()
    private var socket: WebSocket? = null
    private var connection = 0L
    private var sequence = 0L
    private var reconnect: Job? = null
    private var ping: Job? = null
    private var name = "Guest"
    private var pendingControl: JSONObject? = null
    private var pendingRetries = 0
    var pendingUntil = 0L; private set
    private var lastVersion = -1L
    private var attempt = 0
    init {
        val room = prefs.getString("room", "").orEmpty()
        val invite = prefs.getString("invite", "").orEmpty()
        if (room.matches(Regex("[a-f0-9]{32}"))) scope.launch { join(room, invite) }
    }
    private suspend fun identity() = identityLock.withLock { withContext(Dispatchers.IO) {
        val hasRoom = !prefs.getString("room", "").isNullOrBlank()
        val lifetime = if (!hasRoom) 9 * 3600000L else 60000L
        val user = FirebaseAuth.getInstance().currentUser
        val owner = user?.uid ?: "guest"
        val ownerChanged = !hasRoom && prefs.getString("identity-owner", "guest") != owner
        if (prefs.getLong("expires", 0) < System.currentTimeMillis() + lifetime || token.isBlank() || ownerChanged) {
            val input = JSONObject()
            user?.getIdToken(false)?.await()?.token?.let { input.put("idToken", it) }
            val result = post("/v1/party/identity", input, authenticated = false)
            token = result.getString("token")
            prefs.edit().putString("token", token).putString("identity-owner", owner).putLong("expires", result.getLong("expires")).apply()
        }
        // Signed payload is used only as a display identity here; the backend verifies the signature.
        JSONObject(String(android.util.Base64.decode(token.substringBefore('.'), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING))).getString("sub")
    } }
    private fun post(path: String, body: JSONObject, authenticated: Boolean = true): JSONObject {
        val request = Request.Builder().url(BASE + path).post(body.toString().toRequestBody("application/json".toMediaType()))
        if (authenticated) request.header("Authorization", "Bearer $token")
        return http.newCall(request.build()).execute().use { response ->
            val text = response.body?.source()?.let { source -> source.request(65537); source.buffer.readUtf8() }.orEmpty()
            require(text.length <= 65536 && response.isSuccessful) { if (response.code == 429) "Try again shortly" else "Unable to connect" }
            JSONObject(text)
        }
    }
    suspend fun create(selection: PlaybackSelection, position: Long = 0) {
        leave()
        val ticket = ++navigation
        _state.value = PartyState(phase = "creating")
        try {
            val self = identity(); if (ticket != navigation) return; name = displayName()
            val response = withContext(Dispatchers.IO) { post("/v1/party/rooms", JSONObject().put("content", PartyProtocol.content(selection)).put("name", name).put("position", position.coerceAtLeast(0))) }
            if (ticket != navigation) return
            join(response.getString("room"), response.getString("invite"), self)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (ticket == navigation) _state.value = PartyState(phase = "failed", error = "Couldn’t create room") }
    }
    suspend fun join(room: String, invite: String, knownSelf: String? = null) {
        if (!room.matches(Regex("[a-f0-9]{32}")) || !invite.matches(Regex("[A-Za-z0-9_-]{43}"))) {
            ++navigation
            _state.value = if (_state.value.active) _state.value.copy(error = "Invalid invitation") else PartyState(phase = "expired-invitation", error = "Invalid invitation")
            return
        }
        val previousRoom = _state.value.room.ifBlank { prefs.getString("room", "").orEmpty() }
        if (previousRoom.isNotBlank() && previousRoom != room) leave()
        val ticket = ++navigation
        try {
            val self = knownSelf ?: identity(); if (ticket != navigation) return; name = displayName()
            _state.value = PartyState(room, invite, self, "joining")
            prefs.edit().putString("room", room).putString("invite", invite).apply()
            lastVersion = -1; sequence = 0; attempt = 0
            open()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (ticket == navigation) _state.value = PartyState(phase = "failed", error = "Couldn’t join room") }
    }
    private fun displayName() = FirebaseAuth.getInstance().currentUser?.displayName?.take(32)?.takeIf { it.isNotBlank() } ?: "Guest"
    private fun open() {
        reconnect?.cancel(); ping?.cancel(); socket?.cancel()
        pendingControl = null; pendingUntil = 0
        val generation = ++connection; val current = _state.value
        clock.reset()
        val request = Request.Builder().url(BASE.replace("https://", "wss://") + "/v1/party/rooms/${current.room}/socket")
            .header("Authorization", "Bearer $token").header("X-Party-Invite", current.invite)
            .header("X-Party-Name", URLEncoder.encode(name, "UTF-8").replace("+", "%20")).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { scope.launch {
                if (generation != connection) return@launch
                attempt = 0; _state.value = _state.value.copy(phase = "waiting", error = null)
                ping = scope.launch { repeat(4) { send(JSONObject().put("type", "ping").put("sent", SystemClock.elapsedRealtime())); delay(250) }
                    while (isActive) { send(JSONObject().put("type", "ping").put("sent", SystemClock.elapsedRealtime())); delay(5000) } }
            } }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 65536) { webSocket.close(1009, "Too large"); return }
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                val received = SystemClock.elapsedRealtime()
                scope.launch { if (generation == connection) receive(json, received) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { scope.launch {
                if (generation != connection) return@launch
                if (response?.code in listOf(400, 401, 403, 409, 410)) { ended(if (response?.code == 409) "Room full" else "Invitation expired"); return@launch }
                retry(generation)
            } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { scope.launch {
                if (generation != connection) return@launch
                if (code == 4004) ended("Room ended") else retry(generation)
            } }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        })
    }
    private fun receive(json: JSONObject, received: Long) {
        when (json.optString("type")) {
            "pong" -> clock.sample(json.optDouble("sent"), received.toDouble(), json.optDouble("serverTime"))
            "state" -> {
                if (json.optInt("protocol") != 1 || json.optLong("version") <= lastVersion) return
                lastVersion = json.getLong("version")
                val mine = json.optJSONArray("members")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("id") == _state.value.self } }
                sequence = maxOf(sequence, mine?.optLong("sequence") ?: 0)
                _state.value = _state.value.copy(data = json, phase = if (json.optBoolean("ended")) "room-ended" else if (json.optBoolean("playing")) "playing" else "paused", error = null)
                val pending = pendingControl
                if (pending != null) {
                    if ((mine?.optLong("sequence") ?: 0) >= pending.optLong("sequence")) {
                        pendingControl = null; pendingUntil = 0
                    } else if (pending.optLong("version") != json.optLong("playbackVersion") && pendingRetries++ < 2 && _state.value.can(pending.optString("action"))) {
                        pending.put("version", json.optLong("playbackVersion")).put("sequence", ++sequence)
                        send(pending); pendingUntil = SystemClock.elapsedRealtime() + 1500
                    }
                }
                if (json.optBoolean("ended")) ended("Room ended")
            }
            "invite" -> { val value = json.optString("invite"); if (value.matches(Regex("[A-Za-z0-9_-]{43}"))) { _state.value = _state.value.copy(invite = value); prefs.edit().putString("invite", value).apply() } }
            "error" -> { pendingControl = null; pendingUntil = 0; _state.value = _state.value.copy(error = if (json.optString("error") == "PERMISSION_DENIED") "Host controls playback" else "Unable to apply action") }
        }
    }
    private fun retry(generation: Long) {
        ping?.cancel(); pendingControl = null; pendingUntil = 0; _state.value = _state.value.copy(phase = "reconnecting")
        reconnect?.cancel(); reconnect = scope.launch { delay((1000L shl attempt++.coerceAtMost(4)) + kotlin.random.Random.nextLong(250)); if (generation == connection && _state.value.active) open() }
    }
    private fun send(json: JSONObject): Boolean = socket?.send(json.toString()) == true
    fun command(type: String, configure: JSONObject.() -> Unit = {}) { send(JSONObject().put("type", type).apply(configure)) }
    fun control(action: String, position: Long) {
        if (!_state.value.can(action)) { _state.value = _state.value.copy(error = "Host controls playback"); return }
        val event = JSONObject().put("type", "control").put("action", action).put("position", position.coerceIn(0, 604800000)).put("sequence", ++sequence).put("version", _state.value.data?.optLong("playbackVersion") ?: 0)
        pendingControl = event; pendingRetries = 0; pendingUntil = SystemClock.elapsedRealtime() + 1500
        if (!send(event)) { pendingControl = null; pendingUntil = 0 }
    }
    fun status(value: String) { command("status") { put("status", value); put("sequence", ++sequence) } }
    fun episode(selection: PlaybackSelection): Boolean {
        if (!_state.value.host) { _state.value = _state.value.copy(error = "Only the host can change episodes"); return false }
        command("episode") { put("content", PartyProtocol.content(selection)); put("sequence", ++sequence); put("version", _state.value.data?.optLong("playbackVersion") ?: 0) }; return true
    }
    fun reconnectNow() { if (_state.value.active) { _state.value = _state.value.copy(phase = "reconnecting"); open() } }
    fun link() = BASE + "/party/${_state.value.room}#${_state.value.invite}"
    fun leave(end: Boolean = false) {
        ++navigation; pendingControl = null; pendingUntil = 0
        command(if (end) "end" else "leave"); ++connection; reconnect?.cancel(); ping?.cancel(); socket?.close(1000, "Left"); socket = null
        prefs.edit().remove("room").remove("invite").apply(); _state.value = PartyState()
    }
    private fun ended(message: String) { ++connection; reconnect?.cancel(); ping?.cancel(); socket?.close(1000, "Ended"); socket = null
        prefs.edit().remove("room").remove("invite").apply(); _state.value = _state.value.copy(room = "", phase = "room-ended", error = message) }
    companion object {
        const val BASE = BuildConfig.RECOMMENDATION_AI_BASE_URL
        @Volatile private var instance: WatchPartyClient? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: WatchPartyClient(context).also { instance = it } }
    }
}

/** Service-owned listener observes notification, headset, MediaSession and Compose controls alike. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PartyPlaybackBridge(private val player: Player, private val client: WatchPartyClient,
    private val scope: CoroutineScope, private val selection: () -> PlaybackSelection?) : AutoCloseable {
    private var applying = false
    private var wasActive = false
    private var oldSpeed = 1f
    private var lastSeek = -10000L
    private var aligned = false
    private var lastStatus = ""
    private var lastContent = ""
    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (applying || !usable() || player.playbackState != Player.STATE_READY) return
            val action = if (playWhenReady) "play" else "pause"
            client.control(action, player.currentPosition)
            if (!client.state.value.can(action)) apply()
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK || applying || !usable()) return
            client.control("seek", newPosition.positionMs)
            if (!client.state.value.can("seek")) { aligned = false; apply() }
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!usable()) return
            if (playbackState == Player.STATE_ENDED && client.state.value.host) client.control("pause", player.currentPosition)
        }
    }
    private val job = scope.launch { while (isActive) { apply(); delay(500) } }
    init { player.addListener(listener) }
    private fun usable() = client.state.value.active && PartyProtocol.matches(client.state.value.content, selection())
    private fun apply() {
        val state = client.state.value
        if (!state.active) { if (wasActive) { applying = true; player.playbackParameters = PlaybackParameters(oldSpeed); applying = false }; wasActive = false; aligned = false; return }
        if (!wasActive) { oldSpeed = player.playbackParameters.speed; wasActive = true; aligned = false }
        if (!usable()) return
        val content = state.content.toString()
        if (content != lastContent) { lastContent = content; aligned = false }
        val remoteCast = player.deviceInfo.playbackType == androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE
        val status = when {
            remoteCast || player.playerError != null -> "source-unavailable"
            player.playbackState == Player.STATE_BUFFERING -> "buffering"
            player.playbackState == Player.STATE_READY && client.clock.ready && state.data?.let { abs(PartyProtocol.target(it, client.clock.serverNow()) - player.currentPosition) > 1800 } == true -> "desynchronized"
            player.playbackState == Player.STATE_READY -> if (player.isPlaying) "playing" else "ready"
            else -> "waiting"
        }
        if (status != lastStatus) { lastStatus = status; client.status(status)
            if (state.host && status == "source-unavailable") client.control("pause", player.currentPosition) }
        applying = true
        try {
            if (remoteCast || state.phase == "reconnecting") { player.pause(); return }
            if (!client.clock.ready || player.playbackState != Player.STATE_READY || SystemClock.elapsedRealtime() < client.pendingUntil) return
            val data = state.data ?: return
            val now = SystemClock.elapsedRealtime()
            val target = PartyProtocol.target(data, client.clock.serverNow()).let { if (player.duration > 0) it.coerceAtMost(player.duration) else it }
            val drift = target - player.currentPosition
            val correction = if (!aligned || (!data.optBoolean("playing") && abs(drift) > 250 && now - lastSeek >= 6000)) 0f else PartyProtocol.correction(drift, now - lastSeek)
            if (correction == 0f && abs(drift) > 250) { player.seekTo(target); lastSeek = now }
            player.playbackParameters = PlaybackParameters(if (data.optBoolean("playing") && correction != 0f) correction else 1f)
            player.playWhenReady = data.optBoolean("playing")
            aligned = true
        } finally { applying = false }
    }
    override fun close() { job.cancel(); player.removeListener(listener) }
}
