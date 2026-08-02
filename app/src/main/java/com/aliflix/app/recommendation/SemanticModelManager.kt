package com.aliflix.app.recommendation

import android.content.Context
import androidx.core.content.edit
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

sealed interface SemanticModelState {
    data object Unavailable : SemanticModelState
    data class Downloading(val progressPercent: Int) : SemanticModelState
    data object Ready : SemanticModelState
    data object Corrupt : SemanticModelState
    data class Failed(val message: String) : SemanticModelState
}

data class SemanticCandidateDocument(
    val mediaKey: String,
    val metadataIdentity: String,
    val text: String,
)

fun interface SemanticBatchScorer {
    /** Calculates one query representation and scores a bounded candidate set. */
    suspend fun similarities(
        query: String,
        documents: List<SemanticCandidateDocument>,
    ): Map<String, Double>
}

interface SemanticModelManager {
    val state: StateFlow<SemanticModelState>
    val shouldOfferDownload: StateFlow<Boolean>

    fun download()
    fun cancelDownload()
    fun delete()
    fun dismissOffer()
    fun batchScorerOrNull(): SemanticBatchScorer?
    fun close()
}

/**
 * Owns ordering for model lifecycle transitions.
 *
 * A generation can be invalidated synchronously by a newer user action while
 * the older suspend operation is waiting on I/O. All observable publication is
 * committed through the same monitor, so a stale operation cannot win a race
 * between its final generation check and its state/session update.
 */
internal class SemanticLifecycleGate {
    private val monitor = Any()
    private var generation = 0L
    private var closed = false
    private val operationMutex = Mutex()

    fun snapshot(): Long = synchronized(monitor) { generation }

    fun nextGenerationIfOpen(
        canTransition: () -> Boolean = { true },
        onTransition: () -> Unit = {},
    ): Long? =
        synchronized(monitor) {
            if (closed || !canTransition()) return@synchronized null
            generation += 1L
            onTransition()
            generation
        }

    fun close(onTransition: () -> Unit = {}): Long? = synchronized(monitor) {
        if (closed) return@synchronized null
        closed = true
        generation += 1L
        onTransition()
        generation
    }

    fun isCurrent(token: Long): Boolean = synchronized(monitor) {
        !closed && generation == token
    }

    fun commit(token: Long, mutation: () -> Unit): Boolean =
        synchronized(monitor) {
            if (closed || generation != token) return@synchronized false
            mutation()
            true
        }

    suspend fun <T> serialized(operation: suspend () -> T): T =
        operationMutex.withLock { operation() }
}

/** Only called while running on the dedicated semantic execution context. */
internal interface SemanticEmbeddingSession {
    suspend fun similarities(
        query: String,
        documents: List<SemanticCandidateDocument>,
    ): Map<String, Double>

    fun close()
}

/**
 * A scorer may be retained by an already-running recommendation request.
 * Retirement is synchronous, while physical close waits for any in-flight use.
 */
internal class DedicatedSemanticBatchScorer(
    private val session: SemanticEmbeddingSession,
    private val execution: SemanticExecutionContext,
) : SemanticBatchScorer {
    private val useMutex = Mutex()
    private val retired = AtomicBoolean(false)
    private val sessionClosed = AtomicBoolean(false)

    fun retire() {
        retired.set(true)
    }

    suspend fun retireAndClose() {
        retire()
        if (sessionClosed.get()) return
        withContext(NonCancellable) {
            useMutex.withLock {
                if (!sessionClosed.get()) {
                    withContext(execution.dispatcher) {
                        session.close()
                    }
                    sessionClosed.set(true)
                }
            }
        }
    }

    override suspend fun similarities(
        query: String,
        documents: List<SemanticCandidateDocument>,
    ): Map<String, Double> {
        // This check deliberately happens before dispatcher submission. A
        // retained scorer therefore stays safe even after that dispatcher closes.
        if (retired.get()) return emptyMap()
        return useMutex.withLock {
            if (retired.get()) return@withLock emptyMap()
            withContext(execution.dispatcher) {
                currentCoroutineContext().ensureActive()
                session.similarities(query, documents.take(MAX_BATCH_DOCUMENTS))
            }
        }
    }
}

/** Synchronous embedding primitive; the owning session supplies serialization. */
internal interface SemanticEmbeddingBackend {
    fun embed(text: String): List<Float>
    fun close()
}

/**
 * Dedicated-context cache and scoring loop. Cancellation is checked around
 * every non-interruptible MediaPipe call and yielded in small bounded chunks.
 */
internal class CachedSemanticEmbeddingSession(
    private val backend: SemanticEmbeddingBackend,
) : SemanticEmbeddingSession {
    private val queryCache = object : LinkedHashMap<String, List<Float>>(
        QUERY_EMBEDDING_CACHE_LIMIT,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<Float>>?,
        ): Boolean = size > QUERY_EMBEDDING_CACHE_LIMIT
    }
    private val candidateCache = object : LinkedHashMap<String, List<Float>>(
        EMBEDDING_CACHE_LIMIT,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<Float>>?,
        ): Boolean = size > EMBEDDING_CACHE_LIMIT
    }
    private var closed = false

    override suspend fun similarities(
        query: String,
        documents: List<SemanticCandidateDocument>,
    ): Map<String, Double> {
        currentCoroutineContext().ensureActive()
        if (closed || query.isBlank() || documents.isEmpty()) return emptyMap()

        val normalizedQuery = normalizedEmbeddingText(query)
        val queryVector = queryCache[normalizedQuery]
            ?: embedding(normalizedQuery).also {
                queryCache[normalizedQuery] = it
            }
        if (queryVector.isEmpty()) return emptyMap()

        val distinctDocuments = LinkedHashMap<String, SemanticCandidateDocument>()
        documents.forEach { document ->
            if (
                document.mediaKey.isNotBlank() &&
                document.text.isNotBlank() &&
                !distinctDocuments.containsKey(document.mediaKey)
            ) {
                distinctDocuments[document.mediaKey] = document
            }
        }

        val result = LinkedHashMap<String, Double>(distinctDocuments.size)
        distinctDocuments.values.forEachIndexed { index, document ->
            currentCoroutineContext().ensureActive()
            val normalizedText = normalizedEmbeddingText(document.text)
            val identity = buildString {
                append(document.mediaKey)
                append('|')
                append(document.metadataIdentity)
                append('|')
                append(normalizedText.hashCode().toUInt().toString(16))
            }
            val candidateVector = candidateCache[identity]
                ?: embedding(normalizedText).also {
                    candidateCache[identity] = it
                }
            currentCoroutineContext().ensureActive()
            result[document.mediaKey] = cosineSimilarity(
                queryVector,
                candidateVector,
            )
            if ((index + 1) % CANCELLATION_CHUNK_SIZE == 0) yield()
        }
        return result
    }

    override fun close() {
        if (closed) return
        closed = true
        queryCache.clear()
        candidateCache.clear()
        backend.close()
    }

    private fun embedding(text: String): List<Float> {
        if (closed) return emptyList()
        val key = normalizedEmbeddingText(text)
        if (key.isBlank()) return emptyList()
        return backend.embed(key)
    }

    private fun normalizedEmbeddingText(text: String): String =
        text.trim().take(MAX_EMBEDDING_TEXT_LENGTH)

    private fun cosineSimilarity(
        left: List<Float>,
        right: List<Float>,
    ): Double {
        if (left.isEmpty() || left.size != right.size) return 0.0
        var dot = 0.0
        var leftMagnitude = 0.0
        var rightMagnitude = 0.0
        left.indices.forEach { index ->
            val a = left[index].toDouble()
            val b = right[index].toDouble()
            dot += a * b
            leftMagnitude += a * a
            rightMagnitude += b * b
        }
        val denominator = kotlin.math.sqrt(leftMagnitude) *
            kotlin.math.sqrt(rightMagnitude)
        return if (denominator <= 0.0) 0.0 else (dot / denominator)
            .coerceIn(-1.0, 1.0)
    }
}

private class MediaPipeEmbeddingBackend(
    private val embedder: TextEmbedder,
) : SemanticEmbeddingBackend {
    override fun embed(text: String): List<Float> = embedder.embed(text)
        .embeddingResult()
        .embeddings()
        .firstOrNull()
        ?.floatEmbedding()
        ?.toList()
        .orEmpty()

    override fun close() {
        embedder.close()
    }
}

class AndroidSemanticModelManager(
    context: Context,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher =
        RecommendationDispatchers.Default.io,
    private val semanticExecution: SemanticExecutionContext =
        DedicatedSemanticExecutionContext(),
) : SemanticModelManager {
    private val appContext = context.applicationContext
    private val preferences by lazy {
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private val modelDirectory = File(appContext.filesDir, MODEL_DIRECTORY)
    private val modelFile = File(modelDirectory, MODEL_FILE_NAME)
    private val partialFile = File(modelDirectory, "$MODEL_FILE_NAME.part")
    private val _state = MutableStateFlow<SemanticModelState>(
        SemanticModelState.Unavailable,
    )
    override val state: StateFlow<SemanticModelState> = _state.asStateFlow()

    private val _shouldOfferDownload = MutableStateFlow(false)
    override val shouldOfferDownload: StateFlow<Boolean> =
        _shouldOfferDownload.asStateFlow()

    private val lifecycle = SemanticLifecycleGate()
    private val operationMonitor = Any()
    private var initializationJob: Job? = null
    private var downloadJob: Job? = null
    @Volatile private var batchScorer: DedicatedSemanticBatchScorer? = null

    init {
        val token = lifecycle.snapshot()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            lifecycle.serialized {
                if (!lifecycle.isCurrent(token)) return@serialized
                val offerHandled = withContext(ioDispatcher) {
                    preferences.getBoolean(KEY_OFFER_HANDLED, false)
                }
                lifecycle.commit(token) {
                    _shouldOfferDownload.value = !offerHandled
                }
                installEmbedderIfValidLocked(
                    token = token,
                    markMissingAsUnavailable = true,
                )
            }
        }
        synchronized(operationMonitor) {
            initializationJob = job
        }
        job.invokeOnCompletion {
            synchronized(operationMonitor) {
                if (initializationJob === job) initializationJob = null
            }
        }
        job.start()
    }

    override fun download() {
        lateinit var job: Job
        val initializationToCancel: Job?
        synchronized(operationMonitor) {
            if (downloadJob?.isActive == true) {
                return
            }
            val token = lifecycle.nextGenerationIfOpen(
                canTransition = {
                    _state.value !is SemanticModelState.Ready
                },
                onTransition = {
                    _state.value = SemanticModelState.Downloading(0)
                },
            ) ?: return
            initializationToCancel = initializationJob
            job = scope.launch(start = CoroutineStart.LAZY) {
                performDownload(token)
            }
            downloadJob = job
        }
        initializationToCancel?.cancel()
        markOfferHandled()
        job.invokeOnCompletion {
            synchronized(operationMonitor) {
                if (downloadJob === job) downloadJob = null
            }
        }
        job.start()
    }

    override fun cancelDownload() {
        val operation: Pair<Long, Job> = synchronized(operationMonitor) {
            val active = downloadJob?.takeIf { it.isActive }
                ?: return
            val token = lifecycle.nextGenerationIfOpen {
                _state.value = SemanticModelState.Unavailable
            } ?: return
            downloadJob = null
            token to active
        }
        operation.second.cancel()
        scope.launch {
            lifecycle.serialized {
                if (!lifecycle.isCurrent(operation.first)) return@serialized
                withContext(ioDispatcher) { partialFile.delete() }
            }
        }
    }

    override fun delete() {
        lateinit var detached: DedicatedSemanticBatchScorer
        var hasDetached = false
        val jobs: Pair<Job?, Job?>
        val token = synchronized(operationMonitor) {
            val next = lifecycle.nextGenerationIfOpen {
                batchScorer?.let {
                    detached = it
                    hasDetached = true
                }
                batchScorer = null
                _state.value = SemanticModelState.Unavailable
            } ?: return
            jobs = initializationJob to downloadJob
            initializationJob = null
            downloadJob = null
            next
        }
        if (hasDetached) detached.retire()
        jobs.first?.cancel()
        jobs.second?.cancel()
        scope.launch {
            lifecycle.serialized {
                if (hasDetached) detached.retireAndClose()
                if (!lifecycle.isCurrent(token)) return@serialized
                withContext(ioDispatcher) {
                    partialFile.delete()
                    modelFile.delete()
                }
            }
        }
    }

    override fun dismissOffer() {
        markOfferHandled()
    }

    override fun batchScorerOrNull(): SemanticBatchScorer? = batchScorer

    override fun close() {
        lateinit var detached: DedicatedSemanticBatchScorer
        var hasDetached = false
        val jobs: Pair<Job?, Job?>
        synchronized(operationMonitor) {
            lifecycle.close {
                batchScorer?.let {
                    detached = it
                    hasDetached = true
                }
                batchScorer = null
            } ?: return
            jobs = initializationJob to downloadJob
            initializationJob = null
            downloadJob = null
        }
        if (hasDetached) detached.retire()
        jobs.first?.cancel()
        jobs.second?.cancel()

        // ViewModel scope cancellation can race onCleared(), so close uses a
        // small independent job and still performs all work off the main thread.
        CoroutineScope(SupervisorJob() + ioDispatcher).launch {
            try {
                lifecycle.serialized {
                    if (hasDetached) detached.retireAndClose()
                    withContext(ioDispatcher) { partialFile.delete() }
                }
            } finally {
                semanticExecution.close()
            }
        }
    }

    private fun markOfferHandled() {
        _shouldOfferDownload.value = false
        scope.launch(ioDispatcher) {
            preferences.edit { putBoolean(KEY_OFFER_HANDLED, true) }
        }
    }

    private suspend fun performDownload(token: Long) {
        lifecycle.serialized {
            if (!lifecycle.isCurrent(token)) return@serialized
            try {
                withContext(ioDispatcher) {
                    currentCoroutineContext().ensureActive()
                    modelDirectory.mkdirs()
                    partialFile.delete()
                    downloadVerifiedModel(token)
                    currentCoroutineContext().ensureActive()
                    val committed = lifecycle.commit(token) {
                        if (!partialFile.renameTo(modelFile)) {
                            partialFile.copyTo(modelFile, overwrite = true)
                            partialFile.delete()
                        }
                    }
                    if (!committed) return@withContext
                }
                if (lifecycle.isCurrent(token)) {
                    installEmbedderIfValidLocked(
                        token = token,
                        markMissingAsUnavailable = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                lifecycle.commit(token) {
                    _state.value = SemanticModelState.Unavailable
                }
                throw cancelled
            } catch (error: ModelIntegrityException) {
                withContext(NonCancellable + ioDispatcher) {
                    lifecycle.commit(token) {
                        partialFile.delete()
                        modelFile.delete()
                    }
                }
                lifecycle.commit(token) {
                    _state.value = SemanticModelState.Corrupt
                }
            } catch (error: Throwable) {
                lifecycle.commit(token) {
                    _state.value = SemanticModelState.Failed(
                        error.message?.take(120) ?: "Model download failed.",
                    )
                }
            } finally {
                withContext(NonCancellable + ioDispatcher) {
                    partialFile.delete()
                }
            }
        }
    }

    /** Called only while [SemanticLifecycleGate.serialized] is held. */
    private suspend fun installEmbedderIfValidLocked(
        token: Long,
        markMissingAsUnavailable: Boolean,
    ) {
        if (!lifecycle.isCurrent(token)) return
        val validation = withContext(ioDispatcher) {
            currentCoroutineContext().ensureActive()
            when {
                !modelFile.isFile -> ModelValidation.MISSING
                modelFile.hasExpectedIntegrity() -> ModelValidation.VALID
                else -> ModelValidation.INVALID
            }
        }
        when (validation) {
            ModelValidation.MISSING -> {
                if (markMissingAsUnavailable) {
                    lifecycle.commit(token) {
                        _state.value = SemanticModelState.Unavailable
                    }
                }
                return
            }
            ModelValidation.INVALID -> {
                withContext(ioDispatcher) {
                    lifecycle.commit(token) { modelFile.delete() }
                }
                lifecycle.commit(token) {
                    _state.value = SemanticModelState.Corrupt
                }
                return
            }
            ModelValidation.VALID -> Unit
        }

        // Creation itself is not cancellable. Running it in NonCancellable lets
        // us always receive and explicitly close the result when the generation
        // was invalidated while MediaPipe was initializing.
        val installedSession = withContext(
            NonCancellable + semanticExecution.dispatcher,
        ) {
            CachedSemanticEmbeddingSession(
                MediaPipeEmbeddingBackend(
                    TextEmbedder.createFromFile(appContext, modelFile),
                ),
            )
        }
        val installedScorer = DedicatedSemanticBatchScorer(
            installedSession,
            semanticExecution,
        )
        if (!lifecycle.isCurrent(token)) {
            installedScorer.retireAndClose()
            return
        }

        val previous = batchScorer
        previous?.retire()
        previous?.retireAndClose()
        val published = lifecycle.commit(token) {
            batchScorer = installedScorer
            _shouldOfferDownload.value = false
            _state.value = SemanticModelState.Ready
        }
        if (!published) installedScorer.retireAndClose()
    }

    private suspend fun downloadVerifiedModel(token: Long) {
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", MODEL_USER_AGENT)
        }
        try {
            currentCoroutineContext().ensureActive()
            val status = runInterruptible { connection.responseCode }
            if (status !in 200..299) {
                error("Model server returned HTTP $status.")
            }
            val advertisedSize = connection.contentLengthLong
            if (advertisedSize > 0L && advertisedSize != EXPECTED_SIZE_BYTES) {
                throw ModelIntegrityException()
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            runInterruptible { connection.inputStream }.use { input ->
                FileOutputStream(partialFile).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = runInterruptible { input.read(buffer) }
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        total += count
                        lifecycle.commit(token) {
                            _state.value = SemanticModelState.Downloading(
                                ((total * 100L) / EXPECTED_SIZE_BYTES)
                                    .coerceIn(0L, 99L)
                                    .toInt(),
                            )
                        }
                    }
                    runInterruptible { output.fd.sync() }
                }
            }
            currentCoroutineContext().ensureActive()
            val hash = digest.digest().joinToString("") { byte ->
                "%02x".format(byte)
            }
            if (total != EXPECTED_SIZE_BYTES || hash != EXPECTED_SHA256) {
                throw ModelIntegrityException()
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun File.hasExpectedIntegrity(): Boolean {
        if (length() != EXPECTED_SIZE_BYTES) return false
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = runInterruptible { input.read(buffer) }
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte)
        } == EXPECTED_SHA256
    }

    private class ModelIntegrityException : IllegalStateException()

    private enum class ModelValidation {
        MISSING,
        VALID,
        INVALID,
    }

    private companion object {
        const val PREFERENCES_NAME = "aliflix_semantic_model"
        const val KEY_OFFER_HANDLED = "download_offer_handled_v1"
        const val MODEL_DIRECTORY = "recommendation-models"
        const val MODEL_FILE_NAME = "universal_sentence_encoder_v1.tflite"
        const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/text_embedder/" +
                "universal_sentence_encoder/float32/latest/" +
                "universal_sentence_encoder.tflite"
        const val EXPECTED_SIZE_BYTES = 6_120_274L
        const val EXPECTED_SHA256 =
            "89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749"
        const val MODEL_USER_AGENT = "Aliflix-Android/2.7.17"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 45_000
        const val DOWNLOAD_BUFFER_SIZE = 32 * 1024
    }
}

private const val EMBEDDING_CACHE_LIMIT = 512
private const val QUERY_EMBEDDING_CACHE_LIMIT = 24
private const val MAX_BATCH_DOCUMENTS = 160
private const val MAX_EMBEDDING_TEXT_LENGTH = 1_500
private const val CANCELLATION_CHUNK_SIZE = 4
