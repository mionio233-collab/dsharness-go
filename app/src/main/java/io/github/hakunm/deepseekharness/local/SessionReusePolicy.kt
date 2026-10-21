package io.github.hakunm.deepseekharness.local

/** File identity and inference settings select residency; conversation identity selects KV reuse. */
internal object SessionReusePolicy {
    fun replaceResident(currentKey: String?, incomingKey: String) = currentKey != incomingKey
    fun clearPrefix(previousOwner: Any?, incomingOwner: Any) = previousOwner != incomingOwner
}
