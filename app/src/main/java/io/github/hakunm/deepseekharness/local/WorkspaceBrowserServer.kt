package io.github.hakunm.deepseekharness.local

import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A short-lived capability server: loopback only, GET/HEAD only, fixed public-asset allowlist.
 * No directory listings, writes, proxying, private app storage, hidden files or arbitrary JSON.
 * Relative CSS/JS/images/fonts retain their directory structure and MIME types.
 */
class WorkspaceBrowserServer(root: File, entry: File, lifetimeMillis: Long = 15 * 60_000L,
    includeSubdirectories: Boolean = true) : Closeable {
    private val base = root.canonicalFile
    private val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val assets: Map<String, File>
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val workers = java.util.concurrent.ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(16), { task -> Thread(task, "workspace-browser").apply { isDaemon = true } })
    private val expiry = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "workspace-browser-expiry").apply { isDaemon = true } }
    val url: String
    init {
        try {
            val page = entry.canonicalFile
            require(page.isFile && page.path.startsWith(base.path + File.separator)) { "Page outside workspace" }
            require(page.extension.lowercase() in setOf("html", "htm")) { "Not an HTML page" }
            val allowed = setOf("css", "js", "mjs", "png", "jpg", "jpeg", "webp", "gif", "svg", "ico", "woff", "woff2", "ttf", "otf", "mp4", "webm", "mp3", "wav")
            val files = mutableMapOf(page.relativeTo(base).invariantSeparatorsPath to page)
            var visited = 0
            // Never follow directory symlinks; cap traversal and individual response size.
            fun collect(dir: File, depth: Int) {
                if (depth > 8 || visited >= 2048) return
                for (file in dir.listFiles().orEmpty()) {
                    if (++visited > 2048) return
                    if (file.name.startsWith('.')) continue
                    val canonical = file.canonicalFile
                    if (canonical.path != file.absolutePath || !canonical.path.startsWith(base.path + File.separator)) continue
                    if (file.isDirectory) {
                        if (includeSubdirectories) collect(file, depth + 1)
                    } else if (file.extension.lowercase() in allowed && file.length() <= 32 * 1024 * 1024L)
                        files[file.relativeTo(base).invariantSeparatorsPath] = file
                }
            }
            collect(base, 0)
            require(page.length() <= 32 * 1024 * 1024L) { "Page too large" }
            assets = files.toMap()
            val path = URI(null, null, "/$token/${page.relativeTo(base).invariantSeparatorsPath}", null).rawPath
            url = "http://127.0.0.1:${socket.localPort}$path"
            expiry.schedule({ close() }, lifetimeMillis.coerceIn(1L, 15 * 60_000L), TimeUnit.MILLISECONDS)
            Thread({
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    try { workers.execute { client.use { serve(it) } } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { client.close() }
                }
            }, "workspace-browser-accept").apply { isDaemon = true; start() }
        } catch (failure: Exception) { close(); throw failure }
    }
    private fun serve(client: Socket) {
        try {
            client.soTimeout = 3000
            val input = client.getInputStream().buffered()
            fun line(): String {
                val bytes = java.io.ByteArrayOutputStream()
                while (bytes.size() < 8192) {
                    val next = input.read()
                    if (next < 0 || next == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                    bytes.write(next)
                }
                error("Request line too long")
            }
            val request = line().split(' ')
            val headers = mutableMapOf<String, String>()
            var headerBytes = 0
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                headerBytes += header.length
                require(headerBytes < 16_384)
                headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
            }
            val method = request.firstOrNull()
            val raw = request.getOrNull(1).orEmpty()
            val uri = runCatching { URI(raw) }.getOrNull()
            val path = uri?.path.orEmpty()
            val prefix = "/$token/"
            val relative = path.removePrefix(prefix)
            val file = assets[relative]?.takeIf {
                path.startsWith(prefix) && !uri!!.isAbsolute &&
                    relative.split('/').none { part -> part == ".." || part == "." || part.startsWith('.') } &&
                    it.canonicalFile == it && it.isFile && it.length() <= 32 * 1024 * 1024L
            }
            val hostOk = headers["host"] == "127.0.0.1:${socket.localPort}"
            val status = if (method !in setOf("GET", "HEAD")) 405 else if (!hostOk || file == null) 404 else 200
            val body = if (status == 200) null else "Unavailable".toByteArray()
            val length = file?.takeIf { status == 200 }?.length() ?: body!!.size.toLong()
            val output = client.getOutputStream().buffered()
            val mime = if (status == 200) deliveryMime(file!!) else "text/plain"
            output.write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Unavailable"}\r\n" +
                "Content-Type: $mime\r\nContent-Length: $length\r\nConnection: close\r\n" +
                "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n\r\n").toByteArray())
            if (method != "HEAD") {
                if (status == 200) file!!.inputStream().use { it.copyTo(output) } else output.write(body!!)
            }
            output.flush()
        } catch (_: Exception) { /* Malformed requests never reveal paths or errors. */ }
    }
    override fun close() {
        socket.close()
        workers.shutdownNow()
        expiry.shutdownNow()
    }
}
