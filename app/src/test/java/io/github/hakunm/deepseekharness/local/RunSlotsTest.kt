package io.github.hakunm.deepseekharness.local

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RunSlotsTest {
    @Test fun fiveSharedSlotsRejectSixthAndDuplicateAndReleaseIndependently() {
        val slots = RunSlots()
        val ids = listOf("chat-a", "chat-b", "chat-c", "automation-d", "automation-e")
        ids.forEach { assertTrue(slots.acquire(it)) }
        assertFalse(slots.acquire("sixth"))
        assertFalse(slots.acquire("chat-a"))
        slots.release("chat-b")
        assertEquals(ids.toSet() - "chat-b", slots.ids())
        assertTrue(slots.acquire("sixth"))
        slots.release("chat-b")
        assertEquals(5, slots.ids().size)
    }
    @Test fun completedAndStoppedAndFailedHaveRealPersistedElapsedLabels() {
        val completed = LocalMessage("assistant", "done", elapsedMillis = 534_000, runStatus = "completed")
        val restored = Json.decodeFromString<LocalMessage>(Json.encodeToString(completed))
        assertEquals("已完成，用时8分54秒", runHeader(restored.runStatus, restored.elapsedMillis))
        assertEquals("已停止，用时8分54秒", runHeader("stopped", 534_000))
        assertEquals("失败，用时8分54秒", runHeader("failed", 534_000))
        assertEquals("已中断，用时8分54秒", runHeader("interrupted", 534_000))
        assertNull(runHeader(null, null))
    }
}
