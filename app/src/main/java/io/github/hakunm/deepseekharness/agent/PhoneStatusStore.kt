package io.github.hakunm.deepseekharness.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Public phases and sanitized task-facing progress; never private model reasoning. */
enum class PhonePhase(val label: String) {
    OBSERVING("Observing"), THINKING("Thinking"), EXECUTING("Executing"),
    AWAITING_APPROVAL("Awaiting approval"), SUCCESS("Success"), FAILURE("Failure"), CANCELLED("Cancelled")
}
enum class PhoneObservationMode { ACCESSIBILITY, VISUAL, HYBRID }
enum class PhoneSource(val label: String) { LOCAL("Local agent"), PREVIEW("Preview · no actions") }
enum class PhoneStatusAction(val label: String) {
    NONE("Task"), OBSERVE("Read screen"), OPEN_APP("Open app"), CLICK("Tap"), LONG_PRESS("Long press"), DRAG("Drag"), SET_TEXT("Enter text"),
    SUBMIT_TEXT("Submit"), SCROLL_FORWARD("Scroll forward"), SCROLL_BACKWARD("Scroll backward"), BACK("Back"), HOME("Home"),
    SWIPE("Swipe"), PATH("Draw"), TAP("Tap");
    companion object { fun fromTool(value: String) = entries.firstOrNull { it.name.lowercase() == value } ?: NONE }
}
data class PhoneStatus(val token: Long, val source: PhoneSource, val phase: PhonePhase,
    val action: PhoneStatusAction = PhoneStatusAction.NONE, val packageName: String? = null,
    val terminal: Boolean = false, val sessionId: String? = null, val progressSummary: String? = null,
    val observationMode: PhoneObservationMode = PhoneObservationMode.ACCESSIBILITY) {
    fun presentation(): String = "${phase.label} · ${action.label}\n${progressSummary ?: source.label} · ${packageName ?: "Phone"}"
}

/** Generation fencing makes late completion/cancellation and old hide timers harmless. */
class PhoneStatusStore {
    private val mutable = MutableStateFlow<PhoneStatus?>(null)
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var publicSummary: String? = null
    @Synchronized fun publicProgress(token: Long?, summary: String?) {
        val old = mutable.value ?: return
        if (token != old.token || old.terminal) return
        publicSummary = PhoneProgress.safe(summary)
        mutable.value = old.copy(progressSummary = publicSummary)
    }
    @Synchronized fun begin(source: PhoneSource = PhoneSource.LOCAL, sessionId: String? = null, progressSummary: String? = null): Long {
        val token = ++generation
        publicSummary = null
        mutable.value = PhoneStatus(token, source, PhonePhase.THINKING, sessionId = sessionId, progressSummary = PhoneProgress.safe(progressSummary))
        return token
    }
    @Synchronized fun preview(sessionId: String? = null): Long? {
        if (mutable.value?.let { !it.terminal && it.source != PhoneSource.PREVIEW } == true) return null
        val token = begin(PhoneSource.PREVIEW, sessionId)
        update(token, PhonePhase.OBSERVING, PhoneStatusAction.OBSERVE, "io.github.hakunm.deepseekharness")
        return token
    }
    @Synchronized fun update(token: Long?, phase: PhonePhase, action: PhoneStatusAction? = null, packageName: String? = null, progressSummary: String? = null) {
        val old = mutable.value ?: return
        if (token != old.token || old.terminal) return
        val safePackage = packageName?.takeIf { it.length <= 120 && it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) }
        mutable.value = old.copy(phase = phase, action = action ?: old.action, packageName = safePackage ?: old.packageName,
            progressSummary = publicSummary ?: if (progressSummary == null) old.progressSummary else PhoneProgress.safe(progressSummary))
    }
    @Synchronized fun observationMode(token: Long?, mode: PhoneObservationMode) {
        val old = mutable.value ?: return
        if (token == old.token && !old.terminal) mutable.value = old.copy(observationMode = mode)
    }
    @Synchronized fun finish(token: Long?, phase: PhonePhase) {
        require(phase in setOf(PhonePhase.SUCCESS, PhonePhase.FAILURE, PhonePhase.CANCELLED))
        val old = mutable.value ?: return
        if (old.token != token || old.terminal) return
        mutable.value = old.copy(phase = phase, terminal = true)
    }
    @Synchronized fun hide(token: Long?) { if (mutable.value?.token == token) { generation++; mutable.value = null } }
    @Synchronized fun clear() { generation++; mutable.value = null }
}
object PhoneAgentStatus {
    val store = PhoneStatusStore()
    val foreground = MutableStateFlow(false)
    fun visible(status: PhoneStatus?, appForeground: Boolean): Boolean =
        status != null && (!appForeground || status.source == PhoneSource.PREVIEW)
}

object PhoneConversationReturn {
    const val EXTRA = "phone_result_token"
    val requested = MutableStateFlow<String?>(null)
    private var pending: Pair<Long, String?>? = null
    @Synchronized fun prepare(status: PhoneStatus) { pending = status.token to status.sessionId }
    @Synchronized fun consume(token: Long): Boolean {
        val value = pending?.takeIf { it.first == token } ?: return false
        pending = null
        requested.value = value.second
        return true
    }
}

object PhoneOverlayValues {
    fun opacity(value: Float): Float = if (value.isFinite()) value.coerceIn(0.35f, 1f) else 0.85f
}
