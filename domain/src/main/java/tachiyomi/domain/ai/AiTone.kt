package tachiyomi.domain.ai

/**
 * Available AI communication tones/styles.
 * Each tone affects how Gexu AI phrases its responses.
 */
enum class AiTone(val displayName: String, val systemPrompt: String) {
    FRIENDLY(
        displayName = "Friendly",
        systemPrompt = "Respond in a warm, friendly, and approachable manner. " +
            "Use casual language and be encouraging.",
    ),
    FORMAL(
        displayName = "Formal",
        systemPrompt = "Respond in a professional and formal manner. " +
            "Use proper grammar and avoid slang or colloquialisms.",
    ),
    CASUAL(
        displayName = "Casual",
        systemPrompt = "Respond in a very relaxed and casual way, like chatting with a close friend. " +
            "Feel free to use informal expressions.",
    ),
    ENTHUSIASTIC(
        displayName = "Enthusiastic",
        systemPrompt = "Respond with high energy and enthusiasm! " +
            "Show excitement about the topics being discussed. Use exclamation marks and positive language.",
    ),
    CONCISE(
        displayName = "Concise",
        systemPrompt = "Keep responses brief and to the point. " +
            "Avoid unnecessary elaboration. Be direct and efficient with words.",
    ),
    ;

    companion object {
        fun fromName(name: String): AiTone {
            return entries.find { it.name == name } ?: FRIENDLY
        }
    }
}
