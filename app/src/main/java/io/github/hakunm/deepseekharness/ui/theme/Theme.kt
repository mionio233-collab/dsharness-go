@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package io.github.hakunm.deepseekharness.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hakunm.deepseekharness.R

/**
 * 主题入口。
 *
 * 这里把官方 DSH 设计令牌（[DswColors]）映射到 Material 3 角色，
 * 并同时提供 `DswTheme.colors / spacing / radius` 给新组件使用。
 * 旧的"暖纸感 + 衬线标题"配色已废弃，界面与官方 Web 客户端保持一致：
 * 中性冷灰蓝底、近黑主色、DeepSeek 蓝作强调、无衬线、8–18dp 圆角、细描边、不用投影。
 */

private fun m3Light(c: DswColors) = lightColorScheme(
    primary = c.brand,
    onPrimary = c.onBrand,
    primaryContainer = c.bgModule,
    onPrimaryContainer = c.labelPrimary,
    inversePrimary = androidx.compose.ui.graphics.Color(0xFF4D93F8),
    secondary = c.accent,
    onSecondary = androidx.compose.ui.graphics.Color.Black,
    secondaryContainer = c.accentSoft,
    onSecondaryContainer = c.labelPrimary,
    tertiary = c.warning,
    onTertiary = androidx.compose.ui.graphics.Color(0xFF27241F),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFFEF5E7),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF27241F),
    background = c.bgBase,
    onBackground = c.labelPrimary,
    surface = c.bgBase,
    onSurface = c.labelPrimary,
    surfaceVariant = c.bgModule,
    onSurfaceVariant = c.labelSecondary,
    surfaceTint = c.accent,
    surfaceDim = androidx.compose.ui.graphics.Color(0xFFEBEEF2),
    surfaceBright = androidx.compose.ui.graphics.Color.White,
    surfaceContainerLowest = androidx.compose.ui.graphics.Color.White,
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF9FAFB),
    surfaceContainer = c.bgModule,
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFF1F3F5),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFEBEEF2),
    inverseSurface = c.labelPrimary,
    inverseOnSurface = androidx.compose.ui.graphics.Color(0xFFF9FAFB),
    outline = androidx.compose.ui.graphics.Color(0xFFCFD3D6),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFFE1E5EE),
    error = c.danger,
    onError = androidx.compose.ui.graphics.Color.Black,
    errorContainer = androidx.compose.ui.graphics.Color(0xFFFEE2E2),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFF570C0C),
)

private fun m3Dark(c: DswColors) = darkColorScheme(
    primary = c.brand,
    onPrimary = c.onBrand,
    primaryContainer = c.bgLayer3,
    onPrimaryContainer = c.labelPrimary,
    inversePrimary = androidx.compose.ui.graphics.Color(0xFF315EB7),
    secondary = c.accent,
    onSecondary = androidx.compose.ui.graphics.Color(0xFF0F1115),
    secondaryContainer = c.accentSoft,
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFD3E2FF),
    tertiary = c.warning,
    onTertiary = androidx.compose.ui.graphics.Color(0xFF27241F),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF27241F),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFFEF5E7),
    background = c.bgBase,
    onBackground = c.labelPrimary,
    surface = c.bgBase,
    onSurface = c.labelPrimary,
    surfaceVariant = c.bgLayer2,
    onSurfaceVariant = c.labelSecondary,
    surfaceTint = c.accent,
    surfaceDim = androidx.compose.ui.graphics.Color(0xFF151517),
    surfaceBright = androidx.compose.ui.graphics.Color(0xFF353638),
    surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF1B1B1C),
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF232324),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFF2C2C2E),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF2C2C2E),
    surfaceContainerHighest = c.bgLayer3,
    inverseSurface = androidx.compose.ui.graphics.Color(0xFFF9FAFB),
    inverseOnSurface = androidx.compose.ui.graphics.Color(0xFF0F1115),
    outline = androidx.compose.ui.graphics.Color(0xFF43454A),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF3A3A3C),
    error = c.danger,
    onError = androidx.compose.ui.graphics.Color(0xFF0F1115),
    errorContainer = androidx.compose.ui.graphics.Color(0xFF570C0C),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFFFEE2E2),
)

private val Baseline = Typography()

/** 官方 Web 使用 Montserrat（OFL，随包内置可变字体）；中文由系统字体回退。 */
private val Sans = FontFamily(
    Font(R.font.montserrat_variable, FontWeight.Light, variationSettings = FontVariation.Settings(FontVariation.weight(300))),
    Font(R.font.montserrat_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.montserrat_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.montserrat_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

private val AppTypography = Typography(
    displaySmall = Baseline.displaySmall.copy(
        fontFamily = Sans, fontSize = 20.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp,
    ),
    headlineMedium = Baseline.headlineMedium.copy(
        fontFamily = Sans, fontSize = 18.sp, lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp,
    ),
    headlineSmall = Baseline.headlineSmall.copy(
        fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp,
    ),
    titleLarge = Baseline.titleLarge.copy(
        fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.sp,
    ),
    titleMedium = Baseline.titleMedium.copy(
        fontFamily = Sans, fontSize = 14.sp, lineHeight = 22.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.sp,
    ),
    titleSmall = Baseline.titleSmall.copy(
        fontFamily = Sans, fontSize = 13.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.sp,
    ),
    bodyLarge = Baseline.bodyLarge.copy(
        fontFamily = Sans, fontSize = 15.sp, lineHeight = 24.sp, letterSpacing = 0.sp,
    ),
    bodyMedium = Baseline.bodyMedium.copy(
        fontFamily = Sans, fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.sp,
    ),
    bodySmall = Baseline.bodySmall.copy(
        fontFamily = Sans, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp,
    ),
    labelLarge = Baseline.labelLarge.copy(
        fontFamily = Sans, fontSize = 14.sp, lineHeight = 22.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.sp,
    ),
    labelMedium = Baseline.labelMedium.copy(
        fontFamily = Sans, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp,
    ),
    labelSmall = Baseline.labelSmall.copy(
        fontFamily = Sans, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun DeepSeekHarnessTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val dsw = if (darkTheme) DswDarkColors else DswLightColors
    CompositionLocalProvider(
        LocalDswColors provides dsw,
        LocalDswSpacing provides DswSpacing(),
        LocalDswRadius provides DswRadius(),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) m3Dark(dsw) else m3Light(dsw),
            typography = AppTypography,
            shapes = AppShapes,
            content = {
                CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides dsw.labelPrimary,
                ) {
                    content()
                }
            },
        )
    }
}
