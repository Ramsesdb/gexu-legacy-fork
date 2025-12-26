package eu.kanade.tachiyomi.ui.ai

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.ai.AiPreferences
import tachiyomi.domain.ai.AiProvider
import tachiyomi.domain.ai.model.AiConversation
import tachiyomi.domain.ai.model.AiConversationCreate
import tachiyomi.domain.ai.model.AiMessage
import tachiyomi.domain.ai.model.AiMessageCreate
import tachiyomi.domain.ai.model.ChatMessage
import tachiyomi.domain.ai.model.MessageRole
import tachiyomi.domain.ai.model.StreamChunk
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

    // Reading Buddy Provider (injected when chat opened from Reader)
    private var textContentProvider: tachiyomi.domain.ai.TextContentProvider? = null

    init {
        // Check if ANY provider has an API key configured
        // Only show wizard if NO providers are configured at all
        screenModelScope.launch {
            val configuredProviders = aiPreferences.getConfiguredProviders()
            val shouldShowWizard = configuredProviders.isEmpty()

            // Load saved preferences
            val currentProvider = AiProvider.fromName(aiPreferences.provider().get())
            mutableState.update { state ->
                state.copy(
                    showApiKeySetup = shouldShowWizard,
                    selectedProvider = currentProvider,
                    apiKey = aiPreferences.getApiKeyForProvider(currentProvider),
                    selectedModel = aiPreferences.model().get(),
                    persistEnabled = aiPreferences.persistConversations().get(),
                    isReadingBuddyGloballyEnabled = aiPreferences.readingBuddyEnabled().get(),
                )
            }
        }

        // Load saved conversations
        screenModelScope.launchIO {
            conversationRepository.getAllConversations().collectLatest { conversations ->
                mutableState.update { it.copy(savedConversations = conversations) }
            }
        }

        // Observe Web Search preference
        screenModelScope.launchIO {
            aiPreferences.enableWebSearch().changes().collectLatest { enabled ->
                mutableState.update { it.copy(isWebSearchEnabled = enabled) }
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
        val image = state.value.attachedImage
        val userMessage = ChatMessage.user(content, image)

        // Add user message + placeholder for streaming assistant response
        mutableState.update { state ->
            state.copy(
                messages = state.messages + userMessage + ChatMessage.assistant(""),
                isLoading = true,
                error = null,
                attachedImage = null, // Clear after sending
            )
        }

        screenModelScope.launchIO {
            try {
                // Persist user message if enabled (pass content for title on first message)
                val conversationId = ensureConversationExists(content)
                if (shouldPersist() && conversationId != null) {
                    conversationRepository.addMessage(
                        AiMessageCreate(
                            conversationId = conversationId,
                            role = MessageRole.USER,
                            content = content,
                        ),
                    )
                }

                // Build messages with system context (includes RAG search based on query)
                val systemPrompt = buildSystemPrompt(content)
                // Exclude the empty placeholder from messages sent to API
                val messagesForApi = state.value.messages.dropLast(1)
                val allMessages = listOf(ChatMessage.system(systemPrompt)) + messagesForApi

                var fullResponse = ""

                // Use streaming for real-time response display
                aiRepository.streamMessage(allMessages).collect { chunk ->
                    when (chunk) {
                        is StreamChunk.Text -> {
                            fullResponse += chunk.delta
                            // Update the last message (assistant placeholder) with accumulated text
                            mutableState.update { state ->
                                val updatedMessages = state.messages.toMutableList()
                                if (updatedMessages.isNotEmpty()) {
                                    updatedMessages[updatedMessages.lastIndex] =
                                        ChatMessage.assistant(fullResponse)
                                }
                                state.copy(messages = updatedMessages)
                            }
                        }
                        is StreamChunk.Done -> {
                            mutableState.update { it.copy(isLoading = false) }

                            // Persist assistant response if enabled
                            if (shouldPersist() && conversationId != null && fullResponse.isNotBlank()) {
                                conversationRepository.addMessage(
                                    AiMessageCreate(
                                        conversationId = conversationId,
                                        role = MessageRole.ASSISTANT,
                                        content = fullResponse,
                                    ),
                                )
                            }
                        }
                        is StreamChunk.Error -> {
                            // Remove the empty placeholder on error
                            mutableState.update { state ->
                                val messagesWithoutPlaceholder = if (state.messages.isNotEmpty() &&
                                    state.messages.last().content.isEmpty()
                                ) {
                                    state.messages.dropLast(1)
                                } else {
                                    state.messages
                                }
                                state.copy(
                                    messages = messagesWithoutPlaceholder,
                                    isLoading = false,
                                    error = chunk.message,
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Catch any other exceptions (e.g. from buildSystemPrompt or cancellation)
                mutableState.update { state ->
                    val messagesWithoutPlaceholder = if (state.messages.isNotEmpty() &&
                        state.messages.last().content.isEmpty()
                    ) {
                        state.messages.dropLast(1)
                    } else {
                        state.messages
                    }
                    state.copy(
                        messages = messagesWithoutPlaceholder,
                        isLoading = false,
                        error = e.localizedMessage ?: "Unknown error",
                    )
                }
            }
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
            ),
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

    fun toggleWebSearch() {
        if (!aiPreferences.canEnableWebSearch()) {
            mutableState.update { it.copy(error = "Web search unavailable: Requesting Gemini API key in settings") }
            return
        }
        val current = aiPreferences.enableWebSearch().get()
        aiPreferences.enableWebSearch().set(!current)
    }

    fun saveApiKey() {
        val currentState = state.value
        screenModelScope.launch {
            // Save provider as primary
            aiPreferences.provider().set(currentState.selectedProvider.name)

            // Save API key for this specific provider (multi-provider support)
            aiPreferences.setApiKeyForProvider(currentState.selectedProvider, currentState.apiKey)

            // Set default model for the provider
            val defaultModel = currentState.selectedProvider.models.firstOrNull() ?: ""
            aiPreferences.model().set(defaultModel)

            mutableState.update {
                it.copy(
                    showApiKeySetup = false,
                    selectedModel = defaultModel,
                )
            }
        }
    }

    private suspend fun buildSystemPrompt(userQuery: String = ""): String = buildString {
        // Apply user's selected tone
        val tone = tachiyomi.domain.ai.AiTone.fromName(aiPreferences.tone().get())

        appendLine("You are Gexu AI, a knowledgeable reading companion for manga, manhwa, and light novels.")
        appendLine()

        // CRITICAL: Language adaptation rule
        appendLine("LANGUAGE RULE (MANDATORY):")
        appendLine("- ALWAYS detect the language of the user's message and respond in that SAME language.")
        appendLine("- Apply this rule to ALL responses without exception.")
        appendLine()

        appendLine("COMMUNICATION STYLE:")
        appendLine(tone.systemPrompt)
        appendLine()

        // Check if context injection is enabled
        val includeContext = aiPreferences.includeContext().get()
        val currentState = state.value

        if (includeContext) {
            if (currentState.currentMangaId != null) {
                // READER MODE: Include basic manga context (title, progress, anti-spoiler)
                // This is always needed since user is actively reading
                append(getReadingContext.getContextForManga(currentState.currentMangaId, null))
                appendLine()
                appendLine(
                    "PRIORITY: User is reading this manga. Focus answers on it unless they ask otherwise.",
                )
            } else {
                // GENERAL CHAT MODE: Minimal context, use tools for data
                appendLine("USER'S LIBRARY ACCESS:")
                appendLine("You have access to tools to query the user's manga library:")
                appendLine("- get_library_stats: Get library statistics and top genres")
                appendLine("- search_library(query): Search manga by title, genre, or author")
                appendLine(
                    "- get_full_manga_context(title): Get FULL details about a manga " +
                        "(description, notes, progress, etc.)",
                )
                appendLine("- get_user_notes(manga_title?): Get user's reading notes")
                appendLine("- get_reading_history(limit?): Get recent reading history")
                appendLine()
                appendLine("USE THESE TOOLS when you need specific information about the user's library.")
                appendLine("Do NOT guess or make up information - call the appropriate tool.")

                if (currentState.isReadingBuddyEnabled) {
                    appendLine()
                    appendLine("READING BUDDY MODE ACTIVE:")
                    appendLine(
                        "The user wants to discuss a specific story. " +
                            "IMMEDIATELY check if their message mentions a story title.",
                    )
                    appendLine(
                        "If yes, use `get_full_manga_context(title)` " +
                            "to retrieve its plot summary and details.",
                    )
                    appendLine("If no title is clear, ask them which story they want to talk about.")
                }
            }

            // Reading Buddy Context Injection (If toggle ON and provider available)
            // This injects Tier 3 (recent pages) context when in PDF/novel reader
            if (
                state.value.isReadingBuddyEnabled &&
                textContentProvider != null &&
                currentState.currentMangaId != null
            ) {
                logcat { "AiChatScreenModel: Requesting recent text from provider..." }
                val tier3Text = textContentProvider?.getRecentText(4) ?: ""
                logcat { "AiChatScreenModel: Received tier3Text length: ${tier3Text.length}" }

                if (tier3Text.isNotBlank()) {
                    append(
                        getReadingContext.getContextForNovel(
                            mangaId = currentState.currentMangaId,
                            tier3Text = tier3Text,
                            currentPage = 0, // Page info not available in main chat screen
                            totalPages = 0,
                        ),
                    )
                    appendLine()
                }
            }
            appendLine()
        }

        // Apply user's custom instructions if provided
        val customInstructions = aiPreferences.customInstructions().get()
        if (customInstructions.isNotBlank()) {
            appendLine("USER'S CUSTOM INSTRUCTIONS (follow these):")
            appendLine(customInstructions)
            appendLine()
        }

        appendLine("Your role is to:")
        appendLine("- Help users discover new series based on their preferences")
        appendLine("- Answer questions about manga/manhwa/novel genres, tropes, and terminology")
        appendLine("- Discuss plot points and characters (while avoiding spoilers)")
        appendLine("- Recommend similar series")
        appendLine()
        appendLine("Guidelines:")
        appendLine("- Keep responses concise unless asked for detail")
        appendLine("- Always avoid spoilers unless explicitly asked")
        appendLine("- If you don't know something, use tools to look it up or say so honestly")
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
        val isWebSearchEnabled: Boolean = false,
        // History drawer state
        val showHistoryDrawer: Boolean = false,
        val savedConversations: List<AiConversation> = emptyList(),
        val attachedImage: String? = null, // Base64 encoded image
        val showVisualSelection: Boolean = false,
        val capturedBitmap: android.graphics.Bitmap? = null,
        // Reading Buddy State
        val isReadingBuddyEnabled: Boolean = false, // Session toggle
        val isReadingBuddyGloballyEnabled: Boolean = false, // Preference toggle
    )

    fun setTextContentProvider(provider: tachiyomi.domain.ai.TextContentProvider?) {
        this.textContentProvider = provider
        // Auto-enable buddy mode if provider is available (Reader Overlay)
        if (provider != null) {
            mutableState.update { it.copy(isReadingBuddyEnabled = true) }
        }
    }

    fun toggleReadingBuddy() {
        mutableState.update { it.copy(isReadingBuddyEnabled = !it.isReadingBuddyEnabled) }
    }

    fun attachImage(base64Image: String) {
        mutableState.update { it.copy(attachedImage = base64Image) }
    }

    fun removeAttachedImage() {
        mutableState.update { it.copy(attachedImage = null) }
    }

    /**
     * Start the visual selection flow with a captured bitmap.
     */
    fun startVisualSelection(bitmap: android.graphics.Bitmap) {
        mutableState.update {
            it.copy(
                showVisualSelection = true,
                capturedBitmap = bitmap,
            )
        }
    }

    /**
     * Called when user confirms selection in VisualSelectionScreen.
     * Converts bitmap to Base64 and attaches it.
     */
    fun confirmVisualSelection(bitmap: android.graphics.Bitmap) {
        screenModelScope.launchIO {
            val base64 = bitmapToBase64(bitmap)
            mutableState.update {
                it.copy(
                    showVisualSelection = false,
                    capturedBitmap = null,
                    attachedImage = base64,
                )
            }
        }
    }

    /**
     * Cancel visual selection without attaching.
     */
    fun cancelVisualSelection() {
        mutableState.update {
            it.copy(
                showVisualSelection = false,
                capturedBitmap = null,
            )
        }
    }

    private fun bitmapToBase64(bitmap: android.graphics.Bitmap): String {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
        return android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
    }
}

/**
 * Extension to convert persisted AiMessage to in-memory ChatMessage.
 */
private fun AiMessage.toChatMessage(): ChatMessage {
    return when (role) {
        MessageRole.USER -> ChatMessage.user(content, null) // History currently doesn't persist images
        MessageRole.ASSISTANT -> ChatMessage.assistant(content)
        MessageRole.SYSTEM -> ChatMessage.system(content)
    }
}
