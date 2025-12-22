package tachiyomi.domain.ai.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.ai.model.ChatMessage
import tachiyomi.domain.ai.model.StreamChunk

/**
 * Repository interface for AI chat operations.
 * Implementation will handle API calls to the selected provider.
 */
interface AiRepository {
    /**
     * Send a message to the AI and get a response.
     * @param messages The conversation history including system context
     * @return The AI's response message
     */
    suspend fun sendMessage(messages: List<ChatMessage>): Result<ChatMessage>

    /**
     * Stream a message from the AI with real-time chunks.
     * @param messages The conversation history including system context
     * @return Flow of StreamChunk representing incremental response
     */
    fun streamMessage(messages: List<ChatMessage>): Flow<StreamChunk>

    /**
     * Check if the AI service is properly configured (valid API key, etc.)
     */
    suspend fun isConfigured(): Boolean

    /**
     * Test the connection to the AI provider
     * @return Error message if failed, null if successful
     */
    suspend fun testConnection(): String?
}
