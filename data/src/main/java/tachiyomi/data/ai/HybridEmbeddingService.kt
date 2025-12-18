package tachiyomi.data.ai

import tachiyomi.domain.ai.service.EmbeddingResult
import tachiyomi.domain.ai.service.EmbeddingService
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlinx.coroutines.runBlocking

/**
 * Hybrid embedding service that tries cloud first, then falls back to local.
 * Returns metadata about which source was used for proper dimension tracking.
 */
class HybridEmbeddingService(
    private val context: android.content.Context,
    private val cloudService: EmbeddingServiceImpl,
    private val localService: LocalEmbeddingService,
) : EmbeddingService {

    // Track which service was last used successfully
    @Volatile
    private var lastUsedSource: String = EmbeddingServiceImpl.SOURCE_ID

    // Cache configuration state to avoid suspend calls where not possible
    @Volatile
    private var cloudConfiguredCache: Boolean? = null
    @Volatile
    private var localConfiguredCache: Boolean? = null

    private fun isOnline(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            connectivityManager?.activeNetworkInfo?.isConnected == true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun isConfigured(): Boolean {
        // Considered configured if either service is available
        return try {
            val cloudConfigured = cloudService.isConfigured()
            val localConfigured = localService.isConfigured()
            // Update cache
            cloudConfiguredCache = cloudConfigured
            localConfiguredCache = localConfigured
            cloudConfigured || localConfigured
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Quick sync check using cached values (call isConfigured() first to populate cache).
     */
    fun isConfiguredSync(): Boolean {
        return (cloudConfiguredCache == true) || (localConfiguredCache == true)
    }

    override suspend fun embed(text: String): FloatArray? {
        return embedWithMeta(text)?.embedding
    }

    /**
     * Embed text with full metadata about source and dimensions.
     * This is the preferred method for indexing operations.
     */
    override suspend fun embedWithMeta(text: String): EmbeddingResult? {
        // 1. Try Cloud if online and configured (better quality, consistent dimensions)
        if (isOnline()) {
            try {
                if (cloudService.isConfigured() && !cloudService.isRateLimited()) {
                    val result = cloudService.embedWithMeta(text)
                    if (result != null) {
                        lastUsedSource = EmbeddingServiceImpl.SOURCE_ID
                        return result
                    }
                    logcat(LogPriority.DEBUG) { "Cloud embedding failed, trying local..." }
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Cloud embedding error: ${e.message}" }
                // Fall through to local
            }
        }

        // 2. Try Local if cloud failed or offline/unconfigured
        try {
            if (localService.isConfigured()) {
                val result = localService.embedWithMeta(text)
                if (result != null) {
                    lastUsedSource = LocalEmbeddingService.SOURCE_ID
                    return result
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Local embedding error: ${e.message}" }
        }

        return null
    }

    override fun getSourceId(): String = lastUsedSource

    /**
     * Get the expected embedding dimension for the current configuration.
     * Uses cached configuration values.
     * Note: Actual dimension is determined dynamically from model output tensor.
     */
    fun getCurrentDimension(): Int {
        return if (isOnline() && cloudConfiguredCache == true) {
            EmbeddingServiceImpl.EMBEDDING_DIM  // 768 for Gemini
        } else {
            LocalEmbeddingService.EMBEDDING_DIM  // 100 for USE (Universal Sentence Encoder)
        }
    }

    /**
     * Check if cloud service is available for consistent embeddings.
     * Uses cached configuration values.
     */
    fun isCloudAvailable(): Boolean {
        return isOnline() && cloudConfiguredCache == true && !cloudService.isRateLimited()
    }
}
