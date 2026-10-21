package io.github.hakunm.deepseekharness.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hakunm.deepseekharness.R
import io.github.hakunm.deepseekharness.local.LocalProtocol
import io.github.hakunm.deepseekharness.local.LocalProvider
import io.github.hakunm.deepseekharness.local.LocalProviderStore
import io.github.hakunm.deepseekharness.local.ModelDiscovery
import io.github.hakunm.deepseekharness.local.parseModelDiscovery
import io.github.hakunm.deepseekharness.local.withDiscovery
import io.github.hakunm.deepseekharness.local.withManualContextLimit
import io.github.hakunm.deepseekharness.local.resolveContextLimit
import io.github.hakunm.deepseekharness.local.ContextLimitSource
import io.github.hakunm.deepseekharness.local.normalizeBaseUrl
import io.github.hakunm.deepseekharness.ui.theme.DswTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 本机模式：完全在手机上配置并使用自己的 API Key。
 *
 * 与「接管电脑」模式互不影响：这里的数据只存在本机（加密），请求由 App 直接发出。
 */
@Composable
fun LocalModeScreen(onExit: () -> Unit, onConfigurationChanged: () -> Unit = {}, embedded: Boolean = false) {
    if (!embedded) androidx.activity.compose.BackHandler(onBack = onExit)
    val context = LocalContext.current
    // 在 Composable 作用域里取好文案；回调（非 Composable）里直接用这些值。
    val keyEmptyLabel = stringResource(R.string.local_key_empty)
    val savedLabel = stringResource(R.string.local_saved)
    val fetchedEmptyLabel = stringResource(R.string.local_fetched_empty)
    val testOkLabel = stringResource(R.string.local_test_ok)
    val fetchModelsLabel = stringResource(R.string.local_fetch_models)
    val store = remember { LocalProviderStore(context) }
    var providers by remember { mutableStateOf(store.seedIfEmpty()) }
    var editing by remember { mutableStateOf<LocalProvider?>(null) }
    var deleting by remember { mutableStateOf<LocalProvider?>(null) }
    var chatting by remember { mutableStateOf<LocalProvider?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    // 对话框里即时展示的模型目录与拉取结果
    var editorModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var editorNotice by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    // 拉取到的可用模型：非空时弹出「选择要添加的模型」对话框
    var fetchedOptions by remember { mutableStateOf<List<String>?>(null) }
    val colors = DswTheme.colors
    val radius = DswTheme.radius
    val scope = rememberCoroutineScope()

    // 进入对话后本页让位给对话界面。
    chatting?.let { active ->
        LocalChatScreen(provider = active, onExit = { chatting = null })
        return
    }

    Column(if (embedded) Modifier.fillMaxWidth() else Modifier.fillMaxSize().background(colors.bgBase)) {
        if (!embedded) Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), Modifier.size(20.dp), tint = colors.labelPrimary)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(stringResource(R.string.local_mode), color = colors.labelPrimary, fontSize = 20.sp, lineHeight = 28.sp)
                Text(
                    stringResource(R.string.local_mode_subtitle),
                    color = colors.labelTertiary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            if (embedded) Modifier.fillMaxWidth() else Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            providers.forEach { provider ->
                Surface(
                    onClick = {
                        editing = provider
                        editorModels = provider.models
                        editorNotice = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(radius.m),
                    color = colors.inputFill,
                    border = BorderStroke(0.5.dp, colors.borderL2),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BrandLogo(BrandResolver.provider(provider.id, provider.name, provider.baseUrl))
                        Column(Modifier.weight(1f)) {
                            Text(provider.name, color = colors.labelPrimary, fontSize = 15.sp, lineHeight = 21.sp)
                            Text(
                                provider.baseUrl,
                                color = colors.labelTertiary,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (provider.apiKey.isBlank()) {
                                    stringResource(R.string.local_key_empty)
                                } else {
                                    "${provider.protocol.name} · ${provider.defaultModel ?: provider.models.firstOrNull().orEmpty()}"
                                },
                                color = colors.labelTertiary,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (provider.id != "deepseek") {
                            IconButton(onClick = { deleting = provider }, modifier = Modifier.size(40.dp)) {
                                Icon(androidx.compose.material.icons.Icons.Outlined.DeleteOutline,
                                    stringResource(R.string.delete), Modifier.size(18.dp), tint = colors.danger)
                            }
                        }
                        IconButton(
                            onClick = {
                                editing = provider
                                editorModels = provider.models
                                editorNotice = null
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Edit,
                                stringResource(R.string.local_edit),
                                modifier = Modifier.size(16.dp),
                                tint = colors.labelTertiary,
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    editing = LocalProvider(
                        id = "custom-${System.currentTimeMillis()}",
                        name = "Custom",
                        protocol = LocalProtocol.OpenAI,
                        baseUrl = "https://",
                        apiKey = "",
                    )
                },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(radius.m),
            ) {
                Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                Text(stringResource(R.string.add_custom_provider), Modifier.padding(start = 6.dp))
            }
            notice?.let {
                Text(it, color = colors.labelSecondary, fontSize = 13.sp, lineHeight = 20.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    deleting?.let { provider ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.local_delete_provider_title)) },
            text = { Text(stringResource(R.string.local_delete_provider_message, provider.name)) },
            confirmButton = {
                TextButton(onClick = {
                    store.remove(provider.id)
                    providers = store.load()
                    onConfigurationChanged()
                    deleting = null
                }) { Text(stringResource(R.string.delete), color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    editing?.let { provider ->
        LocalProviderEditor(
            initial = provider,
            models = editorModels,
            notice = editorNotice,
            busy = fetching,
            onDismiss = { editing = null },
            onSave = { updated ->
                store.upsert(
                    updated.copy(
                        models = editorModels,
                        defaultModel = updated.defaultModel ?: editorModels.firstOrNull(),
                    ),
                )
                providers = store.load()
                onConfigurationChanged()
                editing = null
                notice = savedLabel
            },
            onTest = { candidate ->
                val key = candidate.apiKey.trim()
                if (key.isEmpty()) {
                    editorNotice = keyEmptyLabel
                } else {
                    scope.launch {
                        fetching = true
                        editorNotice = null
                        val result = withContext(Dispatchers.IO) { testLocalProvider(candidate) }
                        fetching = false
                        result.fold(
                            onSuccess = { discovery ->
                                // Save metadata before selection, including unselected model IDs.
                                val refreshed = candidate.withDiscovery(discovery)
                                editing = refreshed
                                store.upsert(refreshed.copy(models = editorModels))
                                providers = store.load()
                                onConfigurationChanged()
                                val models = discovery.ids
                                if (models.isEmpty()) {
                                    editorNotice = fetchedEmptyLabel
                                } else {
                                    editorNotice = "${testOkLabel} · ${models.size}"
                                    fetchedOptions = models
                                }
                            },
                            onFailure = { error ->
                                editorNotice =
                                    "${fetchModelsLabel}: ${error.message ?: error::class.simpleName}"
                            },
                        )
                    }
                }
            },
        )
    }

    // 拉取成功后由用户勾选要加入目录的模型（官方「选择要添加的模型」）。
    fetchedOptions?.let { options ->
        LocalModelPickerDialog(
            options = options,
            onDismiss = { fetchedOptions = null },
            onConfirm = { picked ->
                fetchedOptions = null
                val merged = (editorModels + picked).distinct()
                editorModels = merged
                editing?.let { provider ->
                    store.upsert(
                        provider.copy(
                            models = merged,
                            defaultModel = provider.defaultModel ?: merged.firstOrNull(),
                        ),
                    )
                    providers = store.load()
                onConfigurationChanged()
                }
                editorNotice = savedLabel
            },
        )
    }
}

/** 官方「选择要添加的模型」：搜索 + 全选 + 勾选 + 添加所选。 */
@Composable
private fun LocalModelPickerDialog(
    options: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    val colors = DswTheme.colors
    val visible = remember(query, options) {
        if (query.isBlank()) options else options.filter { it.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.local_pick_models_title), fontSize = 17.sp) },
        text = {
            Column(Modifier.widthIn(max = 460.dp)) {
                Text(
                    stringResource(R.string.local_pick_models_desc),
                    color = colors.labelSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.local_search_model), fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            picked = if (visible.isNotEmpty() && picked.size == visible.size) emptySet() else visible.toSet()
                        },
                    ) { Text(stringResource(R.string.local_select_all), fontSize = 13.sp) }
                }
                Spacer(Modifier.height(6.dp))
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    visible.forEach { model ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { picked = if (model in picked) picked - model else picked + model }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = model in picked,
                                onCheckedChange = { checked ->
                                    picked = if (checked) picked + model else picked - model
                                },
                            )
                            Text(
                                model,
                                color = colors.labelPrimary,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            Button(onClick = { onConfirm(picked.toList()) }, enabled = picked.isNotEmpty()) {
                Text(stringResource(R.string.local_add_selected))
            }
        },
    )
}

/** 编辑单个本机 Provider：协议、地址、API Key（加密保存）、默认模型。 */
@Composable
private fun LocalProviderEditor(
    initial: LocalProvider,
    models: List<String>,
    notice: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (LocalProvider) -> Unit,
    onTest: (LocalProvider) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.defaultModel ?: initial.models.firstOrNull().orEmpty()) }
    var protocol by remember { mutableStateOf(initial.protocol) }
    var contextLimit by remember(model) { mutableStateOf(initial.contextLimits[model]?.toString().orEmpty()) }
    fun limits() = initial.withManualContextLimit(model, contextLimit).contextLimits
    val colors = DswTheme.colors

    fun currentDraft() = initial.copy(
        name = name.trim().ifBlank { initial.name },
        baseUrl = normalizeBaseUrl(baseUrl),
        apiKey = apiKey.trim(),
        protocol = protocol,
            defaultModel = model.trim().ifBlank { null },
            contextLimits = limits(),
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name.ifBlank { stringResource(R.string.local_providers) }, fontSize = 17.sp) },
        text = {
            Column(Modifier.widthIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.local_protocol), color = colors.labelTertiary, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = protocol == LocalProtocol.OpenAI,
                        onClick = { protocol = LocalProtocol.OpenAI },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("OpenAI") }
                    SegmentedButton(
                        selected = protocol == LocalProtocol.Anthropic,
                        onClick = { protocol = LocalProtocol.Anthropic },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("Anthropic") }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.local_base_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.local_api_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = contextLimit, onValueChange = { contextLimit = it.filter(Char::isDigit) },
                    label = { Text("当前模型上下文上限（tokens，未知留空）") },
                    supportingText = {
                        val resolved = initial.withManualContextLimit(model, contextLimit).resolveContextLimit(model)
                        Text("$model · " + (resolved?.let {
                            "${it.tokens} tokens · " + if (it.source == ContextLimitSource.Manual) "手动设置" else "服务商 /models"
                        } ?: "上限未知 · 服务商可能未提供上下文信息"))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                // 官方「模型目录」：展示服务商真实返回的模型，配合下方「获取可用模型」刷新。
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.local_models_configured),
                        modifier = Modifier.weight(1f),
                        color = colors.labelTertiary,
                        fontSize = 12.sp,
                    )
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                    // 官方把「获取可用模型」放在模型目录标题右侧：键盘弹出时也点得到。
                    TextButton(onClick = { onTest(currentDraft()) }, enabled = !busy) {
                        Text(stringResource(R.string.local_fetch_models), fontSize = 13.sp)
                    }
                }
                notice?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 4.dp),
                        color = colors.labelSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
                if (models.isEmpty()) {
                    Text(
                        stringResource(R.string.local_models_none),
                        modifier = Modifier.padding(top = 4.dp),
                        color = colors.labelTertiary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                } else {
                    models.forEach { item ->
                        Text(
                            item,
                            modifier = Modifier.padding(top = 4.dp),
                            color = colors.labelPrimary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim().ifBlank { initial.name },
                            baseUrl = normalizeBaseUrl(baseUrl),
                            apiKey = apiKey.trim(),
                            protocol = protocol,
                            defaultModel = model.trim().ifBlank { null },
            contextLimits = limits(),
                        ),
                    )
                },
            ) {
                Icon(Icons.Outlined.Check, null, Modifier.size(16.dp))
                Text(stringResource(R.string.confirm), Modifier.padding(start = 4.dp))
            }
        },
    )
}

/**
 * 连通性测试：直接向服务商请求模型列表。
 * OpenAI 兼容用 `Authorization: Bearer`，Anthropic 用 `x-api-key` + 版本头。
 */
internal fun testLocalProvider(provider: LocalProvider): Result<ModelDiscovery> {
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    val trimmedBase = provider.baseUrl.trimEnd('/')
    val root = if (trimmedBase.endsWith("/v1")) trimmedBase else "$trimmedBase/v1"
    val url = "$root/models"
    val builder = Request.Builder().url(url).get()
    when (provider.protocol) {
        LocalProtocol.OpenAI -> builder.header("Authorization", "Bearer ${provider.apiKey}")
        LocalProtocol.Anthropic -> builder
            .header("x-api-key", provider.apiKey)
            .header("anthropic-version", "2023-06-01")
    }
    return runCatching {
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }
            parseModelDiscovery(body)
        }
    }
}
