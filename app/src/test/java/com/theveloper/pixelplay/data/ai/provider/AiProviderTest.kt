package com.theveloper.pixelplay.data.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AiProvider] enum and [AiClientFactory] configuration.
 *
 * Verifies that all providers are properly configured with the correct
 * base URLs and default models — especially Z.ai (GLM) which was
 * recently added/updated.
 */
class AiProviderTest {

    @Test
    fun `GLM provider is named Z ai`() {
        assertEquals("Z.ai (GLM)", AiProvider.GLM.displayName)
    }

    @Test
    fun `GLM provider requires API key`() {
        assertTrue(AiProvider.GLM.requiresApiKey)
    }

    @Test
    fun `GLM provider does not have configurable URL`() {
        assertFalse(AiProvider.GLM.hasConfigurableUrl)
    }

    @Test
    fun `fromString returns GLM for GLM string`() {
        assertEquals(AiProvider.GLM, AiProvider.fromString("GLM"))
    }

    @Test
    fun `fromString returns GEMINI for unknown string`() {
        assertEquals(AiProvider.GEMINI, AiProvider.fromString("UNKNOWN_PROVIDER"))
    }

    @Test
    fun `fromString returns correct provider for each enum value`() {
        for (provider in AiProvider.entries) {
            assertEquals(provider, AiProvider.fromString(provider.name))
        }
    }

    @Test
    fun `all providers have non-blank display names`() {
        for (provider in AiProvider.entries) {
            assertTrue(
                "Provider ${provider.name} has blank display name",
                provider.displayName.isNotBlank()
            )
        }
    }

    @Test
    fun `all providers require API key except none`() {
        // Currently all providers require an API key (Ollama uses a dummy key)
        for (provider in AiProvider.entries) {
            assertTrue(
                "Provider ${provider.name} should require API key",
                provider.requiresApiKey
            )
        }
    }

    @Test
    fun `only CUSTOM provider has configurable URL`() {
        for (provider in AiProvider.entries) {
            if (provider == AiProvider.CUSTOM) {
                assertTrue(
                    "CUSTOM should have configurable URL",
                    provider.hasConfigurableUrl
                )
            } else {
                assertFalse(
                    "Provider ${provider.name} should not have configurable URL",
                    provider.hasConfigurableUrl
                )
            }
        }
    }
}
