package tachiyomi.domain.ai.interactor

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
 * Automatically detects which embedding source was used for indexing
 * and uses the same source for query embedding to ensure dimension compatibility.
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
        // Detect which source was used for indexing
        val indexedSource = vectorStore.getPredominantSource()

        // Select the matching embedding service for the query
        val embeddingService = when (indexedSource) {
            "local" -> localService
            "gemini" -> cloudService
            else -> cloudService // Default if no embeddings exist yet
        }

        if (!embeddingService.isConfigured()) return@withIOContext emptyList()

        val queryEmbedding = embeddingService.embed(query) ?: return@withIOContext emptyList()

        // Get more candidates for re-ranking
        val candidateLimit = if (useReranking) (limit * 3).coerceAtMost(20) else limit
        val mangaIds = vectorStore.search(queryEmbedding, candidateLimit)

        if (mangaIds.isEmpty()) return@withIOContext emptyList()

        // Fetch manga objects
        val mangas = mangaIds.mapNotNull { id ->
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
            vectorRanking = mangaIds,
            vectorWeight = 0.7,
            limit = limit
        )

        // Return mangas in re-ranked order
        rerankedIds.mapNotNull { id ->
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

