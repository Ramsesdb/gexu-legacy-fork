package tachiyomi.domain.ai.interactor

import kotlinx.coroutines.ensureActive
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.ai.repository.VectorStore
import tachiyomi.domain.ai.service.EmbeddingService
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.repository.MangaRepository
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
        maxChunkSize = 1800, // Leave room for metadata
        overlapSize = 150,
        minChunkSize = 100,
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
        onProgress: ((current: Int, total: Int, title: String) -> Unit)? = null,
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

            // Track progress within batch
            var batchProcessed = 0
            val batchStartIndex = batchIndex * BATCH_SIZE

            try {
                // Use batch embedding with adaptive delay provider
                val embeddings = embeddingService.embedBatchWithMeta(
                    texts = textsToEmbed.map { it.second },
                    delayProvider = {
                        // Check if we're rate-limited and need to wait longer
                        if (embeddingService.isRateLimited()) {
                            val cooldown = embeddingService.getRemainingCooldownSeconds() * 1000L + 1000L
                            cooldown.coerceAtLeast(500L)
                        } else {
                            500L // Default delay
                        }
                    },

                )

                // Store successful embeddings with per-item progress
                embeddings.forEach { (index, result) ->
                    val mangaId = textsToEmbed[index].first
                    val mangaTitle = batch.getOrNull(index)?.manga?.title ?: "Processing..."
                    try {
                        vectorStore.storeWithMeta(mangaId, result)
                        indexed++
                    } catch (e: Exception) {
                        failed++
                    }
                    batchProcessed++
                    // Report progress per item
                    val currentProgress = batchStartIndex + batchProcessed
                    onProgress?.invoke(minOf(currentProgress, total), total, mangaTitle)
                }

                // Count failures
                val successCount = embeddings.size
                failed += textsToEmbed.size - successCount
            } catch (e: Exception) {
                e.printStackTrace()
                failed += textsToEmbed.size
            }
        }

        // Invalidate AI cache if any manga was indexed (affects RAG search results)
        if (indexed > 0) {
            tachiyomi.domain.ai.AiCacheInvalidator.onLibraryChanged()
        }

        IndexingResult(indexed, skipped, failed, source = embeddingService.getSourceId())
    }

    /**
     * Build embedding text using intelligent chunking.
     * Returns a single optimized chunk with smart truncation at sentence boundaries.
     */
    private fun buildEmbeddingText(item: LibraryManga): String {
        val manga = item.manga

        return textChunker.buildPrimaryEmbeddingText(
            title = manga.title,
            author = manga.author,
            genres = manga.genre,
            description = manga.description,
        )
    }

    data class IndexingResult(
        val indexed: Int,
        val skipped: Int,
        val failed: Int,
        val notConfigured: Boolean = false,
        val source: String = "unknown",
    )
}
