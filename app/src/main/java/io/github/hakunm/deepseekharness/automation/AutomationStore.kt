package io.github.hakunm.deepseekharness.automation

import android.content.Context
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import io.github.hakunm.deepseekharness.local.LocalMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable enum class TaskSchedule { Manual, Once, Interval, Daily, Weekly }
@Serializable enum class RunStatus { Running, Succeeded, Failed, NeedsAttention, Interrupted }
@Serializable data class AutomationTask(
    val id: String = UUID.randomUUID().toString(), val title: String, val prompt: String,
    val providerId: String, val model: String, val workspace: String? = null,
    val permission: String = "ReadOnly", val allowWrite: Boolean = false, val allowNetwork: Boolean = false,
    val schedule: TaskSchedule = TaskSchedule.Manual, val intervalMinutes: Long = 60,
    val firstRunAt: Long = System.currentTimeMillis(), val enabled: Boolean = true,
    val revision: String = UUID.randomUUID().toString(), val effort: String? = null,
    val wallTime: String = "09:00", val timeZone: String = java.time.ZoneId.systemDefault().id,
    /** ISO weekdays: Monday = 1, Sunday = 7. */
    val weekdays: Set<Int> = setOf(1), val allowImageGeneration: Boolean = false,
)
@Serializable data class AutomationRun(
    val id: String = UUID.randomUUID().toString(), val taskId: String, val title: String,
    val providerId: String, val model: String, val workspace: String?,
    val startedAt: Long = System.currentTimeMillis(), val finishedAt: Long? = null,
    val status: RunStatus = RunStatus.Running, val detail: String = "",
    val messages: List<LocalMessage> = emptyList(),
)
@Serializable data class AutomationState(val tasks: List<AutomationTask> = emptyList(), val runs: List<AutomationRun> = emptyList())

/** Single-process, serialized read/modify/write transactions; an atomic file prevents partial JSON. */
class AutomationStore private constructor(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(context.filesDir, "automations.json")
    var loadError: String? = null
        private set
    private val mutable = MutableStateFlow(runCatching { load(context) }.getOrElse {
        loadError = "Unable to read saved automations. Existing data is preserved; no edits can be saved."
        AutomationState()
    })
    val state = mutable.asStateFlow()
    private fun load(context: Context): AutomationState {
        if (file.exists()) {
            val saved = json.decodeFromString<AutomationState>(file.readText())
            return saved.copy(runs = saved.runs.map { run ->
                if (run.status == RunStatus.Running) run.copy(status = RunStatus.Interrupted,
                    finishedAt = System.currentTimeMillis(), detail = "Android interrupted execution; review partial output before running again.",
                    messages = run.messages.map { if (it.role == "assistant") it.copy(runStatus = "interrupted", interrupted = true) else it }) else run
            })
        }
        // Preserve old manual templates as disabled, unbound tasks. Editing binds a real model.
        val raw = context.getSharedPreferences("mobile_automation_tasks", 0).getString("tasks", "[]") ?: "[]"
        val legacy = json.decodeFromString<List<LegacyTask>>(raw)
        return AutomationState(legacy.map { AutomationTask(id = it.id, title = it.title, prompt = it.prompt,
            workspace = it.workspace, providerId = "", model = "", enabled = false) })
    }
    @Synchronized fun update(change: (AutomationState) -> AutomationState) {
        check(loadError == null) { loadError.orEmpty() }
        val next = change(mutable.value)
        val temporary = File(file.parentFile, "automations.json.pending")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.encodeToString(next).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            mutable.value = next
        } catch (failure: Exception) { temporary.delete(); throw failure }
    }
    fun save(task: AutomationTask) = update { it.copy(tasks = it.tasks.filterNot { old -> old.id == task.id } + task) }
    fun delete(id: String) = update { it.copy(tasks = it.tasks.filterNot { task -> task.id == id }) }
    @Synchronized fun claim(taskId: String, revision: String, manual: Boolean): Pair<AutomationTask, AutomationRun>? {
        val task = mutable.value.tasks.find { it.id == taskId && it.revision == revision } ?: return null
        if (!manual && !task.enabled) return null
        val active = mutable.value.runs.find { it.taskId == taskId && it.status == RunStatus.Running }
        if (active != null) {
            if (!io.github.hakunm.deepseekharness.local.ChatExecution.slots.contains("automation-$taskId")) {
                finish(active.id, RunStatus.Interrupted, "Previous execution was interrupted. Task paused; review workspace changes before enabling again.")
                save(task.copy(enabled = false))
            }
            return null
        }
        val run = AutomationRun(taskId = task.id, title = task.title, providerId = task.providerId,
            model = task.model, workspace = task.workspace, messages = listOf(LocalMessage("user", task.prompt)))
        update { it.copy(runs = it.runs + run) }
        return task to run
    }
    fun checkpoint(id: String, messages: List<LocalMessage>) = update { state -> state.copy(runs = state.runs.map {
        if (it.id == id) it.copy(messages = messages) else it
    }) }
    fun finish(id: String, status: RunStatus, detail: String = "") = update { state -> state.copy(runs = state.runs.map {
        if (it.id == id) it.copy(status = status, detail = detail, finishedAt = System.currentTimeMillis(),
            messages = it.messages.map { message -> if (message.role == "assistant") message.copy(
                runStatus = when (status) { RunStatus.Succeeded -> "completed"; RunStatus.Interrupted -> "interrupted"; else -> "failed" },
                elapsedMillis = message.elapsedMillis ?: (System.currentTimeMillis() - it.startedAt),
                interrupted = status != RunStatus.Succeeded) else message }) else it
    }) }
    companion object {
        @Volatile private var instance: AutomationStore? = null
        fun get(context: Context): AutomationStore = instance ?: synchronized(this) {
            instance ?: AutomationStore(context.applicationContext).also { instance = it }
        }
    }
}
@Serializable private data class LegacyTask(val id: String, val title: String, val prompt: String,
    val workspace: String? = null, val sessionId: String? = null)

fun validateTask(task: AutomationTask) {
    require(task.title.isNotBlank() && task.prompt.isNotBlank()) { "Title and instructions are required" }
    require(task.providerId.isNotBlank() && task.model.isNotBlank()) { "Select a configured local model" }
    require(task.schedule != TaskSchedule.Interval || task.intervalMinutes >= 15) { "Recurring interval must be at least 15 minutes" }
    if (task.schedule.isCalendar) {
        require(Regex("\\d{2}:\\d{2}").matches(task.wallTime)) { "Use HH:mm wall-clock time" }
        java.time.LocalTime.parse(task.wallTime)
        java.time.ZoneId.of(task.timeZone)
        require(task.schedule != TaskSchedule.Weekly || (task.weekdays.isNotEmpty() && task.weekdays.all { it in 1..7 })) {
            "Select at least one valid weekday"
        }
    }
    require(task.workspace == null || (task.workspace.isNotBlank() && task.workspace !in setOf(".", "..") &&
        !task.workspace.contains('/') && !task.workspace.contains('\\'))) { "Invalid workspace" }
}
