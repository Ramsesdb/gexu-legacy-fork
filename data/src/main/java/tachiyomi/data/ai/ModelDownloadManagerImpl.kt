package tachiyomi.data.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.ai.AiPreferences
import tachiyomi.domain.ai.service.ModelDownloadManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * Implementation of ModelDownloadManager for downloading local embedding models.
 *
 * Uses Universal Sentence Encoder (USE) which is optimized for MediaPipe TextEmbedder.
 * Model is downloaded from GitHub releases and stored in app's files directory.
 */
class ModelDownloadManagerImpl(
    private val context: Context,
    private val client: OkHttpClient,
    private val aiPreferences: AiPreferences,
) : ModelDownloadManager {

    companion object {
        // Official Google Storage URL for Universal Sentence Encoder
        // This is a public Google model, no restrictions or login required
        private const val MODEL_DOWNLOAD_URL =
            "https://storage.googleapis.com/mediapipe-tasks/text_embedder/universal_sentence_encoder.tflite"

        // Model file - Universal Sentence Encoder (~30MB, 100 dimensions)
        private const val MODEL_FILE_NAME = "universal_sentence_encoder.tflite"

        private const val DOWNLOADED_MODEL_NAME = MODEL_FILE_NAME

        private const val BUFFER_SIZE = 8192

        // Approximate size for UI display (~30MB)
        const val APPROXIMATE_SIZE_MB = 30
    }

    private val modelDir: File by lazy {
        File(context.filesDir, "ai_models").apply { mkdirs() }
    }

    private val modelFile: File by lazy {
        File(modelDir, DOWNLOADED_MODEL_NAME)
    }

    private val downloadProgress = AtomicReference(0f)
    private val isCurrentlyDownloading = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)

    override suspend fun isModelAvailable(): Boolean {
        return modelFile.exists() && modelFile.length() > 0
    }

    override suspend fun getModelSize(): Long {
        return if (modelFile.exists()) modelFile.length() else 0L
    }

    override fun getDownloadProgress(): Float = downloadProgress.get()

    override fun isDownloading(): Boolean = isCurrentlyDownloading.get()

    private suspend fun downloadFile(
        url: String,
        targetFile: File,
        onProgress: ((Long) -> Unit)? = null
    ) {
        val request = Request.Builder().url(url).build()
        logcat(LogPriority.INFO) { "Starting download: $url" }

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Download failed: ${response.code} for $url")

            val body = response.body ?: throw Exception("Empty response body for $url")
            val contentLength = body.contentLength()
            val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")

            var bytesReadTotal: Long = 0
            val buffer = ByteArray(BUFFER_SIZE)

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (cancelRequested.get() || !coroutineContext.isActive) {
                            tempFile.delete()
                            throw Exception("Download cancelled")
                        }
                        output.write(buffer, 0, read)
                        bytesReadTotal += read
                        onProgress?.invoke(bytesReadTotal)
                    }
                }
            }

            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)
            logcat(LogPriority.INFO) { "Download completed: ${targetFile.name} (${targetFile.length()} bytes)" }
        }
    }

    override suspend fun downloadModel(
        onProgress: (Float) -> Unit,
        onComplete: (success: Boolean, error: String?) -> Unit
    ) {
        if (isCurrentlyDownloading.get()) {
            onComplete(false, "Download already in progress")
            return
        }

        isCurrentlyDownloading.set(true)
        cancelRequested.set(false)
        downloadProgress.set(0f)

        withContext(Dispatchers.IO) {
            try {
                // Download Model (~30MB for USE)
                downloadFile(MODEL_DOWNLOAD_URL, modelFile) { bytesRead ->
                    // Estimate based on ~30MB model size
                    val estimatedSize = APPROXIMATE_SIZE_MB * 1024 * 1024L
                    val progress = (bytesRead.toFloat() / estimatedSize).coerceIn(0f, 1f)

                    downloadProgress.set(progress)
                    onProgress(progress)
                }

                aiPreferences.localModelDownloaded().set(true)

                withContext(Dispatchers.Main) {
                    onComplete(true, null)
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Model download failed" }
                // Cleanup partials
                File(modelDir, "${DOWNLOADED_MODEL_NAME}.tmp").delete()

                withContext(Dispatchers.Main) {
                    onComplete(false, e.message ?: "Unknown error")
                }
            } finally {
                isCurrentlyDownloading.set(false)
                downloadProgress.set(0f)
            }
        }
    }

    override fun cancelDownload() {
        cancelRequested.set(true)
    }

    override suspend fun deleteModel(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val deleted = if (modelFile.exists()) modelFile.delete() else true

                if (deleted) {
                    aiPreferences.localModelDownloaded().set(false)
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to delete model" }
                false
            }
        }
    }

    override fun getModelPath(): String? {
        return if (modelFile.exists()) modelFile.absolutePath else null
    }
}
