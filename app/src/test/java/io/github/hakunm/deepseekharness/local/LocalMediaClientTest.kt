package io.github.hakunm.deepseekharness.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class LocalMediaClientTest {
    private fun client() = LocalMediaClient(pollIntervalMs = 1, maxWaitMs = 5_000, allowInsecureDownloads = true)

    @Test fun musicPostsMiniMaxBodyAndDecodesHexAudio() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"data":{"audio":"494433ff","status":2},"base_resp":{"status_code":0,"status_msg":"success"}}"""))
            val api = CustomApi(server.url("/v1").toString(), "music-key", "music-2.6")
            val media = client().generateMusic(api, "Indie folk", null, instrumental = false)
            assertArrayEquals(byteArrayOf(0x49, 0x44, 0x33, 0xff.toByte()), media.bytes)
            assertEquals("audio/mpeg", media.mimeType)
            val request = server.takeRequest()
            assertEquals("/v1/music_generation", request.path)
            assertEquals("Bearer music-key", request.getHeader("Authorization"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("music-2.6", body["model"]?.jsonPrimitive?.content)
            assertEquals("hex", body["output_format"]?.jsonPrimitive?.content)
            assertEquals(true, body["lyrics_optimizer"]?.jsonPrimitive?.boolean)
        }
    }

    @Test fun musicBaseRespErrorIsReported() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"base_resp":{"status_code":1004,"status_msg":"auth failed"}}"""))
            val api = CustomApi(server.url("/v1").toString(), "k", "music-2.6")
            val failure = runCatching { client().generateMusic(api, "x", null, true) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException && failure.message!!.contains("1004"))
        }
    }

    @Test fun seedanceCreatesTaskPollsAndDownloadsWithoutKey() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val videoUrl = server.url("/video.mp4").toString()
            server.enqueue(MockResponse().setBody("""{"id":"cgt-1"}"""))
            server.enqueue(MockResponse().setBody("""{"id":"cgt-1","status":"running"}"""))
            server.enqueue(MockResponse().setBody("""{"id":"cgt-1","status":"succeeded","content":{"video_url":"$videoUrl"}}"""))
            server.enqueue(MockResponse().setBody("MP4DATA"))
            val setting = VideoSetting(VideoProtocol.Seedance,
                CustomApi(server.url("/api/v3").toString(), "ark-key", "seedance"), durationSeconds = 5, ratio = "16:9")
            val media = client().generateVideo(setting, "A cat")
            assertEquals("MP4DATA", media.bytes.toString(Charsets.UTF_8))
            val create = server.takeRequest()
            assertEquals("/api/v3/contents/generations/tasks", create.path)
            val text = Json.parseToJsonElement(create.body.readUtf8()).jsonObject["content"]!!.jsonArray[0]
                .jsonObject["text"]!!.jsonPrimitive.content
            assertEquals("A cat --duration 5 --ratio 16:9", text)
            assertEquals("/api/v3/contents/generations/tasks/cgt-1", server.takeRequest().path)
            server.takeRequest()
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun miniMaxVideoQueriesTaskThenRetrievesFile() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val videoUrl = server.url("/out.mp4").toString()
            server.enqueue(MockResponse().setBody("""{"task_id":"t1","base_resp":{"status_code":0}}"""))
            server.enqueue(MockResponse().setBody("""{"task_id":"t1","status":"Processing","base_resp":{"status_code":0}}"""))
            server.enqueue(MockResponse().setBody("""{"task_id":"t1","status":"Success","file_id":"f1","base_resp":{"status_code":0}}"""))
            server.enqueue(MockResponse().setBody("""{"file":{"file_id":"f1","download_url":"$videoUrl"},"base_resp":{"status_code":0}}"""))
            server.enqueue(MockResponse().setBody("VID"))
            val setting = VideoSetting(VideoProtocol.MiniMax,
                CustomApi(server.url("/v1").toString(), "mm-key", "MiniMax-Hailuo-02"), resolution = "768p")
            assertEquals("VID", client().generateVideo(setting, "City").bytes.toString(Charsets.UTF_8))
            val create = server.takeRequest()
            assertEquals("/v1/video_generation", create.path)
            assertEquals("768P", Json.parseToJsonElement(create.body.readUtf8()).jsonObject["resolution"]?.jsonPrimitive?.content)
            assertEquals("/v1/query/video_generation?task_id=t1", server.takeRequest().path)
            server.takeRequest()
            assertEquals("/v1/files/retrieve?file_id=f1", server.takeRequest().path)
        }
    }

    @Test fun failedVideoTaskStopsPolling() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"id":"x"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"failed","error":{"message":"policy"}}"""))
            val setting = VideoSetting(VideoProtocol.Seedance, CustomApi(server.url("/api/v3").toString(), "k", "m"))
            val failure = runCatching { client().generateVideo(setting, "x") }.exceptionOrNull()
            assertTrue(failure!!.message!!.contains("policy"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun insecureDownloadRejectedByDefault() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"id":"x"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"succeeded","content":{"video_url":"http://evil.test/v.mp4"}}"""))
            val setting = VideoSetting(VideoProtocol.Seedance, CustomApi(server.url("/api/v3").toString(), "k", "m"))
            val failure = runCatching { LocalMediaClient(pollIntervalMs = 1).generateVideo(setting, "x") }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
    }

    @Test fun hexDecodingRejectsMalformedInput() {
        assertThrows(IllegalArgumentException::class.java) { LocalMediaClient.decodeHex("abc") }
        assertThrows(IllegalArgumentException::class.java) { LocalMediaClient.decodeHex("zz") }
    }
}
