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
    val chapters: List<ScriptChapterFile> = emptyList(),
    val isAdventure: Boolean = false,
    val boundCharacterFolder: String = "",
    val order: Int = 99,
    val unlockConditions: List<UnlockCondition> = emptyList(),
    val triggerMode: String = "manual"
)

data class UnlockCondition(
    val type: String,          // "adventure_completed"
    val adventureFolder: String // 需要先完成的剧本文件夹名
)

data class ScriptBranch(
    val nextChapter: String,
    val condition: String = "",
    val text: String = "",
    val default: Boolean = false
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
    val branches: List<ScriptBranch> = emptyList(),
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
    val chapterNames: Map<String, String> = emptyMap(), // chapter key -> display name
    val storyConfigPath: String = "",
    val assetBasePath: String = ""
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
        val chapterNames = mutableMapOf<String, String>()
        val chapterFiles = findChapterFiles(chaptersDir)

        for (cf in chapterFiles) {
            val yaml = File(cf).readText()
            val result = parseChapterYaml(yaml)
            if (result != null) {
                val (chName, events) = result
                // 用不含扩展名的路径作为 key
                val key = cf.removePrefix(chaptersDir.absolutePath).removePrefix(File.separator)
                    .removeSuffix(".yaml").removeSuffix(".yml")
                    .replace("\\", "/")
                chapters[key] = events
                if (chName.isNotBlank()) chapterNames[key] = chName
            }
        }

        return LingChatScript(
            name = config.scriptName,
            description = config.description,
            introChapter = config.introChapter,
            chapters = chapters,
            chapterNames = chapterNames,
            storyConfigPath = dir.absolutePath
        )
    }

    private fun parseStoryConfig(yaml: String): StoryConfig? = try {
        val doc = Yaml().load<Map<String, Any>>(yaml) ?: return null
        val adv = doc["adventure"] as? Map<String, Any>
        val rawUnlocks = adv?.get("unlock_conditions") as? List<Map<String, Any>>
        StoryConfig(
            scriptName = (doc["script_name"] as? String) ?: "",
            introChapter = (doc["intro_chapter"] as? String) ?: "main",
            description = (doc["description"] as? String) ?: "",
            chapters = emptyList(),
            isAdventure = (adv?.get("is_adventure") as? Boolean) ?: false,
            boundCharacterFolder = (adv?.get("bound_character_folder") as? String) ?: "",
            order = ((adv?.get("order") as? Number)?.toInt()) ?: 99,
            triggerMode = (adv?.get("trigger") as? Map<String, Any>)?.get("mode") as? String ?: "manual",
            unlockConditions = if (rawUnlocks != null) rawUnlocks.mapNotNull { u ->
                UnlockCondition(
                    type = (u["type"] as? String) ?: "",
                    adventureFolder = (u["adventure_folder"] as? String) ?: ""
                )
            } else emptyList()
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

    private fun parseChapterYaml(yaml: String): Pair<String, List<ScriptEvent>>? = try {
        val doc = Yaml().load<Map<String, Any>>(yaml) ?: return null
        val chapterName = (doc["name"] as? String) ?: ""
        val eventsRaw = doc["events"] as? List<Map<String, Any>> ?: return null
        chapterName to eventsRaw.mapNotNull { parseEventDirect(it) }
    } catch (_: Exception) { null }

    // ---------- 兼容：单文件 YAML ----------

    fun parseYaml(yamlContent: String): LingChatScript? {
        return try {
            val yaml = Yaml()
            val doc = yaml.load<Map<String, Any>>(yamlContent) ?: return null

            val chapters = mutableMapOf<String, List<ScriptEvent>>()
            val chapterNames = mutableMapOf<String, String>()
            val rawChapters = doc["chapters"] as? Map<String, Any>
            if (rawChapters != null) {
                for ((chName, chData) in rawChapters) {
                    val chMap = chData as? Map<String, Any>
                    val chDisplayName = chMap?.get("name") as? String ?: ""
                    val eventsRaw = chMap?.get("events") as? List<Map<String, Any>>
                    if (eventsRaw != null) {
                        chapters[chName] = eventsRaw.mapNotNull { parseEventDirect(it) }
                        if (chDisplayName.isNotBlank()) chapterNames[chName] = chDisplayName
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
                chapters = chapters,
                chapterNames = chapterNames
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
            branches = parseBranches(e["branches"] as? List<Map<String, Any>>),
            action = (e["action"] as? String) ?: "",
            duration = ((e["duration"] as? Number)?.toDouble()) ?: 1.0,
            maxRounds = ((e["max_rounds"] as? Number)?.toInt()) ?: -1,
            endLine = (e["end_line"] as? String) ?: "结束",
            endPrompt = (e["end_prompt"] as? String) ?: "",
            dialogPrompt = (e["dialog_prompt"] as? String) ?: (e["prompt"] as? String) ?: "",
            content = processText(e["content"] as? String)
        )
    }

    private fun parseBranches(raw: List<Map<String, Any>>?): List<ScriptBranch> {
        if (raw == null) return emptyList()
        return raw.mapNotNull { b ->
            val next = (b["next_chapter"] as? String) ?: return@mapNotNull null
            ScriptBranch(
                nextChapter = next,
                condition = (b["condition"] as? String) ?: "",
                text = (b["text"] as? String) ?: "",
                default = (b["default"] as? Boolean) ?: false
            )
        }
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

    /**
     * 检查条件表达式。支持:
     *   key==value  相等
     *   key!=value  不等
     *   key>=value  大于等于（数值优先）
     *   key<=value  小于等于（数值优先）
     *   key>value   大于（数值优先）
     *   key<value   小于（数值优先）
     */
    fun checkCondition(condition: String): Boolean {
        if (condition.isBlank()) return true

        val operators = listOf(">=", "<=", "!=", "==", ">", "<")
        for (op in operators) {
            val parts = condition.split(op).map { it.trim() }
            if (parts.size == 2) {
                val key = parts[0]
                val expected = parts[1].trim('"')
                val actual = variables[key] ?: ""
                return when (op) {
                    "==" -> actual == expected
                    "!=" -> actual != expected
                    ">=", "<=", ">", "<" -> {
                        val aNum = actual.toDoubleOrNull()
                        val eNum = expected.toDoubleOrNull()
                        if (aNum != null && eNum != null) {
                            when (op) {
                                ">=" -> aNum >= eNum; "<=" -> aNum <= eNum
                                ">" -> aNum > eNum; "<" -> aNum < eNum
                                else -> true
                            }
                        } else {
                            // 非数值回退字符串比较
                            when (op) {
                                ">=" -> actual >= expected
                                "<=" -> actual <= expected
                                ">" -> actual > expected
                                "<" -> actual < expected
                                else -> true
                            }
                        }
                    }
                    else -> true
                }
            }
        }
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
