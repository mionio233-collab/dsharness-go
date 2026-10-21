package io.github.hakunm.deepseekharness.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.github.hakunm.deepseekharness.data.ProcessEntry
import io.github.hakunm.deepseekharness.data.reasoningProcessEntries
import io.github.hakunm.deepseekharness.data.toolProcessEntry

/** Display-only metadata: never included in model request fields. Null means unreported. */
@Serializable
data class ReplyUsage(
    val total: Long? = null,
    val input: Long? = null,
    val output: Long? = null,
    val cacheRead: Long? = null,
    val cacheWrite: Long? = null,
    val uncachedInput: Long? = null,
    val provider: String? = null,
    val model: String? = null,
    /** Known actual counts only; later unreported work is excluded. */
    val incomplete: Boolean = false,
    val localFreshContext: Boolean = false,
    /** Request wall time includes loading/prefill; generation time starts before sampling. */
    val requestMillis: Long? = null,
    val promptMillis: Long? = null,
    val generationMillis: Long? = null,
    val loadMillis: Long? = null,
    val localBackend: String? = null,
    val localFallbackReason: String? = null,
    val localCacheDisabledReason: String? = null,
) {
    val cacheHitPercent: Int? get() = if (!incomplete && input != null && input > 0 && cacheRead != null)
        (cacheRead * 100 / input).toInt().coerceIn(0, 100) else null
}

internal fun responseUsageDetails(root: JsonObject, anthropic: Boolean): ReplyUsage {
    val usage = root["usage"] as? JsonObject ?: return ReplyUsage(model = (root["model"] as? JsonPrimitive)?.contentOrNull)
    fun count(key: String) = (usage[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    val output = count(if (anthropic) "output_tokens" else "completion_tokens")
    val read = if (anthropic) count("cache_read_input_tokens") else
        count("prompt_cache_hit_tokens") ?: ((usage["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    val write = if (anthropic) count("cache_creation_input_tokens") else count("cache_creation_input_tokens")
    val rawInput = count(if (anthropic) "input_tokens" else "prompt_tokens")
    val input = if (anthropic) rawInput?.let { it + (read ?: 0) + (write ?: 0) } else rawInput
    return ReplyUsage(
        total = responseUsage(root, anthropic), input = input, output = output,
        cacheRead = read, cacheWrite = write,
        uncachedInput = if (anthropic) rawInput else count("prompt_cache_miss_tokens") ?: if (input != null && read != null) (input - read).takeIf { it >= 0 } else null,
        model = (root["model"] as? JsonPrimitive)?.contentOrNull,
    )
}

/** Each completed HTTP round contributes exactly once, not once per tool or display record. */
internal class ReplyUsageAccumulator {
    private val rounds = mutableListOf<ReplyUsage>()
    fun add(usage: ReplyUsage) { rounds += usage }
    /** A partial is a replaceable round snapshot, never added to completed rounds. */
    fun checkpoint(provider: String?, model: String?, partial: ReplyUsage? = null, pending: Boolean = false): ReplyUsage {
        val known = rounds + listOfNotNull(partial)
        fun sum(field: (ReplyUsage) -> Long?): Long? = known.mapNotNull(field).takeIf { it.isNotEmpty() }?.sum()
        return ReplyUsage(sum { it.total }, sum { it.input }, sum { it.output }, sum { it.cacheRead },
            sum { it.cacheWrite }, sum { it.uncachedInput }, provider, known.lastOrNull { it.model != null }?.model ?: model,
            incomplete = pending || known.any { it.total == null || it.incomplete })
    }
    fun snapshot(provider: String?, model: String?): ReplyUsage {
        fun sum(field: (ReplyUsage) -> Long?): Long? = if (rounds.isEmpty() || rounds.any { field(it) == null }) null else rounds.sumOf { field(it)!! }
        return ReplyUsage(sum { it.total }, sum { it.input }, sum { it.output }, sum { it.cacheRead },
            sum { it.cacheWrite }, sum { it.uncachedInput }, provider, rounds.lastOrNull()?.model ?: model)
    }
}

/** Sum only the last snapshot of each ended user turn (including stopped/failed turns).
 * Intermediate assistants carry cumulative per-turn usage and must not be added again.
 * Local provider configuration has model IDs only: no measured window or output duration.
 */
fun List<LocalMessage>.localConversationStats(busy: Boolean = false): io.github.hakunm.deepseekharness.data.ConversationStats {
    val finals = mutableListOf<ReplyUsage>()
    var cursor = 0
    var turns = 0
    while (cursor < size) {
        if (this[cursor].role != "user") { cursor++; continue }
        turns++
        val start = ++cursor
        while (cursor < size && this[cursor].role != "user") cursor++
        val records = subList(start, cursor)
        val last = records.lastOrNull { it.role == "assistant" }
        val measured = records.lastOrNull { it.role == "assistant" && (it.usage != null || it.usageTokens != null) }
        if (last != null && !(busy && cursor == size)) {
            finals += measured?.usage ?: ReplyUsage(total = measured?.usageTokens, incomplete = last.interrupted)
        }
    }
    fun sum(field: (ReplyUsage) -> Long?): Long? =
        if (finals.any { field(it) == null && !it.incomplete }) null
        else finals.mapNotNull(field).takeIf { it.isNotEmpty() }?.sum()
    val input = sum { it.input }
    val read = sum { it.cacheRead }
    val output = sum { it.output }
    val latestTurn = drop(indexOfLast { it.role == "user" }.coerceAtLeast(0))
    val latestReply = latestTurn.lastOrNull { it.role == "assistant" }
    val elapsed = latestReply?.elapsedMillis
    val latestOutput = latestReply?.usage?.output
    val speed = if (!busy && latestOutput != null && elapsed != null && elapsed > 0)
        latestOutput * 1000.0 / elapsed else null
    return io.github.hakunm.deepseekharness.data.ConversationStats(
        rounds = turns, steps = count { it.role == "tool" },
        totalTokens = sum { it.total }, inputTokens = input, outputTokens = output,
        cacheReadTokens = read, cacheWriteTokens = sum { it.cacheWrite },
        cacheHitPercent = if (finals.none { it.incomplete } && input != null && input > 0 && read != null)
            (read * 100 / input).toInt().coerceIn(0, 100) else null,
        tokensPerSecond = speed,
        provider = finals.lastOrNull()?.provider,
        model = finals.lastOrNull()?.model,
        usageIncomplete = finals.any { it.incomplete },
    )
}

data class LocalDisplayItem(
    val index: Int,
    val message: LocalMessage,
    val process: String = "",
    val processEntries: List<ProcessEntry> = emptyList(),
    val streaming: Boolean = false,
    val latestReasoning: String? = null,
    val actionsVisible: Boolean = true,
) {
    /** User boundary + role stays constant as assistants and tool results arrive. */
    val key: String get() = "local:$index:${message.role}"
}

/** Group by user turn without rewriting assistant tool_calls or tool results in transport history. */
fun List<LocalMessage>.localDisplayItems(
    busy: Boolean = false,
    activeTurnIndex: Int? = if (busy) indexOfLast { it.role == "user" }.takeIf { it >= 0 } else null,
): List<LocalDisplayItem> {
    val result = mutableListOf<LocalDisplayItem>()
    var cursor = 0
    while (cursor < size) {
        val userIndex = cursor
        val user = this[cursor]
        if (user.role != "user") { cursor++; continue }
        val actionsVisible = userIndex != activeTurnIndex
        result += LocalDisplayItem(cursor, user, actionsVisible = actionsVisible)
        cursor++
        val start = cursor
        while (cursor < size && this[cursor].role != "user") cursor++
        val records = subList(start, cursor)
        val streaming = busy && userIndex == activeTurnIndex
        if (records.isEmpty() && !streaming) continue
        val lastAssistant = (start until cursor).lastOrNull { this[it].role == "assistant" }
        val publicContent = records.filter { it.role == "assistant" }
            .map { it.content }.filter { it.isNotBlank() }.joinToString("\n\n")
        val entries = mutableListOf<ProcessEntry>()
        val process = buildList {
            records.forEach { message ->
                if (message.role == "assistant") {
                    message.reasoningContent?.takeIf { it.isNotBlank() }?.let {
                        add(it)
                        entries += reasoningProcessEntries(it)
                    }
                    message.toolCallsJson?.let { raw ->
                        val calls = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull()
                        calls?.forEach { call ->
                            val obj = call.jsonObject
                            val function = obj["function"]?.jsonObject
                            val id = obj["id"]?.jsonPrimitive?.contentOrNull
                            val name = function?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
                            val arguments = function?.get("arguments")?.jsonPrimitive?.contentOrNull
                            val output = records.firstOrNull { it.role == "tool" && it.toolCallId == id }?.content
                            entries += toolProcessEntry(name, arguments)
                            add("$name · ${if (output == null) "执行中" else "完成"}" +
                                if (output == null) "" else "\n${output.take(2000)}")
                        }
                    }
                }
            }
            if (streaming) add("正在处理…")
        }.joinToString("\n\n")
        val metadata = lastAssistant?.let { this[it] }
        val measured = records.lastOrNull { it.role == "assistant" && (it.usage != null || it.usageTokens != null) }
        // Public progress remains public across rounds. Reasoning only belongs to process.
        // This aggregation is display-only; it never changes request history or tool chronology.
        val main = (metadata ?: LocalMessage("assistant", "")).copy(
                content = publicContent,
                usage = metadata?.usage ?: measured?.usage,
                usageTokens = metadata?.usageTokens ?: measured?.usageTokens,
                contextInputTokens = metadata?.contextInputTokens ?: measured?.contextInputTokens,
                toolCallsJson = null,
                reasoningContent = null,
                attachments = records.filter { it.role == "assistant" }.flatMap { it.attachments }.distinctBy { it.localPath })
        // The key is tied to the user boundary, not the changing last assistant record.
        val latestReasoning = records.lastOrNull { it.role == "assistant" }?.reasoningContent
        result += LocalDisplayItem(
            userIndex,
            main,
            process,
            processEntries = entries.takeLast(80),
            streaming = streaming,
            latestReasoning = latestReasoning,
            actionsVisible = actionsVisible,
        )
    }
    return result
}
