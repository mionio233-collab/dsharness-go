package io.github.hakunm.deepseekharness.local

import io.github.hakunm.deepseekharness.ui.LocalPermission
import io.github.hakunm.deepseekharness.ui.LocalSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class LocalTaskModeTest {
    @Test fun scopesAreClosedAndModeSpecific() {
        assertEquals(LocalTaskScope.workspaceTools, LocalTaskScope.offered(LocalTaskMode.Workspace, LocalPermission.Full, true, true))
        assertEquals(LocalTaskScope.phoneTools, LocalTaskScope.offered(LocalTaskMode.Phone, LocalPermission.Full, true, true))
        assertTrue(LocalTaskScope.offered(LocalTaskMode.ChatOnly, LocalPermission.AutoReview, true, true).isEmpty())
        assertEquals(LocalTaskScope.workspaceTools + "http_get" + LocalTaskScope.phoneTools,
            LocalTaskScope.offered(LocalTaskMode.Smart, LocalPermission.Full, true, true))
    }
    @Test fun smartDoesNotBypassPhoneOrProviderGates() {
        assertFalse(LocalTaskScope.offered(LocalTaskMode.Smart, LocalPermission.Full, true, false).contains("phone_action"))
        assertTrue(LocalTaskScope.offered(LocalTaskMode.Phone, LocalPermission.Full, true, false).isEmpty())
        LocalTaskMode.entries.forEach { mode ->
            assertTrue(LocalTaskScope.offered(mode, LocalPermission.Full, false, true).isEmpty())
            assertFalse(LocalTaskScope.offered(mode, LocalPermission.ReadOnly, true, true).contains("phone_action"))
        }
        assertEquals(setOf("read_file", "list_files"), LocalTaskScope.offered(LocalTaskMode.Workspace, LocalPermission.ReadOnly, true, false))
    }
    @Test fun executionRejectsHallucinationsAndRechecksChangedAuthorization() {
        val offered = LocalTaskScope.offered(LocalTaskMode.Smart, LocalPermission.Full, true, true)
        assertFalse(LocalTaskScope.canExecute("shell", offered, LocalTaskMode.Smart, LocalPermission.Full, true, true))
        assertFalse(LocalTaskScope.canExecute("phone_action", offered, LocalTaskMode.Smart, LocalPermission.Full, true, false))
        assertFalse(LocalTaskScope.canExecute("write_file", offered, LocalTaskMode.Smart, LocalPermission.ReadOnly, true, true))
        assertFalse(LocalTaskScope.canExecute("read_file", offered, LocalTaskMode.Smart, LocalPermission.Full, false, true))
        assertFalse(LocalTaskScope.canExecute("http_get", setOf("read_file"), LocalTaskMode.Smart, LocalPermission.Full, true, true))
        assertFalse(LocalTaskScope.canExecute("http_get", offered, LocalTaskMode.Workspace, LocalPermission.Full, true, true))
        assertFalse(LocalTaskScope.canExecute("read_file", offered, LocalTaskMode.ChatOnly, LocalPermission.Full, true, true))
        assertTrue(LocalTaskScope.canExecute("read_file", offered, LocalTaskMode.Workspace, LocalPermission.ReadOnly, true, false))
    }
    @Test fun sessionRoundTripRetainsIndependentModesAndOtherData() {
        val json = Json { ignoreUnknownKeys = true }
        val original = LocalSession("local-1", "title", "model", "folder", listOf(LocalMessage("user", "draft")), LocalTaskMode.Workspace)
        val second = original.copy(id = "local-2", taskMode = LocalTaskMode.ChatOnly)
        assertEquals(listOf(original, second), json.decodeFromString<List<LocalSession>>(json.encodeToString(listOf(original, second))))
        val switched = original.copy(taskMode = LocalTaskScope.switch(original.taskMode!!, LocalTaskMode.Smart, false))
        assertEquals(original.copy(taskMode = LocalTaskMode.Smart), switched)
        assertEquals(original, original.copy(taskMode = LocalTaskScope.switch(original.taskMode, LocalTaskMode.Phone, true)))
    }
    @Test fun legacyMigrationRequiresExistingConsentAndDoesNotOverrideSavedMode() {
        val old = Json.decodeFromString<LocalSession>("""{"id":"old","title":"old","model":"m"}""")
        assertNull(old.taskMode)
        assertEquals(LocalTaskMode.Smart, LocalTaskScope.restore(old.taskMode, false))
        assertEquals(LocalTaskMode.Phone, LocalTaskScope.restore(old.taskMode, true))
        assertEquals(LocalTaskMode.ChatOnly, LocalTaskScope.restore(LocalTaskMode.ChatOnly, true))
    }
    @Test fun chatOnlyRequestHasNoToolSchemaEvenWithFullPhoneAuthorization() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"hello"}}]}"""))
            server.start()
            val names = LocalTaskScope.offered(LocalTaskMode.ChatOnly, LocalPermission.Full, true, true)
            assertTrue(names.isEmpty())
            val history = listOf(LocalMessage("user", "hello"),
                LocalMessage("assistant", "checking", toolCallsJson = "[]"),
                LocalMessage("tool", "old tool result", toolCallId = "old"),
                LocalMessage("user", "continue chatting"))
            val requestHistory = LocalTaskScope.requestHistory(LocalTaskMode.ChatOnly, history)
            assertEquals(3, requestHistory.size)
            assertEquals("checking", requestHistory[1].content)
            assertEquals(history, LocalTaskScope.requestHistory(LocalTaskMode.Smart, history))
            assertEquals("[]", history[1].toolCallsJson)
            val result = LocalChatClient().completeWithTools(
                LocalProvider("test", "Test", LocalProtocol.OpenAI, server.url("/v1").toString(), "test-key"),
                "test-model", requestHistory, toolsJson = null)
            assertTrue(result.isSuccess)
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertFalse(body.containsKey("tools"))
            assertFalse(body.containsKey("tool_choice"))
            assertTrue(body["messages"]!!.jsonArray.all { message ->
                val fields = message.jsonObject
                !fields.containsKey("tool_calls") && !fields.containsKey("tool_call_id") &&
                    fields["role"]!!.jsonPrimitive.content != "tool"
            })
        } finally { server.shutdown() }
    }
}
