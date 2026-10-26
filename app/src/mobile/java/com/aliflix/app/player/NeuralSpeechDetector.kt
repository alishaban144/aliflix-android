package com.aliflix.app.player

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/** Silero's 8 kHz recurrent contract: 256 new samples, 32 preceding samples,
 * and two 128-value recurrent states. Context is audio, not zero padding.
 * ONNX allocations and inference never run on the audible renderer thread.
 */
internal class SileroSpeechSession(model: ByteArray) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1)
        environment.createSession(model, options)
    }
    private var state = FloatArray(256)
    private val context = FloatArray(32)
    private var quietChunks = 0
    fun reset() { state.fill(0f); context.fill(0f); quietChunks = 0 }
    fun probability(samples: FloatArray): Float {
        require(samples.size == 256)
        // Continuous films can retain a previous scene's recurrent bias. A full
        // second of confidently quiet audio provides a measured safe boundary;
        // ordinary pauses and low-confidence speech retain recurrent history.
        if (quietChunks >= 32) reset()
        val input = FloatArray(288)
        context.copyInto(input); samples.copyInto(input, 32)
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 288)).use { audio ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { memory ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(8000)), longArrayOf()).use { rate ->
                    session.run(mapOf("input" to audio, "state" to memory, "sr" to rate)).use { output ->
                        val probability = (output[0] as OnnxTensor).floatBuffer.get(0)
                        (output[1] as OnnxTensor).floatBuffer.get(state)
                        samples.copyInto(context, 0, 224, 256)
                        quietChunks = if (probability < .1f) quietChunks + 1 else 0
                        return probability.coerceIn(0f, 1f)
                    }
                }
            }
        }
    }
    override fun close() = session.close()
}

/** Bounded transient PCM queue (40 KiB). Overflow leaves evidence unknown;
 * it never blocks playback, substitutes silence, or reuses recurrent history.
 */
internal class NeuralSpeechWorker(context: Context,
    private val accept: (Double, Float, Long, Long) -> Unit,
) : AutoCloseable {
    private data class Frame(val pcm: ShortArray, val start: Double, val generation: Long, val boundary: Long)
    private val queue = ArrayBlockingQueue<Frame>(128)
    private val submitted = AtomicLong()
    private val processed = AtomicLong()
    @Volatile private var running = true
    @Volatile var ready = false
        private set
    @Volatile var failure: String? = null
        private set
    @Volatile var dropped = 0L
        private set
    @Volatile private var inferenceNanos = 0L
    @Volatile private var decisions = 0L
    private val appContext = context.applicationContext
    private val thread = Thread({ run() }, "aliflix-speech-vad").apply { isDaemon = true; start() }

    fun offer(pcm: ShortArray, start: Double, generation: Long, boundary: Long) {
        if (!running || !ready) return
        if (queue.offer(Frame(pcm.copyOf(), start, generation, boundary))) submitted.incrementAndGet() else dropped++
    }
    // Only the independent cache decoder uses backpressure. Never call while
    // holding the capture monitor: the worker needs it to commit decisions.
    fun awaitCapacity() { while (running && ready && queue.size > 64) Thread.sleep(2) }
    fun awaitReady() {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (running && !ready && System.nanoTime() < deadline) Thread.sleep(5)
    }
    fun awaitIdle(timeoutMs: Long = 3000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (running && processed.get() < submitted.get() && System.nanoTime() < deadline) Thread.sleep(2)
    }
    fun diagnostics() = "sileroReady=$ready,sileroFailure=$failure,queue=${queue.size},dropped=$dropped," +
        "inferences=$decisions,inferenceMs=${inferenceNanos / 1_000_000}"

    private fun run() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val model = appContext.assets.open("silero-vad-8k.onnx").use { it.readBytes() }
            SileroSpeechSession(model).use { detector ->
                ready = true
                val samples = FloatArray(256)
                var count = 0; var start = 0.0; var expected = Double.NaN
                var generation = -1L; var boundary = -1L
                while (running) {
                    val frame = queue.take()
                    try {
                        if (frame.generation != generation || frame.boundary != boundary ||
                            !expected.isFinite() || kotlin.math.abs(frame.start - expected) > .003) {
                            detector.reset(); count = 0
                            generation = frame.generation; boundary = frame.boundary
                        }
                        frame.pcm.forEachIndexed { index, value ->
                            if (count == 0) start = frame.start + index / 8000.0
                            samples[count++] = value / 32768f
                            if (count == samples.size) {
                                val began = System.nanoTime()
                                val probability = detector.probability(samples)
                                inferenceNanos += System.nanoTime() - began; decisions++
                                accept(start, probability, generation, boundary)
                                count = 0
                            }
                        }
                        expected = frame.start + .02
                    } finally { processed.incrementAndGet() }
                }
            }
        } catch (_: InterruptedException) {
            // Normal service teardown.
        } catch (error: Exception) { failure = error.javaClass.simpleName }
        catch (error: LinkageError) { failure = error.javaClass.simpleName }
        finally { ready = false; running = false; queue.clear() }
    }
    override fun close() { running = false; thread.interrupt() }
}
