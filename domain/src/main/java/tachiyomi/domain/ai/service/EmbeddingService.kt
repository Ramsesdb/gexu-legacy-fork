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
            source = "unknown"
        )
    }

    /**
     * Batch embed multiple texts. Default implementation processes sequentially.
     * Implementations can override for more efficient batch processing.
     *
     * @param texts List of texts to embed
     * @param delayMs Delay between requests for rate limiting (default 500ms)
     * @return Map of index to EmbeddingResult (missing indices indicate failures)
     */
    suspend fun embedBatchWithMeta(
        texts: List<String>,
        delayMs: Long = 500L
    ): Map<Int, EmbeddingResult> {
        val results = mutableMapOf<Int, EmbeddingResult>()
        texts.forEachIndexed { index, text ->
            try {
                val result = embedWithMeta(text)
                if (result != null) {
                    results[index] = result
                }
                if (index < texts.lastIndex && delayMs > 0) {
                    kotlinx.coroutines.delay(delayMs)
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
}
