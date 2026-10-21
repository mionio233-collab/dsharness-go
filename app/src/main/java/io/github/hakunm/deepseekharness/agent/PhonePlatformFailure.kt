package io.github.hakunm.deepseekharness.agent

import android.os.DeadObjectException
import android.util.Log
import android.view.WindowManager

/** Only expected platform rejections are recoverable. Never log action text or exception messages. */
internal fun phonePlatformRejected(failure: RuntimeException): Boolean {
    if (failure is java.util.concurrent.CancellationException) return false
    val dead = generateSequence<Throwable>(failure) { it.cause }.take(8).any { it is DeadObjectException }
    if (dead) PhoneControlService.getInstance()?.connectionLost()
    val expected = dead || failure is IllegalArgumentException || failure is IllegalStateException || failure is SecurityException
    if (expected) Log.w("PhoneControlService", "Action rejected: ${failure.javaClass.simpleName}")
    return expected
}

internal inline fun phoneAction(block: () -> Boolean): Boolean = try { block() }
catch (failure: RuntimeException) {
    if (!phonePlatformRejected(failure)) throw failure
    false
}

internal inline fun phoneWindowOperation(block: () -> Unit) {
    try { block() } catch (failure: RuntimeException) {
        if (failure !is WindowManager.BadTokenException && failure !is WindowManager.InvalidDisplayException &&
            failure !is SecurityException && failure !is IllegalArgumentException) throw failure
        Log.w("PhoneStatusOverlay", "Window rejected: ${failure.javaClass.simpleName}")
    }
}
