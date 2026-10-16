package com.theveloper.pixelplay.data.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AiClientFactory] — verifies that each provider creates
 * the correct AiClient subclass with the right base URL and default model.
 */
class AiClientFactoryTest {

    private val factory = AiClientFactory()

    @Test
    fun `GLM creates GenericOpenAiClient with Z ai endpoint and glm-4-flash default`() {
        val client = factory.createClient(AiProvider.GLM, "test-key") as GenericOpenAiClient

        assertEquals("https://open.bigmodel.cn/api/paas/v4", client.baseUrl)
        assertEquals("glm-4-flash", client.defaultModel)
        assertEquals("Z.ai (GLM)", client.providerName)
    }

    @Test
    fun `GEMINI creates GeminiAiClient`() {
        val client = factory.createClient(AiProvider.GEMINI, "test-key")
        assertTrue("Expected GeminiAiClient, got ${client::class.simpleName}", client is GeminiAiClient)
    }

    @Test
    fun `DEEPSEEK creates GenericOpenAiClient with correct endpoint`() {
        val client = factory.createClient(AiProvider.DEEPSEEK, "test-key") as GenericOpenAiClient

        assertEquals("https://api.deepseek.com", client.baseUrl)
        assertEquals("deepseek-chat", client.defaultModel)
    }

    @Test
    fun `GROQ creates GenericOpenAiClient with correct endpoint`() {
        val client = factory.createClient(AiProvider.GROQ, "test-key") as GenericOpenAiClient

        assertEquals("https://api.groq.com/openai/v1", client.baseUrl)
        assertEquals("llama-3.1-8b-instant", client.defaultModel)
    }

    @Test
    fun `OPENAI creates GenericOpenAiClient with correct endpoint`() {
        val client = factory.createClient(AiProvider.OPENAI, "test-key") as GenericOpenAiClient

        assertEquals("https://api.openai.com/v1", client.baseUrl)
        assertEquals("gpt-4o-mini", client.defaultModel)
    }

    @Test
    fun `KIMI creates GenericOpenAiClient with Moonshot endpoint`() {
        val client = factory.createClient(AiProvider.KIMI, "test-key") as GenericOpenAiClient

        assertEquals("https://api.moonshot.cn/v1", client.baseUrl)
        assertEquals("moonshot-v1-8k", client.defaultModel)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank API key throws IllegalArgumentException`() {
        factory.createClient(AiProvider.GLM, "")
    }
}
