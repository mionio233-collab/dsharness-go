package io.github.hakunm.deepseekharness.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Installs instruction documents, never executes repository scripts. */
class MobileSkillStore(context: Context) {
    private val root = File(context.filesDir, "mobile_skills").apply { mkdirs() }
    fun installed(): List<File> = root.listFiles().orEmpty().filter { it.extension == "md" }.sortedBy { it.name }
    fun remove(file: File) { require(file.canonicalFile.parentFile == root.canonicalFile); file.delete() }
    suspend fun instructions(): String {
        return (installed().take(8).joinToString("\n\n") {
        "[Installed user skill: ${it.name}]\n${it.readText().take(12000)}"
    }).take(75000)
    }
    suspend fun install(source: String): String = withContext(Dispatchers.IO) {
        val raw = source.trim()
        val url = when {
            raw.startsWith("https://github.com/") -> {
                val parts = raw.removePrefix("https://github.com/").trimEnd('/').split('/')
                require(parts.size >= 2)
                if (parts.size >= 5 && parts[2] == "blob") "https://raw.githubusercontent.com/${parts[0]}/${parts[1]}/${parts.drop(3).joinToString("/")}"
                else "https://raw.githubusercontent.com/${parts[0]}/${parts[1]}/main/SKILL.md"
            }
            else -> raw
        }
        require(url.startsWith("https://")) { "请输入 HTTPS SKILL.md 地址或 GitHub 仓库地址" }
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val text = http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            check(response.isSuccessful) { "下载 HTTP ${response.code}；请指定仓库中 SKILL.md 的完整地址" }
            val buffer = okio.Buffer()
            val input = response.body?.source() ?: error("文档为空")
            while (buffer.size <= 65536) { if (input.read(buffer, 65537 - buffer.size) == -1L) break }
            require(buffer.size <= 65536) { "技能文档超过 64 KB" }
            buffer.readUtf8()
        }
        require(text.isNotBlank() && !text.trimStart().startsWith("<!DOCTYPE", true)) { "不是有效技能文档" }
        val id = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        val file = File(root, "$id.md")
        file.writeText(text)
        file.name
    }
}
