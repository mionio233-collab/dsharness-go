package io.github.hakunm.deepseekharness.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 本机 agent 的一个工具。
 *
 * `parametersJson` 直接作为 OpenAI 兼容协议里 function 的 `parameters`（JSON Schema）。
 */
interface LocalTool {
    val name: String
    val description: String
    val parametersJson: String
    suspend fun execute(argumentsJson: String): String
}

/**
 * 工具注册表。
 *
 * 文件类工具被**限制在 [root] 之内**（App 私有目录），并拒绝符号链接逃逸与 `..` 穿越；
 * 这样模型即使被诱导也无法读写沙箱外的文件。
 */
class LocalToolRegistry(root: File) {

    /**
     * 当前工作区目录。切换本机工作区时更新，文件类工具随之被限制在新目录内
     * （沙箱 → 工作区，两级隔离）。
     */
    private var root: File = root

    init {
        root.mkdirs()
    }

    fun setRoot(newRoot: File) {
        newRoot.mkdirs()
        root = newRoot
    }

    val tools: List<LocalTool> = listOf(
        ReadFileTool(::resolve),
        WriteFileTool(::resolve),
        ListFilesTool(::resolve),
        HttpGetTool(),
    )

    fun find(name: String): LocalTool? = tools.find { it.name == name }

    /** Successful write results become durable display metadata, never request attachments. */
    fun writtenAttachment(arguments: String, output: String): LocalAttachment? {
        if (!output.startsWith("wrote ")) return null
        return runCatching {
            val path = Json.parseToJsonElement(arguments).jsonObject["path"]!!.jsonPrimitive.content
            deliveryAttachment(root, path)
        }.getOrNull()
    }

    /** 供 OpenAI 兼容协议使用的 tools 数组（不含实际实现）。 */
    fun toolsJson(): String = tools.joinToString(",", "[", "]") { tool ->
        """{"type":"function","function":{"name":"${tool.name}","description":"${tool.description}","parameters":${tool.parametersJson}}}"""
    }

    /** 把相对路径解析到沙箱内；越界直接抛错。 */
    private fun resolve(path: String): File {
        val base = root.canonicalFile
        val target = File(base, path.ifBlank { "." }).canonicalFile
        require(target.path == base.path || target.path.startsWith(base.path + File.separator)) {
            "path escapes sandbox: $path"
        }
        return target
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }

    private class ReadFileTool(private val resolve: (String) -> File) : LocalTool {
        override val name = "read_file"
        override val description = "读取沙箱内的文本文件内容"
        override val parametersJson =
            """{"type":"object","properties":{"path":{"type":"string","description":"相对沙箱的路径"}},"required":["path"]}"""

        override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
            val path = Json.parseToJsonElement(argumentsJson).jsonObject["path"]?.jsonPrimitive?.content.orEmpty()
            val file = resolve(path)
            if (!file.isFile) return@withContext "error: no such file: $path"
            file.readText().take(20_000)
        }
    }

    private class WriteFileTool(private val resolve: (String) -> File) : LocalTool {
        override val name = "write_file"
        override val description = "把文本写入沙箱内的文件（覆盖）"
        override val parametersJson =
            """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}"""

        override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
            val root = Json.parseToJsonElement(argumentsJson).jsonObject
            val path = root["path"]?.jsonPrimitive?.content.orEmpty()
            val content = root["content"]?.jsonPrimitive?.content.orEmpty()
            val file = resolve(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            "wrote ${content.length} chars to $path"
        }
    }

    private class ListFilesTool(private val resolve: (String) -> File) : LocalTool {
        override val name = "list_files"
        override val description = "列出沙箱内某个目录的文件"
        override val parametersJson =
            """{"type":"object","properties":{"path":{"type":"string"}},"required":[]}"""

        override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
            val path = runCatching {
                Json.parseToJsonElement(argumentsJson).jsonObject["path"]?.jsonPrimitive?.content
            }.getOrNull().orEmpty()
            val dir = resolve(path)
            if (!dir.isDirectory) return@withContext "error: not a directory: $path"
            dir.listFiles()?.sortedBy { it.name }?.joinToString("\n") { entry ->
                if (entry.isDirectory) "${entry.name}/" else "${entry.name} (${entry.length()}B)"
            }.orEmpty().ifEmpty { "(empty)" }
        }
    }

    private class HttpGetTool : LocalTool {
        override val name = "http_get"
        override val description = "发起一个 HTTP GET 请求并返回响应文本（最多 20000 字符）"
        override val parametersJson =
            """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}"""

        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
            val url = Json.parseToJsonElement(argumentsJson).jsonObject["url"]?.jsonPrimitive?.content.orEmpty()
            require(url.startsWith("http://") || url.startsWith("https://")) { "unsupported url: $url" }
            val request = Request.Builder().url(url).get().build()
            runCatching {
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    "HTTP ${response.code}\n" + body.take(20_000)
                }
            }.getOrElse { error -> "error: ${error.message}" }
        }
    }
}
