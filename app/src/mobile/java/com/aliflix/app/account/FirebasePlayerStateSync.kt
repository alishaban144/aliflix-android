package com.aliflix.app.account

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Account-scoped, realtime settings and complete subtitle payloads, including offline writes. */
internal class FirebasePlayerStateSync(
    context: Context,
    private val account: AccountRepository,
    scope: CoroutineScope,
    suspendedUid: kotlinx.coroutines.flow.StateFlow<String?>,
    private val db: FirebaseFirestore,
    private val onError: (Throwable) -> Unit,
) {
    private val stores = mapOf(
        "playerPreferences" to context.getSharedPreferences("aliflix_player_settings", Context.MODE_PRIVATE),
        "subtitleChoices" to context.getSharedPreferences("native-subtitle-choice", Context.MODE_PRIVATE),
        "captionFiles" to context.getSharedPreferences("account-caption-files", Context.MODE_PRIVATE),
    )
    private val scopes = context.getSharedPreferences("player-account-scopes", Context.MODE_PRIVATE)
    private var applying = false

    init {
        scope.launch {
            kotlinx.coroutines.flow.combine(account.state.map { it.uid }.distinctUntilChanged(), suspendedUid) { uid, suspended -> uid to suspended }.collectLatest { (uid, suspended) ->
                switchScope(uid ?: "guest")
                if (uid != null && uid != suspended) sync(uid)
            }
        }
    }

    fun forget(uid: String) {
        if (scopes.getString("active", null) == uid) switchScope("guest")
        scopes.edit().also { editor -> scopes.all.keys.filter { it.startsWith("$uid/") }.forEach { editor.remove(it) } }.apply()
    }

    private fun encoded(value: Any): String = JSONObject().put("type", value.javaClass.simpleName).put("value", value).toString()

    private fun apply(store: SharedPreferences, key: String, raw: String) {
        val json = JSONObject(raw)
        val editor = store.edit()
        when (json.getString("type")) {
            "Float" -> editor.putFloat(key, json.getDouble("value").toFloat())
            "Integer" -> editor.putInt(key, json.getInt("value"))
            "Long" -> editor.putLong(key, json.getLong("value"))
            "Boolean" -> editor.putBoolean(key, json.getBoolean("value"))
            else -> editor.putString(key, json.getString("value"))
        }
        editor.apply()
    }

    private fun switchScope(next: String) {
        val previous = scopes.getString("active", "guest") ?: "guest"
        if (next == previous) return
        applying = true
        try {
            stores.forEach { (name, store) ->
                val saved = JSONObject()
                store.all.forEach { (key, value) -> if (value != null) saved.put(key, encoded(value)) }
                scopes.edit().putString("$previous/$name", saved.toString()).apply()
                val restored = scopes.getString("$next/$name", null)
                // Guest choices may seed a first sign-in, but another user's data never does.
                if (restored != null || previous != "guest") {
                    store.edit().clear().apply()
                    val json = JSONObject(restored ?: "{}")
                    json.keys().forEach { key -> apply(store, key, json.getString(key)) }
                }
            }
            scopes.edit().putString("active", next).apply()
        } finally { applying = false }
    }

    private suspend fun sync(uid: String) = coroutineScope {
        val changes = Channel<Pair<String, String>>(Channel.UNLIMITED)
        val listeners = mutableListOf<ListenerRegistration>()
        val callbacks = stores.map { (name, store) ->
            val callback = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (!applying && key != null) changes.trySend(name to key)
            }
            store.registerOnSharedPreferenceChangeListener(callback)
            store to callback
        }
        val user = db.collection("users").document(uid)
        fun timestampKey(name: String, key: String) = "$uid/$name/$key/time"
        suspend fun upload(name: String, key: String) {
            if (account.uid != uid) return
            val value = stores.getValue(name).all[key] ?: return
            val raw = encoded(value)
            val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw.toByteArray(Charsets.UTF_8)) } }.toByteArray()
            val chunks = Base64.encodeToString(bytes, Base64.NO_WRAP).chunked(180_000)
            val timestamp = maxOf(System.currentTimeMillis(), scopes.getLong(timestampKey(name, key), 0L) + 1L)
            scopes.edit().putLong(timestampKey(name, key), timestamp).apply()
            val batch = db.batch()
            chunks.forEachIndexed { index, part ->
                batch.set(user.collection(name).document("$key~$index"), mapOf(
                    "key" to key, "part" to index, "count" to chunks.size,
                    "payload" to part, "version" to timestamp,
                ))
            }
            // Firestore persists this write offline and retries it when connectivity returns.
            batch.commit().addOnFailureListener(onError)
        }
        try {
            stores.forEach { (name, store) ->
                listeners += user.collection(name).addSnapshotListener { snapshot, error ->
                    if (account.uid != uid) return@addSnapshotListener
                    if (error != null) { onError(error); return@addSnapshotListener }
                    if (snapshot == null || snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
                    snapshot.documents.groupBy { it.getString("key") }.forEach group@ { (key, docs) ->
                        if (key == null) return@group
                        val head = docs.firstOrNull { it.getLong("part") == 0L } ?: return@group
                        val version = head.getLong("version") ?: return@group
                        if (version <= scopes.getLong(timestampKey(name, key), 0L) && store.contains(key)) return@group
                        val count = head.getLong("count")?.toInt() ?: return@group
                        val parts = docs.filter { it.getLong("version") == version }.sortedBy { it.getLong("part") }
                        if (parts.size != count) return@group
                        runCatching {
                            val packed = parts.joinToString("") { it.getString("payload").orEmpty() }
                            val raw = GZIPInputStream(Base64.decode(packed, Base64.NO_WRAP).inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
                            applying = true
                            apply(store, key, raw)
                            scopes.edit().putLong(timestampKey(name, key), version).apply()
                        }.onFailure(onError)
                        applying = false
                    }
                    // Seed only absent cloud values after an authoritative server snapshot.
                    if (!snapshot.metadata.isFromCache) {
                        val remoteKeys = snapshot.documents.mapNotNull { it.getString("key") }.toSet()
                        store.all.keys.filterNot { it in remoteKeys }.forEach { changes.trySend(name to it) }
                    }
                }
            }
            for ((name, key) in changes) upload(name, key)
        } finally {
            callbacks.forEach { (store, callback) -> store.unregisterOnSharedPreferenceChangeListener(callback) }
            listeners.forEach { it.remove() }
            changes.close()
        }
    }
}
