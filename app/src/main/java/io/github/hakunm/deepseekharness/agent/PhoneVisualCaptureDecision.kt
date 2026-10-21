package io.github.hakunm.deepseekharness.agent

import io.github.hakunm.deepseekharness.local.PhonePermissionPolicy
import io.github.hakunm.deepseekharness.local.PhoneScreen

/** Actual execution decision, also used at the tool boundary. No Android objects or cached grants. */
internal fun phoneVisualCaptureRefusal(policy: PhonePermissionPolicy, screen: PhoneScreen?,
    serviceReady: Boolean, osCaptureActive: Boolean, unlocked: Boolean): String? {
    if (!policy.visualAuthorized(serviceReady, osCaptureActive, unlocked)) return when {
        !policy.authorized() -> "phone_not_authorized"
        !serviceReady -> "accessibility_disconnected"
        !unlocked -> "device_locked_or_noninteractive"
        else -> "android_media_projection_consent_required"
    }
    return policy.captureRefusal(screen)
}

/**
 * Fail closed on alpha-zero pixels (no frame data at all). A frame where every sampled pixel
 * is alpha-zero means the VirtualDisplay produced no content — the surface was never composed.
 * Any other uniform color, including a fully black drawing canvas, is accepted as valid content.
 * FLAG_SECURE windows are rejected upstream via visualSafetyReason, not by pixel heuristics.
 * Colorful system bars outside the viewport do not qualify the viewport.
 */
internal fun phoneVisualViewportHasContent(bounds: List<Int>, pixel: (Int, Int) -> Int): Boolean {
    for (y in bounds[1] until bounds[3] step maxOf(1, (bounds[3] - bounds[1]) / 24))
        for (x in bounds[0] until bounds[2] step maxOf(1, (bounds[2] - bounds[0]) / 24)) {
            if (pixel(x, y) != 0) return true
        }
    // All sampled viewport pixels were alpha-zero: no frame data was delivered.
    return false
}
