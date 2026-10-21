package io.github.hakunm.deepseekharness.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 从自定义 API 端点获取模型列表。
 * 支持多种格式自动检测：
 * - OpenAI 格式：`{"data": [{"id": "model-1"}, ...]}`
 * - MiniMax 格式：`{"models": [{"model_name": "model-1"}, ...]}`
 * - 简单数组：`["model-1", "model-2"]`
 */
class CustomApiClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    /**
     * 从 API 获取可用模型列表，并按 [filter] 只保留对应类型的模型。
     * @return 去重排序后的模型名称；请求失败返回空列表
     */
    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String,
        filter: (String) -> Boolean = { true },
    ): List<String> = when (val result = discoverModels(baseUrl, apiKey, filter)) {
        is ModelFetchResult.Success -> result.models
        is ModelFetchResult.Failure -> emptyList()
    }

    /** A successful empty catalog is distinct from authentication, transport and parse failures. */
    suspend fun discoverModels(
        baseUrl: String,
        apiKey: String,
        filter: (String) -> Boolean = { true },
    ): ModelFetchResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(apiRoot(baseUrl) + "/models")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext ModelFetchResult.Failure(response.code)
                val bodyText = response.body?.string().orEmpty()
                val root = try { Json.parseToJsonElement(bodyText) }
                    catch (_: Exception) { return@withContext ModelFetchResult.Failure(invalidResponse = true) }
                if (root !is JsonArray && (root !is JsonObject || root["data"] !is JsonArray && root["models"] !is JsonArray)) {
                    return@withContext ModelFetchResult.Failure(invalidResponse = true)
                }
                val all = parseModelIds(root).distinct().sorted()
                ModelFetchResult.Success(all.filter(filter), all)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            ModelFetchResult.Failure()
        }
    }
}

sealed interface ModelFetchResult {
    data class Success(val models: List<String>, val allModels: List<String> = models) : ModelFetchResult
    data class Failure(val status: Int? = null, val invalidResponse: Boolean = false) : ModelFetchResult
}

/** 兼容 OpenAI `{data:[{id}]}`、MiniMax `{models:[{model_name}]}` 和纯数组三种返回格式。 */
internal fun parseModelIds(root: JsonElement): List<String> {
    val items: JsonArray = when (root) {
        is JsonArray -> root
        is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: return emptyList()
        else -> return emptyList()
    }
    return items.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull
            is JsonObject -> (item["id"] ?: item["model_name"] ?: item["model"] ?: item["name"])
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
            else -> null
        }
    }.filter { it.isNotBlank() }
}

/** 用户可能把完整的任务地址当成 Base URL 填入，这里统一去掉接口路径，只保留 API 根地址。 */
fun apiRoot(baseUrl: String): String = baseUrl.trim().trimEnd('/')
    .removeSuffix("/contents/generations/tasks")
    .removeSuffix("/video_generation")
    .removeSuffix("/music_generation")
    .removeSuffix("/images/generations")
    .removeSuffix("/images/edits")
    .removeSuffix("/models")
    .trimEnd('/')

/**
 * 获取模型后的筛选规则：通用接口会返回大量对话/向量模型，只保留对应类型。
 * - Seedance 视频：名称含 seedance
 * - MiniMax 视频：名称含 hailuo，或含独立的 H2 / H3 字样
 * - 音乐：名称含 music
 */
object MediaModelFilter {
    /** Suggest likely image models; callers retain the full catalog for manual selection. */
    val image: (String) -> Boolean = { id ->
        listOf("image", "dall-e", "flux", "stable-diffusion", "sdxl", "sd3", "cogview", "kolors", "seedream", "wanx")
            .any { id.contains(it, ignoreCase = true) }
    }
    private val miniMaxVideo = Regex("hailuo|(^|[^a-z0-9])h[23]([^a-z0-9]|$)", RegexOption.IGNORE_CASE)

    fun video(protocol: VideoProtocol): (String) -> Boolean = when (protocol) {
        VideoProtocol.Seedance -> { id -> id.contains("seedance", ignoreCase = true) }
        VideoProtocol.MiniMax -> { id -> miniMaxVideo.containsMatchIn(id) }
    }

    val music: (String) -> Boolean = { id -> id.contains("music", ignoreCase = true) }
}
