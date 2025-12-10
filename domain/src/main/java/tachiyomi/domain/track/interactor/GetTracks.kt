package tachiyomi.domain.track.interactor

import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

class GetTracks(
    private val trackRepository: TrackRepository,
) {
    suspend fun awaitOne(id: Long): Track? =
        try {
            trackRepository.getTrackById(id)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }

    suspend fun await(mangaId: Long): List<Track> =
        try {
            trackRepository.getTracksByMangaId(mangaId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }

    fun subscribe(mangaId: Long): Flow<List<Track>> = trackRepository.getTracksByMangaIdAsFlow(mangaId)
}

