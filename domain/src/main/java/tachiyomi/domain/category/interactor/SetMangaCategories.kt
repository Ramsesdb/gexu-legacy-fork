package tachiyomi.domain.category.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.repository.MangaRepository

class SetMangaCategories(
    private val mangaRepository: MangaRepository,
) {

    suspend fun await(mangaId: Long, categoryIds: List<Long>) {
        try {
            mangaRepository.setMangaCategories(mangaId, categoryIds)
            // Invalidate AI cache since categories affect context
            tachiyomi.domain.ai.AiCacheInvalidator.onCategoriesChanged()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }
}
