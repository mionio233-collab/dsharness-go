package io.github.hakunm.deepseekharness.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only the current service owner may publish capture readiness or invalidate its grant. */
class CaptureSessionState {
    data class Snapshot(
        val active: Boolean = false,
        val starting: Boolean = false,
        val visible: Boolean = true,
        val mappingReady: Boolean = true,
        val reason: String = "needs_authorization",
    ) {
        val ready get() = active && visible && mappingReady
    }
    private var owner: Any? = null
    private val mutable = MutableStateFlow(Snapshot())
    val state = mutable.asStateFlow()
    private val enabledMutable = MutableStateFlow(false)
    val enabled = enabledMutable.asStateFlow()

    @Synchronized fun claim(value: Any) {
        owner = value
        publish(Snapshot(starting = true, reason = "starting"))
    }
    @Synchronized fun owns(value: Any) = owner === value
    @Synchronized fun started(value: Any) {
        if (owner === value) publish(mutable.value.copy(active = true, starting = false, reason = "active"))
    }
    @Synchronized fun content(value: Any, visible: Boolean? = null, mappingReady: Boolean? = null) {
        if (owner !== value) return
        val next = mutable.value.copy(visible = visible ?: mutable.value.visible,
            mappingReady = mappingReady ?: mutable.value.mappingReady)
        publish(next.copy(reason = when {
            !next.mappingReady -> "full_display_required"
            !next.visible -> "content_hidden"
            next.active -> "active"
            else -> next.reason
        }))
    }
    @Synchronized fun stopped(value: Any, reason: String) {
        if (owner !== value) return
        owner = null
        publish(Snapshot(reason = reason))
    }
    @Synchronized fun failedStart(reason: String) {
        if (owner == null) publish(Snapshot(reason = reason))
    }
    private fun publish(value: Snapshot) {
        mutable.value = value
        enabledMutable.value = value.active
    }
}

internal fun captureResizeInvalidates(mappingReady: Boolean, nextMappingReady: Boolean,
    readerWidth: Int, readerHeight: Int, width: Int, height: Int): Boolean =
    mappingReady != nextMappingReady || (nextMappingReady && (readerWidth != width || readerHeight != height))

