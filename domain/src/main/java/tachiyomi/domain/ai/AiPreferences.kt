package tachiyomi.domain.ai

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class AiPreferences(
    private val preferenceStore: PreferenceStore,
) {
    /** Whether AI features are enabled (requires valid API key) */
    fun enabled() = preferenceStore.getBoolean("ai_enabled", false)

    /** Selected AI provider (primary provider) */
    fun provider() = preferenceStore.getString("ai_provider", AiProvider.OPENAI.name)

    // ========== Per-Provider API Keys ==========

    /** OpenAI API key */
    fun openaiApiKey() = preferenceStore.getString("ai_openai_api_key", "")

    /** Google Gemini API key */
    fun geminiApiKey() = preferenceStore.getString("ai_gemini_api_key", "")

    /** Anthropic Claude API key */
    fun anthropicApiKey() = preferenceStore.getString("ai_anthropic_api_key", "")

    /** OpenRouter API key */
    fun openRouterApiKey() = preferenceStore.getString("ai_openrouter_api_key", "")

    /** Custom provider API key */
    fun customApiKey() = preferenceStore.getString("ai_custom_api_key", "")

    /** Legacy single API key (for migration) */
    private fun legacyApiKey() = preferenceStore.getString("ai_api_key", "")

    /**
     * Get the API key for the current primary provider.
     * This is the main entry point used by AiRepositoryImpl.
     */
    fun apiKey(): Preference<String> {
        val currentProvider = AiProvider.fromName(provider().get())
        return getApiKeyPreference(currentProvider)
    }

    /** Get the preference object for a specific provider's API key */
    fun getApiKeyPreference(provider: AiProvider): Preference<String> {
        return when (provider) {
            AiProvider.OPENAI -> openaiApiKey()
            AiProvider.GEMINI -> geminiApiKey()
            AiProvider.ANTHROPIC -> anthropicApiKey()
            AiProvider.OPENROUTER -> openRouterApiKey()
            AiProvider.CUSTOM -> customApiKey()
        }
    }

    /** Get the API key value for a specific provider */
    fun getApiKeyForProvider(provider: AiProvider): String {
        return getApiKeyPreference(provider).get()
    }

    /** Set the API key for a specific provider */
    fun setApiKeyForProvider(provider: AiProvider, key: String) {
        getApiKeyPreference(provider).set(key)
    }

    /** Get list of providers that have a valid API key configured */
    fun getConfiguredProviders(): List<AiProvider> {
        return AiProvider.entries.filter { provider ->
            val key = getApiKeyForProvider(provider)
            Companion.isApiKeyValid(key, provider)
        }
    }

    /**
     * Migrate legacy single API key to per-provider storage.
     * Should be called once during app initialization.
     */
    fun migrateToMultiProvider() {
        val legacyKey = legacyApiKey().get()
        if (legacyKey.isNotBlank()) {
            val currentProvider = AiProvider.fromName(provider().get())
            // Only migrate if the new provider key is empty
            if (getApiKeyForProvider(currentProvider).isBlank()) {
                setApiKeyForProvider(currentProvider, legacyKey)
            }
            // Clear the legacy key after migration
            legacyApiKey().set("")
        }
    }

    /** Selected model for the current provider (may be incompatible if provider changed) */
    fun model() = preferenceStore.getString("ai_model", AiProvider.OPENAI.defaultModel)

    /**
     * Get the effective model for the current provider.
     * Falls back to provider's default if stored model is incompatible.
     */
    fun getEffectiveModel(): String {
        val currentProvider = AiProvider.fromName(provider().get())
        val storedModel = model().get()

        // If stored model is valid for current provider, use it
        if (currentProvider.models.contains(storedModel) || currentProvider == AiProvider.CUSTOM) {
            return storedModel
        }

        // Otherwise, use provider's default
        return currentProvider.defaultModel
    }

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

    /** Whether to use local embedding model (offline mode) */
    fun useLocalEmbeddings() = preferenceStore.getBoolean("ai_use_local_embeddings", false)

    /** Whether the local embedding model has been downloaded */
    fun localModelDownloaded() = preferenceStore.getBoolean("ai_local_model_downloaded", false)

    /** Preferred embedding source: "cloud", "local", "hybrid" */
    fun embeddingSource() = preferenceStore.getString("ai_embedding_source", "hybrid")

    // ========== Web Search (Gemini Grounding) ==========

    /** Enable web search grounding for AI responses */
    fun enableWebSearch() = preferenceStore.getBoolean("ai_enable_web_search", false)

    /** Gemini API key for web search (required if primary provider is not Gemini) */
    fun geminiSearchApiKey() = preferenceStore.getString("ai_gemini_search_key", "")

    /**
     * Get Gemini key for web search:
     * - If primary = Gemini → use geminiSearchApiKey if set, else use primary apiKey
     * - If primary = Other → must use geminiSearchApiKey
     */
    fun getGeminiKeyForSearch(): String? {
        val primaryProvider = AiProvider.fromName(provider().get())
        val searchKey = geminiSearchApiKey().get()

        return if (primaryProvider == AiProvider.GEMINI) {
            // Gemini user: use override key if set, else use primary key
            searchKey.ifBlank { apiKey().get().ifBlank { null } }
        } else {
            // Other provider: must have dedicated Gemini key
            searchKey.ifBlank { null }
        }
    }

    /** Check if web search is available (toggle enabled + key exists) */
    fun isWebSearchAvailable() = enableWebSearch().get() && getGeminiKeyForSearch() != null

    /** Check if web search CAN be enabled (key exists, regardless of toggle state) */
    fun canEnableWebSearch() = getGeminiKeyForSearch() != null

    // ========== Reading Buddy ==========

    /**
     * Whether "Reading Buddy" mode is enabled.
     * Defaults to TRUE if a Gemini API key is configured.
     */
    fun readingBuddyEnabled() = preferenceStore.getBoolean("ai_reading_buddy_enabled", true)

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
