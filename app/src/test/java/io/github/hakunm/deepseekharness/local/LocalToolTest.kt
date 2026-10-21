package io.github.hakunm.deepseekharness.local

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 本机 agent 工具的验证：文件工具必须在沙箱内、HTTP 工具要真的发请求。
 */
class LocalToolTest {

    private fun registry(): LocalToolRegistry =
        LocalToolRegistry(Files.createTempDirectory("pocket").toFile())

    @Test
    fun writeThenReadRoundTrip() = runBlocking {
        val tools = registry()
        val written = tools.find("write_file")!!.execute("""{"path":"notes/a.txt","content":"hello 世界"}""")
        assertTrue(written, written.startsWith("wrote"))

        // 子目录会被自动创建，内容原样读回。
        assertEquals("hello 世界", tools.find("read_file")!!.execute("""{"path":"notes/a.txt"}"""))
    }

    @Test
    fun pathTraversalIsRejected() = runBlocking {
        val root = Files.createTempDirectory("pocket").toFile()
        val tools = LocalToolRegistry(root)

        val result = runCatching {
            tools.find("write_file")!!.execute("""{"path":"../escaped.txt","content":"x"}""")
        }

        assertTrue("should reject escaping path", result.isFailure)
        assertFalse(File(root.parentFile, "escaped.txt").exists())
    }

    @Test
    fun readMissingFileReturnsErrorText() = runBlocking {
        val text = registry().find("read_file")!!.execute("""{"path":"nope.txt"}""")
        assertTrue(text.startsWith("error:"))
    }

    @Test
    fun listFilesDescribesEntries() = runBlocking {
        val tools = registry()
        tools.find("write_file")!!.execute("""{"path":"b.txt","content":"12"}""")
        tools.find("write_file")!!.execute("""{"path":"sub/c.txt","content":"x"}""")

        val listing = tools.find("list_files")!!.execute("""{"path":"."}""")
        assertTrue(listing, listing.contains("b.txt (2B)"))
        assertTrue(listing, listing.contains("sub/"))
    }

    @Test
    fun httpGetPerformsRealRequest() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("pong"))
        server.start()

        val result = registry().find("http_get")!!
            .execute("""{"url":"${server.url("/ping")}"}""")

        assertTrue(result, result.contains("HTTP 200"))
        assertTrue(result, result.contains("pong"))
        assertEquals("/ping", server.takeRequest().path)
        server.shutdown()
    }

    @Test
    fun httpGetRejectsNonHttpScheme() = runBlocking {
        val result = runCatching {
            registry().find("http_get")!!.execute("""{"url":"file:///etc/passwd"}""")
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun toolsJsonExposesFunctionSchemas() {
        val json = registry().toolsJson()
        listOf("read_file", "write_file", "list_files", "http_get").forEach { name ->
            assertTrue(json, json.contains("\"name\":\"$name\""))
        }
        assertTrue(json, json.contains("\"parameters\""))
        assertTrue(json.startsWith("["))
    }
}
