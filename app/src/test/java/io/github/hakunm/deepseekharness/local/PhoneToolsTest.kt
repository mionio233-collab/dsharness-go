package io.github.hakunm.deepseekharness.local

import io.github.hakunm.deepseekharness.ui.LocalPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PhoneToolsTest {
    private class Fake : PhoneBackend {
        var connected = true
        var performed = 0
        var onObserve: (() -> Unit)? = null
        var onAction: (() -> Unit)? = null
        var actionAccepted = true
        var screen = PhoneScreen("com.xiaomi.market", 1080, 2400,
            listOf(PhoneNode(0, "Search", listOf(0, 0, 100, 100), true, true, true, false, false)))
        val installed = listOf(
            PhoneApp("com.xiaomi.market", "Store"),
            PhoneApp("com.android.settings", "Settings"),
        )
        override fun ready() = connected
        override fun stores() = listOf("com.xiaomi.market")
        override fun apps() = installed
        override fun observe(): PhoneScreen { onObserve?.invoke(); return screen }
        override fun open(packageName: String): Boolean { performed++; return true }
        override fun act(screen: PhoneScreen, node: PhoneNode?, action: String, text: String): Boolean { onAction?.invoke(); performed++; return actionAccepted }
    }
    private suspend fun snapshot(tools: PhoneTools) = Json.parseToJsonElement(tools.observe.execute("{}"))
        .jsonObject["snapshotId"]!!.jsonPrimitive.content
    private fun args(id: String, action: String = "click", pkg: String = "com.xiaomi.market", node: Int = 0) =
        """{"action":"$action","targetPackage":"$pkg","snapshotId":"$id","nodeId":$node}"""

    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (e: IllegalArgumentException) { } catch (e: IllegalStateException) { }
    }
    @Test fun statusStartsOnlyAfterAuthorizedValidPhoneCall() = runBlocking {
        val store = io.github.hakunm.deepseekharness.agent.PhoneAgentStatus.store
        store.clear()
        var allowed = false
        val tools = PhoneTools(Fake(), PhonePermissionPolicy({ LocalPermission.Full }, { allowed }))
        tools.reset()
        assertNull(store.state.value)
        rejected { tools.observe.execute("{}") }
        assertNull(store.state.value)
        allowed = true
        rejected { tools.observe.execute("{\"invalid\":true}") }
        rejected { tools.action.execute("{}") }
        assertNull(store.state.value)
        tools.sessionId = "local-origin"
        tools.observe.execute("{}")
        assertEquals("local-origin", store.state.value!!.sessionId)
        store.clear()
    }

    @Test fun permissionsNeverAllowPhoneInWorkspaceModes() {
        for (permission in listOf(LocalPermission.ReadOnly, LocalPermission.WorkspaceWrite)) {
            assertFalse(permission.allows("phone_observe")); assertFalse(permission.allows("phone_action"))
        }
    }
    @Test fun executionRechecksAuthorizationAndReadiness() = runBlocking {
        val backend = Fake(); var allowed = true
        val tools = PhoneTools(backend, PhonePermissionPolicy({ LocalPermission.Full }, { allowed })); tools.reset()
        val id = snapshot(tools); allowed = false
        rejected { tools.action.execute(args(id)) }; allowed = true; backend.connected = false
        rejected { tools.observe.execute("{}") }; assertEquals(0, backend.performed)
    }
    @Test fun stalePackageInstallerAndPasswordAreBlocked() = runBlocking {
        val backend = Fake(); val tools = PhoneTools(backend, PhonePermissionPolicy({ LocalPermission.Full })); tools.reset()
        val id = snapshot(tools)
        backend.screen = backend.screen.copy(packageName = "com.android.packageinstaller")
        rejected { tools.action.execute(args(id)) }
        for (label in listOf("Pay", "Purchase", "Transfer")) {
            backend.screen = backend.screen.copy(packageName = "com.xiaomi.market",
                nodes = listOf(backend.screen.nodes[0].copy(label = label, sensitive = label == "Password")))
            rejected { tools.action.execute(args(snapshot(tools))) }
        }
        assertEquals(0, backend.performed)
    }
    @Test fun strictArgumentsAndOneUseSnapshot() = runBlocking {
        val backend = Fake(); val tools = PhoneTools(backend, PhonePermissionPolicy({ LocalPermission.Full })); tools.reset()
        rejected { tools.observe.execute("{\"extra\":true}") }
        rejected { tools.action.execute("{\"action\":\"click\"}") }
        val id = snapshot(tools)
        rejected { tools.action.execute(args(id).dropLast(1) + ",\"url\":\"https://evil/apk\"}") }
        tools.action.execute(args(id))
        rejected { tools.action.execute(args(id)) }; assertEquals(1, backend.performed)
    }
    @Test fun downloadsDoNotRequireRepeatedApproval() = runBlocking {
        val backend = Fake(); backend.screen = backend.screen.copy(nodes = listOf(backend.screen.nodes[0].copy(label = "Download")))
        val tools = PhoneTools(backend, PhonePermissionPolicy({ LocalPermission.Full })); tools.reset()
        tools.action.execute(args(snapshot(tools)))
        assertEquals(1, backend.performed)
    }
    @Test fun openAiPhoneObservationRoundTripRetainsToolProtocol() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"choices":[{"message":{"content":null,"tool_calls":[{"id":"observe1","type":"function","function":{"name":"phone_observe","arguments":"{}"}}]}}]}"""))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"choices":[{"message":{"content":"Observed; installation remains manual"}}]}"""))
        server.start()
        try {
            val tools = PhoneTools(Fake(), PhonePermissionPolicy({ LocalPermission.Full })); tools.reset()
            val provider = LocalProvider("test", "Test", LocalProtocol.OpenAI, server.url("/v1").toString(), "test-only")
            val client = LocalChatClient()
            val history = listOf(LocalMessage("user", "Search Douyin"))
            val schema = """[{"type":"function","function":{"name":"phone_observe","description":"Observe","parameters":${tools.observe.parametersJson}}}]"""
            val completion = client.completeWithTools(provider, "test", history, toolsJson = schema).getOrThrow()
            val call = completion.toolCalls.single()
            assertEquals("phone_observe", call.name)
            val output = tools.observe.execute(call.arguments)
            assertTrue(output.contains("1080")); assertTrue(output.contains("screenshotSupported"))
            client.completeWithTools(provider, "test", history + completion.assistant +
                LocalMessage("tool", output, toolCallId = call.id), toolsJson = schema).getOrThrow()
            server.takeRequest()
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"tool_call_id\":\"observe1\""))
            assertTrue(body.contains("\"tool_calls\""))
        } finally { server.shutdown() }
    }

    @Test fun ordinaryInstalledAppsAreLaunchableAndSystemPackagesAreNot() = runBlocking {
        val backend = Fake(); val tools = PhoneTools(backend, PhonePermissionPolicy({ LocalPermission.Full })); tools.reset()
        var result = Json.parseToJsonElement(tools.observe.execute("{}")).jsonObject
        var id = result["snapshotId"]!!.jsonPrimitive.content
        tools.action.execute("""{"action":"open_app","targetPackage":"com.android.settings","snapshotId":"$id"}""")
        assertEquals(1, backend.performed)
        id = Json.parseToJsonElement(tools.observe.execute("{}")).jsonObject["snapshotId"]!!.jsonPrimitive.content
        rejected { tools.action.execute("""{"action":"open_app","targetPackage":"com.android.systemui","snapshotId":"$id"}""") }
        assertEquals(1, backend.performed)
    }

    @Test fun clickOnTextCanResolveToObservedClickableParent() = runBlocking {
        val backend = Fake()
        backend.screen = backend.screen.copy(nodes = listOf(
            PhoneNode(0, "Search", listOf(0, 0, 100, 100), false, false, true, false, false, clickTargetId = 1),
            PhoneNode(1, "Search field", listOf(0, 0, 100, 100), true, false, true, false, false),
        ))
        val tools = PhoneTools(backend, PhonePermissionPolicy({ LocalPermission.Full })); tools.reset()
        tools.action.execute(args(snapshot(tools), node = 0))
        assertEquals(1, backend.performed)
    }

    @Test fun unrelatedWholeScreenChangesDoNotInvalidateSameTarget() = runBlocking {
        val backend = Fake(); val tools = PhoneTools(backend, PhonePermissionPolicy({ LocalPermission.Full })); tools.reset()
        val id = snapshot(tools)
        backend.screen = backend.screen.copy(nodes = listOf(backend.screen.nodes[0].copy(focused = true)))
        tools.action.execute(args(id))
        assertEquals(1, backend.performed)
    }

    @Test fun realToolPathPublishesSafeCompletionAndFailure() = runBlocking {
        val store = io.github.hakunm.deepseekharness.agent.PhoneAgentStatus.store
        store.clear()
        val backend = Fake(); val tools = PhoneTools(backend, PhonePermissionPolicy({ LocalPermission.Full }))
        tools.statusToken = store.begin()
        tools.action.execute(args(snapshot(tools)))
        assertEquals(io.github.hakunm.deepseekharness.agent.PhonePhase.THINKING, store.state.value!!.phase)
        assertEquals("com.xiaomi.market", store.state.value!!.packageName)
        rejected { tools.action.execute("{\"private\":\"secret\"}") }
        assertEquals(io.github.hakunm.deepseekharness.agent.PhonePhase.FAILURE, store.state.value!!.phase)
        assertFalse(store.state.value!!.presentation().contains("secret"))
        store.clear()
    }

    @Test fun cancellationBeforeExecutionPublishesNoPayloads() = runBlocking {
        val store = io.github.hakunm.deepseekharness.agent.PhoneAgentStatus.store
        store.clear()
        val backend = Fake()
        val tools = PhoneTools(backend, PhonePermissionPolicy({ LocalPermission.Full }))
        val id = snapshot(tools)
        val job = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { tools.action.execute(args(id)) }
        job.cancel(CancellationException("private cancellation detail")); job.join()
        assertEquals(0, backend.performed)
        assertFalse(store.state.value!!.presentation().contains("private"))
        store.clear()
    }

    @Test fun financialTargetsRemainBlocked() = runBlocking {
        val backend = Fake(); val tools = PhoneTools(backend, PhonePermissionPolicy({ LocalPermission.Full })); tools.reset()
        for (label in listOf("Pay now", "Buy", "Checkout", "Transfer", "支付", "确认购买", "转账", "财务授权")) {
            backend.screen = backend.screen.copy(nodes = listOf(backend.screen.nodes.first().copy(label = label)))
            rejected { tools.action.execute(args(snapshot(tools))) }
        }
        assertEquals(0, backend.performed)
    }

    @Test fun publicProgressIsLazyLatestAndNeverFallsBackToPrivateReasoning() = runBlocking {
        val store = io.github.hakunm.deepseekharness.agent.PhoneAgentStatus.store
        store.clear()
        val backend = Fake()
        val tools = PhoneTools(backend, PhonePermissionPolicy({ LocalPermission.Full }))
        val call = LocalToolCall("one", "phone_observe", "{}")
        fun completion(public: String, private: String, calls: List<LocalToolCall>) =
            LocalCompletion(public, calls, LocalMessage("assistant", public, reasoningContent = private))
        tools.preparePublicProgress(completion("正在搜索", "正在查找地址栏 secret", emptyList()))
        assertNull(store.state.value)
        tools.preparePublicProgress(completion("准备查找地址栏", "下载 secret", listOf(call)))
        assertNull(store.state.value)
        var duringObserve: String? = null
        backend.onObserve = { duringObserve = store.state.value?.progressSummary }
        snapshot(tools)
        assertEquals("准备查找地址栏", duringObserve)
        val newest = "应用商店已打开，我再观察一次界面元素。"
        // Streaming updates reach status before the throttled transcript consumer runs.
        tools.preparePublicProgress(newest, true)
        assertEquals(newest, store.state.value!!.progressSummary)
        snapshot(tools)
        assertEquals(newest, store.state.value!!.progressSummary)
        tools.preparePublicProgress(completion("", "正在查找地址栏", listOf(call)))
        snapshot(tools)
        assertEquals(newest, duringObserve)
        tools.preparePublicProgress(completion("准备查找地址栏", "", listOf(call)))
        tools.reset(); store.clear()
        snapshot(tools)
        assertEquals("正在读取页面控件", duringObserve)
        store.clear()
    }

    @Test fun validatedTargetsArePublishedAndTextArgumentsNeverAppear() = runBlocking {
        val store = io.github.hakunm.deepseekharness.agent.PhoneAgentStatus.store
        store.clear()
        val backend = Fake()
        backend.screen = backend.screen.copy(nodes = listOf(
            PhoneNode(0, "Download", listOf(0, 0, 100, 100), false, false, true, false, false, clickTargetId = 1),
            PhoneNode(1, "Download", listOf(0, 0, 100, 100), true, false, true, false, false)))
        val tools = PhoneTools(backend, PhonePermissionPolicy({ LocalPermission.Full }))
        var activeSummary: String? = null
        backend.onAction = { activeSummary = store.state.value!!.progressSummary }
        tools.action.execute(args(snapshot(tools)))
        assertEquals("正在点击 · Store · 下载按钮", activeSummary)
        assertEquals("已点击 · Store · 下载按钮，正在检查页面变化", store.state.value!!.progressSummary)
        backend.screen = backend.screen.copy(nodes = listOf(PhoneNode(0, "Search", listOf(0, 0, 100, 100), true, true, true, false, false)))
        val id = snapshot(tools)
        tools.action.execute(args(id, "set_text").dropLast(1) + ",\"text\":\"user@example.com-password-123456\"}")
        assertEquals("正在输入文本 · Store · 搜索框", activeSummary)
        assertFalse(store.state.value!!.presentation().contains("example"))
        assertFalse(store.state.value!!.presentation().contains("123456"))
        val stale = snapshot(tools)
        backend.screen = backend.screen.copy(nodes = listOf(backend.screen.nodes[0].copy(label = "Download")))
        rejected { tools.action.execute(args(stale)) }
        assertFalse(store.state.value!!.progressSummary.orEmpty().contains("下载"))
        backend.actionAccepted = false
        rejected { tools.action.execute(args(snapshot(tools))) }
        assertFalse(store.state.value!!.progressSummary.orEmpty().contains("已点击"))
        store.clear()
    }

}