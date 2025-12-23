package tachiyomi.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import tachiyomi.domain.ai.AiPreferences
import tachiyomi.domain.ai.AiProvider
import tachiyomi.domain.ai.model.ChatMessage
import tachiyomi.domain.ai.model.StreamChunk
import tachiyomi.domain.ai.repository.AiRepository
import tachiyomi.domain.ai.tools.AiToolDefinitions
import tachiyomi.domain.ai.tools.AiToolHandler
import java.io.IOException

class AiRepositoryImpl(
    private val client: OkHttpClient,
    private val aiPreferences: AiPreferences,
    private val json: Json,
    private val toolHandler: AiToolHandler? = null,
) : AiRepository {

    override suspend fun sendMessage(messages: List<ChatMessage>): Result<ChatMessage> {
        val apiKey = aiPreferences.apiKey().get()
        val provider = AiProvider.fromName(aiPreferences.provider().get())
        val model = aiPreferences.getEffectiveModel()

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
                    if (response.code == 429) {
                        return Result.failure(IOException("Quota exceeded (429). Please check your API limits."))
                    }
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
                val parts = mutableListOf<GeminiPart>()
                if (msg.content.isNotBlank()) {
                    parts.add(GeminiPart(text = msg.content))
                }
                val imageData = msg.image
                if (imageData != null) {
                    parts.add(
                        GeminiPart(
                            inlineData = GeminiInlineData(
                                mimeType = "image/jpeg",
                                data = imageData,
                            ),
                        ),
                    )
                }
                GeminiContent(
                    role = if (msg.role == ChatMessage.Role.USER) "user" else "model",
                    parts = parts,
                )
            }

        // Determine if we should enable web search
        val useWebSearch = aiPreferences.enableWebSearch().get() &&
            aiPreferences.getGeminiKeyForSearch() != null

        val requestBody = GeminiRequest(
            contents = contents,
            systemInstruction = if (systemInstruction.isNotBlank()) {
                GeminiSystemInstruction(parts = listOf(GeminiPart(text = systemInstruction)))
            } else {
                null
            },
            tools = if (useWebSearch) {
                listOf(GeminiTool(googleSearch = GeminiGoogleSearch()))
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
                    if (response.code == 429) {
                        return Result.failure(IOException("Quota exceeded (429). Please check your API limits."))
                    }
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
                val anthropicMessage = AnthropicMessage(
                    role = if (msg.role == ChatMessage.Role.USER) "user" else "assistant",
                    content = msg.content,
                )
                anthropicMessage
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
                    if (response.code == 429) {
                        return Result.failure(IOException("Quota exceeded (429). Please check your API limits."))
                    }
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

    /**
     * Build the list of tools to include in Gemini requests.
     * Includes google_search and custom function declarations when enabled.
     */
    private fun buildGeminiTools(): List<GeminiTool> {
        val tools = mutableListOf<GeminiTool>()

        // Add web search if enabled
        val useWebSearch = aiPreferences.enableWebSearch().get() &&
            aiPreferences.getGeminiKeyForSearch() != null
        if (useWebSearch) {
            tools.add(GeminiTool(googleSearch = GeminiGoogleSearch()))
        }

        // Add function calling tools if handler is available
        if (toolHandler != null) {
            val functionDeclarations = AiToolDefinitions.allTools.map { tool ->
                val props = tool.parameters.properties
                val reqs = tool.parameters.required
                GeminiFunctionDeclaration(
                    name = tool.name,
                    description = tool.description,
                    parameters = GeminiFunctionParameters(
                        type = tool.parameters.type,
                        // Only include properties if not empty (null is omitted from JSON)
                        properties = if (props.isNotEmpty()) {
                            props.mapValues { (_, prop) ->
                                GeminiPropertyDef(
                                    type = prop.type,
                                    description = prop.description,
                                )
                            }
                        } else {
                            null
                        },
                        // Only include required if not empty
                        required = reqs.ifEmpty { null },
                    ),
                )
            }
            tools.add(GeminiTool(functionDeclarations = functionDeclarations))
        }

        return tools.ifEmpty { emptyList() }
    }

    /**
     * Stream AI responses in real-time using Server-Sent Events (SSE).
     * Now supports Gemini and OpenAI/compatible providers.
     */
    override fun streamMessage(messages: List<ChatMessage>): Flow<StreamChunk> = callbackFlow {
        val apiKey = aiPreferences.apiKey().get()
        val provider = AiProvider.fromName(aiPreferences.provider().get())
        val model = aiPreferences.getEffectiveModel()

        if (apiKey.isBlank()) {
            trySend(StreamChunk.Error("API key not configured"))
            close()
            return@callbackFlow
        }

        // Dispatch based on provider
        val flow = if (provider == AiProvider.GEMINI) {
            streamGemini(messages, apiKey, model)
        } else {
            // OpenAI, OpenRouter, Custom, Anthropic (using OpenAI format for now/placeholder)
            // Note: Anthropic has different format, but for now we focus on OpenAI/Custom
            if (provider == AiProvider.ANTHROPIC) {
                // Fallback for Anthropic until specific implementation
                streamNonBlockingFallback(messages)
            } else {
                streamOpenAiCompatible(messages, apiKey, provider, model)
            }
        }

        flow.collect {
            trySend(it)
        }
        close()
    }.flowOn(Dispatchers.IO)

    private fun streamNonBlockingFallback(messages: List<ChatMessage>): Flow<StreamChunk> = callbackFlow {
        try {
            val result = sendMessage(messages)
            result.fold(
                onSuccess = { response ->
                    trySend(StreamChunk.Text(response.content))
                    trySend(StreamChunk.Done)
                },
                onFailure = { error ->
                    trySend(StreamChunk.Error(error.message ?: "Unknown error"))
                },
            )
        } catch (e: Exception) {
            trySend(StreamChunk.Error(e.message ?: "Request failed"))
        }
        close()
    }.flowOn(Dispatchers.IO)

    private fun streamGemini(
        messages: List<ChatMessage>,
        apiKey: String,
        model: String,
    ): Flow<StreamChunk> = callbackFlow {
        val url = "${AiProvider.GEMINI.baseUrl}/$model:streamGenerateContent?alt=sse&key=$apiKey"

        val systemInstruction = messages
            .filter { it.role == ChatMessage.Role.SYSTEM }
            .joinToString("\n") { it.content }

        val contents = messages
            .filter { it.role != ChatMessage.Role.SYSTEM }
            .map { msg ->
                val parts = mutableListOf<GeminiPart>()
                if (msg.content.isNotBlank()) {
                    parts.add(GeminiPart(text = msg.content))
                }
                val imageData = msg.image
                if (imageData != null) {
                    parts.add(
                        GeminiPart(
                            inlineData = GeminiInlineData(
                                mimeType = "image/jpeg",
                                data = imageData,
                            ),
                        ),
                    )
                }
                GeminiContent(
                    role = if (msg.role == ChatMessage.Role.USER) "user" else "model",
                    parts = parts,
                )
            }

        // Use the helper to build tools (includes google_search + function declarations)
        val tools = buildGeminiTools()

        val requestBody = GeminiRequest(
            contents = contents,
            systemInstruction = if (systemInstruction.isNotBlank()) {
                GeminiSystemInstruction(parts = listOf(GeminiPart(text = systemInstruction)))
            } else {
                null
            },
            tools = tools.ifEmpty { null },
        )

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trySend(StreamChunk.Error(e.message ?: "Connection failed"))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    // Include actual error body for debugging
                    val errorBody = try {
                        response.body?.string()?.take(300) ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                    val errorMsg = when (response.code) {
                        429 -> "Quota exceeded (429). Please try again later."
                        400 -> "Bad request (400): $errorBody"
                        else -> "API error: ${response.code}"
                    }
                    trySend(StreamChunk.Error(errorMsg))
                    close()
                    return
                }

                try {
                    response.body?.source()?.let { source ->
                        // Collect all function call parts from the stream (preserving thoughtSignature)
                        val pendingFunctionCallParts = mutableListOf<GeminiPart>()

                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: continue
                            if (line.startsWith("data: ")) {
                                val jsonData = line.removePrefix("data: ").trim()
                                if (jsonData.isNotEmpty()) {
                                    try {
                                        val chunk = json.decodeFromString<GeminiResponse>(jsonData)
                                        val parts = chunk.candidates?.firstOrNull()?.content?.parts

                                        parts?.forEach { part ->
                                            // Handle text response
                                            if (!part.text.isNullOrEmpty()) {
                                                trySend(StreamChunk.Text(part.text))
                                            }
                                            // Collect full part with functionCall (preserves thoughtSignature)
                                            if (part.functionCall != null) {
                                                pendingFunctionCallParts.add(part)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // Skip malformed chunks
                                    }
                                }
                            }
                        }

                        // If there are pending function calls, execute them and continue
                        if (pendingFunctionCallParts.isNotEmpty() && toolHandler != null) {
                            handleFunctionCalls(
                                pendingFunctionCallParts,
                                contents,
                                systemInstruction,
                                apiKey,
                                model,
                            )
                        } else {
                            trySend(StreamChunk.Done)
                        }
                    } ?: trySend(StreamChunk.Error("Empty response body"))
                } catch (e: Exception) {
                    trySend(StreamChunk.Error(e.message ?: "Stream parsing error"))
                } finally {
                    close()
                }
            }

            /**
             * Handle function calls by executing them and sending results back to Gemini.
             * @param functionCallParts The original parts containing functionCall (with thoughtSignature)
             */
            private fun handleFunctionCalls(
                functionCallParts: List<GeminiPart>,
                originalContents: List<GeminiContent>,
                systemInstruction: String,
                apiKey: String,
                model: String,
            ) {
                kotlinx.coroutines.runBlocking {
                    try {
                        // Execute each function call and build responses
                        val functionResponseParts = functionCallParts.mapNotNull { part ->
                            part.functionCall?.let { fc ->
                                val args = fc.args.mapValues { it.value.toString().trim('"') }
                                val result = toolHandler?.execute(fc.name, args)
                                    ?: "Tool not available"

                                GeminiPart(
                                    functionResponse = GeminiFunctionResponse(
                                        id = fc.id, // Match the id from the function call
                                        name = fc.name,
                                        response = mapOf(
                                            "result" to kotlinx.serialization.json.JsonPrimitive(result),
                                        ),
                                    ),
                                )
                            }
                        }

                        // Build new request with function responses
                        val newContents = originalContents.toMutableList()

                        // Add the model's function call with ORIGINAL parts (preserves thoughtSignature)
                        newContents.add(
                            GeminiContent(
                                role = "model",
                                parts = functionCallParts, // Use original parts, not recreated ones
                            ),
                        )

                        // Add function responses (role = "user" for Gemini)
                        newContents.add(
                            GeminiContent(
                                role = "user",
                                parts = functionResponseParts,
                            ),
                        )

                        // Make follow-up request (non-streaming for simplicity)
                        val followUpBody = GeminiRequest(
                            contents = newContents,
                            systemInstruction = if (systemInstruction.isNotBlank()) {
                                GeminiSystemInstruction(
                                    parts = listOf(GeminiPart(text = systemInstruction)),
                                )
                            } else {
                                null
                            },
                            tools = buildGeminiTools().ifEmpty { null },
                        )

                        val followUpUrl =
                            "${AiProvider.GEMINI.baseUrl}/$model:generateContent?key=$apiKey"
                        val followUpRequest = Request.Builder()
                            .url(followUpUrl)
                            .addHeader("Content-Type", "application/json")
                            .post(
                                json.encodeToString(followUpBody)
                                    .toRequestBody("application/json".toMediaType()),
                            )
                            .build()

                        // Loop to handle chained function calls (max 5 iterations for safety)
                        var currentRequest = followUpRequest
                        var currentContents = newContents.toMutableList()
                        var iterations = 0
                        val maxIterations = 5

                        while (iterations < maxIterations) {
                            iterations++
                            val response = client.newCall(currentRequest).execute()

                            if (!response.isSuccessful) {
                                val errorBody = response.body?.string()?.take(300) ?: ""
                                trySend(
                                    StreamChunk.Error(
                                        "Request failed (${response.code}): $errorBody",
                                    ),
                                )
                                break
                            }


                            val body = response.body?.string() ?: break

                            val parsed = json.decodeFromString<GeminiResponse>(body)
                            val responseParts = parsed.candidates?.firstOrNull()?.content?.parts
                                ?: break

                            // Check if we have text response
                            val textResponse = responseParts
                                .mapNotNull { it.text }
                                .joinToString("")

                            if (textResponse.isNotEmpty()) {
                                trySend(StreamChunk.Text(textResponse))
                                break
                            }

                            // Check if there are more function calls to execute
                            val newFunctionParts = responseParts.filter { it.functionCall != null }
                            if (newFunctionParts.isEmpty()) {
                                break // No text and no functions, stop
                            }

                            // Execute the new function calls
                            val newFunctionResponses = newFunctionParts.mapNotNull { part ->
                                part.functionCall?.let { fc ->
                                    val args = fc.args.mapValues {
                                        it.value.toString().trim('"')
                                    }
                                    val result = toolHandler?.execute(fc.name, args)
                                        ?: "Tool not available"
                                    GeminiPart(
                                        functionResponse = GeminiFunctionResponse(
                                            id = fc.id,
                                            name = fc.name,
                                            response = mapOf(
                                                "result" to kotlinx.serialization.json
                                                    .JsonPrimitive(result),
                                            ),
                                        ),
                                    )
                                }
                            }

                            // Add model response and our function responses to conversation
                            currentContents.add(
                                GeminiContent(
                                    role = "model",
                                    parts = responseParts, // Preserve thoughtSignature
                                ),
                            )
                            currentContents.add(
                                GeminiContent(
                                    role = "user",
                                    parts = newFunctionResponses,
                                ),
                            )

                            // Build next request
                            val nextBody = GeminiRequest(
                                contents = currentContents,
                                systemInstruction = if (systemInstruction.isNotBlank()) {
                                    GeminiSystemInstruction(
                                        parts = listOf(GeminiPart(text = systemInstruction)),
                                    )
                                } else {
                                    null
                                },
                                tools = buildGeminiTools().ifEmpty { null },
                            )
                            currentRequest = Request.Builder()
                                .url(followUpUrl)
                                .addHeader("Content-Type", "application/json")
                                .post(
                                    json.encodeToString(nextBody)
                                        .toRequestBody("application/json".toMediaType()),
                                )
                                .build()
                        }

                        trySend(StreamChunk.Done)
                    } catch (e: Exception) {
                        trySend(StreamChunk.Error("Function call error: ${e.message}"))
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun streamOpenAiCompatible(
        messages: List<ChatMessage>,
        apiKey: String,
        provider: AiProvider,
        model: String,
    ): Flow<StreamChunk> = callbackFlow {
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
            stream = true,
        )

        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trySend(StreamChunk.Error(e.message ?: "Connection failed"))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorMsg = if (response.code == 429) {
                        "Quota exceeded (429). Please try again later."
                    } else {
                        "API error: ${response.code}"
                    }
                    trySend(StreamChunk.Error(errorMsg))
                    close()
                    return
                }

                try {
                    response.body?.source()?.let { source ->
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: continue
                            if (line.startsWith("data: ")) {
                                val jsonData = line.removePrefix("data: ").trim()
                                if (jsonData == "[DONE]") {
                                    trySend(StreamChunk.Done)
                                    break
                                }
                                if (jsonData.isNotEmpty()) {
                                    try {
                                        val chunk = json.decodeFromString<OpenAiStreamResponse>(jsonData)
                                        val text = chunk.choices.firstOrNull()?.delta?.content
                                        if (!text.isNullOrEmpty()) {
                                            trySend(StreamChunk.Text(text))
                                        }
                                    } catch (e: Exception) {
                                        // Skip
                                    }
                                }
                            }
                        }
                        // If loop finishes without [DONE], we still close
                        trySend(StreamChunk.Done)
                    } ?: trySend(StreamChunk.Error("Empty response body"))
                } catch (e: Exception) {
                    trySend(StreamChunk.Error(e.message ?: "Stream parsing error"))
                } finally {
                    close()
                }
            }
        })
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    // ========== Request/Response DTOs ==========

    @Serializable
    private data class OpenAiRequest(
        val model: String,
        val messages: List<OpenAiMessage>,
        @SerialName("max_tokens") val maxTokens: Int,
        val temperature: Float,
        val stream: Boolean = false,
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
        val tools: List<GeminiTool>? = null,
    )

    @Serializable
    private data class GeminiTool(
        @SerialName("google_search") val googleSearch: GeminiGoogleSearch? = null,
        @SerialName("function_declarations")
        val functionDeclarations: List<GeminiFunctionDeclaration>? = null,
    )

    @Serializable
    private class GeminiGoogleSearch // Empty object enables the tool

    @Serializable
    private data class GeminiFunctionDeclaration(
        val name: String,
        val description: String,
        val parameters: GeminiFunctionParameters,
    )

    @Serializable
    private data class GeminiFunctionParameters(
        @kotlinx.serialization.EncodeDefault
        val type: String = "object",
        val properties: Map<String, GeminiPropertyDef>? = null,
        val required: List<String>? = null,
    )

    @Serializable
    private data class GeminiPropertyDef(
        val type: String,
        val description: String,
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
        val text: String? = null,
        val thought: Boolean? = null,
        val thoughtSignature: String? = null,
        @SerialName("inline_data") val inlineData: GeminiInlineData? = null,
        @SerialName("functionCall") val functionCall: GeminiFunctionCall? = null,
        @SerialName("functionResponse") val functionResponse: GeminiFunctionResponse? = null,
    )

    @Serializable
    private data class GeminiFunctionCall(
        val id: String? = null,
        val name: String,
        val args: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    )

    @Serializable
    private data class GeminiFunctionResponse(
        val id: String? = null,
        val name: String,
        val response: Map<String, kotlinx.serialization.json.JsonElement>,
    )

    @Serializable
    private data class GeminiInlineData(
        @SerialName("mime_type") val mimeType: String,
        val data: String,
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

    @Serializable
    private data class OpenAiStreamResponse(
        val choices: List<OpenAiStreamChoice>,
    )

    @Serializable
    private data class OpenAiStreamChoice(
        val delta: OpenAiStreamDelta,
    )

    @Serializable
    private data class OpenAiStreamDelta(
        val content: String? = null,
    )
}
