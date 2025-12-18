package tachiyomi.domain.ai.interactor

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
            // Search Cloud embeddings if available
            val cloudDim = cloudService.getEmbeddingDimension()
            if (cloudDim in availableDimensions) {
                async {
                    searchWithService(cloudService, query, limit * 2)
                }.also { deferred ->
                    try {
                        val results = deferred.await()
                        normalizeAndMerge(results, mergedResults)
                    } catch (e: Exception) {
                        // Continue with other dimensions if this fails
                    }
                }
            }

            // Search Local embeddings if available
            val localDim = localService.getEmbeddingDimension()
            if (localDim in availableDimensions && localDim != cloudDim) {
                async {
                    searchWithService(localService, query, limit * 2)
                }.also { deferred ->
                    try {
                        val results = deferred.await()
                        normalizeAndMerge(results, mergedResults)
                    } catch (e: Exception) {
                        // Continue with other dimensions if this fails
                    }
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
     */
    private suspend fun searchWithService(
        service: EmbeddingService,
        query: String,
        limit: Int
    ): List<Pair<Long, Float>> {
        if (!service.isConfigured()) return emptyList()
        val queryEmbedding = service.embed(query) ?: return emptyList()
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

        val queryEmbedding = embeddingService.embed(query) ?: return emptyList()

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
