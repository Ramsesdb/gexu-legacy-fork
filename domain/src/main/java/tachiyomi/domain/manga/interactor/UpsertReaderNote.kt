package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.repository.ReaderNotesRepository

class UpsertReaderNote(
    private val repository: ReaderNotesRepository,
) {
    suspend fun insert(
        mangaId: Long,
        chapterId: Long,
        pageNumber: Int,
        noteText: String,
    ) {
        repository.insertNote(mangaId, chapterId, pageNumber, noteText)
    }

    suspend fun update(noteId: Long, noteText: String) {
        repository.updateNote(noteId, noteText)
    }
}
