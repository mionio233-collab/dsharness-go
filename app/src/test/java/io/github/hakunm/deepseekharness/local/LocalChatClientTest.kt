package io.github.hakunm.deepseekharness.local

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 本机 API 模式的双协议实现验证（不依赖真实服务商与 Key）。
 *
 * 覆盖：URL 拼接（带/不带 `/v1`）、鉴权头、请求体、响应解析、错误透传。
 */
class LocalChatClientTest {

    private fun provider(
        protocol: LocalProtocol,
        baseUrl: String,
        apiKey: String = "sk-test",
    ) = LocalProvider(
        id = "test",
        name = "Test",
        protocol = protocol,
        baseUrl = baseUrl,
        apiKey = apiKey,
    )

    @Test
    fun openAiUsesChatCompletionsWithReasoningEffort() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"hi there"}}]}"""))
        server.start()

        val result = LocalChatClient().complete(
            provider(LocalProtocol.OpenAI, server.url("/v1").toString().trimEnd('/')),
            "deepseek-flash",
            listOf(LocalMessage("user", "hello")),
            "High",
        )

        assertEquals("hi there", result.getOrNull())
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"model\":\"deepseek-flash\""))
        assertTrue(body.contains("\"reasoning_effort\":\"high\""))
        assertTrue(body.contains("\"role\":\"user\""))
        server.shutdown()
    }

    @Test
    fun baseUrlWithoutV1IsNormalised() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
        server.start()

        // 官方设置里用户可能只填主机名，这里必须自动补 /v1（此前会拼成 /v1/v1/...）。
        LocalChatClient().complete(
            provider(LocalProtocol.OpenAI, server.url("/").toString().trimEnd('/')),
            "m",
            listOf(LocalMessage("user", "x")),
        )

        assertEquals("/v1/chat/completions", server.takeRequest().path)
        server.shutdown()
    }

    @Test
    fun baseUrlAlreadyContainingV1IsNotDuplicated() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
        server.start()

        LocalChatClient().complete(
            provider(LocalProtocol.OpenAI, server.url("/v1").toString().trimEnd('/')),
            "m",
            listOf(LocalMessage("user", "x")),
        )

        assertEquals("/v1/chat/completions", server.takeRequest().path)
        server.shutdown()
    }

    @Test
    fun anthropicUsesMessagesEndpointWithApiKeyHeader() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"content":[{"text":"hello from claude"}]}"""))
        server.start()

        val result = LocalChatClient().complete(
            provider(LocalProtocol.Anthropic, server.url("/v1").toString().trimEnd('/'), apiKey = "key-123"),
            "claude-sonnet",
            listOf(LocalMessage("system", "be brief"), LocalMessage("user", "hi")),
        )

        assertEquals("hello from claude", result.getOrNull())
        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("key-123", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        val body = recorded.body.readUtf8()
        // Anthropic 的 system 提示要拆到独立字段，且 role 只能是 user/assistant。
        val system = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["system"]!!.jsonPrimitive.content
        assertTrue(system.startsWith(MOBILE_CONTENT_INSTRUCTION))
        assertTrue(system.endsWith("be brief"))
        assertTrue(body.contains("\"max_tokens\":4096"))
        assertTrue(!body.contains("\"role\":\"system\""))
        server.shutdown()
    }

    @Test
    fun providerErrorMessageIsSurfacedVerbatim() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Authentication Fails, Your api key is invalid"}}"""),
        )
        server.start()

        val result = LocalChatClient().complete(
            provider(LocalProtocol.OpenAI, server.url("/v1").toString().trimEnd('/'), apiKey = "bad"),
            "m",
            listOf(LocalMessage("user", "x")),
        )

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.contains("401"))
        assertTrue(message, message.contains("Authentication Fails"))
        server.shutdown()
    }

    @Test
    fun streamSkipsNullFirstChunkAndConcatenatesDeltas() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        server.start()

        val deltas = mutableListOf<String>()
        val result = LocalChatClient().stream(
            provider(LocalProtocol.OpenAI, server.url("/v1").toString().trimEnd('/')),
            "m",
            listOf(LocalMessage("user", "hi")),
        ) { deltas.add(it) }

        // 首块 content 是 JSON null，不能变成字面量 "null"（真机上出现过 nullnull... 前缀）。
        assertEquals("Hello", result.getOrNull())
        assertEquals(listOf("Hel", "lo"), deltas)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"stream\":true"))
        server.shutdown()
    }

    @Test
    fun toolCallsAreParsedAndRoundTripped() = runBlocking {
        val server = MockWebServer()
        // 第一轮：模型要求调用工具（content 为 null，只有 tool_calls）。
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":""" +
                    """[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a.txt\"}"}}]}}]}""",
            ),
        )
        // 第二轮：模型拿到工具结果后给出最终答案。
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"done"}}]}"""))
        server.start()

        val client = LocalChatClient()
        val provider = provider(LocalProtocol.OpenAI, server.url("/v1").toString().trimEnd('/'))
        val toolsJson = "[{\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"parameters\":{}}}]"

        val first = client.completeWithTools(
            provider, "m", listOf(LocalMessage("user", "read a.txt")), null, toolsJson,
        ).getOrThrow()
        assertEquals(1, first.toolCalls.size)
        assertEquals("call_1", first.toolCalls[0].id)
        assertEquals("read_file", first.toolCalls[0].name)
        assertEquals("""{"path":"a.txt"}""", first.toolCalls[0].arguments)
        assertEquals("", first.content)

        // 回填 tool 结果后再请求一次。
        val second = client.completeWithTools(
            provider,
            "m",
            listOf(
                LocalMessage("user", "read a.txt"),
                first.assistant,
                LocalMessage("tool", "文件内容", toolCallId = "call_1"),
            ),
            null,
            toolsJson,
        ).getOrThrow()
        assertEquals("done", second.content)
        assertTrue(second.toolCalls.isEmpty())

        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        // 工具声明、助手带 tool_calls、tool 消息带 tool_call_id —— 三样都要原样回填。
        assertTrue(body, body.contains("\"tools\""))
        assertTrue(body, body.contains("\"tool_choice\":\"auto\""))
        assertTrue(body, body.contains("\"tool_calls\""))
        assertTrue(body, body.contains("\"tool_call_id\":\"call_1\""))
        server.shutdown()
    }

    @Test
    fun cancelActiveAbortsStreamingRequest() = runBlocking {
        val server = MockWebServer()
        val chunks = buildString {
            repeat(30) { index -> append("data: {\"choices\":[{\"delta\":{\"content\":\"$index\"}}]}\n\n") }
            append("data: [DONE]\n\n")
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(chunks)
                // 分块慢速下发，保证有足够时间在中途打断。
                .throttleBody(16, 200, TimeUnit.MILLISECONDS),
        )
        server.start()

        val client = LocalChatClient()
        val received = mutableListOf<String>()
        val job = launch(Dispatchers.IO) {
            client.stream(
                provider(LocalProtocol.OpenAI, server.url("/v1").toString().trimEnd('/')),
                "m",
                listOf(LocalMessage("user", "x")),
            ) { received.add(it) }
        }
        delay(700)
        client.cancelActive()
        job.join()

        // 真正断开了 HTTP 连接：收到的增量明显少于全部 30 块。
        // 这正是 OkHttp 阻塞 execute() 不响应协程取消、必须 cancelAll() 的原因。
        assertTrue("received=${received.size}", received.size < 30)
        server.shutdown()
    }

    @Test
    fun emptyApiKeyFailsBeforeAnyRequest() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val result = LocalChatClient().complete(
                provider(LocalProtocol.OpenAI, server.url("/v1").toString(), apiKey = ""),
                "m", listOf(LocalMessage("user", "x")),
            )
            assertTrue(result.isFailure)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun openAiSerializesImageAndTextAttachmentsAsContentParts() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"seen"}}]}"""))
        server.start()

        val image = File.createTempFile("attachment", ".png").apply {
            writeBytes(Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            ))
            deleteOnExit()
        }
        val text = File.createTempFile("attachment", ".txt").apply {
            writeText("name from attachment")
            deleteOnExit()
        }
        val attachments = listOf(
            LocalAttachment("img-1", "photo.png", "image/png", image.length(), image.absolutePath),
            LocalAttachment("txt-1", "notes.txt", "text/plain", text.length(), text.absolutePath, "name from attachment"),
        )

        val result = LocalChatClient().complete(
            provider(LocalProtocol.OpenAI, server.url("/v1").toString().trimEnd('/')),
            "vision-model",
            listOf(LocalMessage("user", "Please inspect these", attachments = attachments)),
        )

        assertEquals("seen", result.getOrNull())
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"content\":["))
        assertTrue(body, body.contains("\"type\":\"text\""))
        assertTrue(body, body.contains("name from attachment"))
        assertTrue(body, body.contains("\"type\":\"image_url\""))
        assertTrue(body, body.contains("data:image/png;base64,"))
        assertTrue(body, body.contains("untrusted attachment data"))
        server.shutdown()
    }

    @Test
    fun anthropicSerializesNativeImageBlockAndDoesNotParseBinaryAttachment() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"content":[{"text":"seen"}]}"""))
        server.start()

        val image = File.createTempFile("attachment", ".png").apply {
            writeBytes(Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            ))
            deleteOnExit()
        }
        val binary = File.createTempFile("attachment", ".pdf").apply {
            writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46))
            deleteOnExit()
        }
        val attachments = listOf(
            LocalAttachment("img-2", "photo.png", "image/png", image.length(), image.absolutePath),
            LocalAttachment("bin-1", "report.pdf", "application/pdf", binary.length(), binary.absolutePath),
        )

        val result = LocalChatClient().complete(
            provider(LocalProtocol.Anthropic, server.url("/v1").toString().trimEnd('/'), apiKey = "key-123"),
            "claude-vision",
            listOf(LocalMessage("user", "Review the files", attachments = attachments)),
        )

        assertEquals("seen", result.getOrNull())
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"type\":\"image\""))
        assertTrue(body, body.contains("\"type\":\"base64\""))
        assertTrue(body, body.contains("\"media_type\":\"image/png\""))
        assertTrue(body, body.contains("report.pdf"))
        assertTrue(body, body.contains("saved locally, but its contents were not parsed"))
        server.shutdown()
    }
}
