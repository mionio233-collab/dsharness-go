package io.github.hakunm.deepseekharness.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/** A single trusted, non-input window avoids seams and four separate surfaces at rounded corners.
 * Layered translucent strokes confine the breathing glow to the edge; the center stays transparent.
 * NOT_TOUCHABLE + ACCESSIBILITY_OVERLAY is the trusted-window passthrough exception on Android 12+.
 */
internal class PhoneEdgeOverlay(context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val context = context
    private var view: EdgeView? = null
    fun render(state: PhoneStatus?, enabled: Boolean, lowMotion: Boolean, scale: Float, opacity: Float) {
        if (!enabled || state == null) { remove(); return }
        val edge = view ?: EdgeView(context)
        edge.configure(state.phase, lowMotion, scale, opacity)
        if (view != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Phone agent edge"
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        try { wm.addView(edge, params); view = edge; edge.requestApplyInsets() }
        catch (failure: RuntimeException) { phoneWindowOperation { throw failure }; edge.stop(); remove() }
    }
    fun remove() {
        view?.let { it.stop(); phoneWindowOperation { wm.removeViewImmediate(it) } }
        view = null
    }
}

private class EdgeView(context: Context) : View(context) {
    private val outline = Path()
    private val measure = PathMeasure()
    private val segment = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var phase = PhonePhase.THINKING
    private var style = PhoneEdgeStyle.forPhase(phase)
    private var lowMotion = false
    private var scale = 1f
    private var opacity = 0.85f
    private var epoch = SystemClock.uptimeMillis()
    private var running = false
    private var corner = 0f
    private val frame = object : Runnable {
        override fun run() {
            if (!canAnimate()) { stop(); return }
            invalidate()
            postDelayed(this, PhoneEdgePolicy.FRAME_MS)
        }
    }
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isFocusable = false; isClickable = false; isLongClickable = false
        setWillNotDraw(false)
    }
    fun configure(next: PhonePhase, low: Boolean, animatorScale: Float, alpha: Float) {
        if (phase != next) { epoch = SystemClock.uptimeMillis(); phase = next; style = PhoneEdgeStyle.forPhase(next) }
        lowMotion = low; scale = animatorScale; opacity = alpha
        syncFrames(); invalidate()
    }
    private fun canAnimate() =
        PhoneEdgePolicy.animate(isAttachedToWindow, isShown && windowVisibility == VISIBLE,
        display?.state == android.view.Display.STATE_ON, lowMotion, scale, phase)
    private fun syncFrames() {
        if (canAnimate()) { if (!running) { running = true; postDelayed(frame, PhoneEdgePolicy.FRAME_MS) } }
        else stop()
    }
    fun stop() { running = false; removeCallbacks(frame) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncFrames() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); syncFrames() }
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= 31) {
            corner = listOf(0, 1, 2, 3).mapNotNull { insets.getRoundedCorner(it)?.radius?.toFloat() }.maxOrNull() ?: 0f
        }
        geometry(width, height)
        return super.onApplyWindowInsets(insets)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { geometry(w, h) }
    private fun geometry(w: Int, h: Int) {
        val density = resources.displayMetrics.density
        val pad = 3f * density
        val radius = maxOf(22f * density, corner - pad).coerceAtMost(minOf(w, h) / 2f)
        outline.reset()
        if (w > pad * 2 && h > pad * 2) outline.addRoundRect(RectF(pad, pad, w - pad, h - pad), radius, radius, Path.Direction.CW)
        measure.setPath(outline, true)
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val length = measure.length
        if (length <= 0f) return
        val density = resources.displayMetrics.density
        paint.shader = android.graphics.SweepGradient(width / 2f, height / 2f,
            style.colors + style.colors[0], null).apply {
            val matrix = android.graphics.Matrix()
            val elapsed = if (canAnimate()) SystemClock.uptimeMillis() - epoch else 0L
            matrix.setRotate((elapsed.toFloat() / (style.lapMs * scale.coerceAtLeast(0.1f)) * 360f) % 360f, width / 2f, height / 2f)
            setLocalMatrix(matrix)
        }
        val elapsed = if (canAnimate()) SystemClock.uptimeMillis() - epoch else 0L
        val breath = PhoneEdgeStyle.breathing(elapsed)
        // Wide faint layers create a soft falloff without filling the screen center.
        for (layer in 12 downTo 1) {
            paint.strokeWidth = (2f + layer * 2.4f) * density
            paint.alpha = (opacity * breath * (4 + (12 - layer) * 1.1f)).toInt()
            canvas.drawPath(outline, paint)
        }
        paint.strokeWidth = 1.6f * density
        paint.alpha = (opacity * breath * 145).toInt()
        canvas.drawPath(outline, paint)
        paint.shader = null
        val position = FloatArray(2)
        val travel = elapsed.toFloat() / (style.lapMs * scale.coerceAtLeast(0.1f))
        for (i in 0 until 8) {
            measure.getPosTan(((travel + i / 8f) % 1f) * length, position, null)
            paint.style = Paint.Style.FILL
            paint.color = style.colors[i % style.colors.size]
            paint.alpha = (opacity * breath * 120).toInt()
            canvas.drawCircle(position[0], position[1], density * (if (i % 3 == 0) 1.6f else 0.9f), paint)
        }
        paint.style = Paint.Style.STROKE
    }
}
