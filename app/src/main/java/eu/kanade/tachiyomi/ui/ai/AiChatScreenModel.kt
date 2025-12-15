package eu.kanade.tachiyomi.ui.ai

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.ai.AiPreferences
import tachiyomi.domain.ai.AiProvider
import tachiyomi.domain.ai.model.ChatMessage
import tachiyomi.domain.ai.repository.AiRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AiChatScreenModel(
    private val aiPreferences: AiPreferences = Injekt.get(),
    private val aiRepository: AiRepository = Injekt.get(),
    private val getReadingContext: tachiyomi.domain.ai.GetReadingContext = Injekt.get(),
) : StateScreenModel<AiChatScreenModel.State>(State()) {

    init {
        // Check if API key is configured on init
        screenModelScope.launch {
            val isConfigured = aiRepository.isConfigured()
            mutableState.update { it.copy(showApiKeySetup = !isConfigured) }

            // Load saved preferences
            mutableState.update { state ->
                state.copy(
                    selectedProvider = AiProvider.fromName(aiPreferences.provider().get()),
                    apiKey = aiPreferences.apiKey().get(),
                    selectedModel = aiPreferences.model().get(),
                )
            }
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank()) return

        // Add user message immediately
        val userMessage = ChatMessage.user(content)
        mutableState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isLoading = true,
                error = null,
            )
        }

        screenModelScope.launchIO {
            // Build messages with system context
            val systemPrompt = buildSystemPrompt()
            val allMessages = listOf(ChatMessage.system(systemPrompt)) + state.value.messages

            val result = aiRepository.sendMessage(allMessages)

            result.fold(
                onSuccess = { response ->
                    mutableState.update { state ->
                        state.copy(
                            messages = state.messages + response,
                            isLoading = false,
                        )
                    }
                },
                onFailure = { error ->
                    mutableState.update { state ->
                        state.copy(
                            isLoading = false,
                            error = error.message ?: "Unknown error",
                        )
                    }
                },
            )
        }
    }

    fun clearConversation() {
        mutableState.update { it.copy(messages = emptyList(), error = null) }
    }

    fun setCurrentManga(mangaId: Long?, mangaTitle: String? = null) {
        mutableState.update { it.copy(currentMangaId = mangaId, currentMangaTitle = mangaTitle) }
    }

    fun showApiKeySetup() {
        mutableState.update { it.copy(showApiKeySetup = true) }
    }

    fun dismissApiKeySetup() {
        mutableState.update { it.copy(showApiKeySetup = false) }
    }

    fun setProvider(provider: AiProvider) {
        mutableState.update { it.copy(selectedProvider = provider) }
    }

    fun setApiKey(apiKey: String) {
        mutableState.update { it.copy(apiKey = apiKey) }
    }

    fun saveApiKey() {
        val currentState = state.value
        screenModelScope.launch {
            aiPreferences.provider().set(currentState.selectedProvider.name)
            aiPreferences.apiKey().set(currentState.apiKey)

            // Set default model for the provider
            val defaultModel = currentState.selectedProvider.models.firstOrNull() ?: ""
            aiPreferences.model().set(defaultModel)

            mutableState.update { it.copy(
                showApiKeySetup = false,
                selectedModel = defaultModel,
            ) }
        }
    }

    private suspend fun buildSystemPrompt(): String = buildString {
        appendLine("You are Gexu AI, a friendly and knowledgeable reading companion for manga, manhwa, and light novels.")
        appendLine()

        // Inject Context Adaptively
        val currentState = state.value
        if (currentState.currentMangaId != null) {
            // READER MODE: Focus on current manga + brief profile for general questions
            append(getReadingContext.getContextForManga(currentState.currentMangaId, null))
            appendLine()
            append(getReadingContext.getBriefProfile())
            appendLine()
            appendLine("PRIORITY: User is reading a specific manga. Focus answers on THIS content unless they ask for general recommendations.")
        } else {
            // GENERAL CHAT MODE: Full user profile
            append(getReadingContext.getGlobalContext())
        }
        appendLine()

        appendLine("Your role is to:")
        appendLine("- Help users discover new series based on their preferences")
        appendLine("- Answer questions about manga/manhwa/novel genres, tropes, and terminology")
        appendLine("- Discuss plot points and characters (while avoiding spoilers)")
        appendLine("- Recommend similar series")
        appendLine()
        appendLine("Guidelines:")
        appendLine("- Be enthusiastic and friendly")
        appendLine("- Keep responses concise unless asked for detail")
        appendLine("- Always avoid major spoilers unless explicitly asked")
        appendLine("- If you don't know something, say so honestly")
    }

    data class State(
        // ... (state remains same)
        val messages: List<ChatMessage> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val showApiKeySetup: Boolean = false,
        val selectedProvider: AiProvider = AiProvider.OPENAI,
        val apiKey: String = "",
        val selectedModel: String = "",
        val currentMangaId: Long? = null,
        val currentMangaTitle: String? = null,
    )
}
