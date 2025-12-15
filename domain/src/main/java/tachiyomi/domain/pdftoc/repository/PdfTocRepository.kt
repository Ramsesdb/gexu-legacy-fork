package tachiyomi.domain.pdftoc.repository

import tachiyomi.domain.pdftoc.model.PdfTocEntry

/**
 * Repository interface for PDF Table of Contents entries.
 */
interface PdfTocRepository {

    /**
     * Get all TOC entries for a chapter.
     */
    suspend fun getTocByChapterId(chapterId: Long): List<PdfTocEntry>

    /**
     * Insert multiple TOC entries.
     */
    suspend fun insertAll(entries: List<PdfTocEntry>)

    /**
     * Delete all TOC entries for a chapter.
     */
    suspend fun deleteByChapterId(chapterId: Long)

    /**
     * Check if chapter has TOC entries.
     */
    suspend fun hasTocEntries(chapterId: Long): Boolean

    /**
     * Get TOC entry count for a chapter.
     */
    suspend fun getTocCount(chapterId: Long): Long
}
