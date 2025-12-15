package tachiyomi.domain.pdftoc.interactor

import tachiyomi.domain.pdftoc.model.PdfTocEntry
import tachiyomi.domain.pdftoc.repository.PdfTocRepository

/**
 * Use case to save PDF TOC entries.
 */
class SavePdfToc(
    private val repository: PdfTocRepository,
) {
    /**
     * Save TOC entries for a chapter.
     * Replaces any existing entries.
     */
    suspend fun await(chapterId: Long, entries: List<PdfTocEntry>) {
        // First delete existing entries
        repository.deleteByChapterId(chapterId)
        // Then insert new entries
        if (entries.isNotEmpty()) {
            repository.insertAll(entries)
        }
    }
}
