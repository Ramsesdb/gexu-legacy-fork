package tachiyomi.domain.pdftoc.model

/**
 * Represents a Table of Contents entry for a PDF chapter.
 */
data class PdfTocEntry(
    val id: Long = 0,
    val chapterId: Long,
    val title: String,
    val pageNumber: Int,
    val level: Int,
    val sortOrder: Int,
)
