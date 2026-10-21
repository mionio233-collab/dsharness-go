package io.github.hakunm.deepseekharness.local

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Imports user-selected SAF/photo-picker content into private app storage. */
class LocalAttachmentStore(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val directory = File(appContext.filesDir, DIRECTORY_NAME).apply { mkdirs() }

    /** Downloads an assistant-provided HTTPS image once and persists it locally. */
    suspend fun saveRemoteImage(url: String, requestedName: String? = null): LocalAttachment = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Only HTTPS image URLs are supported" }
        val id = UUID.nameUUIDFromBytes(url.toByteArray(Charsets.UTF_8)).toString()
        val existing = File(directory, id)
        val extensionFor = { mime: String -> if (mime == "image/jpeg") "jpg" else mime.substringAfterLast('/').ifBlank { "png" } }
        if (existing.isFile && existing.length() in 1..MAX_BYTES_PER_ATTACHMENT) {
            val base = requestedName?.trim()?.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")?.trim().orEmpty()
                .ifBlank { "generated-$id" }
            return@withContext LocalAttachment(id, if (base.contains('.')) base else "$base.png", "image/png", existing.length(), existing.absolutePath)
        }
        val client = okhttp3.OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        client.newCall(okhttp3.Request.Builder().url(url).get().build()).execute().use { response ->
            check(response.isSuccessful) { "Remote image HTTP ${response.code}" }
            val body = response.body ?: error("Remote image response is empty")
            require(body.contentLength() <= 20L * 1024 * 1024 || body.contentLength() == -1L)
            val bytes = body.bytes()
            require(bytes.size in 1..20 * 1024 * 1024)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val mime = bounds.outMimeType
            require(mime in PREVIEW_MIME_TYPES) { "Remote response is not a supported image" }
            existing.writeBytes(bytes)
            val base = requestedName?.trim()?.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")?.trim().orEmpty()
                .ifBlank { "generated-$id" }
            LocalAttachment(id, if (base.contains('.')) base else "$base.${extensionFor(mime)}", mime, existing.length(), existing.absolutePath)
        }
    }

    /** Persists generated image bytes using a user/model supplied name when available. */
    suspend fun saveGeneratedImage(bytes: ByteArray, requestedName: String? = null): LocalAttachment = withContext(Dispatchers.IO) {
        require(bytes.size in 1..MAX_BYTES_PER_ATTACHMENT) { "Generated image exceeds size limit" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType in PREVIEW_MIME_TYPES) {
            "Provider did not return a supported image"
        }
        val id = UUID.randomUUID().toString()
        val extension = if (bounds.outMimeType == "image/png") "png" else "jpg"
        val safeName = requestedName.orEmpty().trim().replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
            .trim().take(120).ifBlank { "generated-$id" }
        val file = File(directory, id)
        file.writeBytes(bytes)
        LocalAttachment(id, if (safeName.substringAfterLast('.', "").equals(extension, true)) safeName else "$safeName.$extension",
            bounds.outMimeType, file.length(), file.absolutePath)
    }

    /** Persists generated audio/video bytes; the MIME type comes from the generation client, not the provider. */
    suspend fun saveGeneratedMedia(bytes: ByteArray, mimeType: String, extension: String, requestedName: String? = null): LocalAttachment =
        withContext(Dispatchers.IO) {
            require(bytes.size in 1..MAX_BYTES_PER_ATTACHMENT) { "Generated media exceeds size limit" }
            require(mimeType.startsWith("audio/") || mimeType.startsWith("video/")) { "Unsupported generated media type" }
            val id = UUID.randomUUID().toString()
            val safeName = requestedName.orEmpty().trim().replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
                .trim().take(120).ifBlank { "generated-$id" }
            // Keep the extension on disk so the system player resolves the MIME type when opened.
            val file = File(directory, "$id.${extension.filter(Char::isLetterOrDigit).take(8)}")
            file.writeBytes(bytes)
            LocalAttachment(id, if (safeName.substringAfterLast('.', "").equals(extension, true)) safeName else "$safeName.$extension",
                mimeType, file.length(), file.absolutePath)
        }

    /**
     * Imports one user-granted content URI. The source is copied once into app-private storage;
     * no source URI is retained in the message history.
     */
    suspend fun `import`(uri: Uri): LocalAttachment = withContext(Dispatchers.IO) {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) {
            "Only a granted content URI from the system picker is supported"
        }

        require(appContext.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            "Read permission for the selected attachment was not granted"
        }
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create attachment storage" }
        val id = UUID.randomUUID().toString()
        val destination = File(directory, id)
        try {
            val metadata = queryMetadata(uri)
            if (metadata.sizeBytes > MAX_BYTES_PER_ATTACHMENT) {
                throw IOException("Attachment exceeds ${MAX_BYTES_PER_ATTACHMENT / (1024 * 1024)} MB limit")
            }
            val declaredMime = resolver.getType(uri)?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            val name = sanitizeName(metadata.displayName ?: "attachment")

            resolver.openInputStream(uri)?.use { input ->
                copyBounded(input, destination, MAX_BYTES_PER_ATTACHMENT)
            } ?: throw IOException("Unable to open selected attachment")

            val detectedMime = detectMime(destination, name)
            val sourceMime = declaredMime
                ?.takeUnless { it == "application/octet-stream" || it == "binary/octet-stream" }
                ?: detectedMime
            val imageMime = detectedMime
            val normalized = if (imageMime.startsWith("image/")) {
                normalizeImage(destination, id, imageMime)
            } else {
                NormalizedFile(destination, sourceMime.takeUnless { it.startsWith("image/") }
                    ?: "application/octet-stream")
            }
            if (normalized.file !== destination) destination.delete()

            val textContent = if (!normalized.mimeType.startsWith("image/") && isText(normalized.mimeType, name)) {
                readUtf8Bounded(normalized.file)
            } else {
                null
            }
            LocalAttachment(
                id = id,
                name = name,
                mimeType = normalized.mimeType,
                sizeBytes = normalized.file.length(),
                localPath = normalized.file.absolutePath,
                textContent = textContent,
            )
        } catch (failure: Throwable) {
            destination.delete()
            File(directory, "$id.jpg").delete()
            File(directory, "$id.png").delete()
            if (failure is CancellationException) throw failure
            if (failure is IOException || failure is IllegalArgumentException) throw failure
            throw IOException("Unable to import attachment: ${failure.message ?: "unknown error"}", failure)
        }
    }

    /** Convenience boundary for picker callers that support multi-select. */
    suspend fun importAll(uris: List<Uri>): List<LocalAttachment> {
        require(uris.size <= MAX_ATTACHMENTS_PER_MESSAGE) {
            "At most $MAX_ATTACHMENTS_PER_MESSAGE attachments can be selected"
        }
        val imported = mutableListOf<LocalAttachment>()
        try {
            uris.forEach { imported += `import`(it) }
            return imported
        } catch (failure: Throwable) {
            imported.forEach { File(it.localPath).delete() }
            throw failure
        }
    }

    /** Resolves only app-private imported files. History paths cannot request network/provider data. */
    private fun storedFile(attachment: LocalAttachment): File? {
        val file = File(attachment.localPath).canonicalFile
        return file.takeIf {
            it.parentFile == directory.canonicalFile && it.isFile && it.canRead() &&
                it.length() in 1..MAX_BYTES_PER_ATTACHMENT
        }
    }

    /** Bounds sniffing and sampled decoding also recognize old octet-stream photo imports. */
    suspend fun loadPreview(attachment: LocalAttachment, maxDimension: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val file = storedFile(attachment) ?: return@withContext null
            // 视频文件提取首帧
            if (attachment.isVideo) {
                return@withContext extractVideoFrame(file, maxDimension)
            }
            // 图片文件正常解码
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outMimeType !in PREVIEW_MIME_TYPES) return@withContext null
            val limit = maxDimension.coerceIn(128, MAX_IMAGE_DIMENSION)
            var sample = 1
            while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > limit) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            null
        }
    }

    /** 从视频文件提取首帧作为缩略图 */
    private fun extractVideoFrame(file: File, maxDimension: Int): Bitmap? {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(0) ?: return null
            retriever.release()
            // 如果尺寸过大，缩放
            val limit = maxDimension.coerceIn(128, MAX_IMAGE_DIMENSION)
            if (maxOf(frame.width, frame.height) <= limit) {
                frame
            } else {
                val ratio = limit.toFloat() / maxOf(frame.width, frame.height)
                val scaled = Bitmap.createScaledBitmap(frame, (frame.width * ratio).toInt(), (frame.height * ratio).toInt(), true)
                if (scaled !== frame) frame.recycle()
                scaled
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryMetadata(uri: Uri): SourceMetadata {
        return resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use SourceMetadata(null, -1L)
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            SourceMetadata(
                displayName = nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
                sizeBytes = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong) ?: -1L,
            )
        } ?: SourceMetadata(null, -1L)
    }

    private fun copyBounded(input: java.io.InputStream, destination: File, maxBytes: Long) {
        destination.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var copied = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                copied += read
                if (copied > maxBytes) {
                    throw IOException("Attachment exceeds ${maxBytes / (1024 * 1024)} MB limit")
                }
                output.write(buffer, 0, read)
            }
        }
    }

    private fun normalizeImage(source: File, id: String, sourceMime: String): NormalizedFile {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Selected image could not be decoded")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSample(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, options)
            ?: throw IOException("Selected image could not be decoded")
        val scale = minOf(1f, MAX_IMAGE_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat())
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val keepPng = sourceMime == "image/png"
        val output = File(directory, "$id.${if (keepPng) "png" else "jpg"}")
        try {
            output.outputStream().use { stream ->
                val format = if (keepPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                if (!scaled.compress(format, 90, stream)) {
                    throw IOException("Selected image could not be normalized")
                }
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
        if (output.length() > MAX_BYTES_PER_ATTACHMENT) {
            output.delete()
            throw IOException("Normalized image exceeds ${MAX_BYTES_PER_ATTACHMENT / (1024 * 1024)} MB limit")
        }
        return NormalizedFile(output, if (keepPng) "image/png" else "image/jpeg")
    }

    private fun calculateSample(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width / sample, height / sample) > MAX_IMAGE_DIMENSION) sample *= 2
        return sample
    }

    private fun detectMime(file: File, name: String): String {
        // Verified file contents take precedence even for random or misleading display names.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType in PREVIEW_MIME_TYPES) {
            return bounds.outMimeType.lowercase()
        }
        val byName = when (name.substringAfterLast('.', "").lowercase()) {
            "txt" -> "text/plain"
            "md", "markdown" -> "text/markdown"
            "json" -> "application/json"
            "csv" -> "text/csv"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "yaml", "yml" -> "application/yaml"
            else -> null
        }
        return byName ?: "application/octet-stream"
    }

    private fun readUtf8Bounded(file: File): String {
        val bytes = file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > MAX_TEXT_BYTES) {
                    throw IOException("Text attachment exceeds ${MAX_TEXT_BYTES / 1024} KB limit")
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (failure: java.nio.charset.CharacterCodingException) {
            throw IOException("Text attachment is not valid UTF-8", failure)
        }
    }

    private fun isText(mime: String, name: String): Boolean =
        mime.startsWith("text/") || mime in TEXT_MIME_TYPES ||
            name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

    private fun sanitizeName(raw: String): String {
        val value = raw.substringAfterLast('/').replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().take(120)
        return value.ifBlank { "attachment" }
    }

    private data class SourceMetadata(val displayName: String?, val sizeBytes: Long)
    private data class NormalizedFile(val file: File, val mimeType: String)

    companion object {
        const val MAX_BYTES_PER_ATTACHMENT = 25L * 1024 * 1024
        const val MAX_TEXT_BYTES = 20 * 1024
        const val MAX_ATTACHMENTS_PER_MESSAGE = 4
        const val MAX_IMAGE_DIMENSION = 1600
        private val PREVIEW_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp")
        private const val DIRECTORY_NAME = "chat_attachments"
        private val TEXT_MIME_TYPES = setOf(
            "application/json",
            "application/ld+json",
            "application/xml",
            "application/javascript",
            "application/x-javascript",
            "application/x-yaml",
            "text/csv",
            "text/html",
            "text/markdown",
            "text/plain",
            "text/xml",
        )
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "json", "csv", "xml", "html", "htm", "log",
            "yaml", "yml", "kt", "java", "py", "js", "ts", "css", "sql",
        )
    }
}
