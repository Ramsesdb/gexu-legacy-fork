package tachiyomi.domain.ai.service

/**
 * Result of an embedding operation including metadata.
 */
data class EmbeddingResult(
    val embedding: FloatArray,
    val dimension: Int,
    val source: String, // "gemini", "local", etc.
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EmbeddingResult
        return embedding.contentEquals(other.embedding) && dimension == other.dimension && source == other.source
    }

    override fun hashCode(): Int {
        var result = embedding.contentHashCode()
        result = 31 * result + dimension
        result = 31 * result + source.hashCode()
        return result
    }
}

interface EmbeddingService {
    suspend fun isConfigured(): Boolean
    suspend fun embed(text: String): FloatArray?

    /**
     * Embed text and return result with metadata.
     * Implementations should override this to provide source/dimension info.
     */
    suspend fun embedWithMeta(text: String): EmbeddingResult? {
        val embedding = embed(text) ?: return null
        return EmbeddingResult(
            embedding = embedding,
            dimension = embedding.size,
            source = "unknown",
        )
    }

    /**
     * Batch embed multiple texts. Default implementation processes sequentially.
     * Implementations can override for more efficient batch processing.
     *
     * @param texts List of texts to embed
     * @param delayProvider Lambda that returns the delay in ms before each request.
     *                      This allows for adaptive delays based on rate-limiting state.
     *                      Default returns 500ms.
     * @return Map of index to EmbeddingResult (missing indices indicate failures)
     */
    suspend fun embedBatchWithMeta(
        texts: List<String>,
        delayProvider: suspend () -> Long = { 500L },
    ): Map<Int, EmbeddingResult> {
        val results = mutableMapOf<Int, EmbeddingResult>()
        texts.forEachIndexed { index, text ->
            try {
                val result = embedWithMeta(text)
                if (result != null) {
                    results[index] = result
                }
                if (index < texts.lastIndex) {
                    val delay = delayProvider()
                    if (delay > 0) {
                        kotlinx.coroutines.delay(delay)
                    }
                }
            } catch (e: Exception) {
                // Skip failed embeddings
            }
        }
        return results
    }

    /**
     * Get the source identifier for this service.
     */
    fun getSourceId(): String = "unknown"

    /**
     * Get the embedding dimension produced by this service.
     * Used for dimension-aware search across multiple embedding sources.
     */
    fun getEmbeddingDimension(): Int = 768 // Default to Gemini dimension

    /**
     * Check if the service is currently rate limited.
     * Default returns false (no rate limiting).
     */
    fun isRateLimited(): Boolean = false

    /**
     * Get remaining cooldown time in seconds when rate limited.
     * Default returns 0.
     */
    fun getRemainingCooldownSeconds(): Long = 0
}
