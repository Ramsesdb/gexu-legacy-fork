package tachiyomi.domain.updates.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.repository.UpdatesRepository
import java.time.Instant

class GetUpdates(
    private val repository: UpdatesRepository,
) {
    suspend fun await(
        read: Boolean,
        after: Long,
    ): List<UpdatesWithRelations> = repository.awaitWithRead(read, after, limit = 500)

    fun subscribe(instant: Instant): Flow<List<UpdatesWithRelations>> = repository.subscribeAll(instant.toEpochMilli(), limit = 500)

    fun subscribe(
        read: Boolean,
        after: Long,
    ): Flow<List<UpdatesWithRelations>> = repository.subscribeWithRead(read, after, limit = 500)
}

