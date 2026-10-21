package io.github.hakunm.deepseekharness.local

import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** OpenAI-compatible Images API. Image models are selected explicitly by the user. */
class LocalImageClient(private val http: OkHttpClient = OkHttpClient.Builder()
    .callTimeout(0, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()) {
    @Volatile private var active: Call? = null
    fun cancel() { active?.cancel() }
    fun generate(provider: LocalProvider, model: String, prompt: String): ByteArray {
        require(provider.protocol == LocalProtocol.OpenAI) { "请选择支持 Images API 的 OpenAI 兼容供应商" }
        require(model.isNotBlank() && prompt.isNotBlank())
        val body = buildJsonObject {
            put("model", model); put("prompt", prompt); put("n", 1)
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(normalizeBaseUrl(provider.baseUrl) + "/images/generations")
            .header("Authorization", "Bearer ${provider.apiKey}").post(body).build()
        val call = http.newCall(request); active = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) error("图片接口 HTTP ${response.code}：请确认供应商支持 Images API，且模型名称为图片模型")
                val raw = bounded(response, 32 * 1024 * 1024).toString(Charsets.UTF_8)
                val item = Json.parseToJsonElement(raw).jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: error("图片接口未返回 data 图片数据")
                item["b64_json"]?.jsonPrimitive?.contentOrNull?.let {
                    return Base64.getDecoder().decode(it).also { bytes -> require(bytes.size <= 20 * 1024 * 1024) }
                }
                val url = item["url"]?.jsonPrimitive?.contentOrNull ?: error("图片接口未返回 b64_json 或 url")
                require(url.startsWith("https://")) { "图片下载地址必须使用 HTTPS" }
                // Provider credentials are never forwarded to the image host.
                val download = http.newCall(Request.Builder().url(url).get().build()); active = download
                download.execute().use { image ->
                    check(image.isSuccessful) { "图片下载 HTTP ${image.code}" }
                    return bounded(image, 20 * 1024 * 1024)
                }
            }
        } finally { active = null }
    }
    private fun bounded(response: Response, limit: Int): ByteArray {
        val source = response.body?.source() ?: error("图片响应为空")
        val buffer = okio.Buffer()
        while (buffer.size <= limit) {
            if (source.read(buffer, limit + 1L - buffer.size) == -1L) break
        }
        require(buffer.size <= limit) { "图片响应超过大小限制" }
        return buffer.readByteArray()
    }
}
