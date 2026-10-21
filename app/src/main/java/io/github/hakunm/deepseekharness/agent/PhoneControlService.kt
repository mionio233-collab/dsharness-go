package io.github.hakunm.deepseekharness.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.asStateFlow

class PhoneControlService : AccessibilityService() {

    companion object {
        private const val TAG = "PhoneControlService"

        /** 无障碍树的遍历上限，防止异常界面把主线程拖死。 */
        private const val MAX_NODES = 5000

        @Volatile
        private var instance: PhoneControlService? = null

        private val connectionCounter = java.util.concurrent.atomic.AtomicLong()
        internal val connectionEpoch: Long get() = connectionCounter.get()
        private val mutableConnected = kotlinx.coroutines.flow.MutableStateFlow(false)
        val connected: kotlinx.coroutines.flow.StateFlow<Boolean> =
            mutableConnected.asStateFlow()

        fun getInstance(): PhoneControlService? = instance

        /**
         * 服务是否已被系统启用。
         *
         * 不要只看内存里的 [instance]：服务随时可能被系统重新绑定（解绑后 instance 会短暂为
         * null），权限状态应该以系统设置为准。
         */
        fun isEnabled(context: android.content.Context): Boolean {
            if (android.provider.Settings.Secure.getInt(context.contentResolver,
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
            val expected = android.content.ComponentName(context, PhoneControlService::class.java)
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val configured = enabled.split(':').any { flattened ->
                android.content.ComponentName.unflattenFromString(flattened) == expected
            }
            return configured
        }

        /** 兼容旧调用（仅反映当前是否有活动实例）。 */
        fun isConnected(): Boolean = instance != null
    }

    private var statusOverlay: PhoneStatusOverlay? = null
    private val pendingGestures = mutableSetOf<GestureResultCallback>()

    internal fun forgetGesture(callback: GestureResultCallback) {
        synchronized(pendingGestures) { pendingGestures.remove(callback) }
    }

    // Android exposes no cancel-only API. Settle callbacks and drop ownership; a dispatched
    // finite stroke may run to its scheduled end. Never inject a dummy touch on a new window.
    internal fun cancelPendingGestureCallbacks() = cancelGestures()

    private fun cancelGestures() {
        val callbacks = synchronized(pendingGestures) { pendingGestures.toList().also { pendingGestures.clear() } }
        callbacks.forEach { it.onCancelled(null) }
    }

    internal fun connectionLost() {
        if (instance === this) {
            connectionCounter.incrementAndGet()
            instance = null
            mutableConnected.value = false
            PhoneAgentStatus.store.clear()
        }
        cancelGestures()
    }

    internal fun dispatchChecked(gesture: GestureDescription, callback: GestureResultCallback,
        dispatch: (GestureDescription, GestureResultCallback) -> Boolean = { description, result ->
            dispatchGesture(description, result, android.os.Handler(android.os.Looper.getMainLooper()))
        }): Boolean {
        if (instance !== this) return false
        synchronized(pendingGestures) { pendingGestures.add(callback) }
        val wrapped = object : GestureResultCallback() {
            override fun onCompleted(description: GestureDescription?) { forgetGesture(callback); callback.onCompleted(description) }
            override fun onCancelled(description: GestureDescription?) { forgetGesture(callback); callback.onCancelled(description) }
        }
        return try {
            phoneAction { dispatch(gesture, wrapped) }
                .also { if (!it) { forgetGesture(callback); callback.onCancelled(null) } }
        } catch (failure: RuntimeException) { forgetGesture(callback); throw failure }
    }

    fun previewStatus(): Boolean = statusOverlay?.preview() == true

    suspend fun <T> withCaptureOverlaysHidden(block: suspend () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
        statusOverlay?.maskForCapture(true)
        try {
            kotlinx.coroutines.delay(180) // let the removal reach the compositor before attaching the capture surface
            block()
        } finally { statusOverlay?.maskForCapture(false) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectionCounter.incrementAndGet()
        instance = this
        mutableConnected.value = true
        statusOverlay?.close()
        statusOverlay = PhoneStatusOverlay(this)
        Log.i(TAG, "accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 目前不需要逐条处理事件；界面状态在需要时按需读取。
    }

    override fun onInterrupt() {
        // Drop current status immediately, but remain ready for the next explicit task/preview.
        // The service is still bound; interrupt does not guarantee another onServiceConnected.
        cancelGestures()
        PhoneAgentStatus.store.clear()
        statusOverlay?.close()
        statusOverlay = PhoneStatusOverlay(this)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connectionLost()
        statusOverlay?.close()
        statusOverlay = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionLost()
        statusOverlay?.close()
        statusOverlay = null
    }

    /** 读取当前窗口的 UI 树 */
    fun getUIHierarchy(): UINode? {
        val root = rootInActiveWindow ?: return null
        return parseNode(root, 0)
    }

    private fun parseNode(node: AccessibilityNodeInfo, depth: Int): UINode {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val children = mutableListOf<UINode>()
        if (depth < 60) {
            for (i in 0 until node.childCount) {
                // 不要 recycle()：API 33 起该方法已废弃，而且对活动窗口里的节点调用
                // recycle() 会让后续查询拿到已回收对象，导致崩溃/数据错乱。
                node.getChild(i)?.let { children.add(parseNode(it, depth + 1)) }
            }
        }

        return UINode(
            text = node.text?.toString().orEmpty(),
            contentDescription = node.contentDescription?.toString().orEmpty(),
            className = node.className?.toString().orEmpty(),
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            bounds = bounds,
            children = children,
        )
    }

    /** 在坐标处点击 */
    fun tap(x: Int, y: Int): Boolean = phoneAction {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        instance === this && dispatchGesture(gesture, null, null)
    }

    /** 滑动 */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long = 300): Boolean = phoneAction {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        instance === this && dispatchGesture(gesture, null, null)
    }

    /** 向当前聚焦的输入框写入文本 */
    fun inputText(text: String): Boolean = phoneAction {
        if (instance !== this) return false
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** 按文字查找元素（返回屏幕坐标） */
    fun findNodeByText(text: String): List<Rect> {
        val nodes = rootInActiveWindow?.findAccessibilityNodeInfosByText(text) ?: return emptyList()
        return nodes.mapNotNull { node ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            bounds.takeIf { !it.isEmpty }
        }
    }

    /** 当前前台应用包名 */
    fun getCurrentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    fun pressBack(): Boolean = phoneAction { instance === this && performGlobalAction(GLOBAL_ACTION_BACK) }

    fun pressHome(): Boolean = phoneAction { instance === this && performGlobalAction(GLOBAL_ACTION_HOME) }

    /** 收集屏幕上的所有文字（无障碍替代 OCR），使用迭代遍历避免深递归 */
    fun getAllText(): List<TextWithBounds> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<TextWithBounds>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)

        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            visited++
            val node = stack.removeLast()
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { text ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                result.add(TextWithBounds(text, bounds))
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return result
    }
}

data class UINode(
    val text: String,
    val contentDescription: String,
    val className: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val bounds: Rect,
    val children: List<UINode>,
)

data class TextWithBounds(
    val text: String,
    val bounds: Rect,
)
