package io.github.hakunm.deepseekharness.local

import kotlinx.serialization.json.*

/** Disk projection only: keep usage/cancellation metadata, discard ephemeral phone observations. */
internal fun phoneStorageMessages(source: List<LocalMessage>): List<LocalMessage> {
    fun calls(message: LocalMessage): JsonArray? = message.toolCallsJson?.let {
        runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull()
    }
    fun isPhone(call: JsonElement) = call.jsonObject["function"]?.jsonObject
        ?.get("name")?.jsonPrimitive?.contentOrNull?.startsWith("phone_") == true
    val phoneIds = source.flatMap { calls(it).orEmpty() }.filter(::isPhone)
        .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.toSet()
    return source.map { message ->
        val raw = calls(message)
        when {
            message.toolCallId in phoneIds -> message.copy(content = "[Ephemeral phone result removed; observe again]", reasoningContent = null)
            raw?.any(::isPhone) == true -> message.copy(content = "", reasoningContent = null,
                toolCallsJson = JsonArray(raw.map { element ->
                    if (!isPhone(element)) element else {
                        val call = element.jsonObject
                        val function = call.getValue("function").jsonObject
                        JsonObject(call + ("function" to JsonObject(function + ("arguments" to JsonPrimitive("{}")))))
                    }
                }).toString())
            else -> message
        }
    }
}
