package tachiyomi.domain.pdftoc.interactor

import tachiyomi.domain.pdftoc.model.PdfTocEntry
import tachiyomi.domain.pdftoc.repository.PdfTocRepository

/**
 * Use case to get PDF TOC entries for a chapter.
 */
class GetPdfToc(
    private val repository: PdfTocRepository,
) {
    suspend fun await(chapterId: Long): List<PdfTocEntry> {
        return repository.getTocByChapterId(chapterId)
    }

    suspend fun hasToc(chapterId: Long): Boolean {
        return repository.hasTocEntries(chapterId)
    }
}
