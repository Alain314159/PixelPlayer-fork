package com.theveloper.pixelplay.data.ai.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * A generic AI client for OpenAI-compatible APIs (NVIDIA, Kimi, GLM, etc.)
 */
class GenericOpenAiClient(
    private val apiKey: String,
    private val _baseUrl: String,
    private val defaultModelId: String,
    private val _providerName: String = "OpenAI"
) : AiClient {

    // Public read-only accessors for testing and diagnostics.
    val baseUrl: String get() = _baseUrl.takeIf { it.isNotBlank() } ?: ""
    val defaultModel: String get() = defaultModelId
    val providerName: String get() = _providerName
    
    @Serializable
    private data class ChatMessage(val role: String, val content: String)
    
    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double = 0.7,
        @SerialName("top_p") val topP: Double? = null,
        @SerialName("max_tokens") val maxTokens: Int? = null,
        @SerialName("presence_penalty") val presencePenalty: Double? = null,
        @SerialName("frequency_penalty") val frequencyPenalty: Double? = null
    )
    
    @Serializable
    private data class ChatChoice(val message: ChatMessage)
    
    @Serializable
    private data class ChatResponse(val choices: List<ChatChoice>)
    
    @Serializable
    private data class ModelItem(val id: String)
    
    @Serializable
    private data class ModelsResponse(val data: List<ModelItem>)
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    override suspend fun generateContent(
        model: String, 
        systemPrompt: String, 
        prompt: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        maxTokens: Int,
        presencePenalty: Float,
        frequencyPenalty: Float
    ): String {
        return withContext(Dispatchers.IO) {
            val resolvedModel = model.ifBlank { defaultModelId }
            val messagesList = mutableListOf<ChatMessage>()
            if (systemPrompt.isNotBlank()) {
                messagesList.add(ChatMessage(role = "system", content = systemPrompt))
            }
            messagesList.add(ChatMessage(role = "user", content = prompt))

            val requestBody = ChatRequest(
                model = resolvedModel,
                messages = messagesList,
                temperature = temperature.toDouble(),
                topP = topP.toDouble(),
                maxTokens = maxTokens.takeIf { it > 0 },
                presencePenalty = presencePenalty.toDouble(),
                frequencyPenalty = frequencyPenalty.toDouble()
            )
            
            val jsonBody = json.encodeToString(ChatRequest.serializer(), requestBody)
            val body = jsonBody.toRequestBody("application/json".toMediaType())
            
            val requestBuilder = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
            
            if (providerName.equals("OpenRouter", ignoreCase = true)) {
                requestBuilder.addHeader("HTTP-Referer", "https://github.com/theovilardo/PixelPlayer")
                requestBuilder.addHeader("X-Title", "PixelPlayer")
            }

            val request = requestBuilder.post(body).build()

            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body.string()

                    if (!response.isSuccessful) {
                        throw AiProviderSupport.createException(
                            providerName = providerName,
                            statusCode = response.code,
                            transportMessage = response.message,
                            responseBody = responseBody,
                            requestedModel = resolvedModel
                        )
                    }

                    val chatResponse = json.decodeFromString<ChatResponse>(responseBody)
                    chatResponse.choices.firstOrNull()?.message?.content
                        ?: throw AiProviderSupport.createException(
                            providerName = providerName,
                            statusCode = response.code,
                            transportMessage = "Response had no content",
                            responseBody = responseBody,
                            requestedModel = resolvedModel
                        )
                }
            } catch (e: Exception) {
                throw AiProviderSupport.wrapThrowable(providerName, e, resolvedModel)
            }
        }
    }
    
    override suspend fun countTokens(model: String, systemPrompt: String, prompt: String): Int {
        // Estimation for generic providers
        return (systemPrompt.length + prompt.length) / 4
    }
    
    override suspend fun getAvailableModels(apiKey: String): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/models")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                // BUG FIX: previously this was `val response = client.newCall(request).execute()`
                // without .use {}. On the success path, response.body.string() closes the body,
                // but on the failure path (!response.isSuccessful) the body was never read nor
                // closed, leaking the connection. Over time this exhausts the OkHttp connection
                // pool and all API calls fail with ConnectionPoolTimeoutException.
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext listOf(defaultModelId)
                    }
                    val responseBody = response.body.string()
                    val modelsResponse = json.decodeFromString<ModelsResponse>(responseBody)
                    modelsResponse.data.map { it.id }.let { UnifiedModelFilter.filterChatModels(it) }
                }
            } catch (e: Exception) {
                listOf(defaultModelId)
            }
        }
    }

    override suspend fun validateApiKey(apiKey: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Try a simple models list check as validation.
                // BUG FIX: same connection-leak as getAvailableModels — now uses .use {}
                val request = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/models")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    response.isSuccessful
                }
            } catch (e: Exception) {
                false
            }
        }
    }
    
    override fun getDefaultModel(): String = defaultModelId
}
