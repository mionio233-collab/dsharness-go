package io.github.hakunm.deepseekharness.local

/** Gestures derive points from current semantic nodes, never from model supplied pixels. */
object PhoneGesturePolicy {
    private val protected = Regex("systemui|permissioncontroller|packageinstaller|security|keyguard", RegexOption.IGNORE_CASE)

    fun safeWindow(screen: PhoneScreen): Boolean = screen.windowId >= 0 &&
        !protected.containsMatchIn(screen.packageName) && !screen.truncated &&
        screen.visualSafetyReason == null && !PhonePermissionPolicy.screenCaptureConsent(screen) &&
        !PhonePermissionPolicy.paymentContext(screen)

    fun validBounds(screen: PhoneScreen, node: PhoneNode): Boolean = node.bounds.size == 4 &&
        node.bounds[0] >= 0 && node.bounds[1] >= 0 &&
        node.bounds[2] <= screen.width && node.bounds[3] <= screen.height &&
        node.bounds[2] > node.bounds[0] && node.bounds[3] > node.bounds[1]

    fun permitsLongPress(policy: PhonePermissionPolicy, screen: PhoneScreen, node: PhoneNode): Boolean =
        safeWindow(screen) && screen.nodes.singleOrNull { it.id == node.id } == node &&
        node.longClickable && !node.sensitive && validBounds(screen, node) &&
        policy.permits(screen, node, "long_press")

    fun permitsDrag(policy: PhonePermissionPolicy, screen: PhoneScreen, source: PhoneNode, destination: PhoneNode): Boolean =
        safeWindow(screen) && VisualFallbackPolicy.hardRefusal(screen) == null &&
            (VisualFallbackPolicy.diagnostic(screen) == null || PhoneCoordinatePolicy.scope(screen) != null) &&
        screen.nodes.singleOrNull { it.id == source.id } == source &&
        screen.nodes.singleOrNull { it.id == destination.id } == destination &&
        source.id != destination.id && source.longClickable &&
        source.enabled && destination.enabled && !source.sensitive && !destination.sensitive &&
        validBounds(screen, source) && validBounds(screen, destination) &&
        policy.permits(screen, source, "drag") && policy.permits(screen, destination, "drag") &&
        center(source) != center(destination)

    fun center(node: PhoneNode): Pair<Float, Float> =
        (node.bounds[0] + (node.bounds[2] - node.bounds[0]) / 2f) to
            (node.bounds[1] + (node.bounds[3] - node.bounds[1]) / 2f)
}
