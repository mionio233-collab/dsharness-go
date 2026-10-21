package io.github.hakunm.deepseekharness.local

/** One HTTP round owns one preview. Channel entries are wakeups, never historical snapshots.
 * Closing is irreversible: queued wakeups and late provider callbacks cannot restore a preview.
 * This is display state only; authoritative completion/tool records still populate transport history.
 */
internal class LocalRoundPreview(val generation: Long, val round: Int) {
    private var open = true
    private var latest: LocalMessage? = null

    @Synchronized
    fun accept(message: LocalMessage): Boolean {
        if (!open) return false
        val previous = latest
        fun cumulative(old: String, new: String) = if (new.startsWith(old)) new else old
        latest = message.copy(
            content = cumulative(previous?.content.orEmpty(), message.content),
            reasoningContent = cumulative(previous?.reasoningContent.orEmpty(), message.reasoningContent.orEmpty()).takeIf { it.isNotEmpty() },
            usage = message.usage?.let { current -> current.copy(
                total = current.total ?: previous?.usage?.total,
                input = current.input ?: previous?.usage?.input,
                output = current.output ?: previous?.usage?.output,
                cacheRead = current.cacheRead ?: previous?.usage?.cacheRead,
                cacheWrite = current.cacheWrite ?: previous?.usage?.cacheWrite,
                uncachedInput = current.uncachedInput ?: previous?.usage?.uncachedInput,
                provider = current.provider ?: previous?.usage?.provider,
                model = current.model ?: previous?.usage?.model,
            ) } ?: previous?.usage,
            usageTokens = message.usageTokens ?: previous?.usageTokens,
            toolCallsJson = null,
        )
        return true
    }

    @Synchronized
    fun preview(generation: Long, round: Int): LocalMessage? =
        latest.takeIf { open && generation == this.generation && round == this.round }

    @Synchronized
    fun close(): LocalMessage? {
        open = false
        return latest
    }
}
