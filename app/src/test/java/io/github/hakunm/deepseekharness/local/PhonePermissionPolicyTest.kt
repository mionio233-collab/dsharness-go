package io.github.hakunm.deepseekharness.local

import io.github.hakunm.deepseekharness.ui.LocalPermission
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PhonePermissionPolicyTest {
    private fun node(label: String, id: Int = 0) = PhoneNode(id, label, listOf(0, 0, 100, 100), true, false, true, false, false)
    private fun screen(vararg nodes: PhoneNode) = PhoneScreen("example.app", 1080, 2400, nodes.toList())
    private class Fake(var screen: PhoneScreen) : PhoneBackend {
        var performed = 0
        override fun ready() = true
        override fun stores() = listOf("example.app")
        override fun observe() = screen
        override fun open(packageName: String): Boolean { performed++; return true }
        override fun act(screen: PhoneScreen, node: PhoneNode?, action: String, text: String): Boolean { performed++; return true }
    }
    private suspend fun snapshot(tools: PhoneTools) = Json.parseToJsonElement(tools.observe.execute("{}")).jsonObject["snapshotId"]!!.jsonPrimitive.content
    private fun args(id: String, pkg: String = "example.app") = """{"action":"click","targetPackage":"$pkg","snapshotId":"$id","nodeId":0}"""
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }

    @Test fun ordinaryActionsAndSystemPackagesFollowLiveMode() {
        for (mode in LocalPermission.entries) {
            val policy = PhonePermissionPolicy({ mode })
            val authorized = mode in setOf(LocalPermission.Full, LocalPermission.AutoReview)
            for (pkg in listOf("example.app", "com.android.packageinstaller", "com.google.android.permissioncontroller", "com.android.systemui")) {
                for (label in listOf("Install", "Download", "Allow", "Agree", "Login", "Confirm", "安装", "下载", "允许", "同意", "登录", "确认", "Email login", "Account login")) {
                    val node = node(label)
                    assertEquals("$mode $pkg $label", authorized, policy.permits(screen(node).copy(packageName = pkg), node, "click"))
                }
                if (pkg != "example.app") assertEquals(!authorized, policy.protectedPackage(pkg))
            }
        }
    }

    @Test fun financialActionsAreBlockedInBothFullModes() {
        for (mode in listOf(LocalPermission.Full, LocalPermission.AutoReview)) {
            val policy = PhonePermissionPolicy({ mode })
            for (label in listOf("Pay now", "Purchase", "Buy", "Checkout", "Transfer", "Send money", "Authorize payment", "Place order", "支付", "付款", "购买", "转账", "汇款", "充值", "财务授权")) {
                val target = node(label)
                assertFalse(label, policy.permits(screen(target), target, "click"))
                val child = node("确认").copy(clickable = false, clickTargetId = 1)
                assertFalse(label, policy.permits(screen(child, target.copy(id = 1)), child, "click"))
                val parent = node("", 1)
                val paymentChild = target.copy(clickable = false, clickTargetId = 1)
                assertFalse(label, policy.permits(screen(paymentChild, parent), parent, "click"))
            }
        }
    }

    @Test fun transactionalContextBlocksGenericCommitButUnrelatedMentionDoesNot() {
        val policy = PhonePermissionPolicy({ LocalPermission.Full })
        for (heading in listOf("Payment", "Checkout", "Confirm transfer", "支付", "收银台", "确认转账")) {
            for (label in listOf("Confirm", "Continue", "Allow", "Login", "确认", "确定", "同意", "允许", "登录")) {
                val commit = node(label)
                val page = screen(commit, node(heading, 1).copy(clickable = false))
                assertFalse("$heading $label", policy.permits(page, commit, "click"))
                assertTrue(policy.permits(page, null, "back"))
                val input = node("[redacted]", 2).copy(editable = true, sensitive = true)
                assertFalse(policy.permits(page.copy(nodes = page.nodes + input), input, "set_text"))
                assertFalse(policy.permits(page.copy(nodes = page.nodes + input), input, "submit_text"))
            }
        }
        val commit = node("Confirm")
        assertTrue(policy.permits(screen(commit, node("Payment help is available in settings", 1).copy(clickable = false)), commit, "click"))
    }

    @Test fun ordinaryFullActionsExecuteWithoutDownloadApprovalAndExposeCapabilities() = runBlocking {
        for (mode in listOf(LocalPermission.Full, LocalPermission.AutoReview)) {
            val backend = Fake(screen(node("Download")))
            val tools = PhoneTools(backend, PhonePermissionPolicy({ mode }))
            val observation = tools.observe.execute("{}")
            assertTrue(observation.contains("capabilities")); assertTrue(observation.contains(mode.name))
            tools.action.execute(args(Json.parseToJsonElement(observation).jsonObject["snapshotId"]!!.jsonPrimitive.content))
            backend.screen = backend.screen.copy(packageName = "com.android.packageinstaller", nodes = listOf(node("安装")))
            tools.action.execute(args(snapshot(tools), "com.android.packageinstaller"))
            assertEquals(2, backend.performed)
        }
    }

    @Test fun modeDowngradeRevokesObservedTask() = runBlocking {
        var mode = LocalPermission.Full
        val backend = Fake(screen(node("Install")))
        val policy = PhonePermissionPolicy({ mode })
        val tools = PhoneTools(backend, policy)
        val id = snapshot(tools)
        mode = LocalPermission.WorkspaceWrite
        assertFalse(tools.available()); assertTrue(policy.protectedPackage("com.android.packageinstaller"))
        rejected { tools.action.execute(args(id)) }; rejected { tools.observe.execute("{}") }
        assertEquals(0, backend.performed)
    }

    @Test fun paymentParentAndContextAreCheckedInExecution() = runBlocking {
        val backend = Fake(screen(node("Confirm").copy(clickable = false, clickTargetId = 1), node("Pay now", 1)))
        val tools = PhoneTools(backend, PhonePermissionPolicy({ LocalPermission.Full }))
        rejected { tools.action.execute(args(snapshot(tools))) }
        backend.screen = screen(node("确认"), node("收银台", 1).copy(clickable = false))
        rejected { tools.action.execute(args(snapshot(tools))) }
        assertEquals(0, backend.performed)
    }

    @Test fun staleIdentityAndClickableAncestorAreRejected() = runBlocking {
        val original = node("Confirm")
        val backend = Fake(screen(original))
        val tools = PhoneTools(backend, PhonePermissionPolicy({ LocalPermission.Full }))
        for (changed in listOf(original.copy(label = "Pay"), original.copy(resourceId = "new"), original.copy(className = "new"),
            original.copy(bounds = listOf(0, 0)), original.copy(parentId = 99))) {
            backend.screen = screen(original); val id = snapshot(tools); backend.screen = screen(changed)
            rejected { tools.action.execute(args(id)) }
        }
        backend.screen = screen(original.copy(clickable = false, clickTargetId = 1), node("Continue", 1))
        val id = snapshot(tools)
        backend.screen = backend.screen.copy(nodes = backend.screen.nodes.map { if (it.id == 1) it.copy(resourceId = "different") else it })
        rejected { tools.action.execute(args(id)) }; assertEquals(0, backend.performed)
    }

    @Test fun ordinaryConfirmationsExecuteWithoutDuplicateFullToolApproval() = runBlocking {
        for (mode in listOf(LocalPermission.Full, LocalPermission.AutoReview)) {
            val backend = Fake(screen(node("Allow")))
            val tools = PhoneTools(backend, PhonePermissionPolicy({ mode }))
            for (label in listOf("Download", "Install", "Allow", "Agree", "Login", "确认")) {
                backend.screen = screen(node(label))
                tools.action.execute(args(snapshot(tools)))
            }
            assertEquals(6, backend.performed)
            assertFalse(mode.needsConfirmation("phone_action"))
            assertFalse(mode.needsConfirmation("http_get"))
            assertFalse(mode.needsConfirmation("write_file"))
        }
    }

    @Test fun secretInputIsNeverReturnedInFreshObservation() = runBlocking {
        val backend = Fake(screen(node("[redacted]").copy(editable = true, sensitive = true, resourceId = "app:id/password")))
        val tools = PhoneTools(backend, PhonePermissionPolicy({ LocalPermission.Full }))
        val id = snapshot(tools)
        backend.screen = screen(backend.screen.nodes.single().copy(label = "task-only-secret"))
        val result = tools.action.execute("""{"action":"set_text","targetPackage":"example.app","snapshotId":"$id","nodeId":0,"text":"task-only-secret"}""")
        assertFalse(result.contains("task-only-secret"))
        assertEquals(1, backend.performed)
        assertTrue(result.contains("[redacted]"))
    }

    @Test fun credentialsAreRedactedWhileLoginRemainsExecutable() = runBlocking {
        val backend = Fake(screen(node("Email login"), node("secret-password", 1).copy(sensitive = true),
            node("654321", 2).copy(editable = true, resourceId = "app:id/otp"), node("private@example.com", 3)))
        val tools = PhoneTools(backend, PhonePermissionPolicy({ LocalPermission.Full }))
        val result = tools.observe.execute("{}")
        assertFalse(result.contains("secret-password")); assertFalse(result.contains("654321")); assertFalse(result.contains("private@example.com"))
        assertTrue(result.contains("Email login"))
        tools.action.execute(args(Json.parseToJsonElement(result).jsonObject["snapshotId"]!!.jsonPrimitive.content))
        assertEquals(1, backend.performed)
        assertTrue(PhonePermissionPolicy.sensitiveData("anything", true, true))
        assertTrue(PhonePermissionPolicy.sensitiveData("123456", true, false, "verification_code"))
        assertFalse(PhonePermissionPolicy.sensitiveData("Account login", false, false))
    }
}
