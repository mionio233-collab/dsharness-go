package io.github.hakunm.deepseekharness.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * DeepSeek Harness 官方设计令牌。
 *
 * 数值直接取自官方前端 CSS 变量（`--dsw-static-*` / `--dsw-alias-*`，见本机
 * `_dsh-reference/theme-design_platform_css_default.css`），用于替代项目早期
 * 的"暖纸感"配色，使界面与官方 Web 客户端保持一致。
 */
@Immutable
data class DswColors(
    val bgBase: Color,
    val bgLayer1: Color,
    val bgLayer2: Color,
    val bgLayer3: Color,
    val bgModule: Color,
    val bgOverlay: Color,
    val interactiveHover: Color,
    val interactiveActive: Color,
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val labelCaption: Color,
    val labelBluish: Color,
    val sidebarFill: Color,
    val inputFill: Color,
    val selectorFill: Color,
    val bubbleFill: Color,
    val chatUserFill: Color,
    val chatUserContent: Color,
    val chatAssistantFill: Color,
    val chatAssistantContent: Color,
    val borderL1: Color,
    val borderL2: Color,
    val borderL3: Color,
    val borderL4: Color,
    val brand: Color,
    val onBrand: Color,
    val accent: Color,
    val accentSoft: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val codeBg: Color,
    val skeleton: Color,
    val isLight: Boolean,
)

/** 浅色：官方 `body` 作用域。 */
val DswLightColors = DswColors(
    bgBase = Color(0xFFFFFFFF),
    bgLayer1 = Color(0xFFFFFFFF),
    bgLayer2 = Color(0xFFFFFFFF),
    bgLayer3 = Color(0xFFFFFFFF),
    bgModule = Color(0xFFF5F6F7),
    bgOverlay = Color(0xFFE9ECF2),
    interactiveHover = Color(0x0F263148),
    interactiveActive = Color(0x1A263148),
    labelPrimary = Color(0xFF0F1115),
    labelSecondary = Color(0xFF61666B),
    labelTertiary = Color(0xFF81858C),
    labelCaption = Color(0xFFADB2B8),
    labelBluish = Color(0xFF0E3074),
    sidebarFill = Color(0xFFF9FAFB),
    inputFill = Color(0xFFFFFFFF),
    selectorFill = Color(0xFFF5F6F7),
    bubbleFill = Color(0xFFEDF3FE),
    chatUserFill = Color(0xFF315FC4),
    chatUserContent = Color.White,
    chatAssistantFill = Color(0xFFF0F1F3),
    chatAssistantContent = Color(0xFF0F1115),
    borderL1 = Color(0x0A000000),
    borderL2 = Color(0x1A000000),
    borderL3 = Color(0x1F000000),
    borderL4 = Color(0x29000000),
    brand = Color(0xFF0F1115),
    onBrand = Color(0xFFFFFFFF),
    accent = Color(0xFF4176E6),
    accentSoft = Color(0xFFE4EDFD),
    success = Color(0xFF22C55E),
    warning = Color(0xFFF59E0B),
    danger = Color(0xFFEC1313),
    codeBg = Color(0xFFF9FAFB),
    skeleton = Color(0x0A000000),
    isLight = true,
)

/** 深色：官方 `body[data-ds-dark-theme]` 作用域。 */
val DswDarkColors = DswColors(
    bgBase = Color(0xFF151517),
    bgLayer1 = Color(0xFF232324),
    bgLayer2 = Color(0xFF2C2C2E),
    bgLayer3 = Color(0xFF353638),
    bgModule = Color(0xFF353638),
    bgOverlay = Color(0xFF61666B),
    interactiveHover = Color(0x14FFFFFF),
    interactiveActive = Color(0x24FFFFFF),
    labelPrimary = Color(0xFFF9FAFB),
    labelSecondary = Color(0xFFCFD3D6),
    labelTertiary = Color(0xFFADB2B8),
    labelCaption = Color(0xFF81858C),
    labelBluish = Color(0xFFF9FAFB),
    sidebarFill = Color(0xFF1B1B1C),
    inputFill = Color(0xFF2C2C2E),
    selectorFill = Color(0xFF353638),
    bubbleFill = Color(0xFF2C2C2E),
    chatUserFill = Color(0xFF315FC4),
    chatUserContent = Color.White,
    chatAssistantFill = Color(0xFF2C2C2E),
    chatAssistantContent = Color(0xFFF9FAFB),
    borderL1 = Color(0x0FFFFFFF),
    borderL2 = Color(0x1FFFFFFF),
    borderL3 = Color(0x29FFFFFF),
    borderL4 = Color(0x33FFFFFF),
    brand = Color(0xFFF9FAFB),
    onBrand = Color(0xFF0F1115),
    accent = Color(0xFF7AAAFF),
    accentSoft = Color(0xFF34415B),
    success = Color(0xFF22C55E),
    warning = Color(0xFFF7AD31),
    danger = Color(0xFFF25A5A),
    codeBg = Color(0xFF1B1B1C),
    skeleton = Color(0x14FFFFFF),
    isLight = false,
)

/** 间距阶梯，屏幕内只允许使用这些值。 */
@Immutable
data class DswSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
    /** 列表行最小高度 */
    val rowHeight: Dp = 44.dp,
    /** 图标按钮最小命中区 */
    val touchTarget: Dp = 44.dp,
)

/** 圆角阶梯（官方 `--dsw-radius-*`：xs4 / sm8 / md12 / lg16 / xl20 / panel28）。 */
@Immutable
data class DswRadius(
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 20.dp,
    /** composer 卡片（官方 --dsw-radius-panel） */
    val panel: Dp = 28.dp,
    /** 胶囊控件（官方 999px，Compose 中会自动夹紧为半高） */
    val pill: Dp = 100.dp,
)

internal val LocalDswColors = staticCompositionLocalOf { DswLightColors }
internal val LocalDswSpacing = staticCompositionLocalOf { DswSpacing() }
internal val LocalDswRadius = staticCompositionLocalOf { DswRadius() }

/** 屏幕里取令牌的统一入口：`DswTheme.colors.labelPrimary`。 */
object DswTheme {
    val colors: DswColors
        @Composable @ReadOnlyComposable get() = LocalDswColors.current

    val spacing: DswSpacing
        @Composable @ReadOnlyComposable get() = LocalDswSpacing.current

    val radius: DswRadius
        @Composable @ReadOnlyComposable get() = LocalDswRadius.current
}
