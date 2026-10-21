package io.github.hakunm.deepseekharness.local

import java.io.File

/** Recognizes supported raster formats by bytes, never by an untrusted filename. */
internal fun sniffImageMime(header: ByteArray): String? {
    fun matches(offset: Int, vararg values: Int) = header.size >= offset + values.size &&
        values.indices.all { (header[offset + it].toInt() and 255) == values[it] }
    fun ascii(offset: Int, value: String) = matches(offset, *value.map { it.code }.toIntArray())
    return when {
        matches(0, 0xff, 0xd8, 0xff) -> "image/jpeg"
        matches(0, 137, 80, 78, 71, 13, 10, 26, 10) -> "image/png"
        ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
        ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
        matches(0, 66, 77) -> "image/bmp"
        else -> null
    }
}

/** Only call on an IO dispatcher; reads at most twelve bytes for legacy metadata. */
internal fun LocalAttachment.withDetectedLegacyImageType(): LocalAttachment {
    if (mimeType.lowercase() !in setOf("application/octet-stream", "binary/octet-stream")) return this
    val file = File(localPath)
    if (!file.isFile || file.length() > LocalAttachmentStore.MAX_BYTES_PER_ATTACHMENT) return this
    val mime = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(12)
            var count = 0
            while (count < header.size) {
                val read = input.read(header, count, header.size - count)
                if (read <= 0) break
                count += read
            }
            sniffImageMime(header.copyOf(count))
        }
    }.getOrNull()
    return if (mime == null) this else copy(mimeType = mime)
}
