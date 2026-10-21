package io.github.hakunm.deepseekharness.local

import io.github.hakunm.deepseekharness.data.ModelLabel
import java.net.URI
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Only the official endpoint receives official labels/protocol policy. */
fun LocalProvider.isOfficialDeepSeek(model: String?): Boolean =
    protocol == LocalProtocol.OpenAI && model in setOf("deepseek-flash", "deepseek-v4-pro") &&
        runCatching {
            val uri = URI(baseUrl)
            uri.scheme.equals("https", true) && uri.host.equals("api.deepseek.com", true) &&
                uri.port in setOf(-1, 443) && uri.rawUserInfo == null &&
                uri.path.trimEnd('/') in setOf("", "/v1")
        }.getOrDefault(false)

fun LocalProvider.modelLabel(model: String): ModelLabel =
    if (isOfficialDeepSeek(model) && model == "deepseek-flash") ModelLabel("DeepSeek-V4.1-Flash", "1017")
    else ModelLabel(model)

fun LocalProvider.reasoningEfforts(model: String?): List<String> =
    if (isOfficialDeepSeek(model)) listOf("Off", "Low", "High", "Max")
    else listOf("Low", "Medium", "High")

/** A display label never changes the outgoing model ID. */
internal fun LocalProvider.reasoningRequestFields(model: String, effort: String?): Map<String, JsonElement> {
    if (effort.isNullOrBlank()) return emptyMap()
    val level = effort.lowercase()
    if (!isOfficialDeepSeek(model)) return mapOf("reasoning_effort" to JsonPrimitive(level))
    require(level in setOf("off", "low", "high", "max")) { "Unsupported DeepSeek reasoning effort" }
    val thinking = "thinking" to JsonObject(mapOf("type" to JsonPrimitive(if (level == "off") "disabled" else "enabled")))
    return if (level == "off") mapOf(thinking) else mapOf(thinking, "reasoning_effort" to JsonPrimitive(level))
}
