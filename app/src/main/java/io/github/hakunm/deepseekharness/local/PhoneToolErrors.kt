package io.github.hakunm.deepseekharness.local

import kotlinx.serialization.SerializationException

/** Only exact known validation messages may reach the model. Backend failures can contain secrets. */
object PhoneToolErrors {
    private val reasons = setOf(
        "canvas_window_mismatch", "unknown_coordinate_space", "unknown_gesture_mode", "stroke_count_limit",
        "point_count_limit", "single_stroke_required", "gesture_duration_limit", "invalid_mode_points",
        "tap_duration_limit", "nonfinite_coordinate", "normalized_out_of_screen", "outside_safe_viewport",
        "path_overlaps_protected_control", "phone_or_visual_unavailable", "phone_action_limit",
        "arguments_too_large", "expected_object", "expected_finite_number", "expected_string",
        "expected_strokes", "expected_stroke", "expected_points", "expected_point", "observe_first",
        "stale_snapshot", "capture_canvas_first", "stale_visual_evidence", "viewport_binding_mismatch",
        "canvas_revoked_or_unsafe", "snapshot_package_mismatch", "no_active_window", "unknown_node",
        "unknown_destination_node", "unavailable_target", "unsupported_node_action", "manual_only_package",
        "app_launch_failed", "destinationNodeId_required_only_for_drag"
    )
    private val messages = mapOf(
        "observe_first: no snapshot" to "observe_first",
        "stale_snapshot: observe again" to "stale_snapshot",
        "stale_package: observe again" to "stale_package",
        "stale_window: observe again" to "stale_window",
        "stale_target: observe again" to "stale_target",
        "stale_parent" to "stale_parent", "stale_parent: observe again" to "stale_parent",
        "phone_not_authorized: enable phone control with Full/AutoReview and an OpenAI-compatible provider" to "phone_not_authorized",
        "accessibility_disconnected: enable Phone Control in Android Accessibility settings" to "accessibility_disconnected",
        "payment_or_unauthorized_target: manual action required" to "payment_or_unauthorized_target",
        "gesture_unconfirmed: Android denied or cancelled; observe again" to "gesture_unconfirmed",
        "accessibility_action_failed: Android denied, cancelled, unsupported or stale target; observe again or complete manually" to "accessibility_action_failed",
        "app_not_launchable: use availableApps" to "app_not_launchable",
        "Expected empty object" to "invalid_arguments", "Expected object" to "invalid_arguments",
        "Expected snapshotId only" to "invalid_arguments", "Expected string snapshotId" to "invalid_arguments",
        "Arguments too large" to "arguments_too_large"
    )
    fun safeResult(error: Throwable): String {
        val reason = if (error is SerializationException) "invalid_arguments"
        else if (error is IllegalArgumentException || error is IllegalStateException)
            messages[error.message] ?: error.message?.takeIf { it in reasons } ?: "phone_operation_unconfirmed"
        else "phone_operation_unconfirmed"
        return """{"status":"unconfirmed","reason":"$reason","message":"Observe again; if authorization or Android consent is missing, use the existing settings or OS consent flow. Never claim success; complete manually if refused."}"""
    }
}
