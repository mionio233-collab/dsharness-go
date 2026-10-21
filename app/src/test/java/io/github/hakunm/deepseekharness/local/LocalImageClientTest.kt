package io.github.hakunm.deepseekharness.local

import java.util.Base64
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class LocalImageClientTest {
    @Test fun imagesApiUsesExplicitImageModelAndReturnsDecodedBytes() {
        MockWebServer().use { server ->
            server.start()
            val bytes = byteArrayOf(1, 2, 3, 4)
            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(bytes)}"}]}"""))
            val provider = LocalProvider("test", "Test", LocalProtocol.OpenAI, server.url("/v1").toString(), "test-key")
            assertArrayEquals(bytes, LocalImageClient().generate(provider, "image-model", "A landscape"))
            val request = server.takeRequest()
            assertEquals("/v1/images/generations", request.path)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("image-model", body["model"]?.jsonPrimitive?.content)
            assertEquals("A landscape", body["prompt"]?.jsonPrimitive?.content)
            assertNull(body["messages"])
        }
    }
    @Test fun unsupportedEndpointReportsFailureWithoutCreatingFakeImage() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(404))
            val provider = LocalProvider("t", "Test", LocalProtocol.OpenAI, server.url("/v1").toString(), "key")
            val failure = assertThrows(IllegalStateException::class.java) { LocalImageClient().generate(provider, "chat-model", "image") }
            assertTrue(failure.message!!.contains("404"))
        }
    }
    @Test fun absentImageAndInsecureImageUrlAreRejected() {
        MockWebServer().use { server ->
            server.start()
            val provider = LocalProvider("t", "Test", LocalProtocol.OpenAI, server.url("/v1").toString(), "key")
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            assertThrows(IllegalStateException::class.java) { LocalImageClient().generate(provider, "image", "image") }
            server.enqueue(MockResponse().setBody("""{"data":[{"url":"http://untrusted.test/image.png"}]}"""))
            assertThrows(IllegalArgumentException::class.java) { LocalImageClient().generate(provider, "image", "image") }
        }
    }
}
