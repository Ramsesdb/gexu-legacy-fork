package tachiyomi.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.ai.repository.VectorStore
import tachiyomi.domain.ai.service.EmbeddingResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Optimized VectorStore implementation with:
 * - Pre-normalized vectors for O(1) similarity calculation
 * - Dot product instead of full cosine similarity (equivalent for normalized vectors)
 * - Parallel search using coroutines for large caches
 * - Efficient in-memory caching with ConcurrentHashMap
 * - Dimension-aware search for hybrid embedding compatibility
 */
class VectorStoreImpl(
    private val handler: DatabaseHandler,
) : VectorStore {

    // In-memory cache: Key = MangaID, Value = (Pre-normalized Embedding, Dimension)
    private data class CachedEmbedding(
        val embedding: FloatArray,
        val dimension: Int,
        val source: String
    )

    private val memoryCache = java.util.concurrent.ConcurrentHashMap<Long, CachedEmbedding>()
    @Volatile private var isCacheInitialized = false

    // Current search dimension (set by storeWithMeta or during initialization)
    @Volatile private var activeDimension: Int = 768 // Default Gemini dimension

    // Threshold for parallel search (use parallel if cache size > this)
    private val parallelThreshold = 100
    // Number of chunks for parallel processing
    private val parallelChunks = 4

    private suspend fun ensureCacheLoaded() {
        if (isCacheInitialized) return

        val allEmbeddings = handler.awaitList {
            manga_embeddingsQueries.getAllEmbeddings()
        }

        // Track dimension counts to determine most common dimension
        val dimensionCounts = mutableMapOf<Int, Int>()

        allEmbeddings.forEach { row ->
            val embedding = byteArrayToFloatArray(row.embedding)
            val dimension = row.embedding_dim?.toInt() ?: embedding.size
            val source = row.embedding_source ?: "gemini"

            memoryCache[row.manga_id] = CachedEmbedding(
                embedding = normalize(embedding),
                dimension = dimension,
                source = source
            )

            dimensionCounts[dimension] = (dimensionCounts[dimension] ?: 0) + 1
        }

        // Set active dimension to most common dimension in cache
        if (dimensionCounts.isNotEmpty()) {
            activeDimension = dimensionCounts.maxByOrNull { it.value }?.key ?: 768
            logcat(LogPriority.DEBUG) { "VectorStore loaded ${allEmbeddings.size} embeddings. Active dimension: $activeDimension" }
        }

        isCacheInitialized = true
    }

    /**
     * Store an embedding with metadata (preferred method).
     */
    override suspend fun storeWithMeta(mangaId: Long, result: EmbeddingResult) {
        val normalized = normalize(result.embedding)
        val bytes = floatArrayToByteArray(normalized)

        handler.await {
            manga_embeddingsQueries.insertEmbedding(
                mangaId = mangaId,
                embedding = bytes,
                embeddingDim = result.dimension.toLong(),
                embeddingSource = result.source,
                indexedAt = System.currentTimeMillis()
            )
        }

        memoryCache[mangaId] = CachedEmbedding(
            embedding = normalized,
            dimension = result.dimension,
            source = result.source
        )

        // Update active dimension if storing new embeddings
        activeDimension = result.dimension
    }

    override suspend fun store(mangaId: Long, embedding: FloatArray) {
        // Legacy method - use default metadata
        storeWithMeta(mangaId, EmbeddingResult(
            embedding = embedding,
            dimension = embedding.size,
            source = "gemini"
        ))
    }

    override suspend fun getEmbedding(mangaId: Long): FloatArray? {
        if (isCacheInitialized) {
            return memoryCache[mangaId]?.embedding
        }

        val bytes = handler.awaitOneOrNull {
            manga_embeddingsQueries.getEmbedding(mangaId)
        } ?: return null

        return byteArrayToFloatArray(bytes)
    }

    override suspend fun search(queryVector: FloatArray, limit: Int): List<Long> {
        ensureCacheLoaded()

        if (memoryCache.isEmpty()) return emptyList()

        // Normalize query vector once
        val normalizedQuery = normalize(queryVector)
        val queryDim = queryVector.size

        return withIOContext {
            // Filter to only search embeddings with matching dimension
            val compatibleEntries = memoryCache.entries
                .filter { it.value.dimension == queryDim }
                .toList()

            if (compatibleEntries.isEmpty()) {
                logcat(LogPriority.WARN) {
                    "No embeddings with dimension $queryDim found. Available dimensions: ${
                        memoryCache.values.map { it.dimension }.distinct()
                    }"
                }
                return@withIOContext emptyList()
            }

            if (compatibleEntries.size > parallelThreshold) {
                searchParallel(normalizedQuery, compatibleEntries, limit)
            } else {
                searchSequential(normalizedQuery, compatibleEntries, limit)
            }
        }
    }

    private fun searchSequential(
        normalizedQuery: FloatArray,
        entries: List<Map.Entry<Long, CachedEmbedding>>,
        limit: Int
    ): List<Long> {
        return entries
            .map { entry -> Pair(entry.key, dotProduct(normalizedQuery, entry.value.embedding)) }
            .sortedByDescending { pair -> pair.second }
            .take(limit)
            .map { pair -> pair.first }
    }

    private suspend fun searchParallel(
        normalizedQuery: FloatArray,
        entries: List<Map.Entry<Long, CachedEmbedding>>,
        limit: Int
    ): List<Long> {
        val chunkSize = (entries.size + parallelChunks - 1) / parallelChunks

        return withContext(Dispatchers.Default) {
            val results = entries.chunked(chunkSize)
                .map { chunk ->
                    async {
                        chunk.map { entry ->
                            Pair(entry.key, dotProduct(normalizedQuery, entry.value.embedding))
                        }
                    }
                }
                .awaitAll()
                .flatten()
                .sortedByDescending { pair -> pair.second }
                .take(limit)
                .map { pair -> pair.first }
            results
        }
    }

    override suspend fun delete(mangaId: Long) {
        handler.await {
            manga_embeddingsQueries.deleteEmbedding(mangaId)
        }
        memoryCache.remove(mangaId)
    }

    override suspend fun deleteAll() {
        handler.await {
            manga_embeddingsQueries.deleteAllEmbeddings()
        }
        memoryCache.clear()
        isCacheInitialized = true // Empty cache is valid
    }

    /**
     * Delete all embeddings from a specific source.
     * Useful when re-indexing with a different embedding provider.
     */
    suspend fun deleteBySource(source: String) {
        handler.await {
            manga_embeddingsQueries.deleteEmbeddingsForSource(source)
        }
        memoryCache.entries.removeIf { it.value.source == source }
    }

    override suspend fun count(): Long {
        if (isCacheInitialized) return memoryCache.size.toLong()

        return handler.awaitOne {
            manga_embeddingsQueries.countEmbeddings()
        }
    }

    /**
     * Get count of embeddings for a specific dimension.
     */
    suspend fun countForDimension(dimension: Int): Long {
        if (isCacheInitialized) {
            return memoryCache.values.count { it.dimension == dimension }.toLong()
        }
        return handler.awaitOne {
            manga_embeddingsQueries.countEmbeddingsForDim(dimension.toLong())
        }
    }

    /**
     * Get the currently active dimension (most common in cache).
     */
    fun getActiveDimension(): Int = activeDimension

    /**
     * Get the predominant embedding source used for indexing.
     * Returns "gemini", "local", or null if no embeddings exist.
     * This is used to determine which embedding service to use for search queries,
     * ensuring dimension compatibility.
     */
    override suspend fun getPredominantSource(): String? {
        ensureCacheLoaded()

        if (memoryCache.isEmpty()) return null

        val sourceCounts = memoryCache.values
            .groupingBy { it.source }
            .eachCount()

        return sourceCounts.maxByOrNull { it.value }?.key
    }

    /**
     * Force reload cache from database.
     */
    suspend fun invalidateCache() {
        memoryCache.clear()
        isCacheInitialized = false
        ensureCacheLoaded()
    }

    /**
     * Normalize vector to unit length.
     * For normalized vectors: cosine_similarity(a, b) = dot_product(a, b)
     * This eliminates 2 sqrt() and 2 sum operations per comparison.
     */
    private fun normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0f
        for (v in vector) sumSquares += v * v
        if (sumSquares == 0f) return vector

        val norm = sqrt(sumSquares)
        return FloatArray(vector.size) { vector[it] / norm }
    }

    /**
     * Dot product for pre-normalized vectors.
     * For unit vectors: dot_product = cosine_similarity
     * Much faster than full cosine similarity calculation.
     */
    private fun dotProduct(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size) return 0f

        var sum = 0f
        for (i in v1.indices) {
            sum += v1[i] * v2[i]
        }
        return sum
    }

    private fun floatArrayToByteArray(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (f in floats) {
            buffer.putFloat(f)
        }
        return buffer.array()
    }

    private fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            floats[i] = buffer.float
        }
        return floats
    }
}
