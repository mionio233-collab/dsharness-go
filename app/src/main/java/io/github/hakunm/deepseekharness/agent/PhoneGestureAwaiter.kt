package io.github.hakunm.deepseekharness.agent

import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** minSdk 26: a non-null stroke is required. Acceptance alone never means completion. */
internal suspend fun awaitPhoneGesture(stroke: GestureDescription.StrokeDescription,
    dispatch: (GestureDescription, GestureResultCallback) -> Boolean): Boolean =
    awaitPhoneGesture(GestureDescription.Builder().addStroke(stroke).build(), dispatch)

internal suspend fun awaitPhoneGesture(gesture: GestureDescription,
    dispatch: (GestureDescription, GestureResultCallback) -> Boolean): Boolean =
    withTimeoutOrNull((0 until gesture.strokeCount).maxOf { gesture.getStroke(it).let { s -> s.startTime + s.duration } } + 2000L) {
        suspendCancellableCoroutine { continuation ->
            val settled = AtomicBoolean(false)
            continuation.invokeOnCancellation { settled.set(true) }
            fun finish(success: Boolean) {
                if (settled.compareAndSet(false, true)) continuation.resumeWith(Result.success(success))
            }
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = finish(true)
                override fun onCancelled(gestureDescription: GestureDescription?) = finish(false)
            }
            try {
                if (!dispatch(gesture, callback)) finish(false)
            } catch (failure: RuntimeException) {
                if (!phonePlatformRejected(failure)) throw failure
                finish(false)
            }
        }
    } ?: false
