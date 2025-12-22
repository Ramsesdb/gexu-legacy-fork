package tachiyomi.domain.manga.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.ReaderNote
import tachiyomi.domain.manga.repository.ReaderNotesRepository

class GetReaderNotes(
    private val repository: ReaderNotesRepository,
) {
    fun subscribe(mangaId: Long): Flow<List<ReaderNote>> {
        return repository.getNotesByMangaId(mangaId)
    }

    suspend fun await(chapterId: Long): List<ReaderNote> {
        return repository.getNotesByChapterId(chapterId)
    }

    suspend fun getCount(mangaId: Long): Long {
        return repository.getNotesCountByMangaId(mangaId)
    }
}
