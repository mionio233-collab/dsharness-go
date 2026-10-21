package io.github.hakunm.deepseekharness

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 应用内语言（简体中文 / English）。
 *
 * 背景：本项目原先用 `AppCompatDelegate.setApplicationLocales()` 切换语言，
 * 但在 Android 13+ 上该接口依赖系统 per-app language 能力。实测在模拟器
 * （API 37）上 `cmd locale get-app-locales` 始终为空，语言按钮点了没有反应，
 * 界面停留在系统语言（英文），自定义的中文字符串与英文资源混在一起。
 *
 * 这里改为在 `attachBaseContext` 里直接覆盖 Configuration 的 locale，
 * 不依赖系统 per-app language，任何 Android 版本都稳定生效；用户的选择
 * 存在 SharedPreferences 中，配合 `Activity.recreate()` 立即刷新界面。
 */
object AppLocale {
    var revision by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** 首次启动（用户还没选过）时的默认语言。 */
    const val DEFAULT_TAG = "zh-CN"

    private const val PREFS = "app_locale"
    private const val KEY_TAG = "tag"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 当前语言标签，未选择过时返回 [DEFAULT_TAG]。 */
    fun currentTag(context: Context): String =
        prefs(context).getString(KEY_TAG, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_TAG

    /** 用当前语言包一层 Context，供 Activity.attachBaseContext 使用。 */
    fun wrap(base: Context): Context {
        val tag = currentTag(base)
        val locale = Locale.forLanguageTag(tag)
        if (locale.language.isEmpty()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** 保存语言并重建当前 Activity 让界面立即生效。 */
    fun set(context: Context, tag: String) {
        val normalized = Locale.forLanguageTag(tag).toLanguageTag()
        if (normalized.equals(currentTag(context), ignoreCase = true)) return
        prefs(context).edit().putString(KEY_TAG, normalized).apply()
        revision++
    }

    /** 在当前语言与另一种语言之间切换。 */
    fun toggle(context: Context): String {
        val next = if (currentTag(context).startsWith("en", ignoreCase = true)) "zh-CN" else "en"
        set(context, next)
        return next
    }
}
