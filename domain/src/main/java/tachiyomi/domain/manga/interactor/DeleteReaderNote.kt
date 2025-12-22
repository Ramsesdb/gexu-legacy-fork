package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.repository.ReaderNotesRepository

class DeleteReaderNote(
    private val repository: ReaderNotesRepository,
) {
    suspend fun await(noteId: Long) {
        repository.deleteNote(noteId)
    }

    suspend fun deleteByMangaId(mangaId: Long) {
        repository.deleteNotesByMangaId(mangaId)
    }

    suspend fun deleteByChapterId(chapterId: Long) {
        repository.deleteNotesByChapterId(chapterId)
    }
}
