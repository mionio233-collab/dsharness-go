package io.github.hakunm.deepseekharness.ui

import androidx.annotation.StringRes
import io.github.hakunm.deepseekharness.R

/**
 * 本机模式的权限级别（对齐官方 Composer 的权限菜单）。
 *
 * - [ReadOnly] 只能读
 * - [WorkspaceWrite] 能在工作区（沙箱目录）内写
 * - [Auto] 默认：读写 + 联网不逐次确认；需要操控手机时弹窗请求升级为 [Full]
 * - [Full] 会话已授权读写 + 联网 + 手机控制（手机控制仍需另行启用）
 * - [AutoReview] 旧版本遗留值，行为同 Full；菜单里不再展示，读取时迁移为 Full
 * 支付类操作始终禁止。这些是工具权限，不授予 root 或 Android 系统权限。
 */
enum class LocalPermission(@StringRes val labelRes: Int) {
    ReadOnly(R.string.perm_read_only),
    WorkspaceWrite(R.string.perm_workspace_write),
    Auto(R.string.perm_auto),
    Full(R.string.perm_full),
    AutoReview(R.string.perm_auto_review),
    ;

    /** 该级别允许调用的工具名。Auto 不含手机工具，需升级为 Full。 */
    fun allows(toolName: String): Boolean = when (this) {
        ReadOnly -> toolName in READ_ONLY_TOOLS
        WorkspaceWrite -> toolName in READ_ONLY_TOOLS || toolName == "write_file"
        Auto -> toolName !in PHONE_TOOLS
        Full, AutoReview -> true
    }

    /** WorkspaceWrite 保留逐次写入确认；Auto 的手机升级走单独的升级对话框，不在这里逐次确认。 */
    fun needsConfirmation(toolName: String): Boolean =
        this == WorkspaceWrite && toolName == "write_file"

    companion object {
        val READ_ONLY_TOOLS = setOf("read_file", "list_files")
        /** 与 LocalTaskScope.phoneTools 一致，另含坐标手势工具。 */
        val PHONE_TOOLS = setOf("phone_observe", "phone_action", "phone_capture", "phone_gesture")
        /** 菜单里展示的选项（不含遗留的 AutoReview，避免出现两个「自动」）。 */
        val selectable = listOf(ReadOnly, WorkspaceWrite, Auto, Full)
    }
}
