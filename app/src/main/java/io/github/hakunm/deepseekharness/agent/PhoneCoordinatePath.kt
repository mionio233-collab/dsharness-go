package io.github.hakunm.deepseekharness.agent

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import io.github.hakunm.deepseekharness.local.PhoneCoordinateGesture

/** One contour per stroke: every segment keeps the same pointer down. */
internal fun coordinateGestureDescription(gesture: PhoneCoordinateGesture): GestureDescription =
    GestureDescription.Builder().apply {
        gesture.strokes.forEach { stroke ->
            val path = Path().apply {
                moveTo(stroke.points.first().x.toFloat(), stroke.points.first().y.toFloat())
                stroke.points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
            }
            addStroke(GestureDescription.StrokeDescription(path, stroke.startMs, stroke.durationMs, false))
        }
    }.build()
