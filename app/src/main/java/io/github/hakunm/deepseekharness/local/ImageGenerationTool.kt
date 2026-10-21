package io.github.hakunm.deepseekharness.local

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 按已配置的 Images API 生成图片。 */
suspend fun ImageTarget.generate(client: LocalImageClient, prompt: String): ByteArray {
    val target = this as RemoteImageTarget
    return client.generate(target.provider, target.model.trim(), prompt.trim())
}

fun cancelImageGeneration(client: LocalImageClient) = client.cancel()

/** Tool-only image generation; it never changes the active conversation model. */
class ImageGenerationTool(
    private val client: LocalImageClient,
    private val target: ImageTarget,
    context: Context,
) : LocalTool {
    private val store = LocalAttachmentStore(context)
    override val name = LocalTaskScope.imageTool
    override val description = "使用已配置的图片模型生成图片；仅在用户明确需要图片时调用。"
    override val parametersJson = """{"type":"object","properties":{"prompt":{"type":"string","description":"图片描述"},"filename":{"type":"string","description":"保存文件名，例如 portrait.png"}},"required":["prompt"]}"""

    override suspend fun execute(argumentsJson: String): String {
        val args = Json.parseToJsonElement(argumentsJson).jsonObject
        val prompt = args["prompt"]?.jsonPrimitive?.content.orEmpty()
        require(prompt.isNotBlank()) { "图片描述不能为空" }
        val requestedName = args["filename"]?.jsonPrimitive?.contentOrNull
        // Keeps the chosen target visible in logcat so a report about "the API model was used"
        // can be checked against the actual Settings → Multimodal resolution.
        android.util.Log.i("DshImage", "generate_image target=${target.label} prompt=${prompt.take(80)}")
        val bytes = target.generate(client, prompt)
        val attachment = store.saveGeneratedImage(bytes, requestedName)
        return "image_generated_json:" + Json.encodeToString(LocalAttachment.serializer(), attachment)
    }
}
