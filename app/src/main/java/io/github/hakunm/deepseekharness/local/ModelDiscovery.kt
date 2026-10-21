package io.github.hakunm.deepseekharness.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The catalog contains every returned ID, including IDs without context metadata. */
data class DiscoveredModel(val id: String, val contextLimit: Long? = null)
data class ModelDiscovery(val models: List<DiscoveredModel>) {
    val ids: List<String> get() = models.map { it.id }
    val contextLimits: Map<String, Long> get() = models.mapNotNull { model ->
        model.contextLimit?.let { model.id to it }
    }.toMap()
}

enum class ContextLimitSource { Manual, ModelsEndpoint }
data class ResolvedContextLimit(val tokens: Long, val source: ContextLimitSource)

fun LocalProvider.resolveContextLimit(model: String?): ResolvedContextLimit? {
    contextLimits[model]?.takeIf { it > 0 }?.let {
        return ResolvedContextLimit(it, ContextLimitSource.Manual)
    }
    return modelContextLimits[model]?.takeIf { it > 0 }?.let {
        ResolvedContextLimit(it, ContextLimitSource.ModelsEndpoint)
    }
}

/** Refresh metadata for all returned IDs, clearing stale limits when metadata is omitted. */
fun LocalProvider.withDiscovery(discovery: ModelDiscovery): LocalProvider = copy(
    modelContextLimits = (modelContextLimits - discovery.ids.toSet()) + discovery.contextLimits,
)

/** Editing a manual override never removes the discovered fallback. */
fun LocalProvider.withManualContextLimit(model: String, text: String): LocalProvider {
    val id = model.trim()
    if (id.isEmpty()) return this
    val value = text.toLongOrNull()?.takeIf { it > 0 }
    return copy(contextLimits = (contextLimits - id) + if (value == null) emptyMap() else mapOf(id to value))
}

fun parseModelDiscovery(body: String): ModelDiscovery {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: error("Invalid models response")
    val data = root["data"] as? JsonArray ?: error("Missing models data array")
    val fields = listOf("context_length", "context_window", "contextWindow", "max_context_length")
    fun limit(obj: JsonObject): Long? = fields.firstNotNullOfOrNull { field ->
        (obj[field] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
    }
    val models = linkedMapOf<String, DiscoveredModel>()
    data.forEach { element ->
        val obj = element as? JsonObject ?: return@forEach
        val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() } ?: return@forEach
        val context = limit(obj) ?: listOf("architecture", "top_provider").firstNotNullOfOrNull { key ->
            (obj[key] as? JsonObject)?.let(::limit)
        }
        // Duplicate IDs cannot hide previously validated metadata in the same response.
        models[id] = DiscoveredModel(id, context ?: models[id]?.contextLimit)
    }
    return ModelDiscovery(models.values.toList())
}
