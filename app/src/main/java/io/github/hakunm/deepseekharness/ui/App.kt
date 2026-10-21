package io.github.hakunm.deepseekharness.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.hakunm.deepseekharness.BuildConfig
import io.github.hakunm.deepseekharness.local.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoApp() {
    val context = LocalContext.current
    val local = remember { LocalModeController.get(context.applicationContext) }
    var page by rememberSaveable { mutableStateOf("chat") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var sessions by remember { mutableStateOf(false) }
    val resultSession by io.github.hakunm.deepseekharness.agent.PhoneConversationReturn.requested.collectAsState()
    LaunchedEffect(resultSession) {
        resultSession?.let { local.switchSession(it); page = "chat" }
        io.github.hakunm.deepseekharness.agent.PhoneConversationReturn.requested.value = null
    }
    BackHandler(page != "chat") { page = "chat"; local.reload() }
    if (page == "api") { LocalModeScreen(onExit = { local.reload(); page = "chat" }, onConfigurationChanged = local::reload); return }
    if (page == "automation") { AutomationTasksScreen(local, { page = "chat" }, { page = "chat" }); return }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("DSHarness Go") }, actions = {
                TextButton(onClick = { sessions = true }) { Text("会话") }
                TextButton(onClick = { page = if (page == "settings") "chat" else "settings" }) { Text(if (page == "settings") "返回" else "设置") }
            })
        },
        bottomBar = { if (page == "chat") Column(Modifier.navigationBarsPadding().imePadding().padding(12.dp)) {
            local.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!local.active) TextButton(onClick = { page = "api" }) { Text("配置 API 供应商") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(prompt, { prompt = it }, modifier = Modifier.weight(1f), placeholder = { Text("提问或描述你要完成的任务…") }, maxLines = 5)
                Button(onClick = { if (local.busy) local.cancel() else { local.send(prompt); prompt = "" } }, enabled = local.busy || (local.active && prompt.isNotBlank())) { Text(if (local.busy) "停止" else "发送") }
            }
        } }
    ) { inset ->
        Box(Modifier.fillMaxSize().padding(inset)) {
            when (page) {
                "settings" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("设置", style = MaterialTheme.typography.headlineSmall)
                    Text("任务模式")
                    Row(Modifier.fillMaxWidth()) {
                        LocalTaskMode.entries.forEach { mode -> TextButton(onClick = { local.updateTaskMode(mode) }, enabled = !local.busy) { Text(mode.name) } }
                    }
                    Text("工具权限")
                    Row(Modifier.fillMaxWidth()) {
                        LocalPermission.selectable.forEach { permission -> TextButton(onClick = { local.updatePermission(permission) }, enabled = !local.busy) { Text(androidx.compose.ui.res.stringResource(permission.labelRes)) } }
                    }
                    Row {
                        Checkbox(local.phoneAgentEnabled, { local.enablePhoneAgent(it) })
                        Text("允许当前会话使用手机控制工具")
                    }

                    Button(onClick = { page = "api" }) { Text("API 供应商与模型") }
                    Button(onClick = { page = "media" }) { Text("图片、音乐和视频") }
                    Button(onClick = { page = "files" }) { Text("工作区文件") }
                    Button(onClick = { page = "phone" }) { Text("手机控制") }
                    Button(onClick = { page = "automation" }) { Text("自动化任务") }
                    Text("v${BuildConfig.VERSION_NAME}")
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mionio233-collab/dsharness-go"))) }) { Text("GitHub") }
                }
                "phone" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { PhoneControlSection(onServiceStatusChanged = local::refreshPhoneStatus, visualAllowed = local.phoneAgentEnabled) }
                "files" -> WorkspaceFiles(local.sandboxRoot)
                "media" -> MediaSettings(local.multimodalSettings)
                else -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { page = "api" }) { Text(local.model ?: "选择模型") }
                        TextButton(onClick = { local.newSession() }) { Text("新建会话") }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(local.messages) { message ->
                            Column {
                                Text(if (message.role == "user") "你" else if (message.role == "assistant") "助手" else "工具", style = MaterialTheme.typography.labelMedium)
                                if (!message.reasoningContent.isNullOrBlank()) Text(message.reasoningContent.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(message.content)
                                message.attachments.forEach { attachment -> Text(attachment.name) }
                            }
                        }
                        if (local.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }
    if (sessions) AlertDialog(onDismissRequest = { sessions = false }, title = { Text("会话") }, text = {
        LazyColumn { items(local.sessions.filterNot { it.archived }) { item -> TextButton(onClick = { local.switchSession(item.id); sessions = false; page = "chat" }) { Text(item.title) } } }
    }, confirmButton = { TextButton(onClick = { local.newSession(); sessions = false; page = "chat" }) { Text("新建") } })
    local.pendingUpgradeTool?.let { tool -> AlertDialog(onDismissRequest = local::denyPermissionUpgrade,
        title = { Text("允许手机控制") }, text = { Text("当前任务需要手机控制工具。允许后会在本次会话启用完全工具权限。") },
        confirmButton = { TextButton(onClick = local::approvePermissionUpgrade) { Text("允许") } },
        dismissButton = { TextButton(onClick = local::denyPermissionUpgrade) { Text("取消") } }) }
    local.pendingApproval?.let { call -> AlertDialog(onDismissRequest = local::denyTool, title = { Text("工具调用确认") }, text = { Text("${call.name}\n${call.arguments}") }, confirmButton = { TextButton(onClick = local::approveTool) { Text("允许一次") } }, dismissButton = { TextButton(onClick = local::denyTool) { Text("拒绝") } }) }
}

@Composable
private fun WorkspaceFiles(root: File) {
    var selected by remember { mutableStateOf<File?>(null) }
    var content by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    val files = remember(root, notice) { root.walkTopDown().maxDepth(5).filter { it.isFile && it.canonicalPath.startsWith(root.canonicalPath + File.separator) }.take(500).toList() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        val file = selected
        if (file == null) LazyColumn { items(files) { entry -> TextButton(onClick = { runCatching { require(entry.length() <= 500_000); content = entry.readText(); selected = entry }.onFailure { notice = it.message.orEmpty() } }) { Text(entry.relativeTo(root).path) } } }
        else {
            Text(file.relativeTo(root).path)
            OutlinedTextField(content, { content = it }, modifier = Modifier.weight(1f).fillMaxWidth())
            Row {
                TextButton(onClick = { runCatching { file.writeText(content) }.onSuccess { notice = "已保存" }.onFailure { notice = it.message.orEmpty() } }) { Text("保存") }
                TextButton(onClick = { selected = null }) { Text("返回目录") }
            }
        }
        Text(notice)
    }
}

@Composable
private fun MediaSettings(store: MultimodalSettings) {
    var config by remember { mutableStateOf(store.config) }
    var type by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf("") }
    val api = when(type) { 0 -> config.image.custom; 1 -> config.music.api; else -> config.video.api }
    fun update(value: CustomApi) { config = when(type) { 0 -> config.copy(image = config.image.copy(useCustom = true, custom = value)); 1 -> config.copy(music = config.music.copy(api = value)); else -> config.copy(video = config.video.copy(api = value)) } }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row { listOf("图片", "音乐", "视频").forEachIndexed { index, label -> TextButton(onClick = { type = index }) { Text(if (type == index) "✓ $label" else label) } } }
        OutlinedTextField(api.baseUrl, { update(api.copy(baseUrl = it)) }, label = { Text("API 地址") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(api.apiKey, { update(api.copy(apiKey = it)) }, label = { Text("API Key") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(api.model, { update(api.copy(model = it)) }, label = { Text("模型") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { runCatching { store.save(config) }.onSuccess { notice = "已保存" }.onFailure { notice = it.message.orEmpty() } }) { Text("保存") }
        Text(notice)
    }
}

internal fun imageUrlsInReply(text: String): List<String> = Regex("https://[^\\s)<>\\\"]+", RegexOption.IGNORE_CASE).findAll(text).map { it.value }.filter { it.substringBefore('?').endsWith(".png", true) || it.substringBefore('?').endsWith(".jpg", true) || it.substringBefore('?').endsWith(".webp", true) }.distinct().toList()
