package io.github.hakunm.deepseekharness.local

import io.github.hakunm.deepseekharness.ui.LocalPermission

/** Live session authorization, shared by the tool boundary and the Android execution boundary. */
class PhonePermissionPolicy(private val permission: () -> LocalPermission,
    private val enabled: () -> Boolean = { true }) {
    fun captureRefusal(screen: PhoneScreen?): String? = when {
        !authorized() -> "phone_not_authorized"
        else -> VisualFallbackPolicy.refusal(screen)
    }
    /** Phone consent is supplied by enabled(); OS consent must be a live capture service. */
    fun visualAuthorized(serviceReady: Boolean, osCaptureActive: Boolean, unlocked: Boolean): Boolean =
        authorized() && serviceReady && osCaptureActive && unlocked
    fun authorized() = enabled() && permission() in setOf(LocalPermission.Full, LocalPermission.AutoReview)
    fun description(): String = if (authorized())
        "${permission().name}: task-authorized navigation, downloads, installs, system prompts, ordinary consent and login; payments/purchases/transfers/financial authorization blocked. Credential values redacted. Android may require manual action; no root privileges."
    else "${permission().name}: phone operations disabled"

    fun protectedPackage(packageName: String): Boolean = !authorized() &&
        (packageName.contains("packageinstaller") || packageName.contains("permissioncontroller") || packageName == "com.android.systemui")

    fun permits(screen: PhoneScreen, node: PhoneNode?, action: String): Boolean {
        if (!authorized() || protectedPackage(screen.packageName)) return false
        if (action in setOf("back", "home")) return true
        if (screenCaptureConsent(screen)) return false
        if (node == null || !node.enabled) return false
        val target = if (action == "click") screen.nodes.singleOrNull { it.id == (node.clickTargetId ?: node.id) } ?: return false else node
        if (!target.enabled) return false
        if (action in setOf("long_press", "drag") &&
            (node.sensitive || paymentContext(screen) || !io.github.hakunm.deepseekharness.local.PhoneGesturePolicy.safeWindow(screen))) return false
        if (action == "long_press" && screen.nodes.any { child ->
            var parent = child.parentId
            val seen = mutableSetOf<Int>()
            var descendant = false
            while (parent != null && seen.add(parent)) {
                if (parent == node.id) { descendant = true; break }
                parent = screen.nodes.singleOrNull { it.id == parent }?.parentId
            }
            descendant && (child.sensitive || paymentTarget(child))
        }) return false
        if (action == "click" && screen.nodes.any { it.clickTargetId == target.id && paymentTarget(it) }) return false
        // Check both the requested child and the actual executable ancestor. Scroll/back are safe exits.
        if (action !in setOf("scroll_forward", "scroll_backward") &&
            (paymentTarget(node) || paymentTarget(target) ||
                (paymentContext(screen) && (action in setOf("set_text", "submit_text") ||
                    genericCommit.containsMatchIn(node.label) || genericCommit.containsMatchIn(target.label) ||
                    target.label.isBlank() || target.sensitive)))) return false
        // Values are never read back. Only text supplied by the task may be entered; no autofill extraction.
        return true
    }

    companion object {
        fun screenCaptureConsent(screen: PhoneScreen): Boolean =
            Regex("systemui|permissioncontroller", RegexOption.IGNORE_CASE).containsMatchIn(screen.packageName) &&
                screen.nodes.any { Regex("media.?projection|screen.?capture|screen.?record|recording|casting|share.*screen|start now|录制|录屏|屏幕共享|投屏|立即开始", RegexOption.IGNORE_CASE).containsMatchIn(it.label + " " + it.resourceId + " " + it.className) }
        private val payment = Regex("\\b(pay(?:ment)?|purchase|buy|checkout|check out|transfer|remit(?:tance)?|send money|place order|authorize (?:payment|transaction)|subscribe(?: now)?|top[ -]?up)\\b|支付|付款|购买|结账|结算|转账|汇款|打款|下单|充值|扣款|订阅|财务授权", RegexOption.IGNORE_CASE)
        private val genericCommit = Regex("\\b(confirm|ok|yes|continue|next|submit|agree|allow|authorize|accept|proceed|done|finish|log.?in|sign.?in)\\b|确认|确定|继续|下一步|提交|同意|允许|授权|完成|登录", RegexOption.IGNORE_CASE)
        private val transactionHeading = Regex("^(?:payment(?: confirmation| details)?|checkout|confirm (?:purchase|payment|transfer)|purchase confirmation|transfer(?: details)?|financial authorization|支付(?:确认|详情)?|收银台|确认(?:支付|购买|转账)|转账(?:详情)?|结算|付款)$", RegexOption.IGNORE_CASE)
        private val amount = Regex("\\b(total|amount due|payment amount|recipient|payee)\\b|应付|实付|支付金额|转账金额|收款人", RegexOption.IGNORE_CASE)
        fun paymentTarget(node: PhoneNode) = node.paymentAction || payment.containsMatchIn(node.label) ||
            payment.containsMatchIn(node.resourceId.replace('_', ' '))
        fun paymentContext(screen: PhoneScreen): Boolean = screen.paymentContext ||
            screen.nodes.any { !it.editable && !it.clickable && transactionHeading.matches(it.label.trim()) } ||
            (screen.nodes.any { !it.editable && amount.containsMatchIn(it.label) } && screen.nodes.any { paymentTarget(it) })

        private val credential = Regex("password|passcode|credential|\\botp\\b|one[ -]?time|verification code|security code|密码|口令|验证码|动态码", RegexOption.IGNORE_CASE)
        private val value = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+|\\b[0-9][0-9 -]{6,}[0-9]\\b")
        fun sensitiveData(label: String, editable: Boolean, password: Boolean, fieldIdentity: String = ""): Boolean =
            password || value.containsMatchIn(label) || credential.containsMatchIn(label) ||
                (editable && Regex("password|passcode|otp|verification|credential|email|account|phone|mobile|密码|账号|手机|验证码", RegexOption.IGNORE_CASE).containsMatchIn(fieldIdentity))
        fun redact(screen: PhoneScreen): PhoneScreen = screen.copy(
            paymentContext = paymentContext(screen),
            nodes = screen.nodes.map { node ->
                val sensitive = node.sensitive || sensitiveData(node.label, node.editable, false, node.resourceId)
                node.copy(label = if (sensitive) "[redacted]" else node.label.take(120), sensitive = sensitive,
                    paymentAction = paymentTarget(node))
            })
        fun stableTarget(current: PhoneNode, previous: PhoneNode): Boolean =
            current.id == previous.id && current.label == previous.label &&
                current.resourceId == previous.resourceId && current.className == previous.className &&
                current.clickable == previous.clickable && current.longClickable == previous.longClickable && current.editable == previous.editable &&
                current.scrollable == previous.scrollable && current.enabled == previous.enabled &&
                current.sensitive == previous.sensitive && current.paymentAction == previous.paymentAction &&
                current.clickTargetId == previous.clickTargetId && current.parentId == previous.parentId &&
                current.bounds.size == 4 && previous.bounds.size == 4 &&
                current.bounds.zip(previous.bounds).all { (now, before) -> kotlin.math.abs(now - before) <= 12 }
    }
}
