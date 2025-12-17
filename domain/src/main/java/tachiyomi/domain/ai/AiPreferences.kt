package tachiyomi.domain.ai

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class AiPreferences(
    private val preferenceStore: PreferenceStore,
) {
    /** Whether AI features are enabled (requires valid API key) */
    fun enabled() = preferenceStore.getBoolean("ai_enabled", false)

    /** Selected AI provider */
    fun provider() = preferenceStore.getString("ai_provider", AiProvider.OPENAI.name)

    /** API key for the selected provider (stored securely) */
    fun apiKey() = preferenceStore.getString("ai_api_key", "")

    /** Selected model for the current provider */
    fun model() = preferenceStore.getString("ai_model", "gpt-4o-mini")

    /** Custom base URL (only used when provider is CUSTOM) */
    fun customBaseUrl() = preferenceStore.getString("ai_custom_base_url", "")

    /** Maximum tokens for AI responses */
    fun maxTokens() = preferenceStore.getInt("ai_max_tokens", 1024)

    /** Temperature for AI responses (0.0 = deterministic, 1.0 = creative) */
    fun temperature() = preferenceStore.getFloat("ai_temperature", 0.7f)

    /** Whether to include manga synopsis in context */
    fun includeDescription() = preferenceStore.getBoolean("ai_include_description", true)

    /** Whether to include reading history in context */
    fun includeHistory() = preferenceStore.getBoolean("ai_include_history", true)

    /** Master toggle: Whether to include ANY reading context in prompts (saves tokens when disabled) */
    fun includeContext() = preferenceStore.getBoolean("ai_include_context", true)

    /** Anti-spoiler mode: limit AI knowledge to user's max read chapter */
    fun antiSpoilerMode() = preferenceStore.getBoolean("ai_anti_spoiler", true)

    /** AI communication tone/style */
    fun tone() = preferenceStore.getString("ai_tone", AiTone.FRIENDLY.name)

    /** Custom user instructions for AI behavior */
    fun customInstructions() = preferenceStore.getString("ai_custom_instructions", "")

    /** Response language preference (empty = same as app) */
    fun responseLanguage() = preferenceStore.getString("ai_response_language", "")

    /** Whether to persist conversations to local database */
    fun persistConversations() = preferenceStore.getBoolean("ai_persist_conversations", true)

    companion object {
        /** Validate that API key looks reasonable (basic check) */
        fun isApiKeyValid(apiKey: String, provider: AiProvider): Boolean {
            if (apiKey.isBlank()) return false
            return when (provider) {
                AiProvider.OPENAI -> apiKey.startsWith("sk-")
                AiProvider.GEMINI -> apiKey.length > 20
                AiProvider.ANTHROPIC -> apiKey.startsWith("sk-ant-")
                AiProvider.OPENROUTER -> apiKey.startsWith("sk-or-")
                AiProvider.CUSTOM -> apiKey.isNotBlank()
            }
        }
    }
}
