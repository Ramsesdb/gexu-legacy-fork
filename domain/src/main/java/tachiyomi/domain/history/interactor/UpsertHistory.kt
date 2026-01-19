package tachiyomi.domain.history.interactor

import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.repository.HistoryRepository

class UpsertHistory(
    private val historyRepository: HistoryRepository,
) {

    suspend fun await(historyUpdate: HistoryUpdate) {
        historyRepository.upsertHistory(historyUpdate)
        // Invalidate AI cache since history affects context
        tachiyomi.domain.ai.AiCacheInvalidator.onHistoryChanged()
    }
}
