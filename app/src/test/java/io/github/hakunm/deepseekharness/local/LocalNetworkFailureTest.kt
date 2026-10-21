package io.github.hakunm.deepseekharness.local

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class LocalNetworkFailureTest {
    @Test fun socketAbortIsActionableAndDoesNotClaimUserCancelled() {
        val cause = SocketException("Software caused connection abort: secret-url")
        val failure = providerNetworkFailure(cause, true, true, true)
        assertSame(cause, failure.cause)
        val message = localFailureMessage(failure, true)
        assertTrue(message.contains("connection interrupted"))
        assertTrue(message.contains("partial output retained"))
        assertTrue(message.contains("VPN/proxy"))
        assertTrue(message.contains("Retry manually"))
        assertTrue(message.contains("charges"))
        assertTrue(message.contains("tool actions"))
        assertFalse(message.contains("cancelled"))
        assertFalse(message.contains("secret-url"))
        assertEquals("Stopped by user", localFailureMessage(CancellationException("Stopped by user"), true))
        assertEquals("Provider HTTP 401", localFailureMessage(IllegalStateException("Provider HTTP 401"), false))
    }

    @Test fun transportCategoriesAndResponsePhasesRemainDistinct() {
        val cases = listOf(
            UnknownHostException("private-host") to "hostname",
            SSLHandshakeException("private-host") to "TLS",
            ConnectException("private-host") to "Unable to connect",
            InterruptedIOException("private-host") to "deadline",
        )
        for ((cause, expected) in cases) {
            val message = providerNetworkFailure(cause, false, false, false).message!!
            assertTrue(message.contains(expected))
            assertTrue(message.contains("No complete response received"))
            assertFalse(message.contains("partial output retained"))
            assertFalse(message.contains("private-host"))
        }
        assertTrue(providerNetworkFailure(SocketTimeoutException(), false, false, false).message!!.contains("connect timeout"))
        assertTrue(providerNetworkFailure(SocketTimeoutException(), true, false, false).message!!.contains("waiting for response headers"))
        assertTrue(providerNetworkFailure(SocketTimeoutException(), true, true, true).message!!.contains("stopped sending data"))
    }

    @Test fun latestPartialSnapshotPreservesHistoryDeliveriesReasoningAndUsage() {
        val attachment = LocalAttachment("id", "saved.html", "text/html", 10, "/workspace/saved.html")
        val prior = LocalMessage("assistant", "saved", attachments = listOf(attachment))
        val history = listOf(LocalMessage("user", "make a page"), prior, LocalMessage("tool", "saved", toolCallId = "a"))
        val usage = ReplyUsage(total = 12, input = 8, output = 4)
        val partial = LocalMessage("assistant", "latest text", reasoningContent = "latest reasoning", toolCallsJson = "incomplete", usage = usage)
        val retained = retainedPartialHistory(history, partial, 100, 200)
        assertEquals(history, retained.dropLast(1))
        assertEquals(listOf(attachment), retained[1].attachments)
        assertEquals("latest text", retained.last().content)
        assertEquals("latest reasoning", retained.last().reasoningContent)
        assertEquals(usage, retained.last().usage)
        assertNull(retained.last().toolCallsJson)
        assertEquals(100L, retained.last().timestampMillis)
        assertEquals(200L, retained.last().elapsedMillis)
        assertEquals(history, retainedPartialHistory(history, null, 100, 200))
        assertEquals(history, retainedPartialHistory(history, LocalMessage("assistant", ""), 100, 200))
        assertEquals("reasoning only", retainedPartialHistory(history, LocalMessage("assistant", "", reasoningContent = "reasoning only"), 100, 200).last().reasoningContent)
    }
}
