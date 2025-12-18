package tachiyomi.domain.ai.interactor

import android.util.LruCache
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import tachiyomi.domain.ai.repository.VectorStore
import tachiyomi.domain.ai.service.Bm25Reranker
import tachiyomi.domain.ai.service.EmbeddingService
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Semantic search through the user's manga library.
 *
 * Uses vector similarity search followed by optional BM25 re-ranking
 * for improved precision.
 *
 * Supports mixed embeddings (Gemini + Local) by searching across all
 * available dimensions in parallel and merging results.
 */
class SearchLibrary(
    private val mangaRepository: MangaRepository,
    private val cloudService: EmbeddingService,
    private val localService: EmbeddingService,
    private val vectorStore: VectorStore,
) {

    private val reranker = Bm25Reranker()

    // LRU caches for query embeddings to reduce API calls
    // Separate caches per service since they have different dimensions
    private val cloudQueryCache = LruCache<String, FloatArray>(50)
    private val localQueryCache = LruCache<String, FloatArray>(50)

    /**
     * Search the library using semantic similarity.
     *
     * @param query Search query
     * @param limit Maximum number of results
     * @param useReranking Whether to apply BM25 re-ranking
     * @return List of matching Manga, sorted by relevance
     */
    suspend fun await(
        query: String,
        limit: Int = 5,
        useReranking: Boolean = true
    ): List<Manga> = withIOContext {
        val availableDimensions = vectorStore.getAvailableDimensions()

        if (availableDimensions.isEmpty()) {
            return@withIOContext emptyList()
        }

        // Collect results from all dimension spaces
        val mergedResults = mutableMapOf<Long, Float>()

        coroutineScope {
            val deferredSearches = mutableListOf<kotlinx.coroutines.Deferred<List<Pair<Long, Float>>>>()

            // Launch Cloud search if available
            val cloudDim = cloudService.getEmbeddingDimension()
            if (cloudDim in availableDimensions) {
                deferredSearches.add(async {
                    searchWithService(cloudService, query, limit * 2)
                })
            }

            // Launch Local search if available (and different dimension)
            val localDim = localService.getEmbeddingDimension()
            if (localDim in availableDimensions && localDim != cloudDim) {
                deferredSearches.add(async {
                    searchWithService(localService, query, limit * 2)
                })
            }

            // Await all searches in parallel and merge results
            deferredSearches.forEach { deferred ->
                try {
                    val results = deferred.await()
                    normalizeAndMerge(results, mergedResults)
                } catch (e: Exception) {
                    // Continue with other dimensions if one fails
                }
            }
        }

        if (mergedResults.isEmpty()) {
            // Fallback to old behavior if parallel search failed
            return@withIOContext searchFallback(query, limit, useReranking)
        }

        // Get top candidates sorted by merged score
        val candidateLimit = if (useReranking) (limit * 3).coerceAtMost(24) else limit
        val topMangaIds = mergedResults.entries
            .sortedByDescending { it.value }
            .take(candidateLimit)
            .map { it.key }

        // Fetch manga objects
        val mangas = topMangaIds.mapNotNull { id ->
            try {
                mangaRepository.getMangaById(id)
            } catch (e: Exception) {
                null
            }
        }

        if (!useReranking || mangas.size <= limit) {
            return@withIOContext mangas.take(limit)
        }

        // Build document texts for re-ranking
        val documents = mangas.associate { manga ->
            manga.id to buildSearchableText(manga)
        }

        // Hybrid re-ranking (70% vector, 30% BM25)
        val rerankedIds = reranker.hybridRerank(
            query = query,
            documents = documents,
            vectorRanking = topMangaIds,
            vectorWeight = 0.7,
            limit = limit
        )

        // Return mangas in re-ranked order
        rerankedIds.mapNotNull { id ->
            mangas.find { it.id == id }
        }
    }

    /**
     * Search using a specific embedding service.
     * Uses cached query embeddings when available to reduce API calls.
     */
    private suspend fun searchWithService(
        service: EmbeddingService,
        query: String,
        limit: Int
    ): List<Pair<Long, Float>> {
        if (!service.isConfigured()) return emptyList()

        // Select the appropriate cache based on service
        val cache = if (service == cloudService) cloudQueryCache else localQueryCache

        // Try cache first, then API
        val queryEmbedding = cache.get(query) ?: run {
            val fresh = service.embed(query) ?: return emptyList()
            cache.put(query, fresh)
            fresh
        }

        return vectorStore.searchWithScores(queryEmbedding, limit)
    }

    /**
     * Normalize scores to [0, 1] range and merge into results map.
     * Uses max(existing, new) when the same manga appears in multiple searches.
     */
    private fun normalizeAndMerge(
        results: List<Pair<Long, Float>>,
        mergedResults: MutableMap<Long, Float>
    ) {
        if (results.isEmpty()) return

        // Cosine similarity is already [-1, 1], normalize to [0, 1]
        // For well-matched content, scores are typically 0.3-0.9
        val normalized = results.map { (id, score) ->
            id to ((score + 1f) / 2f).coerceIn(0f, 1f)
        }

        synchronized(mergedResults) {
            normalized.forEach { (id, score) ->
                mergedResults[id] = maxOf(mergedResults[id] ?: 0f, score)
            }
        }
    }

    /**
     * Fallback to single-service search (old behavior).
     * Uses query cache when available.
     */
    private suspend fun searchFallback(
        query: String,
        limit: Int,
        useReranking: Boolean
    ): List<Manga> {
        val indexedSource = vectorStore.getPredominantSource()

        val embeddingService = when (indexedSource) {
            "local" -> localService
            "gemini" -> cloudService
            else -> cloudService
        }

        if (!embeddingService.isConfigured()) return emptyList()

        // Use appropriate cache
        val cache = if (embeddingService == cloudService) cloudQueryCache else localQueryCache
        val queryEmbedding = cache.get(query) ?: run {
            val fresh = embeddingService.embed(query) ?: return emptyList()
            cache.put(query, fresh)
            fresh
        }

        val candidateLimit = if (useReranking) (limit * 3).coerceAtMost(20) else limit
        val mangaIds = vectorStore.search(queryEmbedding, candidateLimit)

        if (mangaIds.isEmpty()) return emptyList()

        val mangas = mangaIds.mapNotNull { id ->
            try {
                mangaRepository.getMangaById(id)
            } catch (e: Exception) {
                null
            }
        }

        if (!useReranking || mangas.size <= limit) {
            return mangas.take(limit)
        }

        val documents = mangas.associate { manga ->
            manga.id to buildSearchableText(manga)
        }

        val rerankedIds = reranker.hybridRerank(
            query = query,
            documents = documents,
            vectorRanking = mangaIds,
            vectorWeight = 0.7,
            limit = limit
        )

        return rerankedIds.mapNotNull { id ->
            mangas.find { it.id == id }
        }
    }

    /**
     * Build searchable text from manga for BM25 matching.
     */
    private fun buildSearchableText(manga: Manga): String {
        return buildString {
            append(manga.title)
            append(" ")
            manga.author?.let { append(it).append(" ") }
            manga.artist?.let { append(it).append(" ") }
            manga.genre?.let { append(it.joinToString(" ")).append(" ") }
            manga.description?.take(500)?.let { append(it) }
        }
    }
}
