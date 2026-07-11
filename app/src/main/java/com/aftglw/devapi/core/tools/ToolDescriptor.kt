package com.aftglw.devapi.core.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wisp 工具描述符 — 基于 Operit METADATA 规范的简化版。
 *
 * 用户通过 JSON 文件定义工具，无需编写 Kotlin 代码。
 *
 * @see [ScriptTool]
 */
data class ToolDescriptor(
    val name: String,
    val description: String,
    val displayName: Map<String, String> = emptyMap(),
    val author: List<String> = emptyList(),
    val category: String = "Other",
    val env: List<String> = emptyList(),
    val tools: List<ToolDef>,
    val impl: ImplDef?
) {
    data class ToolDef(
        val name: String,
        val description: String,
        val parameters: List<ParamDef> = emptyList(),
        val advice: Boolean = false,
        val impl: ImplDef? = null
    )

    data class ParamDef(
        val name: String,
        val description: String,
        val type: String = "string",
        val required: Boolean = false
    )

    data class ImplDef(
        val type: String,       // "http", "shell", "script", "kotlin"
        val url: String? = null,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val command: String? = null,
        val script: String? = null,
        val className: String? = null,
        val response: ResponseDef? = null
    )

    data class ResponseDef(
        val format: String = "text",
        val extract: String? = null,
        val template: String? = null
    )

    companion object {
        fun fromJson(json: JSONObject): ToolDescriptor {
            val toolsArr = json.optJSONArray("tools") ?: JSONArray()
            val tools = (0 until toolsArr.length()).map { i ->
                val t = toolsArr.getJSONObject(i)
                val paramsArr = t.optJSONArray("parameters") ?: JSONArray()
                val params = (0 until paramsArr.length()).map { j ->
                    val p = paramsArr.getJSONObject(j)
                    ParamDef(
                        name = p.getString("name"),
                        description = p.optString("description", ""),
                        type = p.optString("type", "string"),
                        required = p.optBoolean("required", false)
                    )
                }
                val toolImpl = t.optJSONObject("impl")?.let { parseImpl(it) }
                ToolDef(
                    name = t.getString("name"),
                    description = t.optString("description", ""),
                    parameters = params,
                    advice = t.optBoolean("advice", false),
                    impl = toolImpl
                )
            }

            val pkgImpl = json.optJSONObject("impl")?.let { parseImpl(it) }

            // 解析多语言 displayName
            val dnObj = json.optJSONObject("display_name")
            val displayName = if (dnObj != null) {
                dnObj.keys().asSequence().associateWith { k -> dnObj.getString(k) }
            } else emptyMap()

            val authorArr = json.optJSONArray("author")
            val author = if (authorArr != null) {
                (0 until authorArr.length()).map { authorArr.getString(it) }
            } else {
                json.optString("author", "").takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
            }

            val envArr = json.optJSONArray("env")
            val env = if (envArr != null) {
                (0 until envArr.length()).map { envArr.getString(it) }
            } else emptyList()

            return ToolDescriptor(
                name = json.getString("name"),
                description = json.optString("description", ""),
                displayName = displayName,
                author = author,
                category = json.optString("category", "Other"),
                env = env,
                tools = tools,
                impl = pkgImpl
            )
        }

        private fun parseImpl(it: JSONObject): ImplDef {
            val headersObj = it.optJSONObject("headers")
            val headers = if (headersObj != null) {
                headersObj.keys().asSequence().associateWith { k -> headersObj.getString(k) }
            } else emptyMap()

            val respJson = it.optJSONObject("response")
            val resp = respJson?.let { r ->
                ResponseDef(
                    format = r.optString("format", "text"),
                    extract = r.optString("extract", null),
                    template = r.optString("template", null)
                )
            }

            return ImplDef(
                type = it.getString("type"),
                url = it.optString("url", null),
                method = it.optString("method", "GET"),
                headers = headers,
                body = it.optString("body", null),
                command = it.optString("command", null),
                script = it.optString("script", null),
                className = it.optString("className", null),
                response = resp
            )
        }
    }
}
