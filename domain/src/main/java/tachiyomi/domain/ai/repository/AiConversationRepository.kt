package tachiyomi.domain.ai.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.ai.model.AiConversation
import tachiyomi.domain.ai.model.AiConversationCreate
import tachiyomi.domain.ai.model.AiMessage
import tachiyomi.domain.ai.model.AiMessageCreate

/**
 * Repository for managing AI conversation persistence.
 */
interface AiConversationRepository {

    /**
     * Get all conversations ordered by most recent.
     */
    fun getAllConversations(): Flow<List<AiConversation>>

    /**
     * Get conversations for a specific manga.
     */
    fun getConversationsForManga(mangaId: Long): Flow<List<AiConversation>>

    /**
     * Get general conversations (not tied to any manga).
     */
    fun getGeneralConversations(): Flow<List<AiConversation>>

    /**
     * Get a single conversation by ID.
     */
    suspend fun getConversationById(id: Long): AiConversation?

    /**
     * Get all messages for a conversation.
     */
    fun getMessagesForConversation(conversationId: Long): Flow<List<AiMessage>>

    /**
     * Create a new conversation and return its ID.
     */
    suspend fun createConversation(conversation: AiConversationCreate): Long

    /**
     * Add a message to a conversation.
     */
    suspend fun addMessage(message: AiMessageCreate)

    /**
     * Update conversation title.
     */
    suspend fun updateConversationTitle(id: Long, title: String)

    /**
     * Delete a conversation and all its messages.
     */
    suspend fun deleteConversation(id: Long)

    /**
     * Delete all conversations for a manga.
     */
    suspend fun deleteConversationsForManga(mangaId: Long)

    /**
     * Delete all general conversations.
     */
    suspend fun deleteGeneralConversations()

    /**
     * Get total conversation count.
     */
    suspend fun getConversationCount(): Long
}
