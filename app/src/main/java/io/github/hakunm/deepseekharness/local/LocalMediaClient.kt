package io.github.hakunm.deepseekharness.local

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 音乐 / 视频生成客户端（自定义 API）。
 * - 音乐：MiniMax `POST /v1/music_generation`，同步返回 hex 音频
 * - 视频：火山方舟 Seedance 或 MiniMax 海螺，异步任务 + 轮询
 * 下载生成结果时不会携带供应商 API Key。
 */
class LocalMediaClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(0, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build(),
    private val pollIntervalMs: Long = 5_000,
    private val maxWaitMs: Long = 15 * 60_000,
    /** 仅供测试：MockWebServer 只提供 http 地址。 */
    private val allowInsecureDownloads: Boolean = false,
) {
    @Volatile private var active: Call? = null
    fun cancel() { active?.cancel() }

    class Media(val bytes: ByteArray, val mimeType: String, val extension: String)

    suspend fun generateMusic(api: CustomApi, prompt: String, lyrics: String?, instrumental: Boolean): Media =
        withContext(Dispatchers.IO) {
            require(api.ready) { "请在「设置 → 多模态」中配置音乐生成 API" }
            require(prompt.isNotBlank() || !lyrics.isNullOrBlank()) { "音乐描述与歌词不能同时为空" }
            val body = buildJsonObject {
                put("model", api.model.trim())
                if (prompt.isNotBlank()) put("prompt", prompt.take(2000))
                when {
                    instrumental -> put("is_instrumental", true)
                    !lyrics.isNullOrBlank() -> put("lyrics", lyrics.take(3500))
                    // 没有歌词时让服务端根据 prompt 自动写词。
                    else -> put("lyrics_optimizer", true)
                }
                put("output_format", "hex")
                putJsonObject("audio_setting") {
                    put("sample_rate", 44100); put("bitrate", 256000); put("format", "mp3")
                }
            }
            val root = postJson(normalizeBaseUrl(api.baseUrl) + "/music_generation", api.apiKey, body, "音乐接口")
            checkBaseResp(root, "音乐接口")
            val hex = root["data"]?.jsonObject?.get("audio")?.jsonPrimitive?.contentOrNull
                ?: error("音乐接口未返回音频数据")
            Media(decodeHex(hex), "audio/mpeg", "mp3")
        }

    suspend fun generateVideo(setting: VideoSetting, prompt: String, onProgress: suspend (Int) -> Unit = {}): Media = withContext(Dispatchers.IO) {
        require(setting.api.ready) { "请在「设置 → 多模态」中配置视频生成 API" }
        require(prompt.isNotBlank()) { "视频描述不能为空" }
        onProgress(10) // 提交任务
        val url = when (setting.protocol) {
            VideoProtocol.Seedance -> seedanceVideo(setting, prompt, onProgress)
            VideoProtocol.MiniMax -> miniMaxVideo(setting, prompt, onProgress)
        }
        onProgress(90) // 开始下载
        val result = Media(download(url, MAX_MEDIA_BYTES), "video/mp4", "mp4")
        onProgress(100) // 完成
        result
    }

    private suspend fun seedanceVideo(setting: VideoSetting, prompt: String, onProgress: suspend (Int) -> Unit): String {
        val base = apiRoot(setting.api.baseUrl) + "/contents/generations/tasks"
        val flags = buildString {
            setting.durationSeconds?.let { append(" --duration ").append(it) }
            setting.resolution.trim().takeIf { it.isNotEmpty() }?.let { append(" --resolution ").append(it) }
            setting.ratio.trim().takeIf { it.isNotEmpty() }?.let { append(" --ratio ").append(it) }
        }
        val body = buildJsonObject {
            put("model", setting.api.model.trim())
            putJsonArray("content") {
                addJsonObject { put("type", "text"); put("text", prompt.take(2000) + flags) }
            }
        }
        val taskId = postJson(base, setting.api.apiKey, body, "视频接口")["id"]?.jsonPrimitive?.contentOrNull
            ?: error("视频接口未返回任务 ID")
        onProgress(30) // 任务已提交，开始轮询
        return poll(onProgress, 30, 85) {
            val task = getJson("$base/$taskId", setting.api.apiKey, "视频任务查询")
            when (task["status"]?.jsonPrimitive?.contentOrNull) {
                "succeeded" -> task["content"]?.jsonObject?.get("video_url")?.jsonPrimitive?.contentOrNull
                    ?: error("视频任务已完成但未返回 video_url")
                "failed", "cancelled", "expired" -> error("视频生成失败：" +
                    (task["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: task["status"]))
                else -> null
            }
        }
    }

    private suspend fun miniMaxVideo(setting: VideoSetting, prompt: String, onProgress: suspend (Int) -> Unit): String {
        val base = normalizeBaseUrl(setting.api.baseUrl)
        val key = setting.api.apiKey
        val body = buildJsonObject {
            put("model", setting.api.model.trim())
            put("prompt", prompt.take(2000))
            setting.durationSeconds?.let { put("duration", it) }
            setting.resolution.trim().takeIf { it.isNotEmpty() }?.let { put("resolution", it.uppercase()) }
        }
        val created = postJson("$base/video_generation", key, body, "视频接口")
        checkBaseResp(created, "视频接口")
        val taskId = created["task_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("视频接口未返回 task_id")
        onProgress(30) // 任务已提交
        val fileId = poll(onProgress, 30, 85) {
            val url = "$base/query/video_generation".toHttpUrl().newBuilder().addQueryParameter("task_id", taskId).build()
            val task = getJson(url.toString(), key, "视频任务查询")
            checkBaseResp(task, "视频任务查询")
            when (task["status"]?.jsonPrimitive?.contentOrNull) {
                "Success" -> task["file_id"]?.jsonPrimitive?.contentOrNull ?: error("视频任务已完成但未返回 file_id")
                "Fail" -> error("视频生成失败")
                else -> null
            }
        }
        val fileUrl = "$base/files/retrieve".toHttpUrl().newBuilder().addQueryParameter("file_id", fileId).build()
        val file = getJson(fileUrl.toString(), key, "视频文件查询")
        checkBaseResp(file, "视频文件查询")
        return file["file"]?.jsonObject?.get("download_url")?.jsonPrimitive?.contentOrNull
            ?: error("视频文件未返回 download_url")
    }

    /** 轮询直到 [check] 返回非 null；协程取消或超时即停止。支持进度回调。 */
    private suspend fun <T : Any> poll(onProgress: suspend (Int) -> Unit = {}, startProgress: Int = 0, endProgress: Int = 100, check: () -> T?): T {
        val deadline = System.currentTimeMillis() + maxWaitMs
        val startTime = System.currentTimeMillis()
        var iteration = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            check()?.let { return it }
            check(System.currentTimeMillis() < deadline) { "视频生成超时，请稍后重试" }
            // 模拟进度：每次轮询增加一点，但不超过 endProgress
            iteration++
            val elapsed = System.currentTimeMillis() - startTime
            val estimatedProgress = startProgress + ((elapsed.toFloat() / maxWaitMs) * (endProgress - startProgress)).toInt()
            onProgress(estimatedProgress.coerceIn(startProgress, endProgress))
            delay(pollIntervalMs)
        }
    }

    private fun postJson(url: String, key: String, body: JsonObject, label: String): JsonObject =
        execute(Request.Builder().url(url).header("Authorization", "Bearer ${key.trim()}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build(), label)

    private fun getJson(url: String, key: String, label: String): JsonObject =
        execute(Request.Builder().url(url).header("Authorization", "Bearer ${key.trim()}").get().build(), label)

    private fun execute(request: Request, label: String): JsonObject {
        val call = http.newCall(request); active = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) error("$label HTTP ${response.code}：请检查 Base URL、API Key 与模型名称")
                val raw = bounded(response, MAX_JSON_BYTES).toString(Charsets.UTF_8)
                return runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrElse { error("$label 返回了无法解析的内容") }
            }
        } finally { active = null }
    }

    private fun download(url: String, limit: Int): ByteArray {
        require(url.startsWith("https://") || (allowInsecureDownloads && url.startsWith("http://"))) {
            "下载地址必须使用 HTTPS"
        }
        // 结果托管在对象存储上，不转发 API Key。
        val call = http.newCall(Request.Builder().url(url).get().build()); active = call
        try {
            call.execute().use { response ->
                check(response.isSuccessful) { "生成结果下载 HTTP ${response.code}" }
                return bounded(response, limit)
            }
        } finally { active = null }
    }

    private fun checkBaseResp(root: JsonObject, label: String) {
        val base = root["base_resp"]?.jsonObject ?: return
        val code = base["status_code"]?.jsonPrimitive?.intOrNull ?: return
        check(code == 0) { "$label 错误 $code：${base["status_msg"]?.jsonPrimitive?.contentOrNull.orEmpty()}" }
    }

    private fun bounded(response: Response, limit: Int): ByteArray {
        val source = response.body?.source() ?: error("响应为空")
        val buffer = okio.Buffer()
        while (buffer.size <= limit) {
            if (source.read(buffer, limit + 1L - buffer.size) == -1L) break
        }
        require(buffer.size <= limit) { "响应超过大小限制" }
        return buffer.readByteArray()
    }

    companion object {
        /** 与附件存储上限一致。 */
        const val MAX_MEDIA_BYTES = 25 * 1024 * 1024
        /** hex 音频体积约为原始数据两倍。 */
        private const val MAX_JSON_BYTES = 2 * MAX_MEDIA_BYTES + 1024 * 1024

        internal fun decodeHex(hex: String): ByteArray {
            val clean = hex.trim()
            require(clean.isNotEmpty() && clean.length % 2 == 0) { "音频数据格式错误" }
            require(clean.length / 2 <= MAX_MEDIA_BYTES) { "音频超过大小限制" }
            return ByteArray(clean.length / 2) { i ->
                val hi = Character.digit(clean[2 * i], 16)
                val lo = Character.digit(clean[2 * i + 1], 16)
                require(hi >= 0 && lo >= 0) { "音频数据格式错误" }
                ((hi shl 4) or lo).toByte()
            }
        }
    }
}
