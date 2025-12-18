package tachiyomi.domain.ai.repository

import tachiyomi.domain.ai.service.EmbeddingResult

interface VectorStore {
    /**
     * Store an embedding for a manga (legacy, uses default metadata).
     */
    suspend fun store(mangaId: Long, embedding: FloatArray)

    /**
     * Store an embedding with full metadata (preferred).
     * This tracks dimension and source for hybrid embedding compatibility.
     */
    suspend fun storeWithMeta(mangaId: Long, result: EmbeddingResult)

    /**
     * Get the raw embedding for a manga.
     */
    suspend fun getEmbedding(mangaId: Long): FloatArray?

    /**
     * Search for similar items. Returns manga IDs sorted by similarity.
     * Only searches embeddings with matching dimensions to the query.
     */
    suspend fun search(queryVector: FloatArray, limit: Int = 5): List<Long>

    /**
     * Delete embedding for a manga.
     */
    suspend fun delete(mangaId: Long)

    /**
     * Delete all embeddings.
     */
    suspend fun deleteAll()

    /**
     * Get total count of stored embeddings.
     */
    suspend fun count(): Long

    /**
     * Get the predominant embedding source used for indexing.
     * Returns "gemini", "local", or null if no embeddings exist.
     * Used to determine which embedding service to use for search queries.
     */
    suspend fun getPredominantSource(): String?
}
