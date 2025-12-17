package eu.kanade.tachiyomi.ui.ai

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.ai.AiPreferences
import tachiyomi.domain.ai.AiProvider
import tachiyomi.domain.ai.model.AiConversation
import tachiyomi.domain.ai.model.AiConversationCreate
import tachiyomi.domain.ai.model.AiMessage
import tachiyomi.domain.ai.model.AiMessageCreate
import tachiyomi.domain.ai.model.ChatMessage
import tachiyomi.domain.ai.model.MessageRole
import tachiyomi.domain.ai.repository.AiConversationRepository
import tachiyomi.domain.ai.repository.AiRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AiChatScreenModel(
    private val aiPreferences: AiPreferences = Injekt.get(),
    private val aiRepository: AiRepository = Injekt.get(),
    private val conversationRepository: AiConversationRepository = Injekt.get(),
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
                    persistEnabled = aiPreferences.persistConversations().get(),
                )
            }
        }

        // Load saved conversations
        screenModelScope.launchIO {
            conversationRepository.getAllConversations().collectLatest { conversations ->
                mutableState.update { it.copy(savedConversations = conversations) }
            }
        }
    }

    fun toggleHistoryDrawer() {
        mutableState.update { it.copy(showHistoryDrawer = !it.showHistoryDrawer) }
    }

    fun closeHistoryDrawer() {
        mutableState.update { it.copy(showHistoryDrawer = false) }
    }

    fun deleteConversationFromHistory(conversationId: Long) {
        screenModelScope.launchIO {
            conversationRepository.deleteConversation(conversationId)
        }
    }

    /**
     * Load an existing conversation by ID.
     */
    fun loadConversation(conversationId: Long) {
        screenModelScope.launchIO {
            val conversation = conversationRepository.getConversationById(conversationId) ?: return@launchIO

            mutableState.update { it.copy(currentConversationId = conversationId) }

            // Load messages for this conversation
            conversationRepository.getMessagesForConversation(conversationId).collectLatest { messages ->
                val chatMessages = messages.map { it.toChatMessage() }
                mutableState.update { it.copy(messages = chatMessages) }
            }
        }
    }

    /**
     * Start a new conversation (optionally tied to a manga).
     */
    fun startNewConversation() {
        mutableState.update {
            it.copy(
                messages = emptyList(),
                currentConversationId = null,
                error = null,
            )
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
            // Persist user message if enabled (pass content for title on first message)
            val conversationId = ensureConversationExists(content)
            if (shouldPersist() && conversationId != null) {
                conversationRepository.addMessage(
                    AiMessageCreate(
                        conversationId = conversationId,
                        role = MessageRole.USER,
                        content = content,
                    )
                )
            }

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

                    // Persist assistant response if enabled
                    if (shouldPersist() && conversationId != null) {
                        conversationRepository.addMessage(
                            AiMessageCreate(
                                conversationId = conversationId,
                                role = MessageRole.ASSISTANT,
                                content = response.content,
                            )
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

    /**
     * Ensure a conversation exists for persistence, creating one if needed.
     * Uses the first message content as title (truncated to 50 chars).
     * Returns the conversation ID, or null if persistence is disabled.
     */
    private suspend fun ensureConversationExists(firstMessageContent: String? = null): Long? {
        if (!shouldPersist()) return null

        val currentId = state.value.currentConversationId
        if (currentId != null) return currentId

        // Create new conversation with first message as title
        val currentState = state.value
        val title = when {
            currentState.currentMangaTitle != null -> currentState.currentMangaTitle
            firstMessageContent != null -> firstMessageContent.take(50).let {
                if (firstMessageContent.length > 50) "$it..." else it
            }
            else -> "Chat ${System.currentTimeMillis()}"
        }

        val newId = conversationRepository.createConversation(
            AiConversationCreate(
                mangaId = currentState.currentMangaId,
                title = title,
            )
        )

        mutableState.update { it.copy(currentConversationId = newId) }
        return newId
    }

    private fun shouldPersist(): Boolean {
        return state.value.persistEnabled
    }

    fun clearConversation() {
        val conversationId = state.value.currentConversationId

        // Delete from database if it was persisted
        if (conversationId != null && shouldPersist()) {
            screenModelScope.launchIO {
                conversationRepository.deleteConversation(conversationId)
            }
        }

        mutableState.update {
            it.copy(
                messages = emptyList(),
                currentConversationId = null,
                error = null,
            )
        }
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

        // Check if context injection is enabled (saves tokens when disabled)
        val includeContext = aiPreferences.includeContext().get()

        if (includeContext) {
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
        }

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
        val messages: List<ChatMessage> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val showApiKeySetup: Boolean = false,
        val selectedProvider: AiProvider = AiProvider.OPENAI,
        val apiKey: String = "",
        val selectedModel: String = "",
        val currentMangaId: Long? = null,
        val currentMangaTitle: String? = null,
        // Persistence state
        val currentConversationId: Long? = null,
        val persistEnabled: Boolean = true,
        // History drawer state
        val showHistoryDrawer: Boolean = false,
        val savedConversations: List<AiConversation> = emptyList(),
    )
}

/**
 * Extension to convert persisted AiMessage to in-memory ChatMessage.
 */
private fun AiMessage.toChatMessage(): ChatMessage {
    return when (role) {
        MessageRole.USER -> ChatMessage.user(content)
        MessageRole.ASSISTANT -> ChatMessage.assistant(content)
        MessageRole.SYSTEM -> ChatMessage.system(content)
    }
}
