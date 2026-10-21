package io.github.hakunm.deepseekharness.agent

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class PhoneOverlaySettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("phone_status_overlay", Context.MODE_PRIVATE)
    val edgeEnabled get() = prefs.getBoolean("edge_enabled", false)
    val lowMotion get() = prefs.getBoolean("low_motion", false)
    fun edgeEnable(value: Boolean) { prefs.edit().putBoolean("edge_enabled", value).apply() }
    fun lowMotion(value: Boolean) { prefs.edit().putBoolean("low_motion", value).apply() }
    val enabled get() = prefs.getBoolean("enabled", false)
    val opacity get() = PhoneOverlayValues.opacity(prefs.getFloat("opacity", 0.85f))
    fun enable(value: Boolean) { prefs.edit().putBoolean("enabled", value).apply() }
    fun opacity(value: Float) { prefs.edit().putFloat("opacity", PhoneOverlayValues.opacity(value)).apply() }
    fun listen(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)
    fun unlisten(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)
}

/** Owned exclusively by the accessibility service; no FGS, capture grant or alert permission. */
class PhoneStatusOverlay(private val service: PhoneControlService) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wm = service.getSystemService(WindowManager::class.java)
    private val settings = PhoneOverlaySettings(service)
    private val edge = PhoneEdgeOverlay(service)
    private val displayManager = service.getSystemService(android.hardware.display.DisplayManager::class.java)
    private val power = service.getSystemService(android.os.PowerManager::class.java)
    private var previewJob: Job? = null
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) { render() }
        override fun onDisplayChanged(id: Int) { render() }
    }
    private val animatorObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { render() }
    }
    private var view: TextView? = null
    private var layoutJob: Job? = null
    private var closed = false
    private var current: PhoneStatus? = null
    private var captureMasked = false
    fun maskForCapture(value: Boolean) { captureMasked = value; render() }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        scope.launch { render() }
    }
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    init {
        settings.listen(listener)
        displayManager.registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))
        service.contentResolver.registerContentObserver(android.provider.Settings.Global.getUriFor(android.provider.Settings.Global.ANIMATOR_DURATION_SCALE), false, animatorObserver)
        scope.launch {
            PhoneAgentStatus.store.state.collectLatest { state ->
                current = state
                render()
                if (state != null && (state.terminal || state.source == PhoneSource.PREVIEW)) {
                    delay(if (state.terminal) 10000 else 40000)
                    PhoneAgentStatus.store.hide(state.token)
                }
            }
        }
        scope.launch { PhoneAgentStatus.foreground.collectLatest { render() } }
    }
    private fun render() {
        if (closed) return
        val state = current.takeIf { !captureMasked && PhoneAgentStatus.visible(it, PhoneAgentStatus.foreground.value) }
        val displayOn = power.isInteractive && displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state == android.view.Display.STATE_ON
        val scale = android.provider.Settings.Global.getFloat(service.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        edge.render(state, settings.edgeEnabled && displayOn, settings.lowMotion, scale, settings.opacity)
        if (!displayOn || !settings.enabled || state == null) { remove(); return }
        val text = view ?: TextView(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isFocusable = false; isClickable = false; isLongClickable = false
            setTextColor(Color.WHITE); textSize = 13f; maxLines = 3
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setShadowLayer(dp(5).toFloat(), 0f, 0f, Color.argb(90, 100, 205, 255))
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply { setColor(Color.argb(170, 28, 32, 38)); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.argb(55, 180, 225, 245)) }
        }
        val localized = io.github.hakunm.deepseekharness.AppLocale.wrap(service)
        val terminal = state.terminal
        val phaseTitle = localized.resources.getStringArray(io.github.hakunm.deepseekharness.R.array.phone_overlay_phases)[state.phase.ordinal]
        val actionTitle = phoneOverlayActionTitle(localized, state.action)
        val title = if (state.source == PhoneSource.PREVIEW && !terminal)
            localized.getString(io.github.hakunm.deepseekharness.R.string.phone_overlay_preview_title, phaseTitle)
        else if (!terminal && state.phase == PhonePhase.EXECUTING) actionTitle else phaseTitle
        val summary = PhoneProgress.safe(state.progressSummary) ?: localized.getString(io.github.hakunm.deepseekharness.R.string.phone_overlay_progress_fallback)
        val content = title + "\n" + if (terminal)
            localized.getString(io.github.hakunm.deepseekharness.R.string.phone_overlay_return)
        else summary + " · " + localized.resources.getStringArray(io.github.hakunm.deepseekharness.R.array.phone_observation_modes)[state.observationMode.ordinal]
        if (text.text.toString() != content) text.text = content
        text.isClickable = terminal
        text.setOnClickListener(if (terminal) View.OnClickListener {
            PhoneConversationReturn.prepare(state)
            val intent = android.content.Intent(service, io.github.hakunm.deepseekharness.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(PhoneConversationReturn.EXTRA, state.token)
            runCatching { service.startActivity(intent) }
            PhoneAgentStatus.store.hide(state.token)
        } else null)
        val metrics = service.resources.displayMetrics
        var width = metrics.widthPixels
        var bottom = dp(32)
        var side = 0
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics
            width = bounds.bounds.width()
            val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            val stable = bounds.windowInsets.getInsetsIgnoringVisibility(types)
            val insets = (text.rootWindowInsets ?: bounds.windowInsets).getInsets(
                types or WindowInsets.Type.ime())
            bottom = maxOf(insets.bottom, stable.bottom)
            side = maxOf(insets.left, insets.right, stable.left, stable.right)
        } else {
            @Suppress("DEPRECATION")
            text.rootWindowInsets?.let { bottom = maxOf(bottom, it.systemWindowInsetBottom) }
        }
        val params = WindowManager.LayoutParams(
            minOf(dp(300), (width - 2 * (side + dp(12))).coerceAtLeast(1)),
            dp(84),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                (if (terminal) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = bottom + dp(12)
            alpha = settings.opacity
            this.title = "Phone agent status"
        }
        try {
            if (view == null) {
                wm.addView(text, params); view = text; text.requestApplyInsets()
                layoutJob = scope.launch { while (isActive && view != null) { delay(500); render() } }
            }
            else wm.updateViewLayout(text, params)
        } catch (failure: RuntimeException) {
            phoneWindowOperation { throw failure }
            remove()
        }
    }
    private fun remove() {
        layoutJob?.cancel(); layoutJob = null
        view?.let { phoneWindowOperation { wm.removeViewImmediate(it) } }
        view = null
    }
    /** Service-owned preview survives leaving Settings; generation fencing prevents task interference. */
    fun preview(): Boolean {
        val session = service.getSharedPreferences("pocket_local_mode", Context.MODE_PRIVATE).getString("current_session", null)
        val token = PhoneAgentStatus.store.preview(session) ?: return false
        previewJob?.cancel()
        val localized = io.github.hakunm.deepseekharness.AppLocale.wrap(service)
        val examples = localized.resources.getStringArray(io.github.hakunm.deepseekharness.R.array.phone_overlay_progress_examples)
        PhoneAgentStatus.store.update(token, PhonePhase.OBSERVING, PhoneStatusAction.OBSERVE, progressSummary = examples[0])
        previewJob = scope.launch {
            val phases = listOf(PhonePhase.THINKING, PhonePhase.EXECUTING, PhonePhase.AWAITING_APPROVAL,
                PhonePhase.SUCCESS, PhonePhase.FAILURE, PhonePhase.CANCELLED)
            for (phase in phases) {
                delay(if (phase == PhonePhase.THINKING) 2000 else 4000)
                if (PhoneAgentStatus.store.state.value?.token != token) return@launch
                // Terminal samples intentionally remain nonterminal until the full no-action showcase ends.
                val index = when (phase) {
                    PhonePhase.THINKING -> 1
                    PhonePhase.EXECUTING -> 2
                    PhonePhase.AWAITING_APPROVAL -> 3
                    else -> 4
                }
                PhoneAgentStatus.store.update(token, phase, if (phase == PhonePhase.EXECUTING) PhoneStatusAction.CLICK else PhoneStatusAction.NONE,
                    progressSummary = examples[index])
            }
            delay(3000)
            PhoneAgentStatus.store.finish(token, PhonePhase.SUCCESS)
        }
        return true
    }
    fun close() {
        closed = true
        previewJob?.cancel()
        displayManager.unregisterDisplayListener(displayListener)
        service.contentResolver.unregisterContentObserver(animatorObserver)
        edge.remove()
        settings.unlisten(listener)
        scope.cancel()
        remove()
        PhoneAgentStatus.store.clear()
    }
}
