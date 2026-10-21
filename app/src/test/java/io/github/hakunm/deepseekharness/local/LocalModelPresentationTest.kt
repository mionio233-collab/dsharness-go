package io.github.hakunm.deepseekharness.local

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalModelPresentationTest {
    private val official = LocalProvider("deepseek", "DeepSeek", LocalProtocol.OpenAI, "https://api.deepseek.com/v1", "")

    @Test fun officialFlashGetsTheReleaseLabelWhileRequestIdAndCapacityStayIntact() {
        val provider = official.copy(modelContextLimits = mapOf("deepseek-flash" to 1_048_576L))
        assertEquals("DeepSeek-V4.1-Flash 1017", provider.modelLabel("deepseek-flash").text)
        assertEquals(1_048_576L, provider.resolveContextLimit("deepseek-flash")?.tokens)
        assertNull(provider.modelLabel("deepseek-v4-pro").release)
    }

    @Test fun officialLabelsAreNeverAppliedToCustomEndpointsOrUnknownModels() {
        for (url in listOf("https://example.com/v1", "https://api.deepseek.com.example.com/v1", "https://api.deepseek.com/proxy", "http://api.deepseek.com/v1")) {
            val provider = official.copy(baseUrl = url)
            assertNull(provider.modelLabel("deepseek-flash").release)
            assertEquals("deepseek-flash", provider.modelLabel("deepseek-flash").name)
        }
        assertNull(official.modelLabel("future-flash").release)
    }

    @Test fun officialEffortsMatchDesktopAndMaxEnablesThinking() {
        assertEquals(listOf("Off", "Low", "High", "Max"), official.reasoningEfforts("deepseek-flash"))
        val fields = official.reasoningRequestFields("deepseek-flash", "Max")
        assertEquals("max", fields["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("enabled", fields["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test fun offDisablesThinkingAndDefaultDoesNotOverrideProviderPolicy() {
        val fields = official.reasoningRequestFields("deepseek-flash", "Off")
        assertEquals("disabled", fields["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse(fields.containsKey("reasoning_effort"))
        assertTrue(official.reasoningRequestFields("deepseek-flash", null).isEmpty())
    }

    @Test fun genericProvidersRetainTheirEffortProtocol() {
        val provider = official.copy(baseUrl = "https://example.com/v1")
        assertEquals(listOf("Low", "Medium", "High"), provider.reasoningEfforts("deepseek-flash"))
        assertEquals(mapOf("reasoning_effort" to JsonPrimitive("max")), provider.reasoningRequestFields("deepseek-flash", "Max"))
        assertEquals(mapOf("reasoning_effort" to JsonPrimitive("medium")), provider.reasoningRequestFields("deepseek-flash", "Medium"))
    }
}
