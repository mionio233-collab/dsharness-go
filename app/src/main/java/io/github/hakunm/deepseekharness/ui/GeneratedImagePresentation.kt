package io.github.hakunm.deepseekharness.ui

/** Remove duplicate Markdown image slots only when the message has a persisted image. */
internal fun imageReplyText(content: String, hasLocalImage: Boolean): String {
    if (!hasLocalImage) return content
    return content.replace(Regex("!\\[[^\\]]*\\]\\([^\\n]+\\)"), "")
        .replace(Regex("(?is)<img\\b[^>]*>"), "")
        .replace(Regex("\\n[ \\t]*\\n(?:[ \\t]*\\n)+"), "\n\n").trim()
}

internal fun imageDisplayName(original: String, prompt: String): String {
    if (!original.startsWith("remote-") && !original.startsWith("generated-") && !original.startsWith("图片已生成")) return original
    val stem = prompt.replace(Regex("^(请|帮我|生成|画|一个|一张|给我|制作)+"), "")
        .replace(Regex("[\\r\\n<>:\"/\\\\|?*]"), "_").trim().take(36)
    val extension = original.substringAfterLast('.', "png")
    return "${stem.ifBlank { "生成图片" }}.$extension"
}
