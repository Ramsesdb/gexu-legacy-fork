package tachiyomi.domain.ai.service

/**
 * Interface for managing local AI model downloads.
 */
interface ModelDownloadManager {

    /**
     * Check if the local embedding model is available.
     */
    suspend fun isModelAvailable(): Boolean

    /**
     * Get the size of the model file in bytes.
     */
    suspend fun getModelSize(): Long

    /**
     * Get download progress (0.0 to 1.0).
     */
    fun getDownloadProgress(): Float

    /**
     * Check if a download is currently in progress.
     */
    fun isDownloading(): Boolean

    /**
     * Start downloading the model.
     * @param onProgress Callback for download progress (0.0 to 1.0)
     * @param onComplete Callback when download completes (success: Boolean, error: String?)
     */
    suspend fun downloadModel(
        onProgress: (Float) -> Unit = {},
        onComplete: (success: Boolean, error: String?) -> Unit = { _, _ -> }
    )

    /**
     * Cancel an ongoing download.
     */
    fun cancelDownload()

    /**
     * Delete the downloaded model to free space.
     */
    suspend fun deleteModel(): Boolean

    /**
     * Get the path to the model file (if available).
     */
    fun getModelPath(): String?

    companion object {
        // Model file name - Universal Sentence Encoder
        const val MODEL_FILE_NAME = "universal_sentence_encoder.tflite"

        // Approximate model size for UI display (~30MB)
        const val APPROXIMATE_SIZE_MB = 30
    }
}
