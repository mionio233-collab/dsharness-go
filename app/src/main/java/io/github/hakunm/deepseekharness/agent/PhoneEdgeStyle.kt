package io.github.hakunm.deepseekharness.agent

/** Pure policies shared by the renderer and JVM tests. Colors contain no user data. */
class PhoneEdgeStyle(val colors: IntArray, val lapMs: Long) {
    companion object {
        fun forPhase(phase: PhonePhase): PhoneEdgeStyle = PhoneEdgeStyle(
            intArrayOf(0xff58daef.toInt(), 0xff8d94ff.toInt(), 0xffff91c1.toInt()),
            if (phase == PhonePhase.EXECUTING) 8500 else 12000,
        )
        fun breathing(elapsedMs: Long): Float = 0.68f + 0.22f * kotlin.math.sin(elapsedMs * 2.0 * Math.PI / 5200).toFloat()
    }
}
object PhoneEdgePolicy {
    const val FRAME_MS = 34L
    fun animate(attached: Boolean, visible: Boolean, displayOn: Boolean, lowMotion: Boolean, animatorScale: Float, phase: PhonePhase) =
        attached && visible && displayOn && !lowMotion && animatorScale > 0f && phase != PhonePhase.CANCELLED
    /** Normalize a moving segment at the seam, retaining length across the last corner. */
    fun segments(start: Float, span: Float, length: Float): List<Pair<Float, Float>> {
        if (!length.isFinite() || length <= 0 || !start.isFinite() || !span.isFinite() || span <= 0) return emptyList()
        val s = ((start % length) + length) % length
        val n = span.coerceAtMost(length)
        return if (s + n <= length) listOf(s to s + n)
        else listOf(s to length, 0f to s + n - length)
    }
}
