package com.aftglw.devapi

import org.yaml.snakeyaml.Yaml
import java.io.File

// ===== 数据模型 =====

data class ScriptChapterFile(
    val name: String,
    val fileName: String  // 相对于 Chapters/ 的路径，如 "main" 或 "Intro/intro"
)

data class StoryConfig(
    val scriptName: String,
    val introChapter: String = "main",
    val description: String = "",
    val chapters: List<ScriptChapterFile> = emptyList()
)

data class ScriptEvent(
    val type: String,
    val character: String = "",
    val text: String = "",
    val prompt: String = "",
    val hint: String = "",
    val displaySubtitle: String = "",
    val displayName: String = "",
    val emotion: String = "",
    val condition: String = "",
    val options: List<ScriptChoice> = emptyList(),
    val allowFree: Boolean = false,
    val imagePath: String = "",
    val effect: String = "",
    val endType: String = "linear",
    val nextChapter: String = "",
    val action: String = "",
    val duration: Double = 1.0,
    val maxRounds: Int = -1,
    val endLine: String = "结束",
    val endPrompt: String = "",
    val dialogPrompt: String = "",
    val content: String = ""
)

data class ScriptChoice(
    val text: String,
    val condition: String = "",
    val actions: List<ScriptAction> = emptyList()
)

data class ScriptAction(
    val type: String,
    val content: String = ""
)

data class LingChatScript(
    val name: String,
    val description: String = "",
    val introChapter: String = "",
    val chapters: Map<String, List<ScriptEvent>> = emptyMap(),
    val storyConfigPath: String = ""
)

// ===== YAML 引擎 =====

object ScriptEngine {
    private val variables = mutableMapOf<String, String>()
    var playerName: String = "你"

    // ---------- 多文件加载 ----------

    /** 从 LingChat 剧本目录加载完整剧本 */
    fun loadFromDirectory(dir: File): LingChatScript? {
        val configFile = File(dir, "story_config.yaml")
        if (!configFile.exists()) return null

        val config = parseStoryConfig(configFile.readText()) ?: return null
        val chaptersDir = File(dir, "Chapters")
        if (!chaptersDir.exists()) return null

        val chapters = mutableMapOf<String, List<ScriptEvent>>()
        val chapterFiles = findChapterFiles(chaptersDir)

        for (cf in chapterFiles) {
            val yaml = File(cf).readText()
            val events = parseChapterYaml(yaml)
            if (events != null) {
                // 用不含扩展名的路径作为 key
                val key = cf.removePrefix(chaptersDir.absolutePath).removePrefix(File.separator)
                    .removeSuffix(".yaml").removeSuffix(".yml")
                    .replace("\\", "/")
                chapters[key] = events
            }
        }

        return LingChatScript(
            name = config.scriptName,
            description = config.description,
            introChapter = config.introChapter,
            chapters = chapters,
            storyConfigPath = dir.absolutePath
        )
    }

    private fun parseStoryConfig(yaml: String): StoryConfig? = try {
        val doc = Yaml().load<Map<String, Any>>(yaml) ?: return null
        StoryConfig(
            scriptName = (doc["script_name"] as? String) ?: "",
            introChapter = (doc["intro_chapter"] as? String) ?: "main",
            description = (doc["description"] as? String) ?: "",
            chapters = emptyList()
        )
    } catch (_: Exception) { null }

    private fun findChapterFiles(dir: File): List<String> {
        val result = mutableListOf<String>()
        dir.walkTopDown().forEach { f ->
            if (f.isFile && (f.extension == "yaml" || f.extension == "yml")) {
                result.add(f.absolutePath)
            }
        }
        return result.sorted()
    }

    private fun parseChapterYaml(yaml: String): List<ScriptEvent>? = try {
        val doc = Yaml().load<Map<String, Any>>(yaml) ?: return null
        val eventsRaw = doc["events"] as? List<Map<String, Any>> ?: return null
        eventsRaw.mapNotNull { parseEventDirect(it) }
    } catch (_: Exception) { null }

    // ---------- 兼容：单文件 YAML ----------

    fun parseYaml(yamlContent: String): LingChatScript? {
        return try {
            val yaml = Yaml()
            val doc = yaml.load<Map<String, Any>>(yamlContent) ?: return null

            val chapters = mutableMapOf<String, List<ScriptEvent>>()
            val rawChapters = doc["chapters"] as? Map<String, Any>
            if (rawChapters != null) {
                for ((chName, chData) in rawChapters) {
                    val eventsRaw = (chData as? Map<String, Any>)?.get("events") as? List<Map<String, Any>>
                    if (eventsRaw != null) {
                        chapters[chName] = eventsRaw.mapNotNull { parseEventDirect(it) }
                    }
                }
            } else {
                val eventsRaw = doc["events"] as? List<Map<String, Any>> ?: return null
                val chName = (doc["intro_chapter"] as? String) ?: "main"
                chapters[chName] = eventsRaw.mapNotNull { parseEventDirect(it) }
            }

            LingChatScript(
                name = (doc["name"] as? String) ?: (doc["script_name"] as? String) ?: "",
                description = (doc["description"] as? String) ?: "",
                introChapter = (doc["intro_chapter"] as? String) ?: chapters.keys.firstOrNull() ?: "main",
                chapters = chapters
            )
        } catch (_: Exception) { null }
    }

    // ---------- 事件解析器 ----------

    fun parseEventDirect(e: Map<String, Any>): ScriptEvent? {
        val type = e["type"] as? String ?: return null
        return ScriptEvent(
            type = type,
            character = (e["character"] as? String) ?: "",
            text = processText(e["text"]),
            prompt = (e["prompt"] as? String) ?: "",
            hint = (e["hint"] as? String) ?: "",
            displaySubtitle = (e["displaySubtitle"] as? String) ?: "",
            displayName = (e["displayName"] as? String) ?: "",
            emotion = (e["emotion"] as? String) ?: "",
            condition = (e["condition"] as? String) ?: "",
            options = parseChoices(e["options"] as? List<Map<String, Any>>),
            allowFree = (e["allow_free"] as? Boolean) ?: false,
            imagePath = (e["imagePath"] as? String) ?: (e["image_path"] as? String) ?: "",
            effect = (e["effect"] as? String) ?: "",
            endType = (e["end_type"] as? String) ?: "linear",
            nextChapter = (e["next_chapter"] as? String) ?: (e["next"] as? String) ?: "",
            action = (e["action"] as? String) ?: "",
            duration = ((e["duration"] as? Number)?.toDouble()) ?: 1.0,
            maxRounds = ((e["max_rounds"] as? Number)?.toInt()) ?: -1,
            endLine = (e["end_line"] as? String) ?: "结束",
            endPrompt = (e["end_prompt"] as? String) ?: "",
            dialogPrompt = (e["dialog_prompt"] as? String) ?: (e["prompt"] as? String) ?: "",
            content = processText(e["content"] as? String)
        )
    }

    private fun processText(raw: Any?): String {
        if (raw == null) return ""
        val s = raw.toString()
        // 多行文本块 | 支持
        return s.replace("%player%", playerName)
    }

    private fun parseChoices(raw: List<Map<String, Any>>?): List<ScriptChoice> {
        if (raw == null) return emptyList()
        return raw.map { c ->
            val actionsRaw = c["actions"] as? List<Map<String, Any>> ?: emptyList()
            ScriptChoice(
                text = (c["text"] as? String) ?: "",
                condition = (c["condition"] as? String) ?: "",
                actions = actionsRaw.map { a ->
                    ScriptAction(
                        type = (a["type"] as? String) ?: "",
                        content = processText(a["content"] as? String) ?: ""
                    )
                }
            )
        }
    }

    // ---------- 章节和事件查询 ----------

    fun getChapter(script: LingChatScript, chapterName: String): List<ScriptEvent> {
        return script.chapters[chapterName] ?: emptyList()
    }

    // ---------- 条件和变量 ----------

    fun checkCondition(condition: String): Boolean {
        if (condition.isBlank()) return true
        val parts = condition.split("==").map { it.trim() }
        if (parts.size == 2) return variables[parts[0]] == parts[1].trim().trim('"')
        val notParts = condition.split("!=").map { it.trim() }
        if (notParts.size == 2) return variables[notParts[0]] != notParts[1].trim().trim('"')
        return true
    }

    fun applyAction(action: ScriptAction) {
        when (action.type) {
            "set_variable", "set_var" -> {
                val parts = action.content.split("=").map { it.trim() }
                if (parts.size == 2) variables[parts[0]] = parts[1]
            }
        }
    }

    fun reset() { variables.clear() }

    // ---------- 示例剧本 ----------

    fun generateSampleYaml(): String = """name: 日常片段
description: 一段日常对话
intro_chapter: main
chapters:
  main:
    events:
      - type: narration
        text: "傍晚，你坐在窗边发呆。"
      - type: dialogue
        character: "朋友"
        text: "嘿，发什么呆呢？"
      - type: ai_dialogue
        character: "朋友"
        prompt: "朋友看到你发呆，关心地问你在想什么"
      - type: input
        hint: "随便说点什么..."
      - type: choices
        options:
          - text: "谢谢你的关心"
            actions:
              - type: set_variable
                content: "mood=温暖"
          - text: "我想一个人待会儿"
            actions:
              - type: set_variable
                content: "mood=冷淡"
        allow_free: false
      - type: narration
        text: "一杯热茶放在了你面前。温暖透过杯壁传到手心。"
        condition: "mood==温暖"
      - type: narration
        text: "朋友叹了口气，把茶放在桌上，轻轻带上了门。"
        condition: "mood==冷淡"
      - type: chapter_end
        end_type: linear
        next_chapter: "end"
  end:
    events:
      - type: narration
        text: "—— 完 ——"
"""
}
