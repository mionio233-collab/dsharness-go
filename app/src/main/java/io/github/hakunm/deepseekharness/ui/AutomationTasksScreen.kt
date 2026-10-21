package io.github.hakunm.deepseekharness.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.hakunm.deepseekharness.automation.*
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@Composable
internal fun AutomationTasksScreen(local: LocalModeController, onBack: () -> Unit, onOpenChat: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AutomationStore.get(context) }
    val scheduler = remember { AutomationScheduler(context) }
    val state by store.state.collectAsState()
    val english = io.github.hakunm.deepseekharness.AppLocale.currentTag(context).startsWith("en")
    fun text(en: String, zh: String) = if (english) en else zh
    var filter by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<AutomationTask?>(null) }
    var historyId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<AutomationTask?>(null) }
    var notice by remember { mutableStateOf("") }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notice = if (granted) text("Result notifications enabled", "已启用结果通知") else text("Runs remain available in history", "仍可在执行记录中查看结果")
    }
    BackHandler { if (editing != null) editing = null else if (historyId != null) historyId = null else onBack() }
    val editor = editing
    if (editor != null) {
        AutomationEditor(editor, local, english, onCancel = { editing = null }, onSave = { task ->
            runCatching { validateTask(task); store.save(task); scheduler.schedule(task) }
                .onSuccess { editing = null; notice = text("Task saved", "任务已保存") }
                .onFailure { notice = it.message.orEmpty() }
        }, notice = notice)
        return
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (historyId != null) historyId = null else onBack() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, text("Back", "返回"))
            }
            Text(text("Automations", "自动化任务"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = {
                notice = ""
                editing = AutomationTask(title = "", prompt = "", providerId = local.provider?.id.orEmpty(),
                    model = local.model.orEmpty(), workspace = local.activeWorkspace, permission = local.permission.name)
            }) { Icon(Icons.Outlined.Add, null); Text(text("New", "新建")) }
        }
        if (historyId == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(text("All", "全部"), text("Enabled", "已启用"), text("Ended", "已结束")).forEachIndexed { index, label ->
                    FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(label) })
                }
            }
            OutlinedTextField(search, { search = it }, singleLine = true,
                placeholder = { Text(text("Search automations", "搜索自动化任务")) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth())
            Text(text("Runs on this phone. Scheduled times are approximate; Android may delay work.",
                "在本机后台执行。计划时间为近似值，Android 可能延迟执行。"), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { historyId = "*" }) { Text(text("All run history", "全部执行记录")) }
            if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text(text("Enable result notifications", "启用结果通知"))
            }
        }
        store.loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary)
        if (historyId != null) {
            val runs = state.runs.filter { historyId == "*" || it.taskId == historyId }.sortedByDescending { it.startedAt }
            Text(text("Run history", "执行记录"), style = MaterialTheme.typography.titleLarge)
            if (runs.isEmpty()) Text(text("No runs yet", "暂无执行记录"))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(runs, key = { it.id }) { run ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(run.title, style = MaterialTheme.typography.titleMedium)
                            Text("${run.status} · ${formatAutomationTime(run.startedAt)}")
                            Text("${run.model} · ${run.workspace ?: text("Sandbox", "沙箱")}", style = MaterialTheme.typography.bodySmall)
                            if (run.detail.isNotBlank()) Text(run.detail)
                            Text(run.messages.lastOrNull { it.role == "assistant" }?.content.orEmpty().take(240))
                            TextButton(enabled = !local.busy, onClick = {
                                if (local.openAutomationRun(run)) onOpenChat()
                                else notice = text("Unable to open this run while chat is busy", "当前会话正在执行，暂时无法打开")
                            }) { Text(text("Open conversation", "打开会话")) }
                        }
                    }
                }
            }
        } else {
            val tasks = state.tasks.filter { (filter == 0 || (filter == 1 && it.enabled) || (filter == 2 && !it.enabled)) &&
                (it.title.contains(search, true) || it.prompt.contains(search, true)) }
            if (tasks.isEmpty()) {
                Spacer(Modifier.height(32.dp))
                Icon(Icons.Outlined.Schedule, null, modifier = Modifier.align(Alignment.CenterHorizontally).size(32.dp))
                Text(text("No automations", "暂无自动化任务"), modifier = Modifier.align(Alignment.CenterHorizontally))
                Text(text("Create a task with your own instructions and schedule.", "新建任务，填写指令并设置执行计划。"),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(tasks, key = { it.id }) { task ->
                    val running = state.runs.any { it.taskId == task.id && it.status == RunStatus.Running }
                    OutlinedCard(onClick = {
                        if (local.openAutomationTask(task)) onOpenChat()
                        else notice = text("Unable to open while chat is busy", "当前会话正在执行，暂时无法打开")
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(task.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Switch(checked = task.enabled, enabled = task.providerId.isNotBlank(), onCheckedChange = { enabled ->
                                    val updated = task.copy(enabled = enabled, revision = UUID.randomUUID().toString())
                                    runCatching {
                                        store.save(updated); scheduler.cancel(task.id)
                                        if (enabled) scheduler.schedule(updated)
                                    }.onFailure { notice = it.message.orEmpty() }
                                })
                            }
                            Text(task.prompt, maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                            Text("${task.model.ifBlank { text("Model unbound — edit task", "未绑定模型，请编辑") }} · ${task.workspace ?: text("Sandbox", "沙箱")}",
                                style = MaterialTheme.typography.bodySmall)
                            Text(when (task.schedule) {
                                TaskSchedule.Manual -> text("Manual", "手动运行")
                                TaskSchedule.Once -> text("Once, no earlier than ", "单次，不早于 ") + formatAutomationTime(task.firstRunAt)
                                TaskSchedule.Interval -> text("Every ${task.intervalMinutes} minutes", "每 ${task.intervalMinutes} 分钟")
                                TaskSchedule.Daily -> text("Daily", "每天") + " ${task.wallTime} · ${task.timeZone}"
                                TaskSchedule.Weekly -> text("Weekly", "每周") + " ${task.weekdays.sorted().joinToString()} · ${task.wallTime} · ${task.timeZone}"
                            }, style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(enabled = !running && task.providerId.isNotBlank(), onClick = {
                                    runCatching { scheduler.runNow(task) }.onSuccess { notice = text("Run queued; waiting for Android and network", "已排队，等待 Android 调度和网络") }
                                        .onFailure { notice = it.message.orEmpty() }
                                }) { Text(if (running) text("Running", "执行中") else text("Run", "运行")) }
                                TextButton(onClick = { historyId = task.id }) { Text(text("History", "记录")) }
                                TextButton(onClick = { notice = ""; editing = task }) { Text(text("Edit", "编辑")) }
                                TextButton(onClick = { deleting = task }) { Text(text("Delete", "删除")) }
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { task -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(text("Delete automation?", "删除自动化任务？")) },
        text = { Text(text("Stops queued/running work. Run history and workspace files are retained.", "取消排队和执行中的任务，保留执行记录和工作区文件。")) },
        confirmButton = { TextButton(onClick = { store.delete(task.id); scheduler.cancel(task.id); deleting = null }) { Text(text("Delete", "删除")) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(text("Cancel", "取消")) } }) }
}

@Composable
private fun AutomationEditor(initial: AutomationTask, local: LocalModeController, english: Boolean,
    onCancel: () -> Unit, onSave: (AutomationTask) -> Unit, notice: String) {
    fun text(en: String, zh: String) = if (english) en else zh
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var prompt by remember(initial.id) { mutableStateOf(initial.prompt) }
    var providerId by remember(initial.id) { mutableStateOf(initial.providerId) }
    var model by remember(initial.id) { mutableStateOf(initial.model) }
    var workspace by remember(initial.id) { mutableStateOf(initial.workspace) }
    var schedule by remember(initial.id) { mutableStateOf(initial.schedule) }
    var minutes by remember(initial.id) { mutableStateOf(initial.intervalMinutes.toString()) }
    var delay by remember(initial.id) { mutableStateOf(((initial.firstRunAt - System.currentTimeMillis()).coerceAtLeast(0) / 60_000).toString()) }
    var write by remember(initial.id) { mutableStateOf(initial.allowWrite) }
    var network by remember(initial.id) { mutableStateOf(initial.allowNetwork) }
    var image by remember(initial.id) { mutableStateOf(initial.allowImageGeneration) }
    var wallTime by remember(initial.id) { mutableStateOf(initial.wallTime) }
    var timeZone by remember(initial.id) { mutableStateOf(initial.timeZone) }
    var weekdays by remember(initial.id) { mutableStateOf(initial.weekdays) }
    var modelsOpen by remember { mutableStateOf(false) }
    var workspacesOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text(text("Cancel", "取消")) }
            Text(text("Automation", "自动化任务"), style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(title, { title = it }, label = { Text(text("Name", "名称")) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(prompt, { prompt = it }, label = { Text(text("Instructions", "执行指令")) }, minLines = 4, modifier = Modifier.fillMaxWidth())
        Box {
            OutlinedButton(onClick = { local.reload(); modelsOpen = true }) { Text(text("Model: ", "模型：") + model.ifBlank { text("Select", "选择") }) }
            DropdownMenu(modelsOpen, onDismissRequest = { modelsOpen = false }) {
                local.selectableModels().forEach { (provider, name) -> DropdownMenuItem(text = { Text("${provider.name} · $name") }, onClick = {
                    providerId = provider.id; model = name; modelsOpen = false
                }) }
            }
        }
        Box {
            OutlinedButton(onClick = { local.refreshWorkspaces(); workspacesOpen = true }) { Text(text("Workspace: ", "工作区：") + (workspace ?: text("Sandbox", "沙箱"))) }
            DropdownMenu(workspacesOpen, onDismissRequest = { workspacesOpen = false }) {
                DropdownMenuItem(text = { Text(text("Sandbox", "沙箱")) }, onClick = { workspace = null; workspacesOpen = false })
                local.workspaces.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { workspace = name; workspacesOpen = false }) }
            }
        }
        Text(text("Schedule", "执行计划"), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskSchedule.entries.forEach { value -> FilterChip(selected = schedule == value, onClick = { schedule = value }, label = {
                Text(when (value) { TaskSchedule.Manual -> text("Manual", "手动"); TaskSchedule.Once -> text("Once", "单次"); TaskSchedule.Interval -> text("Repeat", "重复"); TaskSchedule.Daily -> text("Daily", "每天"); TaskSchedule.Weekly -> text("Weekly", "每周") })
            }) }
        }
        if (schedule.isCalendar) {
            OutlinedTextField(wallTime, { wallTime = it }, label = { Text(text("Wall-clock time (HH:mm)", "当地时间（HH:mm）")) }, singleLine = true)
            OutlinedTextField(timeZone, { timeZone = it }, label = { Text(text("Timezone (e.g. Asia/Shanghai)", "时区（例如 Asia/Shanghai）")) }, singleLine = true)
            if (schedule == TaskSchedule.Weekly) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { day ->
                    FilterChip(selected = day in weekdays, onClick = {
                        weekdays = if (day in weekdays) weekdays - day else weekdays + day
                    }, label = { Text(java.time.DayOfWeek.of(day).getDisplayName(java.time.format.TextStyle.SHORT,
                        if (english) java.util.Locale.ENGLISH else java.util.Locale.CHINESE)) })
                }
            }
            Text(text("Stored timezone stays fixed when travelling. Missed days are skipped; DST gaps shift forward and overlaps run once at the earlier offset.",
                "使用保存的时区，不随旅行变更。跳过错过的日期；夏令时缺失时间向后顺延，重复时间仅在较早偏移执行一次。"), style = MaterialTheme.typography.bodySmall)
        }
        if (schedule == TaskSchedule.Once || schedule == TaskSchedule.Interval) OutlinedTextField(delay, { delay = it.filter(Char::isDigit).take(6) },
            label = { Text(text("First run delay (minutes)", "首次执行延迟（分钟）")) }, singleLine = true)
        if (schedule == TaskSchedule.Interval) OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(6) },
            label = { Text(text("Repeat interval (minimum 15 minutes)", "重复间隔（至少 15 分钟）")) }, singleLine = true)
        Text(text("Android may delay scheduled runs for network, battery restrictions or Doze. Force-stopped apps cannot run until reopened.",
            "网络、电池限制或休眠可能导致计划延迟。强行停止应用后，需要重新打开才能继续。"), style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(write, { write = it }, enabled = local.permission.allows("write_file"))
            Text(text("Authorize unattended workspace writes", "授权无人值守写入工作区"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(network, { network = it }, enabled = local.permission.allows("http_get"))
            Text(text("Authorize unattended HTTP GET", "授权无人值守 HTTP GET"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(image, { image = it }, enabled = local.permission.allows(io.github.hakunm.deepseekharness.local.LocalTaskScope.imageTool))
            Text(text("Authorize unattended image generation (provider imageModel required)", "授权无人值守生图（需配置供应商生图模型）"))
        }
        Text(text("Uses current tool permission as an upper limit. No phone control or interactive approvals. Changes apply to future runs.",
            "以当前工具权限为上限。后台不提供手机控制或交互审批。编辑后应用于后续执行。"), style = MaterialTheme.typography.bodySmall)
        if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.error)
        Button(enabled = title.isNotBlank() && prompt.isNotBlank() && model.isNotBlank() && providerId.isNotBlank() &&
            (schedule != TaskSchedule.Interval || (minutes.toLongOrNull() ?: 0) >= 15) &&
            (schedule == TaskSchedule.Manual || delay.toLongOrNull() != null), onClick = {
            onSave(initial.copy(title = title.trim(), prompt = prompt.trim(), providerId = providerId, model = model, workspace = workspace,
                schedule = schedule, intervalMinutes = minutes.toLongOrNull() ?: 60,
                wallTime = wallTime.trim(), timeZone = timeZone.trim(), weekdays = weekdays,
                allowImageGeneration = image && local.permission.allows(io.github.hakunm.deepseekharness.local.LocalTaskScope.imageTool),
                firstRunAt = System.currentTimeMillis() + (delay.toLongOrNull() ?: 0) * 60_000,
                allowWrite = write && local.permission.allows("write_file"), allowNetwork = network && local.permission.allows("http_get"),
                permission = local.permission.name, enabled = true, revision = UUID.randomUUID().toString()))
        }) { Text(text("Save automation", "保存自动化任务")) }
    }
}
private fun formatAutomationTime(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
