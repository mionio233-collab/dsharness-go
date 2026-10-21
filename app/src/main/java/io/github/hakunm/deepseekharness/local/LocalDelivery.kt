package io.github.hakunm.deepseekharness.local

import java.io.File
import java.net.URLConnection
import java.util.UUID

/** Canonical containment is checked both when recording and when opening a delivery. */
fun deliveryFile(root: File, path: String): File? = runCatching {
    val base = root.canonicalFile
    File(base, path).canonicalFile.takeIf { it.isFile && it.path.startsWith(base.path + File.separator) }
}.getOrNull()

fun deliveryAttachment(root: File, path: String): LocalAttachment? = deliveryFile(root, path)?.let {
    LocalAttachment(UUID.randomUUID().toString(), it.name, deliveryMime(it), it.length(), it.path,
        workspacePath = root.canonicalPath)
}

fun deliveryMime(file: File): String = when (file.extension.lowercase()) {
    "html", "htm" -> "text/html"
    "css" -> "text/css"
    "js", "mjs" -> "application/javascript"
    "md" -> "text/markdown"
    "json" -> "application/json"
    "svg" -> "image/svg+xml"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    else -> URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
}

/** Saving returned HTML is delivery, not execution. No model paths or titles are trusted here. */
fun saveHtmlDelivery(root: File, content: String): LocalAttachment? {
    val html = Regex("```(?:html|htm)\\s*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        .find(content)?.groupValues?.get(1)
        ?: content.trim().takeIf { it.startsWith("<!doctype html", true) || it.startsWith("<html", true) }
        ?: return null
    root.mkdirs()
    val name = "page-${UUID.randomUUID()}.html"
    val base = root.canonicalFile
    val target = File(base, name)
    target.writeText(html)
    return deliveryAttachment(base, name)
}

/** The durable source stays in transport history; only the chat body hides delivered HTML. */
fun deliveredReplyText(message: LocalMessage): String {
    if (message.attachments.none { it.mimeType == "text/html" }) return message.content
    val text = message.content.trim()
    if (text.startsWith("<!doctype html", true) || text.startsWith("<html", true)) return ""
    return message.content.replace(Regex("```(?:html|htm)\\s*\\n[\\s\\S]*?```", RegexOption.IGNORE_CASE), "").trim()
}
