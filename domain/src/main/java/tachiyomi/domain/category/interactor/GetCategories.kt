package tachiyomi.domain.category.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

class GetCategories(
    private val categoryRepository: CategoryRepository,
) {
    fun subscribe(): Flow<List<Category>> = categoryRepository.getAllAsFlow()

    fun subscribe(mangaId: Long): Flow<List<Category>> = categoryRepository.getCategoriesByMangaIdAsFlow(mangaId)

    suspend fun await(): List<Category> = categoryRepository.getAll()

    suspend fun await(mangaId: Long): List<Category> = categoryRepository.getCategoriesByMangaId(mangaId)
}

