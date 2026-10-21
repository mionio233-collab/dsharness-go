package io.github.hakunm.deepseekharness.local

import io.github.hakunm.deepseekharness.data.ConversationStats

/** Character heuristic only; deliberately labelled as an estimate, never provider usage. */
fun estimatedTextTokens(text: String): Long =
    kotlin.math.ceil(text.sumOf { if (it.code > 127) 1.0 else 0.25 }).toLong()

data class LiveLocalStats(
    val elapsedMillis: Long = 0,
    val completedOutput: Long = 0,
    val completedTotal: Long = 0,
    val completedEstimated: Boolean = false,
    val partial: LocalMessage? = null,
    val promptEstimate: Long = 0,
    val requestInput: Long? = null,
    val pendingRound: Boolean = true,
)

/** Totals here cover the current user request; speed never uses previous user outputs. */
fun List<LocalMessage>.liveLocalConversationStats(live: LiveLocalStats?, contextLimit: Long?): ConversationStats {
    val turn = drop(indexOfLast { it.role == "user" }.coerceAtLeast(0))
    val last = turn.lastOrNull { it.role == "assistant" }
    val usage = last?.usage
    val part = live?.partial
    val estimatedOutput = part?.let { estimatedTextTokens(it.content + it.reasoningContent.orEmpty()) } ?: 0
    val currentOutput = part?.usage?.output ?: estimatedOutput
    val output = if (live != null) (live.completedOutput + currentOutput).takeIf {
        live.completedOutput > 0 || part?.usage?.output != null || estimatedOutput > 0
    } else usage?.output
    val estimated = live != null && output != null &&
        (live.completedEstimated || part != null && part.usage?.output == null)
    val total = if (live != null) (live.completedTotal +
        (if (!live.pendingRound) 0 else part?.usage?.total ?: ((part?.usage?.input ?: live.promptEstimate) + currentOutput))).takeIf {
        live.completedTotal > 0 || live.pendingRound &&
            (part?.usage?.total != null || part?.usage?.input != null || live.promptEstimate > 0 || output != null)
    } else usage?.total ?: last?.usageTokens
    val duration = live?.elapsedMillis ?: last?.elapsedMillis
    val input = live?.partial?.usage?.input ?: live?.requestInput ?: if (live == null) last?.contextInputTokens else null
    val context = input ?: live?.promptEstimate?.takeIf { it > 0 }
    return ConversationStats(
        rounds = count { it.role == "user" }, steps = count { it.role == "tool" },
        outputTokens = output, totalTokens = total,
        tokensEstimated = live != null && (live.completedEstimated || live.pendingRound && part?.usage?.total == null),
        tokensPerSecond = if (duration != null && duration > 0 && output != null) output * 1000.0 / duration else null,
        speedEstimated = estimated,
        inputTokens = input, cacheHitPercent = part?.usage?.cacheHitPercent ?: usage?.cacheHitPercent,
        contextTokens = context, contextLimit = contextLimit?.takeIf { it > 0 },
        contextEstimated = input == null && context != null,
        contextPercent = if (context != null && contextLimit != null && contextLimit > 0)
            (context * 100 / contextLimit).coerceIn(0, 100).toInt() else null,
    )
}

fun runStats(messages: List<LocalMessage>, live: LiveLocalStats?, limit: Long?): ConversationStats {
    val current = messages.liveLocalConversationStats(live, limit)
    val session = messages.localConversationStats()
    return current.copy(
        // Current request throughput uses monotonic elapsed time, including reasoning/tools/wait.
        // Keep live counters separate from exact session/checkpoint usage.
        liveTotalTokens = current.totalTokens.takeIf { live != null },
        liveOutputTokens = current.outputTokens.takeIf { live != null },
        tokensPerSecond = if (live != null) current.tokensPerSecond else session.tokensPerSecond,
        speedEstimated = live != null && current.speedEstimated,
        totalTokens = session.totalTokens, inputTokens = session.inputTokens,
        outputTokens = session.outputTokens, cacheReadTokens = session.cacheReadTokens,
        cacheWriteTokens = session.cacheWriteTokens, cacheHitPercent = session.cacheHitPercent,
        provider = session.provider, model = session.model,
        tokensEstimated = live != null && current.tokensEstimated,
        usageIncomplete = session.usageIncomplete)
}
