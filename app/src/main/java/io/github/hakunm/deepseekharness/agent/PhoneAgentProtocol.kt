package io.github.hakunm.deepseekharness.agent

import io.github.hakunm.deepseekharness.local.PhoneNode
import java.util.Locale
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Agent 可以执行的动作。纯 Kotlin，不依赖 Android 框架，便于单元测试。 */
sealed class PhoneAgentAction {
    data class Tap(val x: Int, val y: Int) : PhoneAgentAction()
    data class TapText(val text: String) : PhoneAgentAction()
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : PhoneAgentAction()
    data class Input(val text: String) : PhoneAgentAction()
    data class OpenApp(val packageName: String) : PhoneAgentAction()
    data object Back : PhoneAgentAction()
    data object Home : PhoneAgentAction()
    data class Wait(val millis: Long) : PhoneAgentAction()
}

/** 屏幕上的一个文本元素。 */
data class PhoneTextEntry(val text: String, val x: Int, val y: Int)

/**
 * 手机控制的命令协议：解析 DSH 发来的文本命令、把屏幕上下文序列化成 JSON。
 *
 * 无障碍节点是默认观察结果，截图只是可选字段。结构化节点让模型可以通过 nodeId
 * 和语义属性选择目标，而不是每次都依赖图像坐标猜测。
 */
object PhoneAgentProtocol {

    fun parseCommand(command: String): PhoneAgentAction? {
        val parts = command.trim().split(Regex("\\s+"), limit = 2)
        val head = parts.getOrNull(0)?.lowercase(Locale.ROOT)
        return when (head) {
            "tap" -> {
                val coords = parts.getOrNull(1)?.split(",") ?: return null
                val x = coords.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
                val y = coords.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
                PhoneAgentAction.Tap(x, y)
            }
            "taptext" -> {
                val text = parts.getOrNull(1)?.trim().orEmpty()
                text.takeIf { it.isNotEmpty() }?.let { PhoneAgentAction.TapText(it) }
            }
            "swipe" -> {
                val coords = parts.getOrNull(1)?.split(",") ?: return null
                if (coords.size < 4) return null
                val values = coords.map { it.trim().toIntOrNull() ?: return null }
                PhoneAgentAction.Swipe(values[0], values[1], values[2], values[3])
            }
            "input" -> {
                val text = parts.getOrNull(1)?.trim().orEmpty()
                text.takeIf { it.isNotEmpty() }?.let { PhoneAgentAction.Input(it) }
            }
            "openapp" -> {
                val pkg = parts.getOrNull(1)?.trim().orEmpty()
                pkg.takeIf { it.isNotEmpty() }?.let { PhoneAgentAction.OpenApp(it) }
            }
            "back" -> PhoneAgentAction.Back
            "home" -> PhoneAgentAction.Home
            "wait" -> PhoneAgentAction.Wait(parts.getOrNull(1)?.trim()?.toLongOrNull() ?: 1000L)
            else -> null
        }
    }

    fun contextToJson(
        timestamp: Long,
        currentPackage: String?,
        screenWidth: Int,
        screenHeight: Int,
        hasScreenshot: Boolean,
        hasUiTree: Boolean,
        texts: List<PhoneTextEntry>,
        nodes: List<PhoneNode> = emptyList(),
        observationSource: String = "accessibility",
    ): String = buildJsonObject {
        put("timestamp", timestamp)
        put("currentPackage", currentPackage ?: "unknown")
        put("observationSource", observationSource)
        put(
            "screenResolution",
            buildJsonObject {
                put("width", screenWidth)
                put("height", screenHeight)
            },
        )
        put(
            "textElements",
            buildJsonArray {
                texts.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("text", entry.text)
                            put("x", entry.x)
                            put("y", entry.y)
                        },
                    )
                }
            },
        )
        put(
            "nodes",
            buildJsonArray {
                nodes.forEach { node ->
                    add(
                        buildJsonObject {
                            put("id", node.id)
                            put("label", node.label)
                            put("bounds", buildJsonArray { node.bounds.forEach(::add) })
                            put("clickable", node.clickable)
                            put("longClickable", node.longClickable)
                            put("editable", node.editable)
                            put("enabled", node.enabled)
                            put("scrollable", node.scrollable)
                            put("sensitive", node.sensitive)
                            put("selected", node.selected)
                            put("focused", node.focused)
                            put("checked", node.checked)
                            put("resourceId", node.resourceId)
                            put("className", node.className)
                            node.clickTargetId?.let { put("clickTargetId", it) }
                            put("imeSubmit", node.imeSubmit)
                        },
                    )
                }
            },
        )
        put("hasScreenshot", hasScreenshot)
        put("hasUITree", hasUiTree)
    }.toString()

    /** 兼容旧调用点：保留一个显式的转义函数，便于在别处安全地拼字符串。 */
    fun escapeJson(value: String): String = JsonPrimitive(value).toString().removeSurrounding("\"")
}
