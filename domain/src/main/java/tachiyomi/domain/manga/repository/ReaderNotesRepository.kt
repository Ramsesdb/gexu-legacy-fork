package tachiyomi.domain.manga.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.ReaderNote
import tachiyomi.domain.manga.model.ReaderNoteWithManga

interface ReaderNotesRepository {
    fun getNotesByMangaId(mangaId: Long): Flow<List<ReaderNote>>

    suspend fun getNotesByChapterId(chapterId: Long): List<ReaderNote>

    suspend fun getNotesCountByMangaId(mangaId: Long): Long

    suspend fun insertNote(
        mangaId: Long,
        chapterId: Long,
        pageNumber: Int,
        noteText: String,
    )

    suspend fun updateNote(noteId: Long, noteText: String)

    suspend fun deleteNote(noteId: Long)

    suspend fun deleteNotesByMangaId(mangaId: Long)

    suspend fun deleteNotesByChapterId(chapterId: Long)

    suspend fun getNotesForAiContext(mangaId: Long): List<ReaderNote>

    /**
     * Get recent notes from all manga for AI global context.
     */
    suspend fun getAllRecentNotes(limit: Int = 20): List<ReaderNoteWithManga>

    /**
     * Search notes by manga title for RAG context.
     */
    suspend fun searchNotesByMangaTitle(query: String, limit: Int = 10): List<ReaderNoteWithManga>
}
