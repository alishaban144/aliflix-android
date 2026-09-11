package com.aliflix.app.player

import android.content.Context
import androidx.media3.common.*
import androidx.media3.datasource.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.audio.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.aliflix.app.BuildConfig
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class TimedSpeechWord(val text: String, val seconds: Double, val sample: Int)

internal fun subtitleMediaSeconds(presentationTimeUs: Long, streamOffsetUs: Long, frame: Int, sampleRate: Int): Double =
    (presentationTimeUs - streamOffsetUs) / 1e6 + frame / sampleRate.toDouble()

/** Decode the selected stream, never microphone audio. PTS ties every sample to the media timeline. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun subtitleSpeechSample(context: Context, request: NativePlaybackRequest, start: Double, sample: Int): List<TimedSpeechWord> {
    val captured = withContext(Dispatchers.Main.immediate) {
        val result = CompletableDeferred<Pair<Double, ByteArray>>()
        val renderer = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
                object : ForwardingAudioSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(false).build()) {
                    private var format = Format.Builder().build()
                    private var lastPts = Long.MIN_VALUE
                    private var streamOffsetUs = 0L
                    private var first = Double.NaN
                    private var frames = 0L
                    private val pcm = ByteArrayOutputStream()
                    override fun configure(config: AudioSink.AudioSinkConfig) { format = config.format; super.configure(config) }
                    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
                        streamOffsetUs = outputStreamOffsetUs
                        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
                    }
                    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                        if (!result.isCompleted && presentationTimeUs != lastPts && format.pcmEncoding == C.ENCODING_PCM_16BIT && format.channelCount > 0 && format.sampleRate > 0) {
                            lastPts = presentationTimeUs
                            val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            var index = 0
                            while (input.remaining() >= format.channelCount * 2 && pcm.size() < 576000) {
                                var sum = 0
                                repeat(format.channelCount) { sum += input.short.toInt() }
                                val time = subtitleMediaSeconds(presentationTimeUs, streamOffsetUs, index++, format.sampleRate)
                                if (time < start) continue
                                if (first.isNaN()) first = time
                                if (kotlin.math.abs(time - (first + frames / format.sampleRate.toDouble())) > .08) {
                                    result.completeExceptionally(IllegalStateException("Audio timeline discontinuity")); break
                                }
                                val before = frames * 16000 / format.sampleRate
                                frames++
                                if (frames * 16000 / format.sampleRate > before) {
                                    val mono = sum / format.channelCount
                                    pcm.write(mono and 255); pcm.write((mono shr 8) and 255)
                                }
                            }
                            if (pcm.size() >= 576000) result.complete(first to pcm.toByteArray())
                        }
                        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                    }
                }
        }
        val origin = URI(request.referer).let { "${it.scheme}://${it.rawAuthority}" }
        val http = DefaultHttpDataSource.Factory().setUserAgent(request.userAgent)
            .setDefaultRequestProperties(mapOf("Referer" to request.referer, "Origin" to origin))
        val scoped = ResolvingDataSource.Factory(http) { spec ->
            if (request.cookie.isNotBlank() && spec.uri.host == android.net.Uri.parse(request.url).host)
                spec.withRequestHeaders(spec.httpRequestHeaders + ("Cookie" to request.cookie)) else spec
        }
        val player = ExoPlayer.Builder(context, renderer)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, scoped))).build()
        try {
            player.volume = 0f
            player.setAudioAttributes(AudioAttributes.DEFAULT, false)
            // Capture is before the sink's time-stretch processor, so ASR receives original PCM.
            player.setPlaybackSpeed(3f)
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { result.completeExceptionally(error) }
            })
            player.setMediaItem(MediaItem.Builder().setUri(request.url).setMimeType(request.mimeType).build(), (start * 1000).toLong())
            player.prepare(); player.play()
            withTimeout(55000) { result.await() }
        } finally { player.release() }
    }
    return withContext(Dispatchers.IO) {
        val pcm = captured.second
        val wav = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
        val connection = URL(BuildConfig.RECOMMENDATION_AI_BASE_URL.trimEnd('/') + "/v3/subtitles/audio-timing").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = 10000; connection.readTimeout = 30000
            connection.setRequestProperty("Content-Type", "audio/wav")
            connection.setFixedLengthStreamingMode(wav.size)
            connection.outputStream.use { it.write(wav) }
            check(connection.responseCode == 200) { "Audio timing unavailable" }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 65536)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(bytes.size <= 65536)
            val words = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("words")
            (0 until words.length()).map { i -> words.getJSONObject(i).let { TimedSpeechWord(it.getString("text"), captured.first + it.getDouble("start"), sample) } }
        } finally { connection.disconnect() }
    }
}
