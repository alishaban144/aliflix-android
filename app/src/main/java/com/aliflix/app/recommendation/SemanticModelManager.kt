package com.aliflix.app.recommendation

import android.content.Context
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SemanticModelState {
    data object Unavailable : SemanticModelState
    data class Downloading(val progressPercent: Int) : SemanticModelState
    data object Ready : SemanticModelState
    data object Corrupt : SemanticModelState
    data class Failed(val message: String) : SemanticModelState
}

interface SemanticModelManager {
    val state: StateFlow<SemanticModelState>
    val shouldOfferDownload: StateFlow<Boolean>

    fun download()
    fun cancelDownload()
    fun delete()
    fun dismissOffer()
    fun scorerOrNull(): SemanticTextScorer?
}

class AndroidSemanticModelManager(
    context: Context,
    private val scope: CoroutineScope,
) : SemanticModelManager {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val modelDirectory = File(appContext.filesDir, MODEL_DIRECTORY)
    private val modelFile = File(modelDirectory, MODEL_FILE_NAME)
    private val partialFile = File(modelDirectory, "$MODEL_FILE_NAME.part")
    private val _state = MutableStateFlow<SemanticModelState>(
        if (modelFile.isFile && modelFile.length() == EXPECTED_SIZE_BYTES) {
            SemanticModelState.Ready
        } else {
            SemanticModelState.Unavailable
        },
    )
    override val state: StateFlow<SemanticModelState> = _state.asStateFlow()

    private val _shouldOfferDownload = MutableStateFlow(
        !preferences.getBoolean(KEY_OFFER_HANDLED, false) &&
            _state.value !is SemanticModelState.Ready,
    )
    override val shouldOfferDownload: StateFlow<Boolean> =
        _shouldOfferDownload.asStateFlow()

    private var downloadJob: Job? = null
    private var embedder: TextEmbedder? = null
    private var scorer: SemanticTextScorer? = null

    init {
        if (_state.value is SemanticModelState.Ready) {
            scope.launch(Dispatchers.Default) {
                installEmbedderIfValid()
            }
        }
    }

    override fun download() {
        if (downloadJob?.isActive == true || _state.value is SemanticModelState.Ready) {
            return
        }
        markOfferHandled()
        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                modelDirectory.mkdirs()
                partialFile.delete()
                downloadVerifiedModel()
                if (!partialFile.renameTo(modelFile)) {
                    partialFile.copyTo(modelFile, overwrite = true)
                    partialFile.delete()
                }
                installEmbedderIfValid()
            } catch (cancelled: CancellationException) {
                partialFile.delete()
                _state.value = SemanticModelState.Unavailable
                throw cancelled
            } catch (error: ModelIntegrityException) {
                partialFile.delete()
                modelFile.delete()
                _state.value = SemanticModelState.Corrupt
            } catch (error: Throwable) {
                partialFile.delete()
                _state.value = SemanticModelState.Failed(
                    error.message?.take(120) ?: "Model download failed.",
                )
            }
        }
    }

    override fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    override fun delete() {
        cancelDownload()
        synchronized(this) {
            embedder?.close()
            embedder = null
            scorer = null
        }
        partialFile.delete()
        modelFile.delete()
        _state.value = SemanticModelState.Unavailable
    }

    override fun dismissOffer() {
        markOfferHandled()
    }

    override fun scorerOrNull(): SemanticTextScorer? = synchronized(this) {
        scorer
    }

    private fun markOfferHandled() {
        preferences.edit().putBoolean(KEY_OFFER_HANDLED, true).apply()
        _shouldOfferDownload.value = false
    }

    private suspend fun installEmbedderIfValid() = withContext(Dispatchers.Default) {
        if (!modelFile.isFile || !modelFile.hasExpectedIntegrity()) {
            modelFile.delete()
            _state.value = SemanticModelState.Corrupt
            return@withContext
        }
        val installed = TextEmbedder.createFromFile(appContext, modelFile)
        synchronized(this@AndroidSemanticModelManager) {
            embedder?.close()
            embedder = installed
            scorer = MediaPipeSemanticScorer(installed)
        }
        _state.value = SemanticModelState.Ready
    }

    private fun downloadVerifiedModel() {
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", MODEL_USER_AGENT)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                error("Model server returned HTTP $status.")
            }
            val advertisedSize = connection.contentLengthLong
            if (advertisedSize > 0L && advertisedSize != EXPECTED_SIZE_BYTES) {
                throw ModelIntegrityException()
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partialFile).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        total += count
                        _state.value = SemanticModelState.Downloading(
                            ((total * 100L) / EXPECTED_SIZE_BYTES)
                                .coerceIn(0L, 99L)
                                .toInt(),
                        )
                    }
                    output.fd.sync()
                }
            }
            val hash = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            if (total != EXPECTED_SIZE_BYTES || hash != EXPECTED_SHA256) {
                throw ModelIntegrityException()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun File.hasExpectedIntegrity(): Boolean {
        if (length() != EXPECTED_SIZE_BYTES) return false
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) } ==
            EXPECTED_SHA256
    }

    private class ModelIntegrityException : IllegalStateException()

    private class MediaPipeSemanticScorer(
        private val embedder: TextEmbedder,
    ) : SemanticTextScorer {
        private val cache = object : LinkedHashMap<String, List<Float>>(
            EMBEDDING_CACHE_LIMIT,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<Float>>?,
            ): Boolean = size > EMBEDDING_CACHE_LIMIT
        }

        override fun similarity(query: String, document: String): Double {
            if (query.isBlank() || document.isBlank()) return 0.0
            return synchronized(this) {
                val queryVector = embedding(query)
                val documentVector = embedding(document)
                cosineSimilarity(queryVector, documentVector)
            }
        }

        private fun embedding(text: String): List<Float> {
            val key = text.trim().take(MAX_EMBEDDING_TEXT_LENGTH)
            cache[key]?.let { return it }
            val embedding = embedder.embed(key)
                .embeddingResult()
                .embeddings()
                .firstOrNull()
                ?.floatEmbedding()
                ?.toList()
                .orEmpty()
            cache[key] = embedding
            return embedding
        }

        private fun cosineSimilarity(left: List<Float>, right: List<Float>): Double {
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
        const val EMBEDDING_CACHE_LIMIT = 512
        const val MAX_EMBEDDING_TEXT_LENGTH = 1_500
    }
}
