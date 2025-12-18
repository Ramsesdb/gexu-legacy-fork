package tachiyomi.domain.ai.service

/**
 * Text chunker for splitting long descriptions into overlapping chunks
 * for better embedding coverage.
 *
 * This ensures that long manga descriptions don't lose information
 * when truncated for embedding.
 */
class TextChunker(
    private val maxChunkSize: Int = 1500,
    private val overlapSize: Int = 200,
    private val minChunkSize: Int = 100,
) {

    /**
     * Split text into overlapping chunks.
     *
     * @param text The text to split
     * @return List of text chunks with metadata
     */
    fun chunk(text: String): List<TextChunk> {
        if (text.length <= maxChunkSize) {
            return listOf(TextChunk(
                content = text,
                index = 0,
                totalChunks = 1,
                startOffset = 0,
                endOffset = text.length
            ))
        }

        val chunks = mutableListOf<TextChunk>()
        var startIndex = 0
        var chunkIndex = 0

        while (startIndex < text.length) {
            // Calculate end index for this chunk
            var endIndex = (startIndex + maxChunkSize).coerceAtMost(text.length)

            // Try to break at sentence boundary if not at end
            if (endIndex < text.length) {
                endIndex = findSentenceBoundary(text, startIndex, endIndex)
            }

            val chunkContent = text.substring(startIndex, endIndex).trim()

            if (chunkContent.length >= minChunkSize) {
                chunks.add(TextChunk(
                    content = chunkContent,
                    index = chunkIndex,
                    totalChunks = -1, // Will be updated after
                    startOffset = startIndex,
                    endOffset = endIndex
                ))
                chunkIndex++
            }

            // Move start with overlap
            startIndex = endIndex - overlapSize
            if (startIndex <= chunks.lastOrNull()?.startOffset ?: -1) {
                startIndex = endIndex  // Prevent infinite loop
            }
        }

        // Update total chunks count
        return chunks.map { it.copy(totalChunks = chunks.size) }
    }

    /**
     * Build embedding texts for a manga with intelligent chunking.
     * Returns multiple texts if the content is long enough to warrant chunking.
     *
     * @param title Manga title
     * @param author Author name
     * @param genres List of genres
     * @param description Full description
     * @return List of texts to embed (usually 1, can be more for long descriptions)
     */
    fun buildEmbeddingTexts(
        title: String,
        author: String?,
        genres: List<String>?,
        description: String?
    ): List<String> {
        // Base metadata (always included in each chunk)
        val baseMetadata = buildString {
            append("Title: $title")
            if (!author.isNullOrBlank()) append("\nAuthor: $author")
            if (!genres.isNullOrEmpty()) append("\nGenres: ${genres.joinToString(", ")}")
        }

        // If no description or short description, return single text
        if (description.isNullOrBlank() || description.length < maxChunkSize - baseMetadata.length) {
            return listOf(buildString {
                append(baseMetadata)
                if (!description.isNullOrBlank()) {
                    append("\nDescription: $description")
                }
            })
        }

        // Chunk the description
        val chunks = chunk(description)

        return chunks.map { chunk ->
            buildString {
                append(baseMetadata)
                append("\nDescription (Part ${chunk.index + 1}/${chunk.totalChunks}): ")
                append(chunk.content)
            }
        }
    }

    /**
     * Find the best sentence boundary near the target position.
     */
    private fun findSentenceBoundary(text: String, start: Int, targetEnd: Int): Int {
        // Look backwards for sentence endings
        val searchStart = (targetEnd - 100).coerceAtLeast(start)
        val searchText = text.substring(searchStart, targetEnd)

        // Find last sentence ending
        val sentenceEnders = listOf(". ", "! ", "? ", ".\n", "!\n", "?\n")
        var bestBoundary = targetEnd

        for (ender in sentenceEnders) {
            val idx = searchText.lastIndexOf(ender)
            if (idx != -1) {
                val absoluteIdx = searchStart + idx + ender.length
                if (absoluteIdx < bestBoundary && absoluteIdx > start + minChunkSize) {
                    bestBoundary = absoluteIdx
                }
            }
        }

        // If no sentence boundary found, try word boundary
        if (bestBoundary == targetEnd && targetEnd < text.length) {
            val lastSpace = text.lastIndexOf(' ', targetEnd)
            if (lastSpace > start + minChunkSize) {
                bestBoundary = lastSpace + 1
            }
        }

        return bestBoundary
    }

    /**
     * Represents a chunk of text with position metadata.
     */
    data class TextChunk(
        val content: String,
        val index: Int,
        val totalChunks: Int,
        val startOffset: Int,
        val endOffset: Int,
    )
}
