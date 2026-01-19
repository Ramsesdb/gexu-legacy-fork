package tachiyomi.domain.ai

/**
 * Interface to provide text content from the current reading session.
 * Implemented by the UI layer (NovelViewer) to expose MuPDF text extraction to the AI domain.
 */
interface TextContentProvider {
    /**
     * Retrieves the text of the recent pages leading up to the current page (Tier 3).
     * @param pagesToExtract Number of previous pages to include (e.g. 10).
     */
    suspend fun getRecentText(pagesToExtract: Int): String

    /**
     * Retrieves the first paragraph of pages in a range for Tier 2 context.
     * @param startPage Start page (0-indexed, inclusive).
     * @param endPage End page (0-indexed, exclusive).
     * @param skipEvery Only extract every Nth page (e.g. 5 = pages 0, 5, 10...).
     * @return Concatenated first paragraphs with page markers.
     */
    suspend fun getFirstParagraphs(startPage: Int, endPage: Int, skipEvery: Int = 5): String

    /**
     * Returns the current page number (0-indexed).
     */
    fun getCurrentPage(): Int

    /**
     * Returns total page count of the document.
     */
    fun getTotalPages(): Int
}
