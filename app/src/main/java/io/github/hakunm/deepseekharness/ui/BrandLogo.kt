package io.github.hakunm.deepseekharness.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hakunm.deepseekharness.R
import io.github.hakunm.deepseekharness.ui.theme.DswTheme
import java.net.URI
import java.util.Locale

internal enum class Brand(@DrawableRes val drawable: Int) {
    DeepSeek(R.drawable.brand_deepseek), OpenAI(R.drawable.brand_openai),
    Anthropic(R.drawable.brand_anthropic), Qwen(R.drawable.brand_qwen),
    Gemma(R.drawable.brand_gemma), Meta(R.drawable.brand_meta),
    Microsoft(R.drawable.brand_microsoft), HuggingFace(R.drawable.brand_huggingface),
    Mistral(R.drawable.brand_mistral),
}

internal object BrandResolver {
    private fun alias(value: String): Brand? = when (value.trim().lowercase(Locale.ROOT)) {
        "deepseek", "deep seek" -> Brand.DeepSeek
        "openai", "open ai" -> Brand.OpenAI
        "anthropic" -> Brand.Anthropic
        else -> null
    }

    // An explicit endpoint is authoritative. Protocol compatibility is never brand identity.
    fun provider(id: String, name: String, endpoint: String): Brand? {
        if (endpoint.isBlank()) return alias(id) ?: alias(name)
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in listOf("https", "http") || uri.userInfo != null) return null
        return when (uri.host?.lowercase(Locale.ROOT)) {
            "api.deepseek.com", "api.deepseek.cn" -> Brand.DeepSeek
            "api.openai.com" -> Brand.OpenAI
            "api.anthropic.com" -> Brand.Anthropic
            else -> null
        }
    }

    fun family(family: String): Brand? = when (family.lowercase(Locale.ROOT)) {
        "deepseek" -> Brand.DeepSeek
        "qwen" -> Brand.Qwen
        "gemma" -> Brand.Gemma
        "llama" -> Brand.Meta
        "phi" -> Brand.Microsoft
        "smollm" -> Brand.HuggingFace
        "mistral" -> Brand.Mistral
        else -> null
    }
}

/** Decorative beside the visible provider/family label; pass a description for standalone use.
 * Original color assets sit on a white tile in both themes to preserve their contrast.
 * Provenance and license: assets/brand-logos-NOTICE.txt.
 */
@Composable
internal fun BrandLogo(
    brand: Brand?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    contentDescription: String? = null,
) {
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(size * 0.28f),
        color = if (brand == null) DswTheme.colors.bgLayer1 else Color.White,
        border = BorderStroke(0.5.dp, DswTheme.colors.borderL2),
    ) {
        Box(Modifier.padding(size * 0.18f), contentAlignment = Alignment.Center) {
            if (brand != null) Image(painterResource(brand.drawable), contentDescription)
            else Icon(Icons.Outlined.Dns, contentDescription, tint = DswTheme.colors.labelSecondary)
        }
    }
}
