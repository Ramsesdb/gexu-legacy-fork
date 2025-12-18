package tachiyomi.domain.ai

/**
 * Supported AI providers that users can configure with their own API keys.
 * BYOK (Bring Your Own Key) architecture - no server costs for us.
 */
enum class AiProvider(
    val displayName: String,
    val baseUrl: String,
    val models: List<String>,
) {
    OPENAI(
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1/chat/completions",
        models = listOf("gpt-4o-mini", "gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo"),
    ),
    GEMINI(
        displayName = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/models",
        models = listOf("gemini-2.0-flash-exp", "gemini-1.5-flash", "gemini-1.5-pro"),
    ),
    ANTHROPIC(
        displayName = "Anthropic Claude",
        baseUrl = "https://api.anthropic.com/v1/messages",
        models = listOf("claude-3-5-sonnet-20241022", "claude-3-haiku-20240307"),
    ),
    OPENROUTER(
        displayName = "OpenRouter (Multi-provider)",
        baseUrl = "https://openrouter.ai/api/v1/chat/completions",
        models = listOf("google/gemini-flash-1.5", "anthropic/claude-3.5-sonnet", "openai/gpt-4o-mini"),
    ),
    CUSTOM(
        displayName = "Custom (OpenAI-compatible)",
        baseUrl = "", // User provides their own URL
        models = emptyList(),
    ),
    ;

    companion object {
        fun fromName(name: String): AiProvider = entries.find { it.name == name } ?: OPENAI
    }
}
