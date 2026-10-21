package io.github.hakunm.deepseekharness.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 本机模式支持的两套协议（按你的选择：OpenAI 兼容 + Anthropic Messages）。 */
@Serializable
enum class LocalProtocol { OpenAI, Anthropic }

/**
 * 用户在本机模式下自己配置的模型服务商。
 *
 * 与「接管电脑」模式不同，这里的 API Key 只保存在手机本地（加密），
 * 请求由 App 自己发出，不依赖 DSH 服务端的 settings 权限。
 */
@Serializable
data class LocalProvider(
    val id: String,
    val name: String,
    val protocol: LocalProtocol,
    val baseUrl: String,
    val apiKey: String,
    val models: List<String> = emptyList(),
    val defaultModel: String? = null,
    /** User-supplied documented context windows keyed by exact provider model ID. */
    val contextLimits: Map<String, Long> = emptyMap(),
    /** Validated context windows reported by /models; separate from manual overrides. */
    val modelContextLimits: Map<String, Long> = emptyMap(),
)

/** 兼容只填主机名的写法：统一补上 `/v1`（已包含则不重复）。 */
fun normalizeBaseUrl(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    if (trimmed.isEmpty()) return trimmed
    return if (trimmed.contains("/v1")) trimmed else "$trimmed/v1"
}

/**
 * 本机 Provider 的持久化。
 *
 * 使用 EncryptedSharedPreferences（KeyStore 主密钥 + AES256-GCM），
 * **绝不写入明文文件或日志**；导出/备份也不会带出密钥。
 */
class LocalProviderStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        migrateLegacyDefaults()
    }

    /**
     * v1 的旧数据有两个问题：地址可能缺少 `/v1`，且模型列表是我预置的猜测值。
     * v2 起模型一律由「获取可用模型」真实拉取，这里做一次性修正。
     */
    private fun migrateLegacyDefaults() {
        val version = prefs.getInt(KEY_VERSION, 1)
        if (version >= CURRENT_VERSION) return
        val fixed = load().map { provider ->
            provider.copy(
                baseUrl = normalizeBaseUrl(provider.baseUrl),
                models = emptyList(),
                defaultModel = null,
            )
        }
        save(fixed)
        prefs.edit().putInt(KEY_VERSION, CURRENT_VERSION).apply()
    }

    /** 兼容只填主机名的写法：统一补上 `/v1`（已包含则不重复）。 */
    fun normalize(url: String): String = normalizeBaseUrl(url)

    /** 读取全部本机 Provider；数据损坏时返回空列表而不是崩溃。 */
    fun load(): List<LocalProvider> = runCatching {
        prefs.getString(KEY_PROVIDERS, null)?.let { json.decodeFromString<List<LocalProvider>>(it) }
    }.getOrNull().orEmpty()

    fun save(providers: List<LocalProvider>) {
        prefs.edit().putString(KEY_PROVIDERS, json.encodeToString(providers)).apply()
    }

    fun upsert(provider: LocalProvider) {
        save(load().filterNot { it.id == provider.id } + provider)
    }

    fun remove(id: String) {
        require(id != "deepseek") { "The built-in DeepSeek provider cannot be deleted" }
        save(load().filterNot { it.id == id })
    }

    /** 首次使用时的占位项：只给地址与协议，**不预置任何模型**（模型必须由「获取可用模型」真实拉取）。 */
    fun seedIfEmpty(): List<LocalProvider> {
        val existing = load()
        if (existing.isNotEmpty()) return existing
        val seeded = listOf(
            LocalProvider(
                id = "deepseek",
                name = "DeepSeek",
                protocol = LocalProtocol.OpenAI,
                baseUrl = "https://api.deepseek.com/v1",
                apiKey = "",
            ),
            LocalProvider(
                id = "openai",
                name = "OpenAI",
                protocol = LocalProtocol.OpenAI,
                baseUrl = "https://api.openai.com/v1",
                apiKey = "",
            ),
            LocalProvider(
                id = "anthropic",
                name = "Anthropic",
                protocol = LocalProtocol.Anthropic,
                baseUrl = "https://api.anthropic.com/v1",
                apiKey = "",
            ),
        )
        save(seeded)
        return seeded
    }

    private companion object {
        const val FILE_NAME = "pocket_local_providers"
        const val KEY_PROVIDERS = "providers"
        const val KEY_VERSION = "schema_version"
        const val CURRENT_VERSION = 2
    }
}
