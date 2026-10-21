package io.github.hakunm.deepseekharness.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

enum class ChatItemKind { USER, ASSISTANT, REASONING, CONTEXT, TOOL }

data class RemoteDeliveredFile(
    val path: String,
    val description: String? = null,
)

data class ChatDisplayItem(
    val id: String,
    val seq: Int,
    val kind: ChatItemKind,
    val title: String? = null,
    val body: String = "",
    val reasoningBody: String? = null,
    val streaming: Boolean = false,
    val timestampMillis: Long? = null,
    val usageTokens: Long? = null,
    /** Desktop-compatible aggregate for the entire completed turn. */
    val turnUsage: TurnTokenUsage? = null,
    /** Files declared by the turn's present tool and rendered as delivery cards. */
    val deliveredFiles: List<RemoteDeliveredFile> = emptyList(),
    /** Structured rows shown when the process disclosure is expanded. */
    val processEntries: List<ProcessEntry> = emptyList(),
    val actionsVisible: Boolean = true,
    /** Wall-clock duration of the owning turn (turn/start → turn/end event times). */
    val turnElapsedMillis: Long? = null,
    /** completed / stopped / failed, from the turn/end reason. */
    val turnStatus: String? = null,
    /** Completion wall-clock time of the owning turn. */
    val turnCompletedAt: Long? = null,
)

data class ChatDelta(
    val sessionId: String,
    val turn: Int,
    val step: Int,
    val index: Int,
    val kind: ChatItemKind,
    val text: String,
)

data class LiveChatBlock(val index: Int, val kind: ChatItemKind, val text: String)

data class LiveChatState(
    val sessionId: String,
    val turn: Int,
    val step: Int,
    val blocks: List<LiveChatBlock> = emptyList(),
    val processEntries: List<ProcessEntry> = emptyList(),
    val revision: Long = 0,
) {
    fun append(delta: ChatDelta): LiveChatState {
        val base = if (sessionId == delta.sessionId && turn == delta.turn && step == delta.step) this else {
            LiveChatState(delta.sessionId, delta.turn, delta.step)
        }
        val existing = base.blocks.indexOfFirst { it.index == delta.index }
        val next = if (existing < 0) {
            base.blocks + LiveChatBlock(delta.index, delta.kind, delta.text)
        } else {
            base.blocks.toMutableList().also { blocks ->
                val previous = blocks[existing]
                blocks[existing] = LiveChatBlock(
                    delta.index,
                    delta.kind,
                    if (previous.kind == delta.kind) previous.text + delta.text else delta.text,
                )
            }
        }
        return base.copy(blocks = next.sortedBy(LiveChatBlock::index), revision = base.revision + 1)
    }

    fun appendAction(sessionId: String, turn: Int, step: Int, entry: ProcessEntry): LiveChatState {
        val base = if (this.sessionId == sessionId && this.turn == turn && this.step == step) this
        else LiveChatState(sessionId, turn, step)
        val reasoning = base.blocks.filter { it.kind == ChatItemKind.REASONING }
            .joinToString("\n\n") { it.text }
        val latestThought = reasoningProcessEntries(reasoning).lastOrNull()
        val existing = base.processEntries
        val withThought = if (latestThought != null && existing.lastOrNull() != latestThought)
            existing + latestThought else existing
        return base.copy(
            processEntries = (withThought + entry).distinct().takeLast(80),
            revision = base.revision + 1,
        )
    }

    fun displayItems(): List<ChatDisplayItem> {
        val reasoning = blocks.filter { it.kind == ChatItemKind.REASONING }
            .joinToString("\n\n") { it.text }
            .takeIf(String::isNotBlank)
        val text = blocks.filter { it.kind == ChatItemKind.ASSISTANT }
            .joinToString("\n\n") { it.text }
        return if (text.isNotBlank() || reasoning != null) {
            listOf(ChatDisplayItem(
                id = "live-$turn-$step-assistant",
                seq = Int.MAX_VALUE,
                kind = ChatItemKind.ASSISTANT,
                body = text,
                reasoningBody = reasoning,
                processEntries = processEntries,
                streaming = true,
                actionsVisible = false,
            ))
        } else emptyList()
    }
}

fun WorkspaceEvent.chatDelta(): ChatDelta? {
    if (type != "chat.message.delta") return null
    val value = data as? JsonObject ?: return null
    val kind = when (value.stringValue("kind")) {
        "text" -> ChatItemKind.ASSISTANT
        "reasoning" -> ChatItemKind.REASONING
        else -> return null
    }
    return ChatDelta(
        sessionId = value.stringValue("sessionId") ?: return null,
        turn = value.intValue("turn") ?: return null,
        step = value.intValue("step") ?: return null,
        index = value.intValue("index") ?: return null,
        kind = kind,
        text = value.stringValue("text") ?: return null,
    )
}

/** Turn boundaries remain active across committed assistant steps and tool execution. */
fun ChatHistory.displayItems(): List<ChatDisplayItem> {
    val toolNamesByCallId = events.mapNotNull { entry ->
        if (entry.event.type != "tool/call") return@mapNotNull null
        val data = entry.event.data as? JsonObject ?: return@mapNotNull null
        val callId = data.stringValue("callId") ?: return@mapNotNull null
        callId to data.stringValue("name").orEmpty()
    }.toMap()
    val active = mutableMapOf<Int, MutableSet<Int>>()
    val pending = mutableSetOf<Int>()
    var currentTurn: Int? = null
    val turnStarts = mutableMapOf<Int, Long>()
    val turnEnds = mutableMapOf<Int, Pair<Long, String>>()
    val itemTurns = mutableMapOf<Int, Int>()
    val delivered = mutableMapOf<Int, List<RemoteDeliveredFile>>()
    val items = events.flatMap { entry ->
        val turn = (entry.event.data as? JsonObject)?.intValue("turn")
        when (entry.event.type) {
            "turn/start" -> if (turn != null) {
                currentTurn = turn
                if (entry.event.time > 0) turnStarts[turn] = entry.event.time
                active.getOrPut(turn) { mutableSetOf() }.addAll(pending)
                pending.clear()
            }
            "turn/end" -> {
                if (turn != null && entry.event.time > 0) turnEnds[turn] = entry.event.time to turnEndStatus(entry.event)
                active.remove(turn ?: currentTurn)
                if (turn == null || currentTurn == turn) currentTurn = null
            }
            "deliverables/presented" -> {
                val owner = turn ?: currentTurn
                val files = deliveredFiles(entry.event)
                if (owner != null && files.isNotEmpty()) delivered[owner] = files
            }
        }
        displayItems(entry, toolNamesByCallId).also { displayed ->
            displayed.filter { it.kind == ChatItemKind.USER || it.kind == ChatItemKind.ASSISTANT }.forEach { item ->
                val owner = turn ?: currentTurn
                when {
                    owner != null && owner in active -> active.getValue(owner).add(item.seq)
                    item.kind == ChatItemKind.USER && owner == null -> pending.add(item.seq)
                }
                owner?.let { itemTurns[item.seq] = it }
            }
        }
    }
    val unfinished = active.values.flatten().toSet() + pending
    // Attach the finished turn's duration/outcome to its last assistant item, so the
    // online conversation shows the same "已完成，用时…" summary as local mode.
    val completions = mutableMapOf<Int, Triple<Long, String, Long>>()
    turnEnds.forEach { (turn, end) ->
        val start = turnStarts[turn] ?: return@forEach
        if (end.first <= start) return@forEach
        // Official TurnProcessNodeView puts the summary on the turn's process row; when a
        // turn has no reasoning block the assistant bubble carries it instead.
        val turnItems = items.filter { itemTurns[it.seq] == turn }
        val target = turnItems.lastOrNull { it.kind == ChatItemKind.REASONING }
            ?: turnItems.lastOrNull { it.kind == ChatItemKind.ASSISTANT }
            ?: return@forEach
        completions[target.seq] = Triple((end.first - start).coerceAtLeast(1_000L), end.second, end.first)
    }
    val turnUsages = turnTokenUsages()
    val usageTargets = turnUsages.mapNotNull { (turn, usage) ->
        items.filter { itemTurns[it.seq] == turn }
            .lastOrNull { it.kind == ChatItemKind.ASSISTANT }
            ?.seq?.let { it to usage }
    }.toMap()
    val deliveryTargets = delivered.mapNotNull { (turn, files) ->
        items.filter { itemTurns[it.seq] == turn }
            .lastOrNull { it.kind == ChatItemKind.ASSISTANT }
            ?.seq?.let { it to files }
    }.toMap()
    // Keep the turn's thinking below all of its assistant text. Earlier steps therefore hand
    // their reasoning to the turn's final assistant bubble instead of interleaving a collapsed
    // row between paragraphs.
    val reasoningByTurn = mutableMapOf<Int, MutableList<String>>()
    val lastAssistantByTurn = mutableMapOf<Int, Int>()
    items.forEach { item ->
        val turn = itemTurns[item.seq] ?: return@forEach
        if (item.kind != ChatItemKind.ASSISTANT) return@forEach
        lastAssistantByTurn[turn] = item.seq
        item.reasoningBody?.takeIf { it.isNotBlank() }?.let { reasoningByTurn.getOrPut(turn) { mutableListOf() }.add(it) }
    }
    return items.map { item ->
        val completion = completions[item.seq]
        val turnUsage = usageTargets[item.seq]
        val turn = itemTurns[item.seq]
        val reasoningBody = if (item.kind == ChatItemKind.ASSISTANT && turn != null) {
            if (lastAssistantByTurn[turn] == item.seq) reasoningByTurn[turn]?.joinToString("\n\n") else null
        } else {
            item.reasoningBody
        }
        // A turn-level aggregate (desktop parity) always wins; only items that cannot be tied
        // to a turn keep the assistant message's own usage value.
        val usageTokens = if (itemTurns.containsKey(item.seq)) turnUsage?.totalTokens else item.usageTokens
        item.copy(
            reasoningBody = reasoningBody,
            actionsVisible = item.seq !in unfinished,
            usageTokens = usageTokens,
            turnUsage = turnUsage,
            deliveredFiles = deliveryTargets[item.seq].orEmpty(),
            turnElapsedMillis = completion?.first,
            turnStatus = completion?.second,
            turnCompletedAt = completion?.third,
        )
    }
}

private fun deliveredFiles(event: SessionEvent): List<RemoteDeliveredFile> {
    val files = (event.data as? JsonObject)?.get("files") as? JsonArray ?: return emptyList()
    return files.mapNotNull { raw ->
        val file = raw as? JsonObject ?: return@mapNotNull null
        val path = file.stringValue("path")?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        RemoteDeliveredFile(path, file.stringValue("description")?.trim()?.takeIf(String::isNotBlank))
    }.distinctBy { it.path }
}

/** Map a turn/end reason to the completed / stopped / failed summary official UI shows. */
private fun turnEndStatus(event: SessionEvent): String {
    val data = event.data as? JsonObject
    val kind = data?.objectValue("reason")?.stringValue("kind") ?: data?.stringValue("kind")
    return when (kind) {
        "aborted", "interrupted", "cancelled", "canceled" -> "stopped"
        "error", "failed" -> "failed"
        else -> "completed"
    }
}

fun ChatHistory.todoItems(): List<TodoItem> {
    val values = (projections as? JsonObject)?.objectValue("values") ?: return emptyList()
    return (values["todos"] as? JsonArray).orEmpty().mapNotNull { raw ->
        val item = raw as? JsonObject ?: return@mapNotNull null
        val content = item.stringValue("content")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val status = item.stringValue("status") ?: return@mapNotNull null
        TodoItem(content, status)
    }
}

fun ChatHistory.permissionSelect(): PermissionSelect? {
    val values = (projections as? JsonObject)?.objectValue("values") ?: return null
    val raw = values.objectValue("permissions") ?: return null
    val currentValue = raw.stringValue("currentValue") ?: return null
    val options = (raw["options"] as? JsonArray).orEmpty().mapNotNull { entry ->
        val option = entry as? JsonObject ?: return@mapNotNull null
        val value = option.stringValue("value") ?: return@mapNotNull null
        val name = option.stringValue("name") ?: return@mapNotNull null
        PermissionOption(value, name, option.stringValue("description"))
    }
    return PermissionSelect(options, currentValue)
}

private fun displayItems(
    entry: HistoryEntry,
    toolNamesByCallId: Map<String, String>,
): List<ChatDisplayItem> {
    val items = when (entry.event.type) {
        "user/message" -> userItems(entry.event)
        "assistant/message" -> assistantItems(entry.event)
        "tool/call" -> listOf(toolItem(entry))
        "tool/result" -> toolResultErrorItem(entry, toolNamesByCallId)
        else -> emptyList()
    }
    return items.map { item -> item.copy(
        timestampMillis = entry.event.time.takeIf { it > 0 },
    ) }
}

private fun userItems(event: SessionEvent): List<ChatDisplayItem> {
    val data = event.data as? JsonObject ?: return emptyList()
    val source = data.objectValue("source")
    val kind = source?.stringValue("kind")
    val body = contentText(data["content"])
    if (kind == "user") {
        return body.takeIf(String::isNotBlank)?.let {
            listOf(ChatDisplayItem("${event.seq}-user", event.seq, ChatItemKind.USER, body = it))
        }.orEmpty()
    }
    return listOf(ChatDisplayItem(
        id = "${event.seq}-context",
        seq = event.seq,
        kind = ChatItemKind.CONTEXT,
        title = contextLabel(source),
        body = body,
    ))
}

private fun assistantItems(event: SessionEvent): List<ChatDisplayItem> {
    val outer = event.data as? JsonObject ?: return emptyList()
    val message = outer.objectValue("message") ?: outer
    // Assistant message events carry their own usage; a turn-level aggregate is preferred when
    // available, but single-step messages must keep this value (used by the desktop parity UI).
    val messageUsage = (outer.objectValue("usage") ?: message.objectValue("usage"))
        ?.stringValue("totalTokens")?.toLongOrNull()
    val content = message["content"]
    val blocks = content as? JsonArray ?: return contentText(content).takeIf(String::isNotBlank)?.let {
        listOf(ChatDisplayItem("${event.seq}-assistant", event.seq, ChatItemKind.ASSISTANT, body = it, usageTokens = messageUsage))
    }.orEmpty()
    val prose = mutableListOf<String>()
    val reasoning = mutableListOf<String>()
    for (block in blocks) {
        val value = block as? JsonObject ?: continue
        when (value.stringValue("type")) {
            "text" -> value.stringValue("text")?.takeIf(String::isNotBlank)?.let(prose::add)
            "image" -> imageMarkdown(value)?.let(prose::add)
            "reasoning" -> value.stringValue("text")?.takeIf(String::isNotBlank)?.let(reasoning::add)
        }
    }
    val body = prose.joinToString("\n\n")
    val thinking = reasoning.joinToString("\n\n").takeIf(String::isNotBlank)
    return if (body.isNotBlank() || thinking != null) listOf(
        ChatDisplayItem(
            id = "${event.seq}-assistant",
            seq = event.seq,
            kind = ChatItemKind.ASSISTANT,
            body = body,
            reasoningBody = thinking,
            usageTokens = messageUsage,
        ),
    ) else emptyList()
}

private fun toolItem(entry: HistoryEntry): ChatDisplayItem {
    val event = entry.event
    val data = event.data as? JsonObject
    val name = data?.stringValue("name").orEmpty()
    val view = (entry.view as? JsonObject)?.objectValue("view")
    val viewTitle = view?.stringValue("title")
    val description = view?.stringValue("description")
    val arguments = data?.stringValue("arguments")
    return ChatDisplayItem(
        id = "${event.seq}-tool",
        seq = event.seq,
        kind = ChatItemKind.TOOL,
        title = toolTitle(name),
        body = description ?: argumentsSummary(arguments) ?: viewTitle ?: name,
        processEntries = listOf(toolProcessEntry(name, description ?: argumentsSummary(arguments) ?: viewTitle)),
    )
}

private fun toolResultErrorItem(
    entry: HistoryEntry,
    toolNamesByCallId: Map<String, String>,
): List<ChatDisplayItem> {
    val data = entry.event.data as? JsonObject ?: return emptyList()
    val message = data.objectValue("message") ?: data
    val isError = (message["isError"] as? JsonPrimitive)?.booleanOrNull == true
    if (!isError) return emptyList()
    val callId = message.stringValue("toolCallId") ?: data.stringValue("callId")
    val name = callId?.let(toolNamesByCallId::get).orEmpty().ifBlank { "tool" }
    val detail = contentText(message["content"]).ifBlank { "Tool call failed" }
    return listOf(ChatDisplayItem(
        id = "${entry.event.seq}-tool-error",
        seq = entry.event.seq,
        kind = ChatItemKind.TOOL,
        title = toolTitle(name),
        body = detail,
        processEntries = listOf(toolProcessEntry(name, detail, isError = true)),
    ))
}

private fun contentText(content: JsonElement?): String = when (content) {
    is JsonPrimitive -> content.contentOrNull.orEmpty()
    is JsonArray -> content.mapNotNull { block ->
        val value = block as? JsonObject ?: return@mapNotNull null
        when (value.stringValue("type")) {
            "text", "reasoning" -> value.stringValue("text")
            "image" -> imageMarkdown(value)
            else -> null
        }
    }.filter(String::isNotBlank).joinToString("\n\n")
    else -> ""
}

private fun imageMarkdown(block: JsonObject): String? {
    val ref = block.objectValue("attachment") ?: return block.stringValue("url")?.let { "![image]($it)" }
    val id = ref.stringValue("attachmentId")?.takeIf { Regex("[a-f0-9]{64}").matches(it) } ?: return null
    val ext = when (ref.stringValue("mediaType")) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "png" }
    return "![image](attachment:$id.$ext)"
}

private fun contextLabel(source: JsonObject?): String? {
    source ?: return null
    return when (val kind = source.stringValue("kind")) {
        "agent-instructions" -> source.arrayLabels("changes", "path").ifEmpty { null }
        "session-reference" -> source.arrayLabels("references", "label").ifEmpty { null }
        "plugin" -> source.stringValue("plugin") ?: kind
        "skill-invocation" -> source.stringValue("name") ?: kind
        else -> kind
    }
}

private fun toolTitle(name: String): String = when {
    name == "bash" -> "Bash"
    name == "pwsh" -> "Pwsh"
    "skill" in name.lowercase() -> "Skill"
    name == "read" || name == "web_fetch" -> "Read"
    name == "grep" || name == "glob" || name == "web_search" -> "Search"
    name == "write" -> "Write"
    name == "edit" -> "Edit"
    name == "run_code" -> "Code"
    else -> "Tool call"
}

private fun argumentsSummary(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val parsed = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    for (key in listOf("description", "path", "file_path", "query", "pattern", "url", "command", "name", "package")) {
        parsed.stringValue(key)?.takeIf(String::isNotBlank)?.let { return it.lineSequence().first() }
    }
    return null
}

private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.stringValue(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.intValue(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.arrayLabels(member: String, field: String): String {
    val values = (this[member] as? JsonArray).orEmpty().mapNotNull { entry ->
        (entry as? JsonObject)?.stringValue(field)?.takeIf(String::isNotBlank)
    }.distinct()
    return values.joinToString(", ")
}

private val json = Json { ignoreUnknownKeys = true }
