package com.aftglw.devapi

import android.content.Context
import com.aftglw.devapi.model.ChatMessage
import com.aftglw.devapi.network.AiServiceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ===== 原有 JSON 脚本引擎 =====

data class ScriptStep(
    val send: String,
    val delayMs: Long = 1500,
    val expect: String? = null
)

data class ScriptResult(
    val step: Int,
    val sent: String,
    val reply: String,
    val passed: Boolean? = null,
    val error: String? = null
)

data class Script(
    val name: String,
    val mode: String,
    val steps: List<ScriptStep>
)

object ScriptPlayer {
    fun parse(json: String): Script? {
        return try {
            val obj = JSONObject(json)
            val stepsArr = obj.getJSONArray("steps")
            val steps = mutableListOf<ScriptStep>()
            for (i in 0 until stepsArr.length()) {
                val s = stepsArr.getJSONObject(i)
                steps.add(ScriptStep(
                    send = s.getString("send"),
                    delayMs = s.optLong("delay", 1500L),
                    expect = s.optString("expect", "").takeIf { it.isNotEmpty() }
                ))
            }
            Script(
                name = obj.getString("name"),
                mode = obj.optString("mode", "demo"),
                steps = steps
            )
        } catch (_: Exception) { null }
    }

    suspend fun run(ctx: Context, script: Script, persona: String = "", onResult: (ScriptResult) -> Unit) {
        val service = AiServiceFactory.getService()
        val history = mutableListOf<ChatMessage>()
        for ((i, step) in script.steps.withIndex()) {
            val reply = withContext(Dispatchers.IO) {
                try {
                    Thread.sleep(step.delayMs)
                    service.sendMessage(history.toList(), step.send, if (persona.isNotBlank()) persona else "你是一个友好的聊天伙伴。")
                } catch (_: Exception) { null }
            } ?: continue
            history.add(ChatMessage("user", step.send))
            history.add(ChatMessage("assistant", reply))
            val result = ScriptResult(
                step = i + 1, sent = step.send, reply = reply,
                passed = if (script.mode == "test" && step.expect != null) reply.contains(step.expect) else null
            )
            onResult(result)
        }
    }

    fun generateYamlSample(): String = """name: 日常片段
description: 一段日常对话
intro_chapter: main
events:
  - type: narration
    text: "傍晚，你坐在窗边发呆。"
  - type: dialogue
    character: "朋友"
    text: "嘿，发什么呆呢？"
  - type: dialogue
    character: "你"
    text: "没什么，就是觉得有点累。"
  - type: dialogue
    character: "朋友"
    text: "累了就歇会儿呗，我给你泡了茶。"
  - type: narration
    text: "一杯热茶放在了你面前。温暖透过杯壁传到手心。"
  - type: chapter_end
    end_type: "linear"
    next_chapter: "end"
"""

    fun generateDemoScripts(): List<Pair<String, String>> = listOf(
        "情绪感知演示" to """{"name":"情绪感知演示","mode":"demo","steps":[{"send":"今天好累啊","delay":1500},{"send":"又被老板骂了一顿","delay":2000},{"send":"不过下班吃了顿好的","delay":2000},{"send":"明天还要加班","delay":1500}]}""",
        "主动关怀演示" to """{"name":"主动关怀演示","mode":"demo","steps":[{"send":"一个人在家好无聊","delay":2000},{"send":"没什么事做","delay":2000},{"send":"你平时都干嘛","delay":2000}]}""",
        "测试：情绪识别" to """{"name":"测试：情绪识别","mode":"test","steps":[{"send":"气死我了","delay":1000,"expect":"生气"},{"send":"好开心啊","delay":1000,"expect":"开心"},{"send":"我有点害怕","delay":1000,"expect":"害怕"}]}"""
    )
}

// ===== LingChat YAML 脚本引擎 =====

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

data class LingChatScript(
    val name: String,
    val description: String = "",
    val introChapter: String = "",
    val events: List<ScriptEvent> = emptyList()
)

object ScriptEngine {
    private val variables = mutableMapOf<String, String>()

    fun parseYaml(yamlContent: String): LingChatScript? {
        return try {
            val yaml = org.yaml.snakeyaml.Yaml()
            val doc = yaml.load<Map<String, Any>>(yamlContent) ?: return null
            val eventsRaw = doc["events"] as? List<Map<String, Any>> ?: return null
            LingChatScript(
                name = (doc["name"] as? String) ?: "",
                description = (doc["description"] as? String) ?: "",
                introChapter = (doc["intro_chapter"] as? String) ?: "",
                events = eventsRaw.mapNotNull { e -> parseEvent(e) }
            )
        } catch (_: Exception) { null }
    }

    private fun parseEvent(e: Map<String, Any>): ScriptEvent? {
        val type = e["type"] as? String ?: return null
        return ScriptEvent(
            type = type, character = (e["character"] as? String) ?: "",
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
            ScriptChoice(text = (c["text"] as? String) ?: "",
                actions = actionsRaw.map { a -> ScriptAction(type = (a["type"] as? String) ?: "", content = (a["content"] as? String) ?: "") }
            )
        }
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
