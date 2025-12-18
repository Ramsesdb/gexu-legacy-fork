package tachiyomi.data.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.ai.AiPreferences
import tachiyomi.domain.ai.AiProvider
import tachiyomi.domain.ai.model.ChatMessage
import tachiyomi.domain.ai.repository.AiRepository
import java.io.IOException

class AiRepositoryImpl(
    private val client: OkHttpClient,
    private val aiPreferences: AiPreferences,
    private val json: Json,
) : AiRepository {

    override suspend fun sendMessage(messages: List<ChatMessage>): Result<ChatMessage> {
        val apiKey = aiPreferences.apiKey().get()
        val provider = AiProvider.fromName(aiPreferences.provider().get())
        val model = aiPreferences.model().get()

        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("API key not configured"))
        }

        return when (provider) {
            AiProvider.OPENAI, AiProvider.OPENROUTER, AiProvider.CUSTOM -> {
                sendOpenAiCompatible(messages, apiKey, provider, model)
            }
            AiProvider.GEMINI -> {
                sendGemini(messages, apiKey, model)
            }
            AiProvider.ANTHROPIC -> {
                sendAnthropic(messages, apiKey, model)
            }
        }
    }

    private fun sendOpenAiCompatible(
        messages: List<ChatMessage>,
        apiKey: String,
        provider: AiProvider,
        model: String,
    ): Result<ChatMessage> {
        val baseUrl = when (provider) {
            AiProvider.CUSTOM -> aiPreferences.customBaseUrl().get()
            else -> provider.baseUrl
        }

        val requestBody = OpenAiRequest(
            model = model,
            messages = messages.map { msg ->
                OpenAiMessage(
                    role = when (msg.role) {
                        ChatMessage.Role.SYSTEM -> "system"
                        ChatMessage.Role.USER -> "user"
                        ChatMessage.Role.ASSISTANT -> "assistant"
                    },
                    content = msg.content,
                )
            },
            maxTokens = aiPreferences.maxTokens().get(),
            temperature = aiPreferences.temperature().get(),
        )

        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("API error: ${response.code} - ${response.body?.string()}"))
                }

                val responseBody = response.body?.string()
                    ?: return Result.failure(IOException("Empty response"))

                val parsed = json.decodeFromString<OpenAiResponse>(responseBody)
                val content = parsed.choices.firstOrNull()?.message?.content
                    ?: return Result.failure(IOException("No response content"))

                Result.success(ChatMessage.assistant(content))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun sendGemini(
        messages: List<ChatMessage>,
        apiKey: String,
        model: String,
    ): Result<ChatMessage> {
        val url = "${AiProvider.GEMINI.baseUrl}/$model:generateContent?key=$apiKey"

        // Convert messages to Gemini format
        val systemInstruction = messages
            .filter { it.role == ChatMessage.Role.SYSTEM }
            .joinToString("\n") { it.content }

        val contents = messages
            .filter { it.role != ChatMessage.Role.SYSTEM }
            .map { msg ->
                GeminiContent(
                    role = if (msg.role == ChatMessage.Role.USER) "user" else "model",
                    parts = listOf(GeminiPart(text = msg.content)),
                )
            }

        val requestBody = GeminiRequest(
            contents = contents,
            systemInstruction = if (systemInstruction.isNotBlank()) {
                GeminiSystemInstruction(parts = listOf(GeminiPart(text = systemInstruction)))
            } else {
                null
            },
        )

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("API error: ${response.code} - ${response.body?.string()}"))
                }

                val responseBody = response.body?.string()
                    ?: return Result.failure(IOException("Empty response"))

                val parsed = json.decodeFromString<GeminiResponse>(responseBody)
                val content = parsed.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: return Result.failure(IOException("No response content"))

                Result.success(ChatMessage.assistant(content))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun sendAnthropic(
        messages: List<ChatMessage>,
        apiKey: String,
        model: String,
    ): Result<ChatMessage> {
        val systemPrompt = messages
            .filter { it.role == ChatMessage.Role.SYSTEM }
            .joinToString("\n") { it.content }

        val anthropicMessages = messages
            .filter { it.role != ChatMessage.Role.SYSTEM }
            .map { msg ->
                AnthropicMessage(
                    role = if (msg.role == ChatMessage.Role.USER) "user" else "assistant",
                    content = msg.content,
                )
            }

        val requestBody = AnthropicRequest(
            model = model,
            maxTokens = aiPreferences.maxTokens().get(),
            system = systemPrompt.ifBlank { null },
            messages = anthropicMessages,
        )

        val request = Request.Builder()
            .url(AiProvider.ANTHROPIC.baseUrl)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("API error: ${response.code} - ${response.body?.string()}"))
                }

                val responseBody = response.body?.string()
                    ?: return Result.failure(IOException("Empty response"))

                val parsed = json.decodeFromString<AnthropicResponse>(responseBody)
                val content = parsed.content.firstOrNull()?.text
                    ?: return Result.failure(IOException("No response content"))

                Result.success(ChatMessage.assistant(content))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun isConfigured(): Boolean {
        val apiKey = aiPreferences.apiKey().get()
        val provider = AiProvider.fromName(aiPreferences.provider().get())
        return AiPreferences.isApiKeyValid(apiKey, provider)
    }

    override suspend fun testConnection(): String? {
        return try {
            val result = sendMessage(listOf(ChatMessage.user("Hello, respond with just 'OK'")))
            if (result.isSuccess) null else result.exceptionOrNull()?.message
        } catch (e: Exception) {
            e.message
        }
    }

    // ========== Request/Response DTOs ==========

    @Serializable
    private data class OpenAiRequest(
        val model: String,
        val messages: List<OpenAiMessage>,
        @SerialName("max_tokens") val maxTokens: Int,
        val temperature: Float,
    )

    @Serializable
    private data class OpenAiMessage(
        val role: String,
        val content: String,
    )

    @Serializable
    private data class OpenAiResponse(
        val choices: List<OpenAiChoice>,
    )

    @Serializable
    private data class OpenAiChoice(
        val message: OpenAiMessage,
    )

    @Serializable
    private data class GeminiRequest(
        val contents: List<GeminiContent>,
        @SerialName("system_instruction") val systemInstruction: GeminiSystemInstruction? = null,
    )

    @Serializable
    private data class GeminiSystemInstruction(
        val parts: List<GeminiPart>,
    )

    @Serializable
    private data class GeminiContent(
        val role: String,
        val parts: List<GeminiPart>,
    )

    @Serializable
    private data class GeminiPart(
        val text: String,
    )

    @Serializable
    private data class GeminiResponse(
        val candidates: List<GeminiCandidate>? = null,
    )

    @Serializable
    private data class GeminiCandidate(
        val content: GeminiContent?,
    )

    @Serializable
    private data class AnthropicRequest(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        val system: String? = null,
        val messages: List<AnthropicMessage>,
    )

    @Serializable
    private data class AnthropicMessage(
        val role: String,
        val content: String,
    )

    @Serializable
    private data class AnthropicResponse(
        val content: List<AnthropicContent>,
    )

    @Serializable
    private data class AnthropicContent(
        val text: String,
    )
}
