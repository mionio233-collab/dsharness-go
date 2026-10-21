package io.github.hakunm.deepseekharness.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.ScreenShare
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.hakunm.deepseekharness.R
import io.github.hakunm.deepseekharness.agent.PhoneAgentEngine
import io.github.hakunm.deepseekharness.agent.PhoneAgentProtocol
import io.github.hakunm.deepseekharness.agent.PhoneControlService
import io.github.hakunm.deepseekharness.agent.ScreenCaptureService
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「设置 → 手机控制」。
 *
 * 之前 agent 包里的三个类没有和任何界面连接，用户既开不了无障碍服务也拿不到
 * 录屏授权，文档里写的手机控制实际不可用。这里补上入口，并把状态、录屏授权、
 * 命令执行的结果直接显示出来。
 */
@Composable
fun PhoneControlSection(
    modifier: Modifier = Modifier,
    onServiceStatusChanged: () -> Unit = {},
    visualAllowed: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val overlaySettings = remember { io.github.hakunm.deepseekharness.agent.PhoneOverlaySettings(context) }
    var overlayEnabled by remember { mutableStateOf(overlaySettings.enabled) }
    var overlayOpacity by remember { mutableStateOf(overlaySettings.opacity) }
    var edgeEnabled by remember { mutableStateOf(overlaySettings.edgeEnabled) }
    var lowMotion by remember { mutableStateOf(overlaySettings.lowMotion) }
    val previewBusyMessage = stringResource(R.string.phone_overlay_preview_busy)
    val previewMessage = stringResource(R.string.phone_overlay_preview_running)
    val screenCaptureDeniedMessage = stringResource(R.string.screen_capture_denied)
    val accessibilityNotEnabledMessage = stringResource(R.string.accessibility_not_enabled)
    val unknownCommandMessage = stringResource(R.string.phone_unknown_command)

    var accessibilityOn by remember { mutableStateOf(PhoneControlService.isEnabled(context)) }
    var serviceConnected by remember { mutableStateOf(PhoneControlService.isConnected()) }
    val captureState by ScreenCaptureService.state.collectAsState()
    val capturing = captureState.active
    val currentVisualAllowed by rememberUpdatedState(visualAllowed)
    val currentStatusChanged by rememberUpdatedState(onServiceStatusChanged)
    var command by remember { mutableStateOf("") }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var screenshotSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val connected by PhoneControlService.connected.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        fun refresh() {
            accessibilityOn = PhoneControlService.isEnabled(context)
            serviceConnected = PhoneControlService.isConnected()
            currentStatusChanged()
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        refresh()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(connected, captureState) {
        serviceConnected = connected
        if (!captureState.active) screenshotSize = null
        currentStatusChanged()
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null && currentVisualAllowed) {
            runCatching { ScreenCaptureService.start(context, result.resultCode, data) }
                .onFailure { status = screenCaptureDeniedMessage }
            onServiceStatusChanged()
        } else {
            status = screenCaptureDeniedMessage
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.phone_control_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            StatusLabel(
                stringResource(if (accessibilityOn) R.string.accessibility_enabled else R.string.accessibility_not_enabled),
                accessibilityOn,
            )
            if (accessibilityOn && !serviceConnected) {
                Text(
                    stringResource(R.string.phone_service_connecting),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = CircleShape,
            ) {
                Icon(Icons.Outlined.Accessibility, null, Modifier.size(18.dp))
                Text(
                    stringResource(R.string.go_to_accessibility_settings),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Text(stringResource(R.string.visual_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.visual_help), style = MaterialTheme.typography.bodySmall)
            StatusLabel(stringResource(if (visualAllowed) R.string.visual_phone_consent_on else R.string.visual_need_phone_consent), visualAllowed)
            StatusLabel(
                stringResource(if (capturing) R.string.visual_os_active else R.string.visual_os_inactive),
                capturing,
            )
            val reasonText = when (captureState.reason) {
                "starting" -> R.string.visual_starting
                "content_hidden" -> R.string.visual_content_hidden
                "full_display_required" -> R.string.visual_full_display_required
                "projection_system_stop" -> R.string.visual_stopped_system
                "screen_off" -> R.string.visual_stopped_screen_off
                "startup_failed", "invalid_grant", "resize_failed" -> R.string.visual_start_failed
                "service_destroyed", "replaced" -> R.string.visual_stopped_service
                "user_stop", "notification_stop", "phone_consent_revoked" -> R.string.visual_stopped_user
                else -> null
            }
            reasonText?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            screenshotSize?.let { (w, h) ->
                Text(
                    stringResource(R.string.screen_capture_size, w, h),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (!visualAllowed || capturing) return@Button
                        val manager = context.getSystemService(MediaProjectionManager::class.java)
                        if (manager == null) {
                            status = screenCaptureDeniedMessage
                        } else {
                            projectionLauncher.launch(if (Build.VERSION.SDK_INT >= 34)
                                manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                                else manager.createScreenCaptureIntent())
                        }
                    },
                    enabled = visualAllowed && serviceConnected && !capturing && !captureState.starting,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ScreenShare, null, Modifier.size(18.dp))
                    Text(
                        stringResource(if (captureState.reason == "needs_authorization") R.string.visual_enable_action else R.string.visual_reauthorize),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (capturing) {
                    OutlinedButton(
                        onClick = {
                            ScreenCaptureService.stop(context)
                            screenshotSize = null
                        },
                        modifier = Modifier.height(48.dp),
                        shape = CircleShape,
                    ) { Text(stringResource(R.string.stop_screen_capture)) }
                }
            }

            androidx.compose.material3.HorizontalDivider()
            Text(stringResource(R.string.phone_overlay_title), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.phone_overlay_compact), Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = overlayEnabled, onCheckedChange = {
                    overlayEnabled = it
                    overlaySettings.enable(it)
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.phone_overlay_edge), Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = edgeEnabled, onCheckedChange = {
                    edgeEnabled = it; overlaySettings.edgeEnable(it)
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.phone_overlay_low_motion), Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = lowMotion, onCheckedChange = {
                    lowMotion = it; overlaySettings.lowMotion(it)
                })
            }
            Text(stringResource(R.string.phone_overlay_opacity, (overlayOpacity * 100).toInt()))
            androidx.compose.material3.Slider(value = overlayOpacity, onValueChange = {
                overlayOpacity = it; overlaySettings.opacity(it)
            }, valueRange = 0.35f..1f)
            OutlinedButton(onClick = {
                status = if (PhoneControlService.getInstance()?.previewStatus() == true) previewMessage else previewBusyMessage
            }, enabled = (overlayEnabled || edgeEnabled) && serviceConnected, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.phone_overlay_preview))
            }
            Text(stringResource(R.string.phone_overlay_help), style = MaterialTheme.typography.bodySmall)

            androidx.compose.material3.HorizontalDivider()
            androidx.compose.material3.TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                Text(stringResource(if (advancedExpanded) R.string.settings_hide_diagnostics else R.string.settings_advanced_diagnostics))
            }
            if (advancedExpanded) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.phone_command_hint)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val action = PhoneAgentProtocol.parseCommand(command)
                        when {
                            !accessibilityOn ->
                                status = accessibilityNotEnabledMessage
                            action == null ->
                                status = unknownCommandMessage
                            else -> scope.launch {
                                val control = awaitControlService()
                            status = if (control == null) {
                                accessibilityNotEnabledMessage
                            } else {
                                val engine = PhoneAgentEngine(
                                    control,
                                    ScreenCaptureService.getInstance(),
                                )
                                when (val result = engine.executeAction(action)) {
                                    is PhoneAgentEngine.ExecutionResult.Success -> result.message
                                    is PhoneAgentEngine.ExecutionResult.Failed -> result.reason
                                }
                            }
                            onServiceStatusChanged()
                            }
                        }
                    },
                    enabled = accessibilityOn && command.isNotBlank(),
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.phone_command_run),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val control = awaitControlService()
                            if (control == null) {
                                status = accessibilityNotEnabledMessage
                            } else {
                                val engine = PhoneAgentEngine(
                                    control,
                                    ScreenCaptureService.getInstance(),
                                )
                                val ctx = engine.getCurrentContext()
                                screenshotSize = ctx.screenshot?.let { it.width to it.height }
                                status = "texts=${ctx.allText.size} pkg=${ctx.currentPackage ?: "-"}"
                                onServiceStatusChanged()
                            }
                        }
                    },
                    enabled = accessibilityOn,
                    modifier = Modifier.height(48.dp),
                    shape = CircleShape,
                ) { Text(stringResource(R.string.phone_read_screen)) }
            }

            }
            status?.let {
                Text(
                    stringResource(R.string.phone_command_result, it),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * 无障碍服务可能刚好在被系统重新绑定，短暂等待一下再执行命令，
 * 避免用户在服务重启的瞬间点按钮就报「未启用」。
 */
private suspend fun awaitControlService(timeoutMs: Long = 4000): PhoneControlService? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        PhoneControlService.getInstance()?.let { return it }
        delay(200)
    }
    return PhoneControlService.getInstance()
}
