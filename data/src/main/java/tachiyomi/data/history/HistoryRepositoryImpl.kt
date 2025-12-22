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

    override fun getHistory(query: String): Flow<List<HistoryWithRelations>> {
        return handler.subscribeToList {
            historyViewQueries.history(query, HistoryMapper::mapHistoryWithRelations)
        }
    }

    override suspend fun getLastHistory(): HistoryWithRelations? {
        return handler.awaitOneOrNull {
            historyViewQueries.getLatestHistory(HistoryMapper::mapHistoryWithRelations)
        }
    }

    override suspend fun getTotalReadDuration(): Long {
        return handler.awaitOne { historyQueries.getReadDuration() }
    }

    override suspend fun getReadDurationByManga(limit: Long): List<Pair<Long, Long>> {
        return handler.awaitList {
            historyQueries.getReadDurationByManga(limit) { mangaId, duration ->
                mangaId to (duration ?: 0L)
            }
        }
    }

    override suspend fun getDaysReadLastWeek(): Long {
        val weekAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
        return handler.awaitOne { historyQueries.getDaysReadLastWeek(java.util.Date(weekAgo)) }
    }

    override suspend fun getReadingStreak(): Int {
        val dates = handler.awaitList { historyQueries.getDistinctReadingDays() }
        if (dates.isEmpty()) return 0

        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val calendar = java.util.Calendar.getInstance()
        val todayStr = dateFormat.format(calendar.time)

        calendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
        val yesterdayStr = dateFormat.format(calendar.time)

        // Reset calendar to expected date based on first read
        val firstReadDate = dates.first()
        if (firstReadDate != todayStr && firstReadDate != yesterdayStr) {
            return 0
        }

        // If usage was today, we start checking from today backwards.
        // If usage was yesterday, we start checking from yesterday backwards.
        calendar.time = dateFormat.parse(firstReadDate)!!

        var streak = 0
        for (date in dates) {
            val expectedStr = dateFormat.format(calendar.time)
            if (date == expectedStr) {
                streak++
                calendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
            } else {
                break
            }
        }
        return streak
    }

    override suspend fun getHistoryByMangaId(mangaId: Long): List<History> {
        return handler.awaitList { historyQueries.getHistoryByMangaId(mangaId, HistoryMapper::mapHistory) }
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

    override suspend fun deleteAllHistory(): Boolean {
        return try {
            handler.await { historyQueries.removeAllHistory() }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
            false
        }
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

    override suspend fun getMaxChapterReadForManga(mangaId: Long): Double {
        return handler.awaitOne { historyQueries.getMaxChapterReadForManga(mangaId) }
    }
}
