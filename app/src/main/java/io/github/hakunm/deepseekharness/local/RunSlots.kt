package io.github.hakunm.deepseekharness.local

/** Atomic admission, shared by manual and scheduled work. A duplicate session cannot run twice. */
class RunSlots(private val limit: Int = 5) {
    private val active = mutableSetOf<String>()
    @Synchronized fun acquire(id: String): Boolean = active.size < limit && active.add(id)
    @Synchronized fun release(id: String) { active.remove(id) }
    @Synchronized fun contains(id: String): Boolean = id in active
    @Synchronized fun ids(): Set<String> = active.toSet()
}

fun runHeader(status: String?, elapsedMillis: Long?, english: Boolean = false): String? {
    if (status == null) return null
    val seconds = (elapsedMillis ?: 0).coerceAtLeast(0) / 1000
    val duration = if (english) "${seconds / 60}m ${seconds % 60}s" else "${seconds / 60}分${seconds % 60}秒"
    return if (english) "${when (status) { "completed" -> "Completed"; "stopped" -> "Stopped"; "failed" -> "Failed"; "interrupted" -> "Interrupted"; else -> "Running" }} · $duration"
    else "${when (status) { "completed" -> "已完成"; "stopped" -> "已停止"; "failed" -> "失败"; "interrupted" -> "已中断"; else -> "执行中" }}，用时$duration"
}
