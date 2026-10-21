package io.github.hakunm.deepseekharness.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.hakunm.deepseekharness.MainActivity
import io.github.hakunm.deepseekharness.local.*
import io.github.hakunm.deepseekharness.ui.LocalPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Serializes background runs to prevent two scheduled tasks mutating the same workspace concurrently. */
class AutomationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString("task") ?: return Result.failure()
        return try {
            if (ChatExecution.submit(applicationContext, "automation-$taskId") { executeWork() }) Result.success()
            else Result.retry() // No claim or side effects have occurred; wait for a shared slot.
        } catch (failure: Exception) {
            val store = AutomationStore.get(applicationContext)
            val revision = inputData.getString("revision") ?: return Result.failure()
            store.claim(taskId, revision, inputData.getBoolean("manual", false))?.let { (_, run) ->
                store.finish(run.id, RunStatus.NeedsAttention, "Android denied background service start. Open the app and run this task again.")
            }
            Result.success()
        }
    }
    private suspend fun executeWork(): Result = kotlinx.coroutines.coroutineScope {
        val store = AutomationStore.get(applicationContext)
        val taskId = inputData.getString("task") ?: return@coroutineScope Result.failure()
        val revision = inputData.getString("revision") ?: return@coroutineScope Result.failure()
        val manual = inputData.getBoolean("manual", false)
        val (task, run) = store.claim(taskId, revision, manual) ?: return@coroutineScope Result.success()
        try {
            validateTask(task)
            val provider = LocalProviderStore(applicationContext).load().find { it.id == task.providerId }
                ?: throw AutomationNeedsAttention("Bound provider no longer exists")
            if (provider.apiKey.isBlank()) throw AutomationNeedsAttention("Bound provider has no API key")
            if (task.model !in provider.models && task.model != provider.defaultModel)
                throw AutomationNeedsAttention("Bound model is no longer configured")
            val base = File(applicationContext.filesDir, "pocket").canonicalFile.also { it.mkdirs() }
            val root = task.workspace?.let { name ->
                File(base, name).canonicalFile.also {
                    if (it.parentFile != base || !it.isDirectory) throw AutomationNeedsAttention("Bound workspace no longer exists")
                }
            } ?: base
            val prefs = applicationContext.getSharedPreferences("pocket_local_mode", 0)
            fun currentPermission() = runCatching { LocalPermission.valueOf(prefs.getString("permission", "Auto")!!) }
                .getOrDefault(LocalPermission.Auto)
            fun authorized(name: String): Boolean = automationToolAllowed(task, name, currentPermission()) &&
                store.state.value.tasks.any { it.id == task.id && it.revision == revision && (manual || it.enabled) }
            val registry = LocalToolRegistry(root)
            val imageClient = LocalImageClient()
            val tools = if (provider.protocol == LocalProtocol.OpenAI) {
                val imageTool = io.github.hakunm.deepseekharness.local.MultimodalSettings(applicationContext)
                    .resolveImage(LocalProviderStore(applicationContext).load(), provider)
                    ?.takeIf { authorized(LocalTaskScope.imageTool) }
                    ?.let { ImageGenerationTool(imageClient, it, applicationContext) }
                (registry.tools + listOfNotNull(imageTool)).filter { authorized(it.name) }
            } else emptyList()
            val client = LocalChatClient()
            ChatExecution.onCancel("automation-$taskId") { client.cancelActive(); io.github.hakunm.deepseekharness.local.cancelImageGeneration(imageClient) }
            try {
            AutomationEngine(
                complete = { history, schemas -> client.completeWithTools(provider, task.model, history, task.effort, schemas).getOrThrow() },
                tools = tools, authorized = ::authorized,
                completeStreaming = { history, schemas, progress ->
                    client.completeWithTools(provider, task.model, history, task.effort, schemas, onProgress = progress).getOrThrow()
                },
                checkpoint = { store.checkpoint(run.id, it) },
                skillInstructions = MobileSkillStore(applicationContext).instructions(),
                delivery = { call, output -> if (call.name == "write_file") registry.writtenAttachment(call.arguments, output) else null },
            ).execute(task.prompt)
            } finally { client.cancelActive() }
            store.finish(run.id, RunStatus.Succeeded)
        } catch (failure: CancellationException) {
            withContext(NonCancellable) {
                store.finish(run.id, RunStatus.Interrupted, "Execution stopped. Partial output is retained; file changes are not rolled back.")
                store.update { state -> state.copy(tasks = state.tasks.map {
                    if (it.id == task.id && it.revision == revision) it.copy(enabled = false) else it
                }) }
            }
            throw failure
        } catch (failure: Exception) {
            // Do not persist transport errors containing URLs/keys or log secrets.
            store.finish(run.id, if (failure is AutomationNeedsAttention) RunStatus.NeedsAttention else RunStatus.Failed,
                if (failure is AutomationNeedsAttention) failure.message.orEmpty() else "Execution failed (${failure.javaClass.simpleName}). Check provider configuration and partial output.")
        }
        if (!manual && task.schedule == TaskSchedule.Once) store.update { state -> state.copy(tasks = state.tasks.map {
            if (it.id == task.id && it.revision == revision) it.copy(enabled = false) else it
        }) }
        if (!manual && task.schedule.isCalendar) {
            store.state.value.tasks.find { it.id == task.id && it.revision == revision && it.enabled }?.let {
                AutomationScheduler(applicationContext).scheduleNext(it)
            }
        }
        notifyRun(store.state.value.runs.first { it.id == run.id })
        Result.success() // No automatic API/tool retries after side effects or provider failures.
    }
    private fun notifyRun(run: AutomationRun) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("automation_results", "Automation results", NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(applicationContext, MainActivity::class.java)
            .setAction("automation.${run.id}").putExtra(AutomationNavigation.EXTRA, run.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(applicationContext, run.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { manager.notify(run.id.hashCode(), NotificationCompat.Builder(applicationContext, "automation_results")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history).setContentTitle(run.title)
            .setContentText("${run.status} · Open conversation").setContentIntent(pending).setAutoCancel(true).build()) }
    }

}

object AutomationNavigation {
    const val EXTRA = "automation_run_id"
    val pendingRun = MutableStateFlow<String?>(null)
    fun consume(intent: Intent?) {
        intent?.getStringExtra(EXTRA)?.let { pendingRun.value = it; intent.removeExtra(EXTRA) }
    }
}
