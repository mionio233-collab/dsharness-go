package io.github.hakunm.deepseekharness.local

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 工具结果前缀：后面跟 [LocalAttachment] JSON，调用方据此把文件挂到助手回复上。 */
const val MEDIA_GENERATED_PREFIX = "media_generated_json:"

/** 从工具输出里取出生成的音频/视频附件；不是生成结果时返回 null。 */
fun generatedMediaAttachment(output: String): LocalAttachment? =
    output.takeIf { it.startsWith(MEDIA_GENERATED_PREFIX) }?.let {
        runCatching { Json.decodeFromString(LocalAttachment.serializer(), it.removePrefix(MEDIA_GENERATED_PREFIX)) }.getOrNull()
    }

/** 音乐生成工具：使用「设置 → 多模态」里的自定义音乐 API。 */
class MusicGenerationTool(
    private val client: LocalMediaClient,
    private val setting: MusicSetting,
    context: Context,
) : LocalTool {
    private val store = LocalAttachmentStore(context)
    override val name = LocalTaskScope.musicTool
    override val description = "使用已配置的音乐模型生成一首音乐（mp3）；仅在用户明确需要音乐/歌曲时调用"
    override val parametersJson = """{"type":"object","properties":{"prompt":{"type":"string","description":"风格、情绪、场景描述"},"lyrics":{"type":"string","description":"可选歌词，用 \\n 分行，可含 [Verse] [Chorus] 等结构标签"},"instrumental":{"type":"boolean","description":"是否纯音乐（无人声）"},"filename":{"type":"string","description":"保存文件名，例如 夏日海边.mp3"}},"required":["prompt"]}"""

    override suspend fun execute(argumentsJson: String): String {
        val args = Json.parseToJsonElement(argumentsJson).jsonObject
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val lyrics = args["lyrics"]?.jsonPrimitive?.contentOrNull
        val instrumental = args["instrumental"]?.jsonPrimitive?.booleanOrNull ?: false
        val media = client.generateMusic(setting.api, prompt, lyrics, instrumental)
        val attachment = store.saveGeneratedMedia(media.bytes, media.mimeType, media.extension,
            args["filename"]?.jsonPrimitive?.contentOrNull)
        return MEDIA_GENERATED_PREFIX + Json.encodeToString(LocalAttachment.serializer(), attachment)
    }
}

/** 视频生成工具：使用「设置 → 多模态」里的自定义视频 API（异步任务，可能需要数分钟）。 */
class VideoGenerationTool(
    private val client: LocalMediaClient,
    private val setting: VideoSetting,
    context: Context,
    /** 生成进度回调（0..100），供界面显示进度条。 */
    private val onProgress: (Int) -> Unit = {},
) : LocalTool {
    private val store = LocalAttachmentStore(context)
    override val name = LocalTaskScope.videoTool
    override val description = "使用已配置的视频模型根据文字生成短视频（mp4，需等待数分钟）；仅在用户明确需要视频时调用"
    override val parametersJson = """{
        "type": "object",
        "properties": {
            "prompt": {"type": "string", "description": "画面与镜头描述，可包含时长（如8秒）、分辨率（如1080p）、画幅（如16:9）等参数"},
            "filename": {"type": "string", "description": "保存文件名，例如 城市夜景.mp4"}
        },
        "required": ["prompt"]
    }"""

    override suspend fun execute(argumentsJson: String): String {
        val args = Json.parseToJsonElement(argumentsJson).jsonObject
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(prompt.isNotBlank()) { "视频描述不能为空" }
        onProgress(0)
        val media = client.generateVideo(setting, prompt) { progress ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onProgress(progress)
            }
        }
        val attachment = store.saveGeneratedMedia(media.bytes, media.mimeType, media.extension,
            args["filename"]?.jsonPrimitive?.contentOrNull)
        return MEDIA_GENERATED_PREFIX + Json.encodeToString(LocalAttachment.serializer(), attachment)
    }
}
