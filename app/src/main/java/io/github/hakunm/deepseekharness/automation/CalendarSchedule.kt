package io.github.hakunm.deepseekharness.automation

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

val TaskSchedule.isCalendar: Boolean get() = this == TaskSchedule.Daily || this == TaskSchedule.Weekly

/** Strictly after now; missed dates are skipped. DST gaps shift forward; overlaps use the earlier offset. */
fun nextCalendarOccurrence(task: AutomationTask, afterMillis: Long): Long {
    require(task.schedule.isCalendar)
    val zone = ZoneId.of(task.timeZone)
    val time = LocalTime.parse(task.wallTime)
    val after = Instant.ofEpochMilli(afterMillis)
    val today = after.atZone(zone).toLocalDate()
    for (offset in 0L..7L) {
        val date = today.plusDays(offset)
        if (task.schedule == TaskSchedule.Weekly && date.dayOfWeek.value !in task.weekdays) continue
        val candidate = date.atTime(time).atZone(zone).toInstant()
        if (candidate > after) return candidate.toEpochMilli()
    }
    error("No selected weekday")
}
