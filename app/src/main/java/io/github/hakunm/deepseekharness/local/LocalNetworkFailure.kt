package io.github.hakunm.deepseekharness.local

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Safe transport detail for the UI. The original exception remains available as the cause. */
internal class LocalNetworkFailure(message: String, cause: IOException) : IOException(message, cause)

internal fun providerNetworkFailure(
    failure: IOException,
    connectionAcquired: Boolean,
    headersReceived: Boolean,
    partialReceived: Boolean,
): LocalNetworkFailure {
    val detail = when (failure) {
        is UnknownHostException -> "Provider hostname could not be resolved. Check the API base URL and DNS/network connection."
        is SSLException -> "Provider TLS connection failed. Check the API base URL, device clock, and provider certificate."
        is SocketTimeoutException -> when {
            !connectionAcquired -> "Provider connect timeout: unable to establish connection."
            headersReceived -> "Provider read timeout: response stopped sending data."
            else -> "Provider read timeout: waiting for response headers."
        }
        is InterruptedIOException -> "Provider request deadline reached."
        is ConnectException -> "Unable to connect to the provider. Check the API base URL and network connection."
        is EOFException -> "Provider stream ended before completion."
        else -> "Provider connection interrupted. Check your network, VPN/proxy, and provider availability."
    }
    val output = if (partialReceived) "partial output retained." else "No complete response received."
    return LocalNetworkFailure("$detail $output Retry manually when ready; the previous request may have incurred charges. Review completed tool actions before retrying. No automatic retry was sent.", failure)
}

internal fun localFailureMessage(failure: Throwable, partialReceived: Boolean): String = when (failure) {
    is LocalNetworkFailure -> failure.message.orEmpty()
    is IOException -> providerNetworkFailure(failure, true, false, partialReceived).message.orEmpty()
    else -> failure.message ?: failure::class.simpleName.orEmpty()
}

/** Flush the latest callback snapshot even if the throttled UI consumer has not displayed it. */
internal fun retainedPartialHistory(
    history: List<LocalMessage>, partial: LocalMessage?, timestampMillis: Long, elapsedMillis: Long,
): List<LocalMessage> {
    if (partial == null || (partial.content.isEmpty() && partial.reasoningContent.isNullOrEmpty() && partial.usage == null)) return history
    return history + partial.copy(toolCallsJson = null, timestampMillis = timestampMillis, elapsedMillis = elapsedMillis)
}
