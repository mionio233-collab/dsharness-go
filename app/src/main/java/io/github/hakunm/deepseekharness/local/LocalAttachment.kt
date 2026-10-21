package io.github.hakunm.deepseekharness.local

import kotlinx.serialization.Serializable

@Serializable
data class LocalAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localPath: String,
    val textContent: String? = null,
    /** Display-only workspace binding for relative browser assets. */
    val workspacePath: String? = null,
) {
    val isImage: Boolean get() = mimeType.lowercase().startsWith("image/")
    val isVideo: Boolean get() = mimeType.lowercase().startsWith("video/")
    val isAudio: Boolean get() = mimeType.lowercase().startsWith("audio/")
}
