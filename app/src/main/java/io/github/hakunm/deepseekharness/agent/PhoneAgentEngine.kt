package io.github.hakunm.deepseekharness.agent

import android.content.Intent
import android.graphics.Bitmap
import io.github.hakunm.deepseekharness.local.PhoneNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Phone Agent Engine - 集成到 DSH 的手机控制引擎
 *
 * 动作定义与命令解析在 [PhoneAgentProtocol]（纯 Kotlin，可单元测试），
 * 这里只负责调用无障碍/截图服务。截图默认关闭，只有显式启用视觉兜底时才读取。
 */
class PhoneAgentEngine(
    private val controlService: PhoneControlService,
    private val captureService: ScreenCaptureService? = null,
    private val captureScreenshots: Boolean = false,
) {

    data class AgentContext(
        val screenshot: Bitmap?,
        val uiHierarchy: UINode?,
        val nodes: List<PhoneNode>,
        val allText: List<TextWithBounds>,
        val currentPackage: String?,
        val screenWidth: Int,
        val screenHeight: Int,
        val observationSource: String = "accessibility",
        val timestamp: Long = System.currentTimeMillis(),
    )

    sealed class ExecutionResult {
        data class Success(val message: String) : ExecutionResult()
        data class Failed(val reason: String) : ExecutionResult()
    }

    /**
     * 获取当前手机状态上下文。
     * 无障碍树的遍历与可选截图都在后台线程执行，避免在主线程处理大界面时卡顿/ANR。
     */
    suspend fun getCurrentContext(): AgentContext = withContext(Dispatchers.Default) {
        val screenshot = if (captureScreenshots && captureService?.isCapturing == true) {
            captureService.captureScreen()
        } else {
            null
        }
        val screen = AccessibilityPhoneBackend(controlService).observe()
        AgentContext(
            screenshot = screenshot,
            uiHierarchy = controlService.getUIHierarchy(),
            nodes = screen?.nodes.orEmpty(),
            allText = controlService.getAllText(),
            currentPackage = screen?.packageName ?: controlService.getCurrentPackage(),
            screenWidth = screen?.width ?: screenshot?.width ?: 0,
            screenHeight = screen?.height ?: screenshot?.height ?: 0,
            observationSource = if (screenshot != null) "hybrid" else "accessibility",
        )
    }

    /** 执行单个操作 */
    suspend fun executeAction(action: PhoneAgentAction): ExecutionResult {
        return try {
            when (action) {
                is PhoneAgentAction.Tap -> {
                    val success = controlService.tap(action.x, action.y)
                    delay(300)
                    if (success) ExecutionResult.Success("Tapped at (${action.x}, ${action.y})")
                    else ExecutionResult.Failed("Tap gesture failed")
                }

                is PhoneAgentAction.TapText -> {
                    val bounds = controlService.findNodeByText(action.text)
                    if (bounds.isEmpty()) {
                        ExecutionResult.Failed("Text '${action.text}' not found")
                    } else {
                        val target = bounds.first()
                        val centerX = target.centerX()
                        val centerY = target.centerY()
                        val success = controlService.tap(centerX, centerY)
                        delay(300)
                        if (success) ExecutionResult.Success("Tapped '${action.text}' at ($centerX, $centerY)")
                        else ExecutionResult.Failed("Tap gesture failed")
                    }
                }

                is PhoneAgentAction.Swipe -> {
                    val success = controlService.swipe(action.x1, action.y1, action.x2, action.y2)
                    delay(300)
                    if (success) {
                        ExecutionResult.Success("Swiped from (${action.x1},${action.y1}) to (${action.x2},${action.y2})")
                    } else {
                        ExecutionResult.Failed("Swipe gesture failed")
                    }
                }

                is PhoneAgentAction.Input -> {
                    val success = controlService.inputText(action.text)
                    delay(200)
                    if (success) ExecutionResult.Success("Input text: ${action.text}")
                    else ExecutionResult.Failed("Text input failed: 请先点击一个输入框")
                }

                is PhoneAgentAction.Back -> {
                    val success = controlService.pressBack()
                    delay(300)
                    if (success) ExecutionResult.Success("Pressed back button")
                    else ExecutionResult.Failed("Back button failed")
                }

                is PhoneAgentAction.Home -> {
                    val success = controlService.pressHome()
                    delay(300)
                    if (success) ExecutionResult.Success("Pressed home button")
                    else ExecutionResult.Failed("Home button failed")
                }

                is PhoneAgentAction.Wait -> {
                    delay(action.millis)
                    ExecutionResult.Success("Waited ${action.millis}ms")
                }

                is PhoneAgentAction.OpenApp -> {
                    val intent = controlService.packageManager
                        .getLaunchIntentForPackage(action.packageName)
                    if (intent == null) {
                        ExecutionResult.Failed("未找到应用 ${action.packageName}")
                    } else {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        controlService.startActivity(intent)
                        delay(500)
                        ExecutionResult.Success("Launched ${action.packageName}")
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ExecutionResult.Failed("Exception: ${e.message}")
        }
    }

    /** 执行一系列操作 */
    suspend fun executeActions(actions: List<PhoneAgentAction>): List<ExecutionResult> =
        actions.map { executeAction(it) }

    /** 将上下文序列化为 JSON 字符串，用于发送给 DSH。 */
    fun contextToJson(context: AgentContext): String = PhoneAgentProtocol.contextToJson(
        timestamp = context.timestamp,
        currentPackage = context.currentPackage,
        screenWidth = context.screenWidth,
        screenHeight = context.screenHeight,
        hasScreenshot = context.screenshot != null,
        hasUiTree = context.uiHierarchy != null || context.nodes.isNotEmpty(),
        observationSource = context.observationSource,
        nodes = context.nodes,
        texts = context.allText.map {
            PhoneTextEntry(it.text, it.bounds.centerX(), it.bounds.centerY())
        },
    )

    /** 从 DSH 命令解析为动作 */
    fun parseCommand(command: String): PhoneAgentAction? = PhoneAgentProtocol.parseCommand(command)
}
