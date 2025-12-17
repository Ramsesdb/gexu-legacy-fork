package tachiyomi.data.ai

import kotlinx.coroutines.flow.Flow
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.ai.model.AiConversation
import tachiyomi.domain.ai.model.AiConversationCreate
import tachiyomi.domain.ai.model.AiMessage
import tachiyomi.domain.ai.model.AiMessageCreate
import tachiyomi.domain.ai.model.MessageRole
import tachiyomi.domain.ai.repository.AiConversationRepository
import java.util.Date

class AiConversationRepositoryImpl(
    private val handler: DatabaseHandler,
) : AiConversationRepository {

    override fun getAllConversations(): Flow<List<AiConversation>> {
        return handler.subscribeToList {
            ai_conversationsQueries.getAllConversations(::mapConversation)
        }
    }

    override fun getConversationsForManga(mangaId: Long): Flow<List<AiConversation>> {
        return handler.subscribeToList {
            ai_conversationsQueries.getConversationsForManga(mangaId, ::mapConversation)
        }
    }

    override fun getGeneralConversations(): Flow<List<AiConversation>> {
        return handler.subscribeToList {
            ai_conversationsQueries.getGeneralConversations(::mapConversation)
        }
    }

    override suspend fun getConversationById(id: Long): AiConversation? {
        return handler.awaitOneOrNull {
            ai_conversationsQueries.getConversationById(id, ::mapConversation)
        }
    }

    override fun getMessagesForConversation(conversationId: Long): Flow<List<AiMessage>> {
        return handler.subscribeToList {
            ai_conversationsQueries.getMessagesForConversation(conversationId, ::mapMessage)
        }
    }

    override suspend fun createConversation(conversation: AiConversationCreate): Long {
        return handler.awaitOneExecutable(inTransaction = true) {
            ai_conversationsQueries.insertConversation(
                mangaId = conversation.mangaId,
                title = conversation.title,
                createdAt = Date(),
                updatedAt = Date(),
            )
            ai_conversationsQueries.lastInsertRowId()
        }
    }

    override suspend fun addMessage(message: AiMessageCreate) {
        handler.await(inTransaction = true) {
            ai_conversationsQueries.insertMessage(
                conversationId = message.conversationId,
                role = message.role.name.lowercase(),
                content = message.content,
                createdAt = Date(),
            )
            ai_conversationsQueries.updateConversationTimestamp(
                id = message.conversationId,
                updatedAt = Date(),
            )
        }
    }

    override suspend fun updateConversationTitle(id: Long, title: String) {
        handler.await {
            ai_conversationsQueries.updateConversationTitle(title, id)
        }
    }

    override suspend fun deleteConversation(id: Long) {
        handler.await {
            ai_conversationsQueries.deleteConversation(id)
        }
    }

    override suspend fun deleteConversationsForManga(mangaId: Long) {
        handler.await {
            ai_conversationsQueries.deleteConversationsForManga(mangaId)
        }
    }

    override suspend fun deleteGeneralConversations() {
        handler.await {
            ai_conversationsQueries.deleteGeneralConversations()
        }
    }

    override suspend fun getConversationCount(): Long {
        return handler.awaitOne {
            ai_conversationsQueries.countConversations()
        }
    }

    private fun mapConversation(
        id: Long,
        mangaId: Long?,
        title: String,
        createdAt: Date?,
        updatedAt: Date?,
    ): AiConversation = AiConversation(
        id = id,
        mangaId = mangaId,
        title = title,
        createdAt = createdAt ?: Date(),
        updatedAt = updatedAt ?: Date(),
    )

    private fun mapMessage(
        id: Long,
        conversationId: Long,
        role: String,
        content: String,
        createdAt: Date?,
    ): AiMessage = AiMessage(
        id = id,
        conversationId = conversationId,
        role = MessageRole.fromString(role),
        content = content,
        createdAt = createdAt ?: Date(),
    )
}
