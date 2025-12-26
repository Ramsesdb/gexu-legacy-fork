package tachiyomi.data.novelcontext

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.novelcontext.model.NovelContext
import tachiyomi.domain.novelcontext.repository.NovelContextRepository

class NovelContextRepositoryImpl(
    private val handler: DatabaseHandler,
) : NovelContextRepository {

    override suspend fun getByMangaId(mangaId: Long): NovelContext? {
        return try {
            handler.awaitOneOrNull {
                novel_contextQueries.getByMangaId(mangaId, ::mapNovelContext)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error getting novel context for manga $mangaId" }
            null
        }
    }

    override suspend fun upsert(context: NovelContext) {
        try {
            handler.await {
                novel_contextQueries.upsert(
                    mangaId = context.mangaId,
                    summaryText = context.summaryText,
                    lastPage = context.summaryLastPage.toLong(),
                    chapterNumber = context.summaryChapterNumber,
                    updatedAt = context.updatedAt,
                )
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error upserting novel context for manga ${context.mangaId}" }
        }
    }

    override suspend fun delete(mangaId: Long) {
        try {
            handler.await { novel_contextQueries.delete(mangaId) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error deleting novel context for manga $mangaId" }
        }
    }

    override suspend fun deleteAll() {
        try {
            handler.await { novel_contextQueries.deleteAll() }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error deleting all novel contexts" }
        }
    }

    private fun mapNovelContext(
        id: Long,
        mangaId: Long,
        summaryText: String?,
        summaryLastPage: Long?,
        summaryChapterNumber: Double?,
        updatedAt: Long,
    ): NovelContext = NovelContext(
        id = id,
        mangaId = mangaId,
        summaryText = summaryText,
        summaryLastPage = summaryLastPage?.toInt() ?: 0,
        summaryChapterNumber = summaryChapterNumber ?: 0.0,
        updatedAt = updatedAt,
    )
}
