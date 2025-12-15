package tachiyomi.domain.ai.interactor

import tachiyomi.domain.ai.AiPreferences
import tachiyomi.domain.ai.GetReadingContext
import tachiyomi.domain.ai.model.ChatConversation
import tachiyomi.domain.ai.model.ChatMessage
import tachiyomi.domain.ai.repository.AiRepository

/**
 * Use case for sending messages to the AI chat companion.
 * Handles building context and managing conversation state.
 */
class SendChatMessage(
    private val aiRepository: AiRepository,
    private val getReadingContext: GetReadingContext,
    private val aiPreferences: AiPreferences,
) {
    /**
     * Send a user message and get AI response.
     *
     * @param userMessage The user's question/message
     * @param mangaId Optional manga ID to provide context
     * @param currentChapterId Optional chapter ID for more specific context
     * @param conversationHistory Previous messages in the conversation
     */
    suspend fun execute(
        userMessage: String,
        mangaId: Long? = null,
        currentChapterId: Long? = null,
        conversationHistory: List<ChatMessage> = emptyList(),
    ): Result<ChatMessage> {
        // Build the messages list with context
        val messages = buildList {
            // 1. Add system context based on current reading state
            if (mangaId != null) {
                val context = getReadingContext.getContextForManga(mangaId, currentChapterId)
                add(ChatMessage.system(buildSystemPrompt(context)))
            } else {
                add(ChatMessage.system(buildGeneralSystemPrompt()))
            }

            // 2. Add conversation history
            addAll(conversationHistory.filter { it.role != ChatMessage.Role.SYSTEM })

            // 3. Add the new user message
            add(ChatMessage.user(userMessage))
        }

        return aiRepository.sendMessage(messages)
    }

    private fun buildSystemPrompt(readingContext: String): String = buildString {
        appendLine("You are Gexu AI, a helpful reading companion for manga, manhwa, and light novels.")
        appendLine()
        appendLine("USER'S CURRENT READING CONTEXT:")
        appendLine(readingContext)
        appendLine()
        appendLine("INSTRUCTIONS:")
        appendLine("- Answer questions about the series the user is reading")
        appendLine("- Help explain plot points, characters, or terminology")
        appendLine("- NEVER spoil content beyond what the user has read (see context above)")
        appendLine("- If asked about future events, politely decline and mention anti-spoiler protection")
        appendLine("- Be friendly and enthusiastic about the content")
        appendLine("- Keep responses concise unless asked for detail")
    }

    private fun buildGeneralSystemPrompt(): String = buildString {
        appendLine("You are Gexu AI, a helpful reading companion for manga, manhwa, and light novels.")
        appendLine()
        appendLine("INSTRUCTIONS:")
        appendLine("- Help users discover new series based on their preferences")
        appendLine("- Answer general questions about manga/manhwa/novel genres and tropes")
        appendLine("- Recommend series but avoid major spoilers")
        appendLine("- Be friendly and knowledgeable about Asian comics and fiction")
    }
}
