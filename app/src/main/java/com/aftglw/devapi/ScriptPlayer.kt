package com.aftglw.devapi

import org.yaml.snakeyaml.Yaml

// ===== 数据模型 =====

data class ScriptEvent(
    val type: String,
    val character: String = "",
    val text: String = "",
    val displaySubtitle: String = "",
    val condition: String = "",
    val options: List<ScriptChoice> = emptyList(),
    val endType: String = "",
    val nextChapter: String = ""
)

data class ScriptChoice(
    val text: String,
    val actions: List<ScriptAction> = emptyList()
)

data class ScriptAction(
    val type: String,
    val content: String = ""
)

data class ScriptChapter(
    val name: String,
    val events: List<ScriptEvent>
)

data class LingChatScript(
    val name: String,
    val description: String = "",
    val introChapter: String = "",
    val chapters: Map<String, ScriptChapter> = emptyMap()
)

// ===== YAML 引擎 =====

object ScriptEngine {
    private val variables = mutableMapOf<String, String>()

    fun parseYaml(yamlContent: String): LingChatScript? {
        return try {
            val yaml = Yaml()
            val doc = yaml.load<Map<String, Any>>(yamlContent) ?: return null

            val chapters = mutableMapOf<String, ScriptChapter>()
            val rawChapters = doc["chapters"] as? Map<String, Any>
            if (rawChapters != null) {
                for ((chName, chData) in rawChapters) {
                    val eventsRaw = (chData as? Map<String, Any>)?.get("events") as? List<Map<String, Any>>
                    if (eventsRaw != null) {
                        chapters[chName] = ScriptChapter(
                            name = chName,
                            events = eventsRaw.mapNotNull { parseEvent(it) }
                        )
                    }
                }
            } else {
                val eventsRaw = doc["events"] as? List<Map<String, Any>> ?: return null
                val chName = (doc["intro_chapter"] as? String) ?: "main"
                chapters[chName] = ScriptChapter(
                    name = chName,
                    events = eventsRaw.mapNotNull { parseEvent(it) }
                )
            }

            LingChatScript(
                name = (doc["name"] as? String) ?: "",
                description = (doc["description"] as? String) ?: "",
                introChapter = (doc["intro_chapter"] as? String) ?: chapters.keys.firstOrNull() ?: "main",
                chapters = chapters
            )
        } catch (_: Exception) { null }
    }

    private fun parseEvent(e: Map<String, Any>): ScriptEvent? {
        val type = e["type"] as? String ?: return null
        return ScriptEvent(
            type = type,
            character = (e["character"] as? String) ?: "",
            text = (e["text"] as? String) ?: "",
            displaySubtitle = (e["displaySubtitle"] as? String) ?: "",
            condition = (e["condition"] as? String) ?: "",
            options = parseChoices(e["options"] as? List<Map<String, Any>>),
            endType = (e["end_type"] as? String) ?: "",
            nextChapter = (e["next_chapter"] as? String) ?: ""
        )
    }

    private fun parseChoices(raw: List<Map<String, Any>>?): List<ScriptChoice> {
        if (raw == null) return emptyList()
        return raw.map { c ->
            val actionsRaw = c["actions"] as? List<Map<String, Any>> ?: emptyList()
            ScriptChoice(
                text = (c["text"] as? String) ?: "",
                actions = actionsRaw.map { a ->
                    ScriptAction(
                        type = (a["type"] as? String) ?: "",
                        content = (a["content"] as? String) ?: ""
                    )
                }
            )
        }
    }

    fun getChapter(script: LingChatScript, chapterName: String): List<ScriptEvent> {
        return script.chapters[chapterName]?.events ?: emptyList()
    }

    fun checkCondition(condition: String): Boolean {
        if (condition.isBlank()) return true
        val parts = condition.split("==").map { it.trim() }
        return if (parts.size == 2) variables[parts[0]] == parts[1].trim().trim('"') else true
    }

    fun applyAction(action: ScriptAction) {
        if (action.type == "set_variable") {
            val parts = action.content.split("=").map { it.trim() }
            if (parts.size == 2) variables[parts[0]] = parts[1]
        }
    }

    fun reset() { variables.clear() }
}
