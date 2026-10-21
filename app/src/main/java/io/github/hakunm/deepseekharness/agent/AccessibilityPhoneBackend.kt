package io.github.hakunm.deepseekharness.agent

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import io.github.hakunm.deepseekharness.local.PhoneApp
import io.github.hakunm.deepseekharness.local.PhoneBackend
import io.github.hakunm.deepseekharness.local.PhoneNode
import io.github.hakunm.deepseekharness.local.PhoneScreen

import io.github.hakunm.deepseekharness.local.PhonePermissionPolicy
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import io.github.hakunm.deepseekharness.local.PhoneVisualFrame
import io.github.hakunm.deepseekharness.ui.LocalPermission

class AccessibilityPhoneBackend(private val context: Context,
    private val policy: PhonePermissionPolicy = PhonePermissionPolicy({ LocalPermission.ReadOnly })) : PhoneBackend, io.github.hakunm.deepseekharness.local.PhoneVisualBackend {
    private val visualStore = io.github.hakunm.deepseekharness.local.EphemeralVisualStore(java.io.File(context.cacheDir, "phone-visual"))
    override fun visualEnabled() = ScreenCaptureService.isRunning()
    override fun visualReadinessReason() = ScreenCaptureService.state.value.let { if (it.ready) null else it.reason }
    override fun visualSessionId() = ScreenCaptureService.getInstance()?.imageEpoch ?: 0L
    override fun stopVisual() { clearVisual(); ScreenCaptureService.stop(context, "phone_consent_revoked") }
    override fun clearVisual() = visualStore.clear()
    override suspend fun captureVisual(): PhoneVisualFrame? {
        val epoch = visualSessionId()
        val before = observe() ?: return null
        if (captureRefusal(before) != null) return null
        val captureGrant = io.github.hakunm.deepseekharness.local.PhoneCoordinatePolicy.scope(before) ?: return null
        val service = PhoneControlService.getInstance() ?: return null
        return try { service.withCaptureOverlaysHidden {
            val bitmap = ScreenCaptureService.getInstance()?.awaitFreshFrame() ?: return@withCaptureOverlaysHidden null
            try {
                val live = observe()
                if (!io.github.hakunm.deepseekharness.local.VisualFallbackPolicy.sameWindow(before, live) ||
                    captureRefusal(live) != null) return@withCaptureOverlaysHidden null
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val bounds = captureGrant.bounds
                    if (!io.github.hakunm.deepseekharness.local.VisualFallbackPolicy.sameWindow(before, observe())) return@withContext null
                    // Projection frames must cover exactly the observed display; never guess offsets after rotation.
                    if (bitmap.width != before.width || bitmap.height != before.height) return@withContext null
                    // Blank/near-uniform app frames may be FLAG_SECURE content. Never manufacture a replacement.
                    if (!phoneVisualViewportHasContent(bounds, bitmap::getPixel)) return@withContext null
                    val crop = android.graphics.Bitmap.createBitmap(bitmap, bounds[0], bounds[1], bounds[2] - bounds[0], bounds[3] - bounds[1])
                    val scale = minOf(1f, 1280f / maxOf(crop.width, crop.height))
                    val scaled = android.graphics.Bitmap.createScaledBitmap(crop, maxOf(1, (crop.width * scale).toInt()), maxOf(1, (crop.height * scale).toInt()), true)
                    try {
                        val bytes = java.io.ByteArrayOutputStream().use { out -> scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out); out.toByteArray() }
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val finalScreen = observe()
                        if (epoch != visualSessionId() || captureRefusal(finalScreen) != null ||
                            !io.github.hakunm.deepseekharness.local.VisualFallbackPolicy.sameWindow(before, finalScreen) ||
                            bytes.size > io.github.hakunm.deepseekharness.local.EphemeralVisualStore.MAX_BYTES) null
                        else {
                            val attachment = visualStore.put(bytes)
                            if (epoch != visualSessionId() || !visualEnabled()) { clearVisual(); null }
                            else PhoneVisualFrame(attachment, scaled.width, scaled.height, bounds)
                        }
                    } finally {
                        if (scaled !== crop && scaled !== bitmap) scaled.recycle()
                        if (crop !== bitmap) crop.recycle()
                    }
                }
            } finally { bitmap.recycle() }
        } } catch (failure: Throwable) { clearVisual(); throw failure }
    }
    override fun protectedPackage(packageName: String) = policy.protectedPackage(packageName)
    private data class Pending(val node: AccessibilityNodeInfo, val inheritedSensitive: Boolean,
        val clickTarget: Int?, val parent: Int?)
    override fun ready() = PhoneControlService.isEnabled(context) && PhoneControlService.isConnected()
    override fun stores(): List<String> = STORE_PACKAGES.filter { context.packageManager.getLaunchIntentForPackage(it) != null }
    override fun apps(): List<PhoneApp> = runCatching {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = info.loadLabel(context.packageManager).toString().ifBlank { packageName }
                PhoneApp(packageName, label)
            }
            .distinctBy { app -> app.packageName }
            .filterNot { app -> protectedPackage(app.packageName) }
            .sortedBy { app -> app.label.lowercase() }
    }.getOrDefault(emptyList())

    override fun open(packageName: String): Boolean = phoneAction {
        if (!policy.authorized() || !ready() || apps().none { it.packageName == packageName } || protectedPackage(packageName)) return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
    private fun collect(): Pair<PhoneScreen, List<AccessibilityNodeInfo>>? {
        val service = PhoneControlService.getInstance() ?: return null
        val root = service.rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        val displayBounds = service.getSystemService(android.view.WindowManager::class.java).let { manager ->
            if (android.os.Build.VERSION.SDK_INT >= 30) manager.maximumWindowMetrics.bounds
            else Rect().apply {
                val point = android.graphics.Point()
                @Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(point)
                set(0, 0, point.x, point.y)
            }
        }
        val stack = ArrayDeque<Pending>()
        val nodes = mutableListOf<PhoneNode>()
        val handles = mutableListOf<AccessibilityNodeInfo>()
        stack.addLast(Pending(root, false, null, null))
        var visited = 0
        var truncatedChildren = false
        while (stack.isNotEmpty() && visited++ < 300) {
            val (node, inheritedSensitive, inheritedClickTarget, parentId) = stack.removeLast()
            val label = listOf(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty())
                .map(String::trim).filter(String::isNotEmpty).distinct().joinToString(" ")
            val sensitive = inheritedSensitive || PhonePermissionPolicy.sensitiveData(label, node.isEditable,
                node.isPassword || (node.inputType and android.text.InputType.TYPE_MASK_VARIATION) in setOf(
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD),
                node.viewIdResourceName.orEmpty() + " " + node.hintText?.toString().orEmpty())
            if (node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val id = handles.size
                val clickTargetId = if (node.isClickable) id else inheritedClickTarget
                handles += node
                nodes += PhoneNode(
                    id = id,
                    label = label,
                    paymentAction = PhonePermissionPolicy.paymentTarget(PhoneNode(id, label, emptyList(), false, false, true, false, false)),
                    parentId = parentId,
                    bounds = listOf(rect.left, rect.top, rect.right, rect.bottom),
                    clickable = node.isClickable,
                    longClickable = node.isLongClickable && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK },
                    editable = node.isEditable,
                    enabled = node.isEnabled,
                    scrollable = node.isScrollable,
                    sensitive = sensitive,
                    selected = node.isSelected,
                    focused = node.isFocused,
                    checked = node.isChecked,
                    resourceId = node.viewIdResourceName.orEmpty(),
                    className = node.className?.toString().orEmpty(),
                    clickTargetId = clickTargetId,
                    imeSubmit = android.os.Build.VERSION.SDK_INT >= 30 && node.isEditable && node.isFocused &&
                        node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id },
                )
            }
            val childClickTarget = if (node.isClickable && node.isVisibleToUser) handles.size - 1 else inheritedClickTarget
            if (node.childCount > 100) truncatedChildren = true
            for (i in minOf(node.childCount, 100) - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(Pending(it, inheritedSensitive || node.isPassword,
                    childClickTarget, if (node.isVisibleToUser) handles.size - 1 else parentId)) } ?: run { truncatedChildren = true }
            }
        }
        val windows = service.windows
        val unsafeWindows = windows.any { window ->
            when (window.type) {
                // Floating panels, dialogs, and palettes spawned by the same package as the foreground
                // window (e.g. a drawing-app color picker or layer panel) are safe same-app UI; only
                // windows from a different package indicate a genuine overlapping foreign surface.
                android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ->
                    window.id != root.windowId &&
                        window.root?.packageName?.toString() != pkg
                android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> window.isActive || window.isFocused
                android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ->
                    window.root?.packageName?.toString() != context.packageName
                else -> true // IME, split divider, magnifier and unknown window types are not app evidence.
            }
        }
        val viewport = Rect().also(root::getBoundsInScreen)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val insets = service.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            if (!viewport.intersect(insets.left, insets.top, displayBounds.width() - insets.right,
                    displayBounds.height() - insets.bottom)) return null
        }
        val protectedBounds = windows.filter { window ->
            window.id != root.windowId && (window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                window.root?.packageName?.toString() != context.packageName)
        }.map { window -> Rect().also(window::getBoundsInScreen).let { listOf(it.left, it.top, it.right, it.bottom) } }
        return PhonePermissionPolicy.redact(PhoneScreen(pkg, displayBounds.width(), displayBounds.height(), nodes,
            windowId = root.windowId, truncated = stack.isNotEmpty() || truncatedChildren,
            connectionEpoch = PhoneControlService.connectionEpoch,
            safeWindowMetadataComplete = windows.any { it.id == root.windowId && it.isActive &&
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION } &&
                deviceUnlocked() && pkg != context.packageName,
            viewportBounds = listOf(viewport.left, viewport.top, viewport.right, viewport.bottom), protectedBounds = protectedBounds,
            visualSafetyReason = if (unsafeWindows) "keyboard_or_overlapping_window" else null)) to handles
    }
    override fun observe(): PhoneScreen? = try { collect()?.first?.let {
        if (protectedPackage(it.packageName)) it.copy(nodes = emptyList()) else it
    } } catch (failure: RuntimeException) {
        if (!phonePlatformRejected(failure)) throw failure
        null
    }
    override fun act(screen: PhoneScreen, node: PhoneNode?, action: String, text: String): Boolean = phoneAction {
        if (!policy.authorized() || !ready() || screen.packageName == context.packageName || protectedPackage(screen.packageName)) return false
        val (live, handles) = collect() ?: return false
        if (live.packageName != screen.packageName || live.windowId != screen.windowId) return false
        val requested = node?.let { old -> live.nodes.singleOrNull { it.id == old.id }?.takeIf { sameTarget(it, old) } }
        if (!policy.permits(live, requested, action)) return false
        val service = PhoneControlService.getInstance() ?: return false
        if (action == "back") return service.pressBack()
        if (action == "home") return service.pressHome()
        if (requested == null || !requested.enabled) return false
        val nodeId = if (action == "click") requested.clickTargetId ?: requested.id else requested.id
        val liveTarget = live.nodes.singleOrNull { it.id == nodeId } ?: return false
        if (!sameTarget(liveTarget, if (nodeId == requested.id) requested else screen.nodes.singleOrNull { it.id == nodeId } ?: return false)) return false
        val target = handles.getOrNull(nodeId) ?: return false
        if (!target.isEnabled || target.packageName?.toString() != live.packageName || target.windowId != live.windowId ||
            !policy.permits(live, requested, action)) return false
        return when (action) {
            "long_press" -> deviceUnlocked() &&
                io.github.hakunm.deepseekharness.local.PhoneGesturePolicy.permitsLongPress(policy, live, requested) &&
                target.isLongClickable && target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            "click" -> target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            "scroll_forward" -> target.isScrollable && target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            "scroll_backward" -> target.isScrollable && target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            "set_text" -> target.isEditable && target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            })
            "submit_text" -> android.os.Build.VERSION.SDK_INT >= 30 && liveTarget.imeSubmit &&
                target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            else -> false
        }
    }

    private fun captureRefusal(screen: PhoneScreen?) =
        phoneVisualCaptureRefusal(policy, screen, ready(), visualEnabled(), deviceUnlocked())

    override fun deviceUnlocked(): Boolean =
        !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked &&
            context.getSystemService(android.os.PowerManager::class.java).isInteractive

    override suspend fun drag(screen: PhoneScreen, source: PhoneNode, destination: PhoneNode): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            phoneAction {
            // Continued strokes preserve contact during the hold, then move without lifting.
            if (android.os.Build.VERSION.SDK_INT < 26 || !ready() || !deviceUnlocked() ||
                screen.packageName == context.packageName) return@withContext false
            val service = PhoneControlService.getInstance() ?: return@withContext false
            val initial = collect() ?: return@withContext false
            if (initial.first != screen || !dragTargetsValid(initial, source, destination)) return@withContext false
            val (x, y) = io.github.hakunm.deepseekharness.local.PhoneGesturePolicy.center(source)
            val (endX, endY) = io.github.hakunm.deepseekharness.local.PhoneGesturePolicy.center(destination)
            val hold = android.accessibilityservice.GestureDescription.StrokeDescription(
                android.graphics.Path().apply { moveTo(x, y) }, 0,
                maxOf(700L, android.view.ViewConfiguration.getLongPressTimeout().toLong() + 200L), true)
            var holdAccepted = false
            var moveAccepted = false
            try {
                if (!dispatchAndAwait(service, hold) { holdAccepted = true }) return@withContext false
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                // A long press can change the UI. Revalidate both nodes and safety before moving.
                val afterHold = collect() ?: return@withContext false
                if (afterHold.first.packageName != screen.packageName || afterHold.first.windowId != screen.windowId ||
                    !ready() || !deviceUnlocked() || !dragTargetsValid(afterHold, source, destination)) return@withContext false
                val move = hold.continueStroke(android.graphics.Path().apply {
                    moveTo(x, y); lineTo(endX, endY)
                }, 0, 600, false)
                val completed = dispatchAndAwait(service, move) { moveAccepted = true }
                completed && policy.authorized() && ready() && deviceUnlocked()
            } finally {
                // Only continue the hold if the final (willContinue=false) move was never accepted.
                // Replaying the old hold after a move uses a stale stroke id/endpoint on OEMs.
                if (holdAccepted && !moveAccepted) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    phoneAction {
                        dispatchAndAwait(service, hold.continueStroke(android.graphics.Path().apply { moveTo(x, y) }, 0, 1, false))
                    }
                }
            }
            }
        }

    private fun dragTargetsValid(collected: Pair<PhoneScreen, List<AccessibilityNodeInfo>>,
        source: PhoneNode, destination: PhoneNode): Boolean {
        val (live, handles) = collected
        val from = live.nodes.singleOrNull { it.id == source.id } ?: return false
        val to = live.nodes.singleOrNull { it.id == destination.id } ?: return false
        if (!sameTarget(from, source) || !sameTarget(to, destination) ||
            from.bounds != source.bounds || to.bounds != destination.bounds ||
            !io.github.hakunm.deepseekharness.local.PhoneGesturePolicy.permitsDrag(policy, live, from, to)) return false
        return listOf(from, to).all { node -> handles.getOrNull(node.id)?.let {
            it.isVisibleToUser && it.isEnabled && it.packageName?.toString() == live.packageName && it.windowId == live.windowId
        } == true }
    }

    private suspend fun dispatchAndAwait(service: PhoneControlService,
        stroke: android.accessibilityservice.GestureDescription.StrokeDescription,
        onAccepted: () -> Unit = {}): Boolean {
        var pending: android.accessibilityservice.AccessibilityService.GestureResultCallback? = null
        return try {
            awaitPhoneGesture(stroke) { gesture, callback ->
                pending = callback
                service.dispatchChecked(gesture, callback).also { if (it) onAccepted() }
            }
        } finally { pending?.let(service::forgetGesture) }
    }

    override suspend fun gesture(screen: PhoneScreen, grant: io.github.hakunm.deepseekharness.local.PhoneCoordinateScope,
        gesture: io.github.hakunm.deepseekharness.local.PhoneCoordinateGesture): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            if (!ready() || !deviceUnlocked() || !visualEnabled() || screen.packageName == context.packageName ||
                collect()?.first != screen || !io.github.hakunm.deepseekharness.local.PhoneCoordinatePolicy.permits(policy, screen, grant)) return@withContext false
            val validated = io.github.hakunm.deepseekharness.local.PhoneCoordinatePolicy.validate(gesture, screen, grant,
                android.accessibilityservice.GestureDescription.getMaxGestureDuration(), android.accessibilityservice.GestureDescription.getMaxStrokeCount())
            val description = coordinateGestureDescription(validated)
            val service = PhoneControlService.getInstance() ?: return@withContext false
            var pending: android.accessibilityservice.AccessibilityService.GestureResultCallback? = null
            var completed = false
            try {
                kotlinx.coroutines.coroutineScope {
                    // Revoke or changed metadata settles the operation as unconfirmed.
                    val monitor = launchCoordinateMonitor(service, grant)
                    try {
                        completed = awaitPhoneGesture(description) { value, callback ->
                            pending = callback
                            deviceUnlocked() && collect()?.first == screen &&
                                io.github.hakunm.deepseekharness.local.PhoneCoordinatePolicy.permits(policy, screen, grant) &&
                                service.dispatchChecked(value, callback)
                        }
                    } finally { monitor.cancel() }
                }
                completed && deviceUnlocked() && visualEnabled() && ready() &&
                    observe()?.let { io.github.hakunm.deepseekharness.local.PhoneCoordinatePolicy.permits(policy, it, grant) } == true
            } finally {
                // No continued contact survives a completed gesture. Android has no cancel-only API.
                if (!completed) service.cancelPendingGestureCallbacks()
                pending?.let(service::forgetGesture)
            }
        }

    private fun kotlinx.coroutines.CoroutineScope.launchCoordinateMonitor(service: PhoneControlService,
        grant: io.github.hakunm.deepseekharness.local.PhoneCoordinateScope) = launch {
        while (true) {
            kotlinx.coroutines.delay(50)
            val live = observe()
            if (!ready() || !deviceUnlocked() || !visualEnabled() || live == null ||
                !io.github.hakunm.deepseekharness.local.PhoneCoordinatePolicy.permits(policy, live, grant)) {
                service.cancelPendingGestureCallbacks(); break
            }
        }
    }

    private fun sameTarget(current: PhoneNode, previous: PhoneNode): Boolean =
        PhonePermissionPolicy.stableTarget(current, previous)
    companion object {
        val STORE_PACKAGES = listOf("com.android.vending", "com.xiaomi.market", "com.huawei.appmarket",
            "com.heytap.market", "com.oppo.market", "com.bbk.appstore", "com.tencent.android.qqdownloader",
            "com.sec.android.app.samsungapps")
    }
}
