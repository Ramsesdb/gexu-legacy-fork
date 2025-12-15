package tachiyomi.domain.ai.model

/**
 * Represents a message in the AI chat conversation.
 */
data class ChatMessage(
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class Role {
        SYSTEM,  // Context injected by the app (reading context, anti-spoiler rules)
        USER,    // Message from the user
        ASSISTANT, // Response from the AI
    }

    companion object {
        fun system(content: String) = ChatMessage(Role.SYSTEM, content)
        fun user(content: String) = ChatMessage(Role.USER, content)
        fun assistant(content: String) = ChatMessage(Role.ASSISTANT, content)
    }
}

/**
 * Represents the full conversation state.
 */
data class ChatConversation(
    val mangaId: Long? = null,
    val mangaTitle: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)
