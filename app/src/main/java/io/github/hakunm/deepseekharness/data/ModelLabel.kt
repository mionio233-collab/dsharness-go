package io.github.hakunm.deepseekharness.data

/** Mirrors Desktop's ui-deepseek-model-label-1017 presentation; never a token count. */
data class ModelLabel(val name: String, val release: String? = null) {
    val text: String get() = listOfNotNull(name, release).joinToString(" ")
}

fun modelLabel(provider: String, model: ModelView): ModelLabel = ModelLabel(
    model.name,
    "1017".takeIf {
        provider in setOf("deepseek-official", "deepseek-account") &&
            model.id == "deepseek-flash" && model.name == "DeepSeek-V4.1-Flash"
    },
)
