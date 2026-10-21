package io.github.hakunm.deepseekharness.automation

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class AutomationScheduler(context: Context) {
    private val context = context.applicationContext
    private val manager = WorkManager.getInstance(context.applicationContext)
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun schedule(task: AutomationTask, replace: Boolean = true) {
        validateTask(task)
        if (replace) manager.cancelAllWorkByTag("automation-${task.id}")
        if (!task.enabled || task.schedule == TaskSchedule.Manual) { manager.cancelAllWorkByTag("automation-${task.id}"); return }
        if (task.schedule.isCalendar) {
            enqueueCalendar(task, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
            return
        }
        val data = workDataOf("task" to task.id, "revision" to task.revision)
        val delay = (task.firstRunAt - System.currentTimeMillis()).coerceAtLeast(0)
        if (task.schedule == TaskSchedule.Interval) {
            val request = PeriodicWorkRequestBuilder<AutomationWorker>(task.intervalMinutes, TimeUnit.MINUTES)
                .setInputData(data).setConstraints(constraints).setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .addTag("automation-${task.id}").build()
            manager.enqueueUniquePeriodicWork("automation-${task.id}",
                if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            val request = OneTimeWorkRequestBuilder<AutomationWorker>().setInputData(data).setConstraints(constraints)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS).addTag("automation-${task.id}").build()
            manager.enqueueUniqueWork("automation-${task.id}", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }
    }
    private fun enqueueCalendar(task: AutomationTask, policy: ExistingWorkPolicy): androidx.work.Operation {
        val now = System.currentTimeMillis()
        val next = nextCalendarOccurrence(task, now)
        val request = OneTimeWorkRequestBuilder<AutomationWorker>()
            .setInputData(workDataOf("task" to task.id, "revision" to task.revision, "scheduledAt" to next))
            .setConstraints(constraints).setInitialDelay(next - now, TimeUnit.MILLISECONDS)
            .addTag("automation-${task.id}").build()
        // Revision isolates an old completing worker from a newly edited task's chain.
        return manager.enqueueUniqueWork("automation-calendar-${task.id}-${task.revision}", policy, request)
    }
    suspend fun scheduleNext(task: AutomationTask) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            enqueueCalendar(task, ExistingWorkPolicy.APPEND_OR_REPLACE).result.get()
        }
    }
    fun runNow(task: AutomationTask) {
        validateTask(task)
        manager.enqueueUniqueWork("automation-manual-${task.id}", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutomationWorker>().setConstraints(constraints)
                .setInputData(workDataOf("task" to task.id, "revision" to task.revision, "manual" to true))
                .addTag("automation-${task.id}").build())
    }
    fun cancel(id: String) {
        io.github.hakunm.deepseekharness.local.ChatExecution.cancel("automation-$id")
        manager.cancelAllWorkByTag("automation-$id")
    }
    fun reconcile(tasks: List<AutomationTask>) {
        tasks.filter { it.enabled && it.providerId.isNotBlank() }.forEach { schedule(it, replace = false) }
    }
}
