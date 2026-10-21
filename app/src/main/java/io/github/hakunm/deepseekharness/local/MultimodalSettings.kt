package io.github.hakunm.deepseekharness.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 自定义 API 接入信息。API Key 只保存在加密存储里。 */
@Serializable
data class CustomApi(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    /** 上次成功获取的模型列表。 */
    val availableModels: List<String> = emptyList(),
) {
    val ready: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/** 生图：可选择已有供应商、自定义 OpenAI 兼容 Images API。 */
@Serializable
data class ImageSetting(
    val useCustom: Boolean = false,
    val providerId: String? = null,
    val model: String? = null,
    val custom: CustomApi = CustomApi(),
)

/** 音乐生成：自定义 API（MiniMax 音乐接口）。 */
@Serializable
data class MusicSetting(val api: CustomApi = CustomApi())

/** 视频生成协议：两家官方接口的任务创建 / 轮询方式不同。 */
@Serializable
enum class VideoProtocol(val defaultBaseUrl: String, val modelHint: String, val resolutionHint: String) {
    /** 火山方舟 Seedance：POST /contents/generations/tasks，轮询 GET /contents/generations/tasks/{id} */
    Seedance("https://ark.cn-beijing.volces.com/api/v3", "doubao-seedance-1-0-pro-250528", "720p / 1080p"),
    /** MiniMax 海螺：POST /v1/video_generation → query → files/retrieve */
    MiniMax("https://api.minimaxi.com", "MiniMax-Hailuo-02", "768P / 1080P"),
}

/** 视频生成：自定义 API。duration / resolution / ratio 留空则不发送，使用服务端默认值。 */
@Serializable
data class VideoSetting(
    val protocol: VideoProtocol = VideoProtocol.Seedance,
    val api: CustomApi = CustomApi(),
    val durationSeconds: Int? = null,
    val resolution: String = "",
    /** 仅 Seedance 使用，例如 16:9 */
    val ratio: String = "",
)

@Serializable
data class MultimodalConfig(
    val image: ImageSetting = ImageSetting(),
    val music: MusicSetting = MusicSetting(),
    val video: VideoSetting = VideoSetting(),
    /** 旧版本的生图选择字段，仅用于读取兼容。 */
    val imageProvider: String? = null,
    val imageModel: String? = null,
)

/** 生图实际要用的目标：远端 Images API。 */
sealed interface ImageTarget {
    /** 用于界面展示和判断是否已配置。 */
    val model: String
    val label: String
}

/** 远端供应商 + 模型（OpenAI 兼容 Images API）。 */
data class RemoteImageTarget(val provider: LocalProvider, override val model: String) : ImageTarget {
    override val label: String get() = "${provider.name} / $model"
}

/**
 * 多模态设置存储（EncryptedSharedPreferences，因为包含自定义 API Key）。
 * 每次读取都直接读存储，设置页修改后对话 / 自动化任务立即生效。
 */
class MultimodalSettings(context: Context) {
    private val appContext = context.applicationContext

    /**
     * Opened lazily: constructing the controller must not touch the KeyStore. If the KeyStore is
     * unavailable (e.g. JVM tests) multimodal features are simply unconfigured; we never fall back
     * to plaintext storage because the config contains API keys.
     */
    private val prefs by lazy {
        runCatching {
            EncryptedSharedPreferences.create(
                appContext,
                FILE_NAME,
                MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val config: MultimodalConfig
        get() = prefs?.getString(KEY, null)
            ?.let { raw -> runCatching { json.decodeFromString<MultimodalConfig>(raw) }.getOrNull() }
            ?: MultimodalConfig()

    fun save(newConfig: MultimodalConfig) {
        val store = checkNotNull(prefs) { "Secure storage unavailable; multimodal settings were not saved" }
        store.edit().putString(KEY, json.encodeToString(MultimodalConfig.serializer(), newConfig)).apply()
    }

    fun updateImage(value: ImageSetting) = save(config.copy(image = value, imageProvider = null, imageModel = null))
    fun updateMusic(value: MusicSetting) = save(config.copy(music = value))
    fun updateVideo(value: VideoSetting) = save(config.copy(video = value))

    /**
     * 解析生图目标：
     * - 自定义模式：使用自定义 OpenAI 兼容 Images API
     * - 供应商模式：使用指定供应商 + 模型（供应商与 Key 成对使用）
     * - 都未配置：退回 [fallback] 里第一个名字含 image 的模型
     */
    fun resolveImage(providers: List<LocalProvider>, fallback: LocalProvider?): ImageTarget? {
        val img = config.image
        if (img.useCustom) {
            if (!img.custom.ready) return null
            val virtual = LocalProvider(
                id = CUSTOM_IMAGE_ID, name = "自定义 API", protocol = LocalProtocol.OpenAI,
                baseUrl = img.custom.baseUrl.trim(), apiKey = img.custom.apiKey.trim(),
                models = listOf(img.custom.model.trim()), defaultModel = img.custom.model.trim(),
            )
            return RemoteImageTarget(virtual, img.custom.model.trim())
        }
        val providerId = img.providerId ?: config.imageProvider
        val model = img.model ?: config.imageModel
        if (providerId != null && !model.isNullOrBlank()) {
            providers.find { it.id == providerId }
                ?.takeIf { it.protocol == LocalProtocol.OpenAI && it.apiKey.isNotBlank() }
                ?.let { return RemoteImageTarget(it, model) }
        }
        val p = fallback?.takeIf { it.protocol == LocalProtocol.OpenAI && it.apiKey.isNotBlank() } ?: return null
        return p.models.firstOrNull { it.contains("image", ignoreCase = true) }?.let { RemoteImageTarget(p, it) }
    }

    /** 音乐生成已配置时返回设置，否则 null。 */
    fun resolveMusic(): MusicSetting? = config.music.takeIf { it.api.ready }

    /** 视频生成已配置时返回设置，否则 null。 */
    fun resolveVideo(): VideoSetting? = config.video.takeIf { it.api.ready }

    companion object {
        const val CUSTOM_IMAGE_ID = "__custom_image__"
        private const val FILE_NAME = "multimodal_settings_secure"
        private const val KEY = "config"
    }
}
