package tachiyomi.domain.ai.repository

import tachiyomi.domain.ai.model.ChatMessage

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
     * Check if the AI service is properly configured (valid API key, etc.)
     */
    suspend fun isConfigured(): Boolean

    /**
     * Test the connection to the AI provider
     * @return Error message if failed, null if successful
     */
    suspend fun testConnection(): String?
}
