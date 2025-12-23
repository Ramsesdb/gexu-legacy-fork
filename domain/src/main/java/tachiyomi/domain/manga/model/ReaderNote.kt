package tachiyomi.domain.manga.model

import java.util.Date

data class ReaderNote(
    val id: Long,
    val mangaId: Long,
    val chapterId: Long,
    val chapterNumber: Double,
    val chapterName: String,
    val pageNumber: Int,
    val noteText: String,
    val tags: List<NoteTag>,
    val createdAt: Date,
) {
    companion object {
        fun create(
            mangaId: Long,
            chapterId: Long,
            chapterNumber: Double,
            chapterName: String,
            pageNumber: Int,
            noteText: String,
            tags: List<NoteTag> = emptyList(),
        ) = ReaderNote(
            id = -1L,
            mangaId = mangaId,
            chapterId = chapterId,
            chapterNumber = chapterNumber,
            chapterName = chapterName,
            pageNumber = pageNumber,
            noteText = noteText,
            tags = tags,
            createdAt = Date(),
        )
    }
}

/**
 * Reader note with manga title included, for AI context.
 */
data class ReaderNoteWithManga(
    val id: Long,
    val mangaId: Long,
    val mangaTitle: String,
    val chapterId: Long,
    val chapterNumber: Double,
    val chapterName: String,
    val pageNumber: Int,
    val noteText: String,
    val tags: List<NoteTag>,
    val createdAt: Date,
)
