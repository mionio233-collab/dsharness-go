package io.github.hakunm.deepseekharness.local

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.EOFException
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun completionApiRoot(base: String): String {
    val url = base.trim().toHttpUrl()
    require(url.query == null && url.fragment == null) { "API base URL must not contain a query or fragment" }
    var path = url.encodedPath.trimEnd('/')
    for (suffix in listOf("/chat/completions", "/messages")) if (path.endsWith(suffix)) path = path.removeSuffix(suffix)
    if (path.isEmpty()) path = "/v1"
    return url.newBuilder().encodedPath(path).build().toString().trimEnd('/')
}

/** One POST only. Cancellation closes the socket even while headers or an SSE line are pending. */
internal suspend fun streamingCompletion(
    http: OkHttpClient, request: Request, guard: (() -> Boolean)?, progress: (LocalMessage) -> Unit,
): LocalCompletion = streamingCompletion(http, request, guard, progress, {})

internal suspend fun streamingCompletion(
    http: OkHttpClient, request: Request, guard: (() -> Boolean)?, progress: (LocalMessage) -> Unit,
    requestBodySent: () -> Unit,
): LocalCompletion = suspendCancellableCoroutine { continuation ->
    var connectionAcquired = false
    val call = http.newBuilder().eventListener(object : EventListener() {
        override fun connectionAcquired(call: Call, connection: Connection) { connectionAcquired = true }
        override fun requestBodyEnd(call: Call, byteCount: Long) { if (byteCount > 0) requestBodySent() }
    }).addNetworkInterceptor { chain ->
        // Recheck after connection setup/queueing, immediately before sending hydrated pixels.
        if (guard != null && !guard()) throw IOException("visual_authorization_or_window_changed")
        chain.proceed(chain.request())
    }.build().newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    if (guard != null && !guard()) {
        continuation.resumeWithException(IllegalStateException("visual_authorization_or_window_changed"))
        return@suspendCancellableCoroutine
    }
    var headersReceived = false
    var partialReceived = false
    fun fail(e: Exception) {
        val failure = if (e is IOException) providerNetworkFailure(e, connectionAcquired, headersReceived, partialReceived) else e
        if (continuation.isActive) continuation.resumeWithException(failure)
    }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = fail(e)
        override fun onResponse(call: Call, response: Response) {
            headersReceived = true
            try {
                val completion = response.use {
                    val body = it.body ?: error("No response body")
                    if (!it.isSuccessful) error("Provider HTTP ${it.code}")
                    val source = body.source()
                    // Some compatible providers ignore stream=true. Parse that same response, never replay.
                    val peek = source.peek()
                    var first: Byte? = null
                    while (!peek.exhausted()) {
                        val byte = peek.readByte()
                        if (byte.toInt().toChar() !in " \r\n\t") { first = byte; break }
                    }
                    val isJson = first?.toInt()?.toChar() == '{'
                    val assembler = OpenAiAssembler { partial ->
                        partialReceived = partialReceived || partial.content.isNotEmpty() || !partial.reasoningContent.isNullOrEmpty()
                        progress(partial)
                    }
                    if (isJson) assembler.acceptJson(Json.parseToJsonElement(body.string()).jsonObject)
                    else {
                        val event = StringBuilder()
                        var done = false
                        fun dispatch() {
                            val data = event.toString().trim()
                            event.setLength(0)
                            if (data == "[DONE]") done = true
                            else if (data.isNotEmpty()) assembler.accept(Json.parseToJsonElement(data).jsonObject)
                        }
                        while (!done && continuation.isActive) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isEmpty()) dispatch()
                            else if (line.startsWith("data:")) {
                                if (event.isNotEmpty()) event.append('\n')
                                event.append(line.removePrefix("data:").trimStart())
                            }
                        }
                        if (!done && event.isNotEmpty()) dispatch()
                        if (!done && !assembler.finished) throw EOFException("Incomplete provider stream")
                    }
                    assembler.result()
                }
                if (continuation.isActive) continuation.resume(completion)
            } catch (e: Exception) { fail(e) }
        }
    })
}

internal class OpenAiAssembler(private val progress: (LocalMessage) -> Unit) {
    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private data class Parts(val id: StringBuilder = StringBuilder(), val name: StringBuilder = StringBuilder(), val args: StringBuilder = StringBuilder())
    private val calls = sortedMapOf<Int, Parts>()
    private var usageRoot = JsonObject(emptyMap())
    var finished = false
        private set
    private var bufferedResponse = false
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    // Only explicit text parts are displayed; unknown blocks (including private reasoning) are ignored.
    private fun textParts(value: JsonElement?): String = when (value) {
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        is JsonArray -> value.joinToString("") { part ->
            val obj = part as? JsonObject
            if (obj?.text("type") in listOf("text", "output_text")) obj?.text("text").orEmpty() else ""
        }
        else -> ""
    }
    private fun emitProgress() {
        progress(LocalMessage("assistant", content.toString(), reasoningContent = reasoning.toString().takeIf { it.isNotEmpty() },
            usage = responseUsageDetails(usageRoot, false), bufferedResponse = bufferedResponse))
    }
    private fun update(message: JsonObject, streaming: Boolean) {
        val publicText = textParts(message["content"])
        val providerReasoning = textParts(message["reasoning_content"]).ifEmpty { textParts(message["reasoning"]) }
        content.append(publicText)
        reasoning.append(providerReasoning)
        (message["tool_calls"] as? JsonArray)?.forEachIndexed { position, element ->
            val call = element.jsonObject
            val index = if (streaming) (call["index"] as? JsonPrimitive)?.intOrNull ?: error("Missing tool call index") else position
            require(index in 0..11) { "Too many tool calls" }
            val parts = calls.getOrPut(index) { Parts() }
            parts.id.append(call.text("id"))
            (call["function"] as? JsonObject)?.let {
                parts.name.append(it.text("name")); parts.args.append(it.text("arguments"))
            }
        }
        if (publicText.isNotEmpty() || providerReasoning.isNotEmpty() || bufferedResponse) {
            emitProgress()
        }
    }
    fun accept(root: JsonObject) {
        check(root["error"] == null) { "Provider returned a stream error; partial output retained" }
        if (root["usage"] is JsonObject) {
            // Stream usage fields are snapshots, not increments. Preserve fields reported
            // in earlier usage-only events without adding the same count twice.
            val previous = usageRoot["usage"] as? JsonObject ?: JsonObject(emptyMap())
            val current = root["usage"] as JsonObject
            usageRoot = JsonObject(usageRoot + root + ("usage" to JsonObject(previous + current)))
            emitProgress()
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return
        (choice["delta"] as? JsonObject)?.let { update(it, true) }
        val reason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        check(reason != "length" && reason != "content_filter") { "Provider stopped generation ($reason); partial output retained" }
        if (reason != null) finished = true
    }
    fun acceptJson(root: JsonObject) {
        bufferedResponse = true
        usageRoot = root
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: error("Empty completion")
        val message = choice["message"]?.jsonObject ?: error("Empty completion")
        update(message, false)
        val reason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        check(reason != "length" && reason != "content_filter") { "Provider stopped generation ($reason); partial output retained" }
        finished = true
    }
    fun result(): LocalCompletion {
        val tools = calls.values.map {
            require(it.id.isNotEmpty() && it.name.isNotEmpty()) { "Incomplete tool call; tools were not executed" }
            val arguments = it.args.toString().ifEmpty { "{}" }
            require(Json.parseToJsonElement(arguments) is JsonObject) { "Invalid tool arguments; tools were not executed" }
            LocalToolCall(it.id.toString(), it.name.toString(), arguments)
        }
        require(content.isNotEmpty() || reasoning.isNotEmpty() || tools.isNotEmpty()) { "Empty completion" }
        val raw = tools.takeIf { it.isNotEmpty() }?.let { list -> JsonArray(list.map {
            buildJsonObject {
                put("id", it.id); put("type", "function")
                putJsonObject("function") { put("name", it.name); put("arguments", it.arguments) }
            }
        }).toString() }
        return LocalCompletion(content.toString(), tools,
            LocalMessage("assistant", content.toString(), toolCallsJson = raw, reasoningContent = reasoning.toString().takeIf { it.isNotEmpty() }, bufferedResponse = bufferedResponse),
            responseUsage(usageRoot, false), responseUsageDetails(usageRoot, false))
    }
}
