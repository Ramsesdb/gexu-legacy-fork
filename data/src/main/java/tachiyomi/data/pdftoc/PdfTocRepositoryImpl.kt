package tachiyomi.data.pdftoc

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.pdftoc.model.PdfTocEntry
import tachiyomi.domain.pdftoc.repository.PdfTocRepository

class PdfTocRepositoryImpl(
    private val handler: DatabaseHandler,
) : PdfTocRepository {

    override suspend fun getTocByChapterId(chapterId: Long): List<PdfTocEntry> {
        return try {
            handler.awaitList {
                pdf_toc_entriesQueries.getTocByChapterId(chapterId, ::mapPdfTocEntry)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error getting TOC entries for chapter $chapterId" }
            emptyList()
        }
    }

    override suspend fun insertAll(entries: List<PdfTocEntry>) {
        try {
            handler.await(inTransaction = true) {
                entries.forEach { entry ->
                    pdf_toc_entriesQueries.insertTocEntry(
                        chapterId = entry.chapterId,
                        title = entry.title,
                        pageNumber = entry.pageNumber.toLong(),
                        level = entry.level.toLong(),
                        sortOrder = entry.sortOrder.toLong(),
                    )
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error inserting TOC entries" }
        }
    }

    override suspend fun deleteByChapterId(chapterId: Long) {
        try {
            handler.await { pdf_toc_entriesQueries.deleteByChapterId(chapterId) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error deleting TOC entries for chapter $chapterId" }
        }
    }

    override suspend fun hasTocEntries(chapterId: Long): Boolean {
        return try {
            handler.awaitOne { pdf_toc_entriesQueries.hasTocEntries(chapterId) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error checking TOC entries for chapter $chapterId" }
            false
        }
    }

    override suspend fun getTocCount(chapterId: Long): Long {
        return try {
            handler.awaitOne { pdf_toc_entriesQueries.getTocCount(chapterId) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error getting TOC count for chapter $chapterId" }
            0L
        }
    }

    private fun mapPdfTocEntry(
        id: Long,
        chapterId: Long,
        title: String,
        pageNumber: Long,
        level: Long,
        sortOrder: Long,
    ): PdfTocEntry = PdfTocEntry(
        id = id,
        chapterId = chapterId,
        title = title,
        pageNumber = pageNumber.toInt(),
        level = level.toInt(),
        sortOrder = sortOrder.toInt(),
    )
}
