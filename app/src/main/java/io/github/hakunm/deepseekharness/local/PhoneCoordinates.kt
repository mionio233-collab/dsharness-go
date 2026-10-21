package io.github.hakunm.deepseekharness.local

import kotlinx.serialization.Serializable

/** Geometry derived from a live observation. This is evidence, never an authorization grant. */
@Serializable
data class PhoneCoordinateScope(val packageName: String, val windowId: Int, val width: Int, val height: Int,
    val bounds: List<Int>, val excludedBounds: List<List<Int>>, val connectionEpoch: Long) {
    fun matches(screen: PhoneScreen) = this == PhoneCoordinatePolicy.scope(screen)
}

@Serializable data class PhonePoint(val x: Double, val y: Double)
@Serializable data class PhoneStroke(val points: List<PhonePoint>, val durationMs: Long, val startMs: Long = 0)
@Serializable data class PhoneCoordinateGesture(val mode: String, val coordinateSpace: String,
    val strokes: List<PhoneStroke>)

object PhoneCoordinatePolicy {
    const val MAX_POINTS = 512
    const val MAX_STROKES = 8
    const val MAX_DURATION = 10_000L
    // Accessibility uses finite integer edges. Negative positions are valid screen-space geometry;
    // missing edges and non-positive dimensions are malformed, even when entirely offscreen.
    private fun positiveRect(b: List<Int>) = b.size == 4 && b[0] < b[2] && b[1] < b[3]
    private fun validRect(b: List<Int>, screen: PhoneScreen) = positiveRect(b) && b[0] >= 0 && b[1] >= 0 &&
        b[2] <= screen.width && b[3] <= screen.height
    /** Reasons are fixed identifiers; never include node labels or window contents. */
    fun scopeRefusal(screen: PhoneScreen): String? = when {
        !screen.safeWindowMetadataComplete -> "incomplete_window_metadata"
        VisualFallbackPolicy.hardRefusal(screen) != null -> "protected_or_unsafe_window"
        !validRect(screen.viewportBounds, screen) -> "invalid_viewport_bounds"
        screen.protectedBounds.any { !positiveRect(it) } -> "invalid_protected_bounds"
        screen.nodes.any { node ->
            val b = node.bounds
            b.size != 4 || b[0] > b[2] || b[1] > b[3] ||
                (blockedControl(node) && (b[0] == b[2] || b[1] == b[3]))
        } -> "invalid_node_bounds"
        else -> null
    }
    private fun clipped(b: List<Int>, viewport: List<Int>): List<Int>? {
        val rect = listOf(maxOf(b[0], viewport[0]), maxOf(b[1], viewport[1]),
            minOf(b[2], viewport[2]), minOf(b[3], viewport[3]))
        return rect.takeIf { it[0] < it[2] && it[1] < it[3] }
    }
    fun scope(screen: PhoneScreen): PhoneCoordinateScope? {
        if (scopeRefusal(screen) != null) return null
        val display = listOf(0, 0, screen.width, screen.height)
        val excluded = (screen.protectedBounds + screen.nodes.filter(::blockedControl).map { it.bounds })
            .mapNotNull { clipped(it, display) }
            .mapNotNull { clipped(it, screen.viewportBounds) }.distinct()
        return PhoneCoordinateScope(screen.packageName, screen.windowId, screen.width, screen.height,
            screen.viewportBounds.toList(), excluded.map { it.toList() }, screen.connectionEpoch)
    }
    fun blockedControl(node: PhoneNode): Boolean {
        val drawingCanvas = node.label.isBlank() && Regex("canvas|drawing|paintview", RegexOption.IGNORE_CASE)
            .containsMatchIn(node.className + " " + node.resourceId)
        return node.editable || !node.enabled || node.sensitive || node.scrollable || PhonePermissionPolicy.paymentTarget(node) ||
            ((node.clickable || node.longClickable) && !drawingCanvas)
    }
    // Slab intersection includes boundaries conservatively and checks the whole dispatched segment.
    private fun intersects(a: PhonePoint, b: PhonePoint, rect: List<Int>): Boolean {
        var low = 0.0; var high = 1.0
        for ((start, end, min, max) in listOf(listOf(a.x, b.x, rect[0].toDouble(), rect[2].toDouble()),
            listOf(a.y, b.y, rect[1].toDouble(), rect[3].toDouble()))) {
            val delta = end - start
            if (delta == 0.0) { if (start < min || start > max) return false }
            else {
                val first = (min - start) / delta; val last = (max - start) / delta
                low = maxOf(low, minOf(first, last)); high = minOf(high, maxOf(first, last))
                if (low > high) return false
            }
        }
        return true
    }
    fun validate(gesture: PhoneCoordinateGesture, screen: PhoneScreen, scope: PhoneCoordinateScope,
        apiDuration: Long = MAX_DURATION, apiStrokes: Int = MAX_STROKES): PhoneCoordinateGesture {
        require(scope.matches(screen)) { "canvas_window_mismatch" }
        require(gesture.coordinateSpace in setOf("screen_pixels", "screen_normalized")) { "unknown_coordinate_space" }
        require(gesture.mode in setOf("tap", "swipe", "long_press", "path")) { "unknown_gesture_mode" }
        require(gesture.strokes.size in 1..minOf(MAX_STROKES, apiStrokes)) { "stroke_count_limit" }
        require(gesture.strokes.sumOf { it.points.size } <= MAX_POINTS) { "point_count_limit" }
        if (gesture.mode != "path") require(gesture.strokes.size == 1) { "single_stroke_required" }
        val converted = gesture.strokes.map { stroke ->
            val minDuration = if (gesture.mode == "long_press") 700L else 40L
            require(stroke.durationMs in minDuration..minOf(MAX_DURATION, apiDuration) && stroke.startMs >= 0 &&
                stroke.startMs <= minOf(MAX_DURATION, apiDuration) - stroke.durationMs) { "gesture_duration_limit" }
            require(stroke.points.size in 1..MAX_POINTS) { "point_count_limit" }
            require(when (gesture.mode) { "tap", "long_press" -> stroke.points.size == 1
                "swipe" -> stroke.points.size == 2; else -> stroke.points.size >= 2 }) { "invalid_mode_points" }
            if (gesture.mode == "tap") require(stroke.durationMs <= 250) { "tap_duration_limit" }
            val points = stroke.points.map { point ->
                require(point.x.isFinite() && point.y.isFinite()) { "nonfinite_coordinate" }
                if (gesture.coordinateSpace == "screen_normalized") require(point.x >= 0 && point.x < 1 && point.y >= 0 && point.y < 1) { "normalized_out_of_screen" }
                val p = if (gesture.coordinateSpace == "screen_normalized") PhonePoint(point.x * screen.width, point.y * screen.height) else point
                val dispatched = PhonePoint(p.x.toFloat().toDouble(), p.y.toFloat().toDouble())
                require(dispatched.x >= scope.bounds[0] && dispatched.y >= scope.bounds[1] &&
                    dispatched.x < scope.bounds[2] && dispatched.y < scope.bounds[3]) { "outside_safe_viewport" }
                dispatched
            }
            val segments = if (points.size == 1) listOf(points.single() to points.single()) else points.zipWithNext()
            require(segments.none { (a, b) -> scope.excludedBounds.any { intersects(a, b, it) } }) { "path_overlaps_protected_control" }
            stroke.copy(points = points)
        }
        return gesture.copy(coordinateSpace = "screen_pixels", strokes = converted)
    }
    fun permits(policy: PhonePermissionPolicy, screen: PhoneScreen, scope: PhoneCoordinateScope): Boolean =
        policy.authorized() && scope.matches(screen)
}
