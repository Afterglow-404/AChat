package com.aftglw.devapi

import android.content.Context
import com.aftglw.devapi.model.ChatMessage
import com.aftglw.devapi.network.AiServiceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
    val mode: String,  // "demo" or "test"
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
                    Thread.sleep(step.delayMs) // 模拟输入间隔
                    service.sendMessage(history.toList(), step.send, if (persona.isNotBlank()) persona else "你是一个友好的聊天伙伴。")
                } catch (_: Exception) { null }
            } ?: continue

            history.add(ChatMessage("user", step.send))
            history.add(ChatMessage("assistant", reply))

            val result = ScriptResult(
                step = i + 1,
                sent = step.send,
                reply = reply,
                passed = if (script.mode == "test" && step.expect != null) reply.contains(step.expect) else null
            )
            onResult(result)
        }
    }

    fun generateDemoScripts(): List<Pair<String, String>> = listOf(
        "情绪感知演示" to """{
  "name": "情绪感知演示",
  "mode": "demo",
  "steps": [
    {"send": "今天好累啊", "delay": 1500},
    {"send": "又被老板骂了一顿", "delay": 2000},
    {"send": "不过下班吃了顿好的", "delay": 2000},
    {"send": "明天还要加班", "delay": 1500}
  ]
}""",
        "主动关怀演示" to """{
  "name": "主动关怀演示",
  "mode": "demo",
  "steps": [
    {"send": "一个人在家好无聊", "delay": 2000},
    {"send": "没什么事做", "delay": 2000},
    {"send": "你平时都干嘛", "delay": 2000}
  ]
}""",
        "测试：情绪识别" to """{
  "name": "测试：情绪识别",
  "mode": "test",
  "steps": [
    {"send": "气死我了", "delay": 1000, "expect": "生气"},
    {"send": "好开心啊", "delay": 1000, "expect": "开心"},
    {"send": "我有点害怕", "delay": 1000, "expect": "害怕"}
  ]
}"""
    )
}
