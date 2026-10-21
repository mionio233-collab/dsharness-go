package io.github.hakunm.deepseekharness.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Exact provider usage for one completed turn. This is the same disclosure shown by
 * Desktop's TurnUsagePanel: every model attempt in the turn, including retries.
 */
data class TurnTokenUsage(
    val totalTokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val provider: String? = null,
    val model: String? = null,
)

internal fun ChatHistory.turnTokenUsages(): Map<Int, TurnTokenUsage> {
    val starts = mutableMapOf<Int, Int>()
    val result = mutableMapOf<Int, TurnTokenUsage>()
    events.forEachIndexed { index, entry ->
        val data = entry.event.data as? JsonObject ?: return@forEachIndexed
        val turn = data.intValue("turn") ?: return@forEachIndexed
        when (entry.event.type) {
            "turn/start" -> starts[turn] = index
            "turn/end" -> {
                val start = starts.remove(turn) ?: return@forEachIndexed
                foldTurnTokenUsage(events.subList(start, index + 1))?.let { result[turn] = it }
            }
        }
    }
    return result
}

private sealed interface AttemptState {
    data object Idle : AttemptState
    data class Open(
        val turn: Int,
        val step: Int,
        val sample: JsonObject? = null,
        val provider: String? = null,
        val model: String? = null,
    ) : AttemptState
    data class FinishClosed(val turn: Int, val step: Int) : AttemptState
    data class Settled(val turn: Int, val step: Int, val by: String) : AttemptState
}

private data class NormalizedAttempt(
    val totalTokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long?,
    val cacheWriteTokens: Long?,
    val provider: String?,
    val model: String?,
)

/** Mirrors @deepseek-ai/dsh-token-meter's deriveTurnTokenUsage lifecycle checks. */
private fun foldTurnTokenUsage(events: List<HistoryEntry>): TurnTokenUsage? {
    val attempts = mutableListOf<NormalizedAttempt>()
    var turn: Int? = null
    var sawEnd = false
    var invalid = false
    var state: AttemptState = AttemptState.Idle

    fun sameAttempt(value: AttemptState, candidateTurn: Int, candidateStep: Int): Boolean = when (value) {
        is AttemptState.Open -> value.turn == candidateTurn && value.step == candidateStep
        is AttemptState.FinishClosed -> value.turn == candidateTurn && value.step == candidateStep
        is AttemptState.Settled -> value.turn == candidateTurn && value.step == candidateStep
        AttemptState.Idle -> false
    }

    fun closeOpen(): Boolean {
        val open = state as? AttemptState.Open ?: return false
        val sample = open.sample ?: return false
        val normalized = normalizeAttempt(sample, open.provider, open.model) ?: return false
        attempts += normalized
        return true
    }

    for (entry in events) {
        if (invalid) break
        val event = entry.event
        val data = event.data as? JsonObject ?: run { invalid = true; break }
        if (event.type == "turn/start") {
            val nextTurn = data.intValue("turn")
            if (nextTurn == null || turn != null || state != AttemptState.Idle) invalid = true
            else turn = nextTurn
            continue
        }
        val currentTurn = turn
        if (currentTurn == null) {
            invalid = true
            break
        }
        if (event.type == "turn/end") {
            if (data.intValue("turn") != currentTurn || state != AttemptState.Idle || sawEnd) invalid = true
            else sawEnd = true
            continue
        }
        if (sawEnd) {
            invalid = true
            break
        }
        when (event.type) {
            "step/start" -> {
                val step = data.intValue("step")
                if (data.intValue("turn") != currentTurn || step == null || state != AttemptState.Idle) {
                    invalid = true
                } else state = AttemptState.Open(currentTurn, step)
            }
            "llm/retry-started" -> {
                val step = data.intValue("step")
                val settled = state as? AttemptState.Settled
                if (data.intValue("turn") != currentTurn || step == null || settled == null ||
                    settled.by != "retry" || !sameAttempt(settled, currentTurn, step)) {
                    invalid = true
                } else state = AttemptState.Open(currentTurn, step)
            }
            "assistant/attempt" -> {
                val step = data.intValue("step")
                val open = state as? AttemptState.Open
                if (data.intValue("turn") != currentTurn || step == null || open == null ||
                    !sameAttempt(open, currentTurn, step)) {
                    invalid = true
                } else {
                    val route = assistantRoute(data)
                    state = open.copy(
                        sample = usageOf(event.type, data) ?: open.sample,
                        provider = route.first,
                        model = route.second,
                    )
                    if (!closeOpen()) invalid = true else state = AttemptState.FinishClosed(currentTurn, step)
                }
            }
            "assistant/message" -> {
                val step = data.intValue("step")
                val open = state as? AttemptState.Open
                if (data.intValue("turn") != currentTurn || step == null || open == null ||
                    !sameAttempt(open, currentTurn, step)) {
                    invalid = true
                } else {
                    val route = assistantRoute(data)
                    state = open.copy(
                        sample = usageOf(event.type, data) ?: open.sample,
                        provider = route.first,
                        model = route.second,
                    )
                    if (!closeOpen()) invalid = true else state = AttemptState.Settled(currentTurn, step, "message")
                }
            }
            "llm/retry" -> {
                val step = data.intValue("step")
                if (data.intValue("turn") != currentTurn || step == null || state == AttemptState.Idle ||
                    !sameAttempt(state, currentTurn, step)) {
                    invalid = true
                } else {
                    if (state is AttemptState.Open && !closeOpen()) invalid = true
                    if (!invalid) state = AttemptState.Settled(currentTurn, step, "retry")
                }
            }
            "step/end" -> {
                val step = data.intValue("step")
                if (data.intValue("turn") != currentTurn || step == null || state == AttemptState.Idle ||
                    !sameAttempt(state, currentTurn, step)) {
                    invalid = true
                } else {
                    if (state is AttemptState.Open && !closeOpen()) invalid = true
                    if (!invalid) state = AttemptState.Idle
                }
            }
        }
    }
    if (invalid || !sawEnd || state != AttemptState.Idle || attempts.isEmpty()) return null

    fun safeSum(values: List<Long>): Long? {
        var total = 0L
        for (value in values) {
            if (value < 0 || total > Long.MAX_VALUE - value) return null
            total += value
        }
        return total
    }

    val total = safeSum(attempts.map { it.totalTokens }) ?: return null
    val input = safeSum(attempts.map { it.inputTokens }) ?: return null
    val output = safeSum(attempts.map { it.outputTokens }) ?: return null
    val cacheRead = attempts.map { it.cacheReadTokens }.takeIf { values -> values.all { it != null } }
        ?.map { it!! }?.let(::safeSum)
    val cacheWrite = attempts.map { it.cacheWriteTokens }.takeIf { values -> values.all { it != null } }
        ?.map { it!! }?.let(::safeSum)
    val providers = attempts.mapNotNull { it.provider }.distinct()
    val models = attempts.mapNotNull { it.model }.distinct()
    return TurnTokenUsage(
        totalTokens = total,
        inputTokens = input,
        outputTokens = output,
        cacheReadTokens = cacheRead,
        cacheWriteTokens = cacheWrite,
        provider = providers.takeIf { it.isNotEmpty() }?.joinToString(", "),
        model = models.takeIf { it.isNotEmpty() }?.joinToString(", "),
    )
}

private fun normalizeAttempt(
    usage: JsonObject,
    provider: String?,
    model: String?,
): NormalizedAttempt? {
    fun count(key: String): Long? = (usage[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    val input = count("inputTokens") ?: return null
    val output = count("outputTokens") ?: return null
    val cacheRead = count("cacheReadTokens")
    val cacheWrite = count("cacheWriteTokens")
    fun sum(values: List<Long>): Long? {
        var total = 0L
        for (value in values) {
            if (total > Long.MAX_VALUE - value) return null
            total += value
        }
        return total
    }
    val knownPrompt = sum(listOfNotNull(input, cacheRead, cacheWrite)) ?: return null
    val suppliedTotal = count("totalTokens")
    val total = if (suppliedTotal != null) {
        val exactPrompt = suppliedTotal - output
        if (exactPrompt < 0 || exactPrompt < knownPrompt) return null
        if (cacheRead != null && cacheWrite != null && exactPrompt != knownPrompt) return null
        suppliedTotal
    } else {
        if (cacheRead == null || cacheWrite == null) return null
        sum(listOf(knownPrompt, output)) ?: return null
    }
    return NormalizedAttempt(
        totalTokens = total,
        inputTokens = input,
        outputTokens = output,
        cacheReadTokens = cacheRead,
        cacheWriteTokens = cacheWrite,
        provider = provider,
        model = model,
    )
}

private fun assistantRoute(data: JsonObject): Pair<String?, String?> {
    val source = (data["message"] as? JsonObject)?.get("source") as? JsonObject
    return ((source?.get("provider") as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)) to
        ((source?.get("model") as? JsonPrimitive)?.content?.takeIf(String::isNotBlank))
}

private fun JsonObject.intValue(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
