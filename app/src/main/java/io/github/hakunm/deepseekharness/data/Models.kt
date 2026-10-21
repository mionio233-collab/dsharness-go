package io.github.hakunm.deepseekharness.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
data class HealthView(val ok: Boolean, val version: String, val pluginVersion: String? = null)

@Serializable
data class RootView(val id: String, val label: String, val createdAt: Long)

@Serializable
data class DeviceView(
    val id: String,
    val name: String,
    val scopes: List<String>,
    val rootIds: List<String>,
)

@Serializable
data class PairingResult(val token: String, val device: DeviceView)

@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    val kind: String,
    val size: Long,
    val modifiedAt: Double,
    val writable: Boolean,
)

@Serializable
data class DirectoryPage(val path: String, val entries: List<FileEntry>, val nextCursor: String? = null)

@Serializable
data class TrashEntry(
    val id: String,
    val rootId: String,
    val path: String,
    val kind: String,
    val size: Long,
    val createdAt: Long,
    val status: String,
)

@Serializable
data class ChatSession(
    val id: String,
    val rootId: String,
    val cwd: String,
    val updatedAt: Long,
    val running: Boolean,
    val blank: Boolean,
    val title: String? = null,
    val workspaceId: String? = null,
    val workspaceTitle: String? = null,
    val agentPreset: String? = null,
    val parentSessionId: String? = null,
    val origin: String? = null,
    val pendingInteraction: String? = null,
)

@Serializable
data class PendingApproval(
    val id: String,
    val sessionId: String,
    val toolName: String,
    val reason: String? = null,
    val detail: String? = null,
    val risk: String,
    val requestedAt: Long,
)

@Serializable
data class CommandInput(val hint: String)

@Serializable
data class CommandDescriptor(
    val name: String,
    val description: String,
    val input: CommandInput? = null,
)

@Serializable
data class CommandResult(
    val kind: String,
    val text: String? = null,
    val sourceEventSeq: Int? = null,
)

@Serializable
data class CommandExecution(val commandId: String, val result: CommandResult)

data class TodoItem(val content: String, val status: String)

@Serializable
data class PermissionOption(val value: String, val name: String, val description: String? = null)

data class PermissionSelect(val options: List<PermissionOption>, val currentValue: String)

@Serializable
data class PermissionCatalog(
    val options: List<PermissionOption>,
    val defaultOptions: List<PermissionOption> = emptyList(),
    val defaultPreset: String,
)

@Serializable
data class CreatedSession(val id: String, val agentPreset: String? = null)

@Serializable
data class RenamedSession(val title: String, val seq: Int)

@Serializable
data class ChatWorkspace(
    val id: String,
    val title: String,
    val rootId: String,
    val path: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class AgentPreset(
    val id: String,
    val name: String,
    val description: String? = null,
    val trust: String,
    val isDefault: Boolean,
    val available: Boolean,
)

@Serializable
data class ModelSelection(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
)

@Serializable
data class ModelReasoningEffort(val id: String, val name: String, val description: String? = null)

@Serializable
data class ModelReasoning(
    val efforts: List<ModelReasoningEffort>,
    val defaultEffort: String? = null,
)

@Serializable
data class ModelView(
    val id: String,
    val name: String,
    val description: String? = null,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val reasoning: ModelReasoning? = null,
)

@Serializable
data class ModelProviderGroup(val id: String, val name: String, val models: List<ModelView>)

@Serializable
data class ModelFailure(val provider: String, val message: String)

@Serializable
data class SessionModels(
    val current: ModelSelection,
    val routable: Boolean,
    val groups: List<ModelProviderGroup>,
    val failures: List<ModelFailure> = emptyList(),
)

@Serializable
data class ProviderModel(
    val id: String,
    val name: String? = null,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
)

@Serializable
data class ProviderConfig(
    val baseURL: String? = null,
    val api: String? = null,
    val displayName: String? = null,
    val thinking: String? = null,
    val reasoningEffort: String? = null,
    val models: List<ProviderModel> = emptyList(),
    val modelsInherited: Boolean = false,
)

@Serializable
data class CredentialState(
    val ref: String,
    val configured: Boolean,
    val source: String? = null,
    val writable: Boolean,
)

@Serializable
data class ProviderView(
    val id: String,
    val displayName: String,
    val active: Boolean,
    val declared: Boolean? = null,
    val configurable: Boolean,
    val configured: Boolean,
    val removable: Boolean,
    val credential: CredentialState,
    val config: ProviderConfig,
)

@Serializable
data class ProviderSettings(
    val writable: Boolean,
    val revisionByNamespace: Map<String, Int>,
    val customProvider: CustomProviderCapability = CustomProviderCapability(),
    val providers: List<ProviderView>,
)

@Serializable
data class CustomProviderCapability(
    val available: Boolean = false,
    val protocols: List<String> = emptyList(),
    val revision: Int? = null,
)

@Serializable
data class ProviderPatch(
    val displayName: String? = null,
    val baseURL: String? = null,
    val api: String? = null,
    val apiKey: String? = null,
    val thinking: String? = null,
    val reasoningEffort: String? = null,
    val models: List<ProviderModel>? = null,
    val expectedRevision: Int? = null,
)

@Serializable
data class CustomProviderCreate(
    val id: String,
    val displayName: String? = null,
    val baseURL: String,
    val api: String,
    val apiKey: String? = null,
    val models: List<ProviderModel>,
    val expectedRevision: Int? = null,
)

@Serializable
data class SessionEvent(
    val type: String,
    val seq: Int,
    val time: Long,
    val data: JsonElement,
    val sourceEventSeqs: List<Int> = emptyList(),
    val surfaceOp: JsonElement? = null,
)

/**
 * 官方 `dsh-token-meter` 的 usage-projection 折叠结果。
 *
 * 数据全部来自真实会话事件：`request/context` 提供 `contextWindow`，
 * `assistant/message` / `assistant/attempt` 的 `usage` 提供压力 token
 * （官方公式 `pressureFrom = inputTokens + cacheReadTokens + cacheWriteTokens`）。
 */
data class TokenUsageView(val usedTokens: Long, val contextWindow: Long) {
    /** 官方 conversation 的 `Math.min(100, Math.round(used / window * 100))`。 */
    val percent: Int
        get() = if (contextWindow <= 0L) 0 else ((usedTokens * 100L) / contextWindow).toInt().coerceIn(0, 100)
}

/** 事件里的 usage 样本：`assistant/message` 直接带，流式 attempt 取 stream 里最后一个。 */
internal fun usageOf(eventType: String, data: JsonObject): JsonObject? {
    if (eventType == "assistant/message") {
        (data["usage"] as? JsonObject)?.let { return it }
    }
    if (eventType != "assistant/message" && eventType != "assistant/attempt") return null
    val stream = data["stream"] as? JsonArray ?: return null
    for (index in stream.indices.reversed()) {
        val chunk = stream[index] as? JsonObject ?: continue
        (chunk["usage"] as? JsonObject)?.let { return it }
    }
    return null
}

/** 读取当前会话真实 token 占用；数据不足时返回 null（不猜测）。 */
fun ChatHistory?.tokenUsage(): TokenUsageView? {
    val entries = this?.events ?: return null
    var contextWindow: Long? = null
    var pressure: Long? = null
    for (entry in entries) {
        val event = entry.event
        val data = event.data as? JsonObject ?: continue
        if (event.type == "request/context") {
            data["contextWindow"]?.jsonPrimitive?.longOrNull?.let { contextWindow = it }
        }
        val usage = usageOf(event.type, data) ?: continue
        val input = usage["inputTokens"]?.jsonPrimitive?.longOrNull ?: continue
        val cacheRead = usage["cacheReadTokens"]?.jsonPrimitive?.longOrNull ?: 0L
        val cacheWrite = usage["cacheWriteTokens"]?.jsonPrimitive?.longOrNull ?: 0L
        pressure = input + cacheRead + cacheWrite
    }
    val used = pressure ?: return null
    val window = contextWindow ?: return null
    return TokenUsageView(usedTokens = used, contextWindow = window)
}

/**
 * 真实历史/事件能够推导出的官方会话底部运行统计。
 * 缺失字段保持 null，UI 应显示破折号而不是虚假的 0。
 */
data class ConversationStats(
    val rounds: Int? = null,
    val steps: Int? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val cacheHitPercent: Int? = null,
    val tokensPerSecond: Double? = null,
    val contextPercent: Int? = null,
    val inputTokens: Long? = null,
    val provider: String? = null,
    val model: String? = null,
    val tokensEstimated: Boolean = false,
    val usageIncomplete: Boolean = false,
    val speedEstimated: Boolean = false,
    val contextEstimated: Boolean = false,
    val contextTokens: Long? = null,
    val contextLimit: Long? = null,
    /** Display-only current request counters; never serialized as provider usage. */
    val liveTotalTokens: Long? = null,
    val liveOutputTokens: Long? = null,
)

fun ChatHistory?.conversationStats(): ConversationStats? {
    val allEntries = this?.events ?: return null
    val lastUser = allEntries.indexOfLast { entry ->
        entry.event.type == "user/message" &&
            (((entry.event.data as? JsonObject)?.get("source") as? JsonObject)?.get("kind") as? JsonPrimitive)?.contentOrNull == "user"
    }
    // Counts displayed on the latest reply must never leak in from an older turn.
    val entries = if (lastUser >= 0) allEntries.drop(lastUser) else allEntries
    if (entries.isEmpty()) return null
    val turnSteps = entries.mapNotNull { entry ->
        val data = entry.event.data as? JsonObject ?: return@mapNotNull null
        val turn = data["turn"]?.jsonPrimitive?.content?.toIntOrNull()
        val step = data["step"]?.jsonPrimitive?.content?.toIntOrNull()
        if (turn != null && step != null) turn to step else null
    }.toSet()
    val rounds = entries.mapNotNull { entry ->
        val data = entry.event.data as? JsonObject ?: return@mapNotNull null
        data["turn"]?.jsonPrimitive?.content?.toIntOrNull()
    }.maxOrNull()?.takeIf { it > 0 }
    var output: Long? = null
    var total: Long? = null
    var cacheRead: Long? = null
    var cacheWrite: Long? = null
    var input: Long? = null
    var provider: String? = null
    var model: String? = null
    entries.forEach { entry ->
        val data = entry.event.data as? JsonObject ?: return@forEach
        val usage = usageOf(entry.event.type, data) ?: return@forEach
        fun number(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
            usage[key]?.jsonPrimitive?.longOrNull
        }
        number("inputTokens", "promptTokens")?.let { input = it }
        (data["provider"] as? JsonPrimitive)?.contentOrNull?.let { provider = it }
        (data["model"] as? JsonPrimitive)?.contentOrNull?.let { model = it }
        number("outputTokens", "completionTokens")?.let { output = it }
        number("totalTokens")?.let { total = it }
        number("cacheReadTokens")?.let { cacheRead = it }
        number("cacheWriteTokens")?.let { cacheWrite = it }
    }
    val denominator = input
    val cachePercent = if (cacheRead != null && denominator != null && denominator > 0L) {
        ((cacheRead * 100L) / denominator).toInt().coerceIn(0, 100)
    } else null
    val context = tokenUsage()?.percent
    // Whole-turn timestamps include network/tool/wait time, not measured output generation.
    val speed: Double? = null
    return ConversationStats(
        rounds = rounds,
        steps = turnSteps.size.takeIf { it > 0 },
        outputTokens = output,
        totalTokens = total,
        cacheReadTokens = cacheRead,
        cacheWriteTokens = cacheWrite,
        cacheHitPercent = cachePercent,
        tokensPerSecond = speed,
        contextPercent = context,
        inputTokens = input, provider = provider, model = model,
    )
}

/** Whole loaded conversation. Only committed messages count; streamed attempts are previews.
 * A provider request is identified by (turn, step) when supplied, otherwise event seq.
 * With older history still unloaded, aggregate totals are deliberately unknown.
 */
fun ChatHistory?.sessionStats(): ConversationStats? {
    val history = this ?: return null
    val committed = linkedMapOf<String, JsonObject>()
    history.events.forEach { entry ->
        if (entry.event.type != "assistant/message") return@forEach
        val data = entry.event.data as? JsonObject ?: return@forEach
        val key = if (data["turn"] != null && data["step"] != null)
            "${data["turn"]}:${data["step"]}" else "seq:${entry.event.seq}"
        committed[key] = data
    }
    val samples = committed.values.map { it["usage"] as? JsonObject }
    fun sum(key: String): Long? {
        if (history.hasMore || samples.isEmpty()) return null
        val numbers = samples.map { (it?.get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 } }
        return if (numbers.any { it == null }) null else numbers.sumOf { it!! }
    }
    val input = sum("inputTokens")
    val read = sum("cacheReadTokens")
    val write = sum("cacheWriteTokens")
    // Server inputTokens excludes cached input, unlike local normalized prompt tokens.
    val prompt = if (input != null && read != null && write != null) input + read + write else null
    return ConversationStats(
        rounds = if (history.hasMore) null else history.events.count { entry ->
            entry.event.type == "user/message" &&
                (((entry.event.data as? JsonObject)?.get("source") as? JsonObject)?.get("kind") as? JsonPrimitive)?.contentOrNull == "user"
        },
        steps = history.events.count { it.event.type == "tool/result" }.takeIf { !history.hasMore && it > 0 },
        totalTokens = sum("totalTokens"), outputTokens = sum("outputTokens"), inputTokens = prompt,
        cacheReadTokens = read, cacheWriteTokens = write,
        cacheHitPercent = if (prompt != null && prompt > 0 && read != null)
            (read * 100 / prompt).toInt().coerceIn(0, 100) else null,
        contextPercent = tokenUsage()?.takeIf { it.contextWindow > 0 }?.percent,
        // No output-only timing field is present in the protocol; whole-turn time includes tools.
        tokensPerSecond = null,
    )
}

@Serializable
data class HistoryEntry(val event: SessionEvent, val view: JsonElement? = null)

@Serializable
data class ChatHistory(
    val events: List<HistoryEntry>,
    val hasMore: Boolean,
    val projections: JsonElement? = null,
)

@Serializable
data class WorkspaceEvent(val id: String, val type: String, val time: Long, val data: JsonElement)

/** One option of an interactive "ask the user" question card. */
@Serializable
data class UserQuestionOption(val label: String, val description: String? = null)

/** One question of an `ask_user_question` tool call awaiting the human. */
@Serializable
data class UserQuestionItem(
    val id: String,
    val question: String,
    val detail: String? = null,
    val header: String? = null,
    val options: List<UserQuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
)

/** A live question call the desktop is waiting on (forwarded through the native bridge). */
@Serializable
data class PendingQuestion(
    val sessionId: String = "",
    val questionId: String,
    val callId: String? = null,
    val questions: List<UserQuestionItem> = emptyList(),
)

@Serializable
data class PendingQuestionList(val items: List<PendingQuestion> = emptyList())

@Serializable
data class UserQuestionAnswerItem(val id: String, val selected: List<String> = emptyList(), val custom: String? = null)

@Serializable
data class UserQuestionAnswerBatch(val answers: List<UserQuestionAnswerItem>)

data class FileContent(val bytes: ByteArray, val etag: String, val contentType: String?)


class DshApiException(
    val status: Int,
    val code: String,
    override val message: String,
) : RuntimeException(message)
