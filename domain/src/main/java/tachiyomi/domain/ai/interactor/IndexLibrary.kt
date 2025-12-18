package tachiyomi.domain.ai.interactor

import kotlinx.coroutines.ensureActive
import tachiyomi.domain.ai.repository.VectorStore
import tachiyomi.domain.ai.service.EmbeddingService
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.core.common.util.lang.withIOContext
import kotlin.coroutines.coroutineContext

class IndexLibrary(
    private val mangaRepository: MangaRepository,
    private val embeddingService: EmbeddingService,
    private val vectorStore: VectorStore,
) {

    companion object {
        // Process in batches for better progress reporting and memory management
        private const val BATCH_SIZE = 20
    }

    // Text chunker for intelligent splitting of long descriptions
    private val textChunker = tachiyomi.domain.ai.service.TextChunker(
        maxChunkSize = 1800,  // Leave room for metadata
        overlapSize = 150,
        minChunkSize = 100
    )

    /**
     * Indexes the library manga for semantic search.
     *
     * @param force If true, re-indexes all manga even if already indexed
     * @param onProgress Callback for progress updates (current, total, mangaTitle)
     * @return IndexingResult with counts of indexed, skipped, and failed items
     */
    suspend fun await(
        force: Boolean = false,
        onProgress: ((current: Int, total: Int, title: String) -> Unit)? = null
    ): IndexingResult = withIOContext {
        if (!embeddingService.isConfigured()) {
            return@withIOContext IndexingResult(0, 0, 0, notConfigured = true)
        }

        val libraryManga = mangaRepository.getLibraryManga()
        val total = libraryManga.size
        var indexed = 0
        var skipped = 0
        var failed = 0

        // Filter items that need indexing
        val itemsToIndex = if (force) {
            libraryManga
        } else {
            libraryManga.filter { vectorStore.getEmbedding(it.manga.id) == null }
        }

        skipped = libraryManga.size - itemsToIndex.size

        // Process in batches
        itemsToIndex.chunked(BATCH_SIZE).forEachIndexed { batchIndex, batch ->
            coroutineContext.ensureActive()

            // Build texts to embed for this batch
            val textsToEmbed = batch.mapNotNull { item ->
                val manga = item.manga
                val text = buildEmbeddingText(item)
                if (text.isBlank()) {
                    skipped++
                    null
                } else {
                    manga.id to text
                }
            }

            if (textsToEmbed.isEmpty()) return@forEachIndexed

            // Report progress at start of batch
            val currentProgress = batchIndex * BATCH_SIZE + 1
            val batchTitle = batch.firstOrNull()?.manga?.title ?: ""
            onProgress?.invoke(minOf(currentProgress, total), total, batchTitle)

            try {
                // Use batch embedding
                val embeddings = embeddingService.embedBatchWithMeta(
                    texts = textsToEmbed.map { it.second },
                    delayMs = 500L
                )

                // Store successful embeddings
                embeddings.forEach { (index, result) ->
                    val mangaId = textsToEmbed[index].first
                    try {
                        vectorStore.storeWithMeta(mangaId, result)
                        indexed++
                    } catch (e: Exception) {
                        failed++
                    }
                }

                // Count failures
                val successCount = embeddings.size
                failed += textsToEmbed.size - successCount

            } catch (e: Exception) {
                e.printStackTrace()
                failed += textsToEmbed.size
            }

            // Update progress after batch
            val afterBatchProgress = (batchIndex + 1) * BATCH_SIZE
            onProgress?.invoke(minOf(afterBatchProgress, total), total, "Processing...")
        }

        // Invalidate AI cache if any manga was indexed (affects RAG search results)
        if (indexed > 0) {
            tachiyomi.domain.ai.AiCacheInvalidator.onLibraryChanged()
        }

        IndexingResult(indexed, skipped, failed, source = embeddingService.getSourceId())
    }

    /**
     * Build embedding text using intelligent chunking.
     * Returns the first (primary) chunk for backwards compatibility.
     *
     * For very long descriptions, this uses smart truncation at sentence
     * boundaries rather than hard truncation.
     */
    private fun buildEmbeddingText(item: LibraryManga): String {
        val manga = item.manga

        // Use optimized single-chunk method (avoids creating unused additional chunks)
        return textChunker.buildPrimaryEmbeddingText(
            title = manga.title,
            author = manga.author,
            genres = manga.genre,
            description = manga.description
        )
    }

    data class IndexingResult(
        val indexed: Int,
        val skipped: Int,
        val failed: Int,
        val notConfigured: Boolean = false,
        val source: String = "unknown"
    )
}
