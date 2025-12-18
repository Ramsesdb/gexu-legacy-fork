package tachiyomi.domain.ai.model

import java.util.Date

/**
 * Represents a persisted AI conversation.
 * Can be tied to a specific manga or be a general conversation.
 */
data class AiConversation(
    val id: Long,
    val mangaId: Long?, // null for general conversations
    val title: String,
    val createdAt: Date,
    val updatedAt: Date,
)

/**
 * Represents a persisted AI message within a conversation.
 */
data class AiMessage(
    val id: Long,
    val conversationId: Long,
    val role: MessageRole,
    val content: String,
    val createdAt: Date,
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    ;

    companion object {
        fun fromString(role: String): MessageRole = when (role.lowercase()) {
            "user" -> USER
            "assistant" -> ASSISTANT
            "system" -> SYSTEM
            else -> USER
        }
    }
}

/**
 * Data class for creating a new conversation.
 */
data class AiConversationCreate(
    val mangaId: Long? = null,
    val title: String = "New Conversation",
)

/**
 * Data class for creating a new message.
 */
data class AiMessageCreate(
    val conversationId: Long,
    val role: MessageRole,
    val content: String,
)
