package io.github.hakunm.deepseekharness.local

import io.github.hakunm.deepseekharness.ui.testLocalProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ModelDiscoveryTest {
    private fun provider() = LocalProvider("test", "Test", LocalProtocol.OpenAI, "https://example.com/v1", "key",
        models = listOf("a"), defaultModel = "a", contextLimits = mapOf("a" to 4000L))

    @Test fun parsesSupportedFieldsAndNestedMetadata() {
        val result = parseModelDiscovery("""{"data":[
            {"id":"a","context_length":8192},
            {"id":"b","context_window":"16384"},
            {"id":"c","contextWindow":32768},
            {"id":"d","max_context_length":65536},
            {"id":"e","architecture":{"context_length":131072}},
            {"id":"f","top_provider":{"context_window":262144,"max_output_tokens":12}},
            {"id":"g","context_length":1024,"top_provider":{"context_length":2048}}
        ]}""")
        assertEquals(listOf(8192L, 16384L, 32768L, 65536L, 131072L, 262144L, 1024L), result.models.map { it.contextLimit })
    }

    @Test fun rejectsInvalidValuesAndNeverTreatsOutputTokensAsContext() {
        val result = parseModelDiscovery("""{"data":[
            {"id":"zero","context_length":0}, {"id":"negative","context_window":-1},
            {"id":"fraction","contextWindow":1.5}, {"id":"overflow","context_length":9223372036854775808},
            {"id":"bool","context_length":true}, {"id":"object","context_length":{"value":4096}},
            {"id":"output","max_output_tokens":8192,"top_provider":{"max_output_tokens":4096}},
            {"id":"null","architecture":null}, {"id":"Dache/Qwen"},
            {"id":"fallback","context_length":0,"architecture":{"context_length":4096}},
            {"id":""}, {"id":123}, null
        ]}""")
        assertEquals(10, result.ids.size)
        assertEquals(mapOf("fallback" to 4096L), result.contextLimits)
    }

    @Test fun deduplicatesIdsWithoutLosingValidMetadata() {
        val result = parseModelDiscovery("""{"data":[{"id":"a","context_length":8192},{"id":"a"}]}""")
        assertEquals(listOf("a"), result.ids)
        assertEquals(8192L, result.contextLimits["a"])
        assertTrue(parseModelDiscovery("""{"data":[]}""").models.isEmpty())
    }

    @Test fun persistsAllDiscoveredLimitsAndPreservesManualOverrides() {
        val discovered = parseModelDiscovery("""{"data":[{"id":"a","context_length":8192},{"id":"unselected","contextWindow":32768}]}""")
        val updated = provider().withDiscovery(discovered)
        val restored = Json.decodeFromString<LocalProvider>(Json.encodeToString(updated))
        assertEquals(listOf("a"), restored.models)
        assertEquals(32768L, restored.modelContextLimits["unselected"])
        assertEquals(ResolvedContextLimit(4000, ContextLimitSource.Manual), restored.resolveContextLimit("a"))
        assertEquals(ResolvedContextLimit(32768, ContextLimitSource.ModelsEndpoint), restored.resolveContextLimit("unselected"))
        val cleared = restored.withManualContextLimit("a", "")
        assertEquals(ResolvedContextLimit(8192, ContextLimitSource.ModelsEndpoint), cleared.resolveContextLimit("a"))
        assertEquals(restored.modelContextLimits, cleared.modelContextLimits)
        assertEquals(9000L, cleared.withManualContextLimit("a", "9000").resolveContextLimit("a")?.tokens)
        assertNull(restored.resolveContextLimit("missing"))
    }

    @Test fun legacyProviderDecodesAndRefreshRemovesStaleReturnedMetadata() {
        val old = Json.decodeFromString<LocalProvider>("""{"id":"test","name":"Test","protocol":"OpenAI","baseUrl":"https://example.com/v1","apiKey":"","contextLimits":{"a":4000}}""")
        assertTrue(old.modelContextLimits.isEmpty())
        assertEquals(ContextLimitSource.Manual, old.resolveContextLimit("a")?.source)
        val updated = old.copy(modelContextLimits = mapOf("a" to 8192L, "b" to 16000L))
            .withDiscovery(parseModelDiscovery("""{"data":[{"id":"a"}]}"""))
        assertEquals(mapOf("b" to 16000L), updated.modelContextLimits)
        assertEquals(old.contextLimits, updated.contextLimits)
    }

    @Test fun knownLimitIsAvailableBeforeUsage() {
        val p = provider().copy(contextLimits = emptyMap(), modelContextLimits = mapOf("a" to 8192L))
        val stats = runStats(emptyList(), null, p.resolveContextLimit("a")?.tokens)
        assertEquals(8192L, stats.contextLimit)
        assertNull(stats.contextTokens)
        assertNull(stats.contextPercent)
    }

    @Test fun fetchAlwaysRequestsModelsEvenWithExistingCatalog() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"a","context_length":8192}]}"""))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"a","context_length":16384},{"id":"b"}]}"""))
            val p = provider().copy(baseUrl = server.url("/v1").toString())
            assertEquals(8192L, testLocalProvider(p).getOrThrow().contextLimits["a"])
            assertEquals(16384L, testLocalProvider(p).getOrThrow().contextLimits["a"])
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("/v1/models", request.path)
                assertEquals("GET", request.method)
                assertEquals("Bearer key", request.getHeader("Authorization"))
            }
        }
    }
}
