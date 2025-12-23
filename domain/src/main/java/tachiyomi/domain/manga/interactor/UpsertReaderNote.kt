package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.model.NoteTag
import tachiyomi.domain.manga.repository.ReaderNotesRepository

class UpsertReaderNote(
    private val repository: ReaderNotesRepository,
) {
    suspend fun insert(
        mangaId: Long,
        chapterId: Long,
        pageNumber: Int,
        noteText: String,
        tags: List<NoteTag> = emptyList(),
    ) {
        repository.insertNote(mangaId, chapterId, pageNumber, noteText, tags)
    }

    suspend fun update(noteId: Long, noteText: String) {
        repository.updateNote(noteId, noteText)
    }

    suspend fun updateTags(noteId: Long, tags: List<NoteTag>) {
        repository.updateNoteTags(noteId, tags)
    }
}
