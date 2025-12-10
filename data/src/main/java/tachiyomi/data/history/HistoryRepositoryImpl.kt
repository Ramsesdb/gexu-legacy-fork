package tachiyomi.data.history

import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository

class HistoryRepositoryImpl(
    private val handler: DatabaseHandler,
) : HistoryRepository {
    override fun getHistory(query: String): Flow<List<HistoryWithRelations>> =
        handler.subscribeToList {
            historyViewQueries.history(query, HistoryMapper::mapHistoryWithRelations)
        }

    override suspend fun getLastHistory(): HistoryWithRelations? =
        handler.awaitOneOrNull {
            historyViewQueries.getLatestHistory(HistoryMapper::mapHistoryWithRelations)
        }

    override suspend fun getTotalReadDuration(): Long = handler.awaitOne { historyQueries.getReadDuration() }

    override suspend fun getHistoryByMangaId(mangaId: Long): List<History> =
        handler.awaitList {
            historyQueries.getHistoryByMangaId(mangaId, HistoryMapper::mapHistory)
        }

    override suspend fun resetHistory(historyId: Long) {
        try {
            handler.await { historyQueries.resetHistoryById(historyId) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun resetHistoryByMangaId(mangaId: Long) {
        try {
            handler.await { historyQueries.resetHistoryByMangaId(mangaId) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun deleteAllHistory(): Boolean =
        try {
            handler.await { historyQueries.removeAllHistory() }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
            false
        }

    override suspend fun upsertHistory(historyUpdate: HistoryUpdate) {
        try {
            handler.await {
                historyQueries.upsert(
                    historyUpdate.chapterId,
                    historyUpdate.readAt,
                    historyUpdate.sessionReadDuration,
                )
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }
}

