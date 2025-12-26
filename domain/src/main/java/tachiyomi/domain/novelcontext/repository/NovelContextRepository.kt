package tachiyomi.domain.novelcontext.repository

import tachiyomi.domain.novelcontext.model.NovelContext

/**
 * Repository interface for NovelContext operations.
 */
interface NovelContextRepository {

    suspend fun getByMangaId(mangaId: Long): NovelContext?

    suspend fun upsert(context: NovelContext)

    suspend fun delete(mangaId: Long)

    suspend fun deleteAll()
}
