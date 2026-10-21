package io.github.hakunm.deepseekharness.local

import io.github.hakunm.deepseekharness.ui.LocalPermission
import kotlinx.serialization.Serializable

@Serializable
enum class LocalTaskMode { Smart, Phone, Workspace, ChatOnly }

/** A closed allowlist shared by schema selection and the execution boundary. */
object LocalTaskScope {
    val workspaceTools = setOf("read_file", "list_files", "write_file")
    val phoneTools = setOf("phone_observe", "phone_action", "phone_capture")
    const val imageTool = "generate_image"
    const val musicTool = "generate_music"
    const val videoTool = "generate_video"

    fun offered(mode: LocalTaskMode, permission: LocalPermission, toolProvider: Boolean,
                phoneReady: Boolean, imageReady: Boolean = false, coordinateReady: Boolean = false,
                musicReady: Boolean = false, videoReady: Boolean = false): Set<String> {
        if (!toolProvider) return emptySet()
        val candidates = when (mode) {
            LocalTaskMode.Smart -> workspaceTools + "http_get" + if (phoneReady) phoneTools else emptySet()
            LocalTaskMode.Phone -> if (phoneReady) phoneTools else emptySet()
            LocalTaskMode.Workspace -> workspaceTools
            LocalTaskMode.ChatOnly -> emptySet()
        }
        val withCoordinates = if (coordinateReady && phoneReady && mode in setOf(LocalTaskMode.Smart, LocalTaskMode.Phone))
            candidates + "phone_gesture" else candidates
        val withImage = if (mode == LocalTaskMode.Smart) withCoordinates +
            listOfNotNull(imageTool.takeIf { imageReady }, musicTool.takeIf { musicReady }, videoTool.takeIf { videoReady })
            else withCoordinates
        return withImage.filterTo(linkedSetOf()) { permission.allows(it) && (it != "phone_gesture" || coordinateReady) }
    }

    /** Keep conversational content while removing the tool protocol from chat-only requests. */
    fun requestHistory(mode: LocalTaskMode, messages: List<LocalMessage>): List<LocalMessage> =
        if (mode == LocalTaskMode.ChatOnly) messages.filter { it.role != "tool" }
            .map { it.copy(toolCallsJson = null, toolCallId = null) }
        else messages

    fun canExecute(name: String, originallyOffered: Set<String>, mode: LocalTaskMode,
                   permission: LocalPermission, toolProvider: Boolean, phoneReady: Boolean,
                   imageReady: Boolean = false, coordinateReady: Boolean = false,
                   musicReady: Boolean = false, videoReady: Boolean = false): Boolean =
        name in originallyOffered && name in offered(mode, permission, toolProvider, phoneReady, imageReady,
            coordinateReady, musicReady, videoReady)

    /** Old releases used the explicit global phone opt-in for every local request. */
    fun restore(saved: LocalTaskMode?, legacyPhoneConsent: Boolean): LocalTaskMode =
        saved ?: if (legacyPhoneConsent) LocalTaskMode.Phone else LocalTaskMode.Smart

    fun switch(current: LocalTaskMode, requested: LocalTaskMode, busy: Boolean): LocalTaskMode =
        if (busy) current else requested
}
