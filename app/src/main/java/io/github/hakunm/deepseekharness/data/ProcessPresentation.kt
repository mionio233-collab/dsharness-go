package io.github.hakunm.deepseekharness.data

enum class ProcessEntryKind { SEARCH, READ, THINK, QUERY, SKILL, INSPECT, AGENT, WRITE, RUN, TOOL }

data class ProcessEntry(
    val kind: ProcessEntryKind,
    val detail: String = "",
    val isError: Boolean = false,
)

/** Keep the visible chain bounded while preserving its chronological tail. */
fun reasoningProcessEntries(text: String?, limit: Int = 40): List<ProcessEntry> {
    val raw = text.orEmpty()
    if (raw.isBlank()) return emptyList()
    val chunks = raw.split(Regex("\\n\\s*\\n"))
        .flatMap { paragraph -> paragraph.lines().filter(String::isNotBlank) }
        .map { it.trim() }
        .filter(String::isNotBlank)
        .filterNot(::isInjectedRuntimeContext)
    return chunks.takeLast(limit).map { ProcessEntry(ProcessEntryKind.THINK, it.take(500)) }
}

private fun isInjectedRuntimeContext(line: String): Boolean {
    val lower = line.lowercase()
    return lower.startsWith("current runtime context") ||
        lower.startsWith("current working directory") ||
        lower.startsWith("current dsh file policy") ||
        lower.startsWith("approval policy:") ||
        lower.startsWith("you are the team lead") ||
        lower.startsWith("to message another teammate") ||
        lower.startsWith("a skill is a reusable") ||
        lower.startsWith("<system-reminder>") ||
        lower.startsWith("<available_skills>") ||
        lower.startsWith("`agent-experience`")
}

fun toolProcessEntry(
    toolName: String,
    detail: String?,
    limit: Int = 500,
    isError: Boolean = false,
): ProcessEntry {
    val value = "$toolName ${detail.orEmpty()}".lowercase()
    val kind = when {
        "skill" in value -> ProcessEntryKind.SKILL
        "list_agents" in value || "subagent" in value -> ProcessEntryKind.AGENT
        "inspect_list" in value || "listproviders" in value || "provider" in value -> ProcessEntryKind.INSPECT
        "inspect_query" in value || "runtime" in value || "query" in value -> ProcessEntryKind.QUERY
        "search" in value || "grep" in value || "glob" in value -> ProcessEntryKind.SEARCH
        "read" in value || "cat " in value || value.startsWith("cat") -> ProcessEntryKind.READ
        "write" in value || "edit" in value || "str_replace" in value -> ProcessEntryKind.WRITE
        "bash" in value || "pwsh" in value || "shell" in value || "run_code" in value -> ProcessEntryKind.RUN
        else -> ProcessEntryKind.TOOL
    }
    val summary = detail.orEmpty().replace(Regex("\\s+"), " ").trim().take(limit)
    return ProcessEntry(kind, summary.ifBlank { toolName }, isError)
}
