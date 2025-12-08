package eu.kanade.domain.manga.interactor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.DatabaseHandler

class GetExcludedScanlators(
    private val handler: DatabaseHandler,
) {
    suspend fun await(mangaId: Long): Set<String> =
        handler
            .awaitList {
                excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(mangaId)
            }.toSet()

    fun subscribe(mangaId: Long): Flow<Set<String>> =
        handler
            .subscribeToList {
                excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(mangaId)
            }.map { it.toSet() }
}

