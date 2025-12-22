package tachiyomi.data.manga

import kotlinx.coroutines.flow.Flow
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.manga.model.ReaderNote
import tachiyomi.domain.manga.model.ReaderNoteWithManga
import tachiyomi.domain.manga.repository.ReaderNotesRepository
import java.util.Date

class ReaderNotesRepositoryImpl(
    private val handler: DatabaseHandler,
) : ReaderNotesRepository {

    override fun getNotesByMangaId(mangaId: Long): Flow<List<ReaderNote>> {
        return handler.subscribeToList {
            reader_notesQueries.getNotesByMangaId(mangaId, ::mapReaderNote)
        }
    }

    override suspend fun getNotesByChapterId(chapterId: Long): List<ReaderNote> {
        return handler.awaitList {
            reader_notesQueries.getNotesByChapterId(chapterId, ::mapReaderNote)
        }
    }

    override suspend fun getNotesCountByMangaId(mangaId: Long): Long {
        return handler.awaitOne {
            reader_notesQueries.getNotesCountByMangaId(mangaId)
        }
    }

    override suspend fun insertNote(
        mangaId: Long,
        chapterId: Long,
        pageNumber: Int,
        noteText: String,
    ) {
        handler.await {
            reader_notesQueries.insertNote(
                mangaId = mangaId,
                chapterId = chapterId,
                pageNumber = pageNumber.toLong(),
                noteText = noteText,
                createdAt = Date(),
            )
        }
    }

    override suspend fun updateNote(noteId: Long, noteText: String) {
        handler.await {
            reader_notesQueries.updateNote(noteText = noteText, noteId = noteId)
        }
    }

    override suspend fun deleteNote(noteId: Long) {
        handler.await {
            reader_notesQueries.deleteNote(noteId)
        }
    }

    override suspend fun deleteNotesByMangaId(mangaId: Long) {
        handler.await {
            reader_notesQueries.deleteNotesByMangaId(mangaId)
        }
    }

    override suspend fun deleteNotesByChapterId(chapterId: Long) {
        handler.await {
            reader_notesQueries.deleteNotesByChapterId(chapterId)
        }
    }

    override suspend fun getNotesForAiContext(mangaId: Long): List<ReaderNote> {
        return handler.awaitList {
            reader_notesQueries.getNotesByMangaId(mangaId, ::mapReaderNote)
        }
    }

    override suspend fun getAllRecentNotes(limit: Int): List<ReaderNoteWithManga> {
        return handler.awaitList {
            reader_notesQueries.getAllRecentNotes(limit.toLong(), ::mapReaderNoteWithManga)
        }
    }

    override suspend fun searchNotesByMangaTitle(query: String, limit: Int): List<ReaderNoteWithManga> {
        return handler.awaitList {
            reader_notesQueries.searchNotesByMangaTitle(query, limit.toLong(), ::mapReaderNoteWithManga)
        }
    }

    private fun mapReaderNote(
        id: Long,
        mangaId: Long,
        chapterId: Long,
        chapterNumber: Double,
        chapterName: String,
        pageNumber: Long,
        noteText: String,
        createdAt: Date?,
    ): ReaderNote = ReaderNote(
        id = id,
        mangaId = mangaId,
        chapterId = chapterId,
        chapterNumber = chapterNumber,
        chapterName = chapterName,
        pageNumber = pageNumber.toInt(),
        noteText = noteText,
        createdAt = createdAt ?: Date(),
    )

    private fun mapReaderNoteWithManga(
        id: Long,
        mangaId: Long,
        mangaTitle: String,
        chapterId: Long,
        chapterNumber: Double,
        chapterName: String,
        pageNumber: Long,
        noteText: String,
        createdAt: Date?,
    ): ReaderNoteWithManga = ReaderNoteWithManga(
        id = id,
        mangaId = mangaId,
        mangaTitle = mangaTitle,
        chapterId = chapterId,
        chapterNumber = chapterNumber,
        chapterName = chapterName,
        pageNumber = pageNumber.toInt(),
        noteText = noteText,
        createdAt = createdAt ?: Date(),
    )
}
