package tachiyomi.domain.novelcontext.model

/**
 * Domain entity representing AI-generated context for a novel/PDF.
 * Used by the Reading Buddy feature to maintain story context.
 */
data class NovelContext(
    val id: Long = 0,
    val mangaId: Long,
    val summaryText: String?,
    val summaryLastPage: Int,
    val summaryChapterNumber: Double = 0.0,
    val updatedAt: Long,
)
