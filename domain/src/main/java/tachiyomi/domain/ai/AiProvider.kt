package tachiyomi.domain.ai

/**
 * Supported AI providers that users can configure with their own API keys.
 * BYOK (Bring Your Own Key) architecture - no server costs for us.
 */
enum class AiProvider(
    val displayName: String,
    val baseUrl: String,
    val models: List<String>,
    val defaultModel: String,
) {
    OPENAI(
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1/chat/completions",
        models = listOf(
            // GPT-5 Series (Dec 2025)
            "gpt-5.2",
            "gpt-5.2-mini",
            "gpt-5",
            "gpt-5-mini",
            // GPT-4 Series
            "gpt-4.1",
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4-turbo",
            // Reasoning Models (o-series)
            "o4-mini",
            "o3-mini",
            "o1",
            "o1-mini",
            // Legacy
            "gpt-3.5-turbo",
        ),
        defaultModel = "gpt-4o-mini",
    ),
    GEMINI(
        displayName = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/models",
        models = listOf(
            // Gemini 3 Series (Preview - Dec 2025)
            "gemini-3-pro-preview",
            "gemini-3-flash-preview",
            // Gemini 2.5 Series (Stable)
            "gemini-2.5-pro",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            // Gemini 2.0 Series
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-2.0-flash-exp",
        ),
        defaultModel = "gemini-2.5-flash",
    ),
    ANTHROPIC(
        displayName = "Anthropic Claude",
        baseUrl = "https://api.anthropic.com/v1/messages",
        models = listOf(
            // Claude 4.5 Series (Dec 2025)
            "claude-opus-4.5-20251124",
            "claude-sonnet-4.5-20250929",
            "claude-haiku-4.5-20251015",
            // Claude 4 Series
            "claude-opus-4.1-20250805",
            "claude-sonnet-4-20250522",
            // Claude 3.5 Series (Legacy)
            "claude-3-5-sonnet-20241022",
            "claude-3-5-haiku-20241022",
        ),
        defaultModel = "claude-sonnet-4.5-20250929",
    ),
    OPENROUTER(
        displayName = "OpenRouter (Multi-provider)",
        baseUrl = "https://openrouter.ai/api/v1/chat/completions",
        models = listOf(
            // DeepSeek (Popular Chinese models)
            "deepseek/deepseek-r1",
            "deepseek/deepseek-v3",
            "deepseek/deepseek-coder-v2",
            // Qwen (Alibaba)
            "qwen/qwen3-coder",
            "qwen/qwen-2.5-72b-instruct",
            // Google via OpenRouter
            "google/gemini-2.5-flash",
            "google/gemini-2.5-pro",
            // Anthropic via OpenRouter
            "anthropic/claude-sonnet-4.5",
            "anthropic/claude-opus-4.5",
            // OpenAI via OpenRouter
            "openai/gpt-4o",
            "openai/gpt-4o-mini",
            // Meta Llama
            "meta-llama/llama-4-maverick",
            "meta-llama/llama-3.3-70b-instruct",
        ),
        defaultModel = "google/gemini-2.5-flash",
    ),
    CUSTOM(
        displayName = "Custom (OpenAI-compatible)",
        baseUrl = "", // User provides their own URL
        models = emptyList(),
        defaultModel = "",
    ),
    ;

    companion object {
        fun fromName(name: String): AiProvider = entries.find { it.name == name } ?: OPENAI
    }
}
