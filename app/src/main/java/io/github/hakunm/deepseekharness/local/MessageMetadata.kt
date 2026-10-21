package io.github.hakunm.deepseekharness.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Exact provider counts only. Cached OpenAI tokens are already included in prompt_tokens. */
internal fun responseUsage(root: JsonObject, anthropic: Boolean): Long? {
    val usage = root["usage"] as? JsonObject ?: return null
    fun count(key: String) = (usage[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    if (!anthropic) {
        return count("total_tokens") ?: run {
            val input = count("prompt_tokens") ?: return null
            val output = count("completion_tokens") ?: return null
            input + output
        }
    }
    val input = count("input_tokens") ?: return null
    val output = count("output_tokens") ?: return null
    return input + output + (count("cache_read_input_tokens") ?: 0) +
        (count("cache_creation_input_tokens") ?: 0)
}

/** Replace a complete latest turn, never leaving its assistant/tool protocol records behind. */
internal fun replaceLatestTurn(history: List<LocalMessage>, index: Int, replacement: LocalMessage): List<LocalMessage> {
    require(index >= 0 && index == history.indexOfLast { it.role == "user" })
    require(replacement.role == "user")
    return history.take(index) + replacement
}

internal class TaskUsage {
    private var complete = true
    private var total = 0L
    fun add(tokens: Long?) { if (tokens == null) complete = false else total += tokens }
    val tokens: Long? get() = total.takeIf { complete }
}
