package io.github.hakunm.deepseekharness.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hakunm.deepseekharness.R
import io.github.hakunm.deepseekharness.local.LocalChatClient
import io.github.hakunm.deepseekharness.local.LocalMessage
import io.github.hakunm.deepseekharness.local.LocalProvider
import io.github.hakunm.deepseekharness.ui.theme.DswTheme
import kotlinx.coroutines.launch

/**
 * 本机对话：请求由手机直接发往服务商（OpenAI 兼容或 Anthropic），不经过 DSH 服务端。
 *
 * 消息视觉沿用官方 Conversation 规格：用户气泡 radius-xl + bubbleFill 右对齐，
 * 助手消息左对齐使用正文色。
 */
@Composable
fun LocalChatScreen(provider: LocalProvider, onExit: () -> Unit) {
    val colors = DswTheme.colors
    val radius = DswTheme.radius
    val client = remember { LocalChatClient() }
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf(listOf<LocalMessage>()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val model = provider.defaultModel ?: provider.models.firstOrNull().orEmpty()

    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        val history = messages + LocalMessage("user", text)
        messages = history
        input = ""
        busy = true
        error = null
        scope.launch {
            val result = client.complete(provider, model, history)
            busy = false
            result
                .onSuccess { reply -> messages = messages + LocalMessage("assistant", reply) }
                .onFailure { failure -> error = failure.message ?: failure::class.simpleName }
        }
    }

    Column(Modifier.fillMaxSize().background(colors.bgBase).imePadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    stringResource(R.string.back),
                    Modifier.size(20.dp),
                    tint = colors.labelPrimary,
                )
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(provider.name, color = colors.labelPrimary, fontSize = 17.sp, lineHeight = 24.sp)
                Text(
                    model.ifBlank { provider.baseUrl },
                    color = colors.labelTertiary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 空态与主界面保持一致：品牌 + 标语 + 预览版，底部同一个 Composer。
        if (messages.isEmpty() && error == null) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(boxed = false, tint = colors.labelPrimary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.hero_headline),
                        color = colors.labelPrimary,
                        fontSize = 26.sp,
                        lineHeight = 34.sp,
                    )
                    Spacer(Modifier.size(8.dp))
                    Surface(shape = RoundedCornerShape(radius.pill), color = colors.selectorFill) {
                        Text(
                            stringResource(R.string.hero_preview),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = colors.labelBluish,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(provider.name, color = colors.labelSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                    Text("·", color = colors.labelTertiary, fontSize = 14.sp)
                    Text(
                        model.ifBlank { provider.baseUrl },
                        color = colors.labelTertiary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { index -> "m${messages.indexOf(index)}" }) { message ->
                if (message.role == "user") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(0.82f).widthIn(max = 525.dp),
                            shape = RoundedCornerShape(radius.xl),
                            color = colors.bubbleFill,
                        ) {
                            Text(
                                message.content,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                color = colors.labelPrimary,
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                            )
                        }
                    }
                } else {
                    Text(
                        message.content,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                        color = colors.labelPrimary,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                    )
                }
            }
            if (busy) {
                item(key = "busy") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.local_testing),
                            color = colors.labelTertiary,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            error?.let { message ->
                item(key = "error") {
                    Text(
                        message,
                        color = colors.danger,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
        }

        // Composer：与官方输入卡片同一套规格（radius-panel、input-major 底、.5px border-l2）。
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            shape = RoundedCornerShape(radius.panel),
            color = colors.inputFill,
            border = BorderStroke(0.5.dp, colors.borderL2),
        ) {
            Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 38.dp, max = 136.dp).padding(horizontal = 3.dp, vertical = 3.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    if (input.isEmpty()) {
                        Text(
                            stringResource(R.string.message_hint),
                            color = colors.labelTertiary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp, max = 130.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.labelPrimary),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                        maxLines = 5,
                        // 键盘上的「发送」键直接发出，不必再去点右下角按钮。
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Surface(
                        onClick = { send() },
                        enabled = input.isNotBlank() && !busy,
                        modifier = Modifier.size(34.dp),
                        shape = CircleShape,
                        color = colors.accent,
                        contentColor = androidx.compose.ui.graphics.Color.White,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.padding(9.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.send_message), Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}
