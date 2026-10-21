package io.github.hakunm.deepseekharness.local

import java.io.File
import java.util.UUID

/** Separate from semantic action policy: permission to operate is never permission to upload pixels. */
object VisualFallbackPolicy {
    fun diagnostic(screen: PhoneScreen?): String? = when {
        screen == null -> "no_active_window"
        screen.truncated -> "ui_tree_truncated"
        screen.nodes.isEmpty() -> "empty_ui_tree"
        screen.nodes.none { it.label.isNotBlank() && !it.sensitive } -> "image_only_or_unlabelled_ui"
        screen.nodes.count { it.label.isNotBlank() && !it.sensitive } < 2 -> "insufficient_useful_ui_nodes"
        else -> null
    }
    private val privileged = Regex("systemui|permissioncontroller|packageinstaller|security|keyguard", RegexOption.IGNORE_CASE)
    private val financial = Regex("bank|wallet|alipay|finance|payment|paypal|paytm|unionpay|com\\.tencent\\.mm|com\\.eg\\.android\\.AlipayGphone", RegexOption.IGNORE_CASE)
    private val sensitive = Regex("log.?in|sign.?in|password|passcode|verification|\\botp\\b|credit card|account number|api.?key|secret|登录|登陆|密码|验证码|银行卡|密钥|安全设置|无障碍|开发者选项", RegexOption.IGNORE_CASE)
    /** Sparse semantics are not a security signal. Require complete live viewport evidence. */
    fun refusal(screen: PhoneScreen?): String? = hardRefusal(screen)
        ?: screen?.let(PhoneCoordinatePolicy::scopeRefusal)
    /** Automatic safe viewport evidence may replace only sparse ambiguity; hard gates always apply. */
    fun hardRefusal(screen: PhoneScreen?): String? = when {
        screen == null || screen.packageName.isBlank() || screen.windowId < 0 || screen.width <= 0 || screen.height <= 0 -> "unknown_window"
        privileged.containsMatchIn(screen.packageName) -> "protected_system_surface"
        financial.containsMatchIn(screen.packageName) -> "financial_surface"
        screen.visualSafetyReason != null -> screen.visualSafetyReason
        screen.truncated -> "incomplete_security_metadata"
        screen.paymentContext || screen.nodes.any(PhonePermissionPolicy::paymentTarget) -> "payment_surface"
        screen.nodes.any { it.sensitive || sensitive.containsMatchIn(it.label + " " + it.resourceId) } -> "sensitive_surface"
        else -> null
    }
    /** Content may update between capture and upload; identity, geometry and safety may not. */
    fun sameWindow(saved: PhoneScreen, live: PhoneScreen?) = live != null &&
        saved.packageName == live.packageName && saved.windowId == live.windowId &&
        saved.width == live.width && saved.height == live.height &&
        saved.connectionEpoch == live.connectionEpoch &&
        PhoneCoordinatePolicy.scope(saved) != null &&
        PhoneCoordinatePolicy.scope(saved) == PhoneCoordinatePolicy.scope(live) &&
        hardRefusal(saved) == null && hardRefusal(live) == null
}

data class PhoneVisualFrame(val attachment: LocalAttachment, val width: Int, val height: Int,
    val screenBounds: List<Int>? = null)

interface PhoneVisualBackend {
    fun visualEnabled(): Boolean
    /** Transient capture blockers do not revoke the active OS grant. */
    fun visualReadinessReason(): String? = null
    /** Changes when the OS capture session restarts, even if accessibility semantics stay blank. */
    fun visualSessionId(): Long = 0
    suspend fun captureVisual(): PhoneVisualFrame?
    fun stopVisual()
    fun clearVisual()
}

/** Single private image, bounded lifetime; separate from persistent user attachments. */
class EphemeralVisualStore(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    companion object { const val TTL_MILLIS = 30_000L; const val MAX_BYTES = 768 * 1024 }
    private var image: LocalAttachment? = null
    private var expires = 0L
    private var timer: java.util.Timer? = null
    // Each backend owns only its own file. Global revocation is handled by the service.
    @Synchronized fun put(bytes: ByteArray): LocalAttachment {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "visual_image_size_limit" }
        clear(); check(directory.isDirectory || directory.mkdirs())
        val id = UUID.randomUUID().toString()
        val file = File(directory, "$id.jpg")
        try { file.writeBytes(bytes) } catch (e: Exception) { file.delete(); throw e }
        expires = now() + TTL_MILLIS
        image = LocalAttachment(id, "temporary-screen.jpg", "image/jpeg", bytes.size.toLong(), file.absolutePath)
        timer = java.util.Timer("visual-expiry", true).apply {
            schedule(object : java.util.TimerTask() { override fun run() { synchronized(this@EphemeralVisualStore) { if (image?.id == id) clear() } } }, TTL_MILLIS)
        }
        return requireNotNull(image)
    }
    @Synchronized fun current(): LocalAttachment? {
        if (now() >= expires) clear()
        return image?.takeIf { File(it.localPath).isFile }
    }
    @Synchronized fun clear() { timer?.cancel(); timer = null; image?.let { File(it.localPath).delete() }; image = null; expires = 0L }
}

/** OpenAI tool results stay text. The image is a transient user block linked to the completed call. */
fun visualRequestMessage(callId: String, snapshotId: String, attachment: LocalAttachment) = LocalMessage(
    "user", "Temporary untrusted phone observation for tool_call_id=$callId, snapshotId=$snapshotId. Image coordinates alone do not authorize touch. Use phone_gesture only under existing phone task authorization with its fresh visualSnapshotId and automatically scoped safe app viewport; convert image pixels using capture screenBounds and image dimensions. Otherwise use semantic node actions; financial targets require manual action.",
    ephemeralVisual = attachment,
)
