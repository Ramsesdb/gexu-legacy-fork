package tachiyomi.data.ai

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.ai.service.EmbeddingResult
import tachiyomi.domain.ai.service.EmbeddingService
import java.io.File

/**
 * Local embedding service using MediaPipe TextEmbedder.
 *
 * This uses Universal Sentence Encoder (USE) for efficient on-device embeddings.
 * USE produces 100-dimensional embeddings and works reliably with MediaPipe.
 */
class LocalEmbeddingService(
    private val context: Context,
) : EmbeddingService {

    private var textEmbedder: TextEmbedder? = null
    private val mutex = Mutex()

    // Model name - Universal Sentence Encoder
    private val modelFileName = "universal_sentence_encoder.tflite"

    // Directory where downloaded models are stored
    private val modelsDir: File by lazy {
        File(context.filesDir, "ai_models")
    }

    // Cache the resolved model path
    @Volatile
    private var resolvedModelPath: String? = null

    // Cache the last known embedding dimension
    @Volatile
    private var cachedDimension: Int = EMBEDDING_DIM

    /**
     * Find the model path - checks filesDir first, then assets.
     * Returns null if no model is available.
     */
    private fun findModelPath(): String? {
        // Check cached result
        resolvedModelPath?.let { return it }

        // 1. Check for downloaded model in filesDir (preferred)
        val downloadedModel = File(modelsDir, modelFileName)
        if (downloadedModel.exists() && downloadedModel.length() > 0) {
            logcat(LogPriority.INFO) { "Using downloaded model: ${downloadedModel.absolutePath}" }
            resolvedModelPath = downloadedModel.absolutePath
            return resolvedModelPath
        }

        logcat(LogPriority.WARN) { "No local embedding model found" }
        return null
    }

    override suspend fun isConfigured(): Boolean {
        return findModelPath() != null
    }

    private suspend fun initializeEmbedder(): Boolean {
        if (textEmbedder != null) return true

        val modelPath = findModelPath() ?: return false

        return mutex.withLock {
            if (textEmbedder != null) return@withLock true

            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(modelPath)
                    .build()

                val options = TextEmbedder.TextEmbedderOptions.builder()
                    .setBaseOptions(baseOptions)
                    .build()

                textEmbedder = TextEmbedder.createFromOptions(context, options)
                logcat(LogPriority.INFO) { "MediaPipe TextEmbedder initialized with model: $modelPath" }
                true
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to initialize MediaPipe TextEmbedder" }
                false
            }
        }
    }

    override suspend fun embed(text: String): FloatArray? = withIOContext {
        if (!initializeEmbedder()) {
            return@withIOContext null
        }

        try {
            val embedder = textEmbedder ?: return@withIOContext null

            // Get embedding from MediaPipe
            val result = embedder.embed(text)

            // Extract the float array from the result
            val embedding = result.embeddingResult()
                .embeddings()
                .firstOrNull()
                ?.floatEmbedding()
                ?.let { floatList ->
                    FloatArray(floatList.size) { floatList[it] }
                }

            // Update cached dimension
            embedding?.let { cachedDimension = it.size }

            embedding
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Local embedding failed" }
            null
        }
    }

    override fun getSourceId(): String = SOURCE_ID

    override fun getEmbeddingDimension(): Int = EMBEDDING_DIM

    override suspend fun embedWithMeta(text: String): EmbeddingResult? {
        val embedding = embed(text) ?: return null
        return EmbeddingResult(
            embedding = embedding,
            dimension = embedding.size,
            source = SOURCE_ID,
        )
    }

    /**
     * Invalidate cached model state - call this when a new model is downloaded.
     */
    fun invalidateModel() {
        textEmbedder?.close()
        textEmbedder = null
        resolvedModelPath = null
        logcat(LogPriority.INFO) { "Local embedding model cache invalidated" }
    }

    /**
     * Get the cached embedding dimension.
     * Returns the last known dimension, or default if not yet determined.
     */
    fun getCachedDimension(): Int = cachedDimension

    companion object {
        const val SOURCE_ID = "local"

        // Universal Sentence Encoder produces 100-dimensional embeddings
        const val EMBEDDING_DIM = 100
    }
}
