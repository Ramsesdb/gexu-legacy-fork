package tachiyomi.data.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.ai.AiPreferences
import tachiyomi.domain.ai.service.EmbeddingService
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

class EmbeddingServiceImpl(
    private val client: OkHttpClient = Injekt.get(),
    private val preferences: AiPreferences = Injekt.get(),
    private val json: Json = Injekt.get(),
) : EmbeddingService {

    // Rate limiting state
    @Volatile
    private var quotaExceededUntil: Long = 0

    // Default cooldown of 60 seconds when we can't parse the retry delay
    private val defaultCooldownMs = TimeUnit.SECONDS.toMillis(60)

    // Maximum retry attempts for rate-limited requests
    private val maxRetries = 2

    // Last error for user feedback
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Clear the last error (call after displaying to user).
     */
    fun clearError() {
        lastError = null
    }

    override suspend fun isConfigured(): Boolean {
        return preferences.apiKey().get().isNotBlank()
    }

    override suspend fun embed(text: String): FloatArray? {
        return embedWithRetry(text, retryCount = 0)
    }

    /**
     * Embed with automatic retry on rate limit.
     * Waits for the cooldown period and retries up to maxRetries times.
     */
    private suspend fun embedWithRetry(text: String, retryCount: Int): FloatArray? {
        val apiKey = preferences.apiKey().get()
        if (apiKey.isBlank()) {
            lastError = "API key not configured"
            return null
        }

        // Check if we're in cooldown from a previous 429 error
        val now = System.currentTimeMillis()
        if (now < quotaExceededUntil) {
            val remainingMs = quotaExceededUntil - now
            val remainingSeconds = remainingMs / 1000

            // If we have retries left, wait and retry
            if (retryCount < maxRetries) {
                logcat(LogPriority.INFO) { "Rate limited. Waiting ${remainingSeconds}s before retry ${retryCount + 1}/$maxRetries" }
                kotlinx.coroutines.delay(remainingMs + 1000) // Add 1s buffer
                return embedWithRetry(text, retryCount + 1)
            }

            logcat(LogPriority.WARN) { "Rate limited. Max retries exceeded. Cooldown: ${remainingSeconds}s" }
            lastError = "Rate limited. Please wait ${remainingSeconds}s and try again."
            return null
        }

        // Only support Gemini for now as it provides free embeddings
        val result = getGeminiEmbedding(text, apiKey)

        // If we got rate limited during this call and have retries left, retry
        if (result == null && quotaExceededUntil > System.currentTimeMillis() && retryCount < maxRetries) {
            val remainingMs = quotaExceededUntil - System.currentTimeMillis()
            logcat(LogPriority.INFO) { "Rate limited during request. Waiting ${remainingMs/1000}s before retry ${retryCount + 1}/$maxRetries" }
            kotlinx.coroutines.delay(remainingMs + 1000)
            return embedWithRetry(text, retryCount + 1)
        }

        return result
    }

    private suspend fun getGeminiEmbedding(text: String, apiKey: String): FloatArray? = withIOContext {
        val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent"
        val url = "$baseUrl?key=$apiKey"

        val requestBody = GeminiEmbeddingRequest(
            content = GeminiContent(parts = listOf(GeminiPart(text = text))),
        )

        val jsonBody = json.encodeToString(requestBody)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(jsonBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()

                    // Handle rate limiting (429)
                    if (response.code == 429) {
                        handleRateLimitError(errorBody)
                        return@withIOContext null
                    }

                    logcat(LogPriority.WARN) { "Embedding error: ${response.code}" }
                    lastError = "Embedding API error: ${response.code}"
                    return@withIOContext null
                }

                val responseBody = response.body?.string() ?: return@withIOContext null
                val embeddingResponse = json.decodeFromString<GeminiEmbeddingResponse>(responseBody)

                return@withIOContext embeddingResponse.embedding.values.toFloatArray()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Embedding request failed" }
            lastError = "Embedding failed: ${e.message ?: "Unknown error"}"
            return@withIOContext null
        }
    }

    private fun handleRateLimitError(errorBody: String?) {
        // Try to parse the retry delay from the error response
        val retryDelaySeconds = try {
            // Look for "retryDelay": "40s" pattern
            val regex = """"retryDelay":\s*"(\d+)s?"""".toRegex()
            val match = regex.find(errorBody ?: "")
            match?.groupValues?.get(1)?.toLongOrNull() ?: 60
        } catch (e: Exception) {
            60L
        }

        // Add some buffer to the retry delay
        val cooldownMs = TimeUnit.SECONDS.toMillis(retryDelaySeconds + 5)
        quotaExceededUntil = System.currentTimeMillis() + cooldownMs

        logcat(LogPriority.WARN) { "Embedding API quota exceeded. Cooling down for ${retryDelaySeconds + 5}s" }
    }

    /**
     * Check if the service is currently rate limited.
     */
    fun isRateLimited(): Boolean {
        return System.currentTimeMillis() < quotaExceededUntil
    }

    /**
     * Get remaining cooldown time in seconds.
     */
    fun getRemainingCooldownSeconds(): Long {
        val remaining = quotaExceededUntil - System.currentTimeMillis()
        return if (remaining > 0) remaining / 1000 else 0
    }

    override fun getSourceId(): String = SOURCE_ID

    override fun getEmbeddingDimension(): Int = EMBEDDING_DIM

    override suspend fun embedWithMeta(text: String): tachiyomi.domain.ai.service.EmbeddingResult? {
        val embedding = embed(text) ?: return null
        return tachiyomi.domain.ai.service.EmbeddingResult(
            embedding = embedding,
            dimension = EMBEDDING_DIM,
            source = SOURCE_ID
        )
    }

    companion object {
        const val SOURCE_ID = "gemini"
        const val EMBEDDING_DIM = 768 // Gemini embedding-001 dimension
    }

    @Serializable
    private data class GeminiEmbeddingRequest(
        val content: GeminiContent,
        val model: String = "models/gemini-embedding-001"
    )

    @Serializable
    private data class GeminiContent(
        val parts: List<GeminiPart>
    )

    @Serializable
    private data class GeminiPart(
        val text: String
    )

    @Serializable
    private data class GeminiEmbeddingResponse(
        val embedding: GeminiEmbeddingValues
    )

    @Serializable
    private data class GeminiEmbeddingValues(
        val values: List<Float>
    )
}
