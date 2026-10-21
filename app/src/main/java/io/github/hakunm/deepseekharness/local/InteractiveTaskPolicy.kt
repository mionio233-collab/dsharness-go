package io.github.hakunm.deepseekharness.local

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** No total application deadline. Constructor retained for source compatibility with saved callers. */
data class InteractiveTaskPolicy(val ceilingMinutes: Int = 30) {
    init {
        require(ceilingMinutes in 1..120) { "Interactive task ceiling must be between 1 and 120 minutes" }
    }

    val ceilingMillis: Long get() = ceilingMinutes * 60_000L
    val ceilingMessage: String get() =
        "Task ceiling reached ($ceilingMinutes minutes); partial output retained."

    suspend fun <T> runTask(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T =
        kotlinx.coroutines.coroutineScope(block)
}

/** SSE activity may continue for the task's entire budget; only stalled reads expire here. */
internal fun OkHttpClient.forLocalStreaming(): OkHttpClient = newBuilder()
    .callTimeout(0, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .build()
