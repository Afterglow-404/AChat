package com.aftglw.devapi.core.tools

import android.content.Context
import com.aftglw.devapi.network.HttpClient
import com.aftglw.devapi.tools.AiTool
import com.aftglw.devapi.tools.RiskLevel
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 基于 JSON 描述文件动态加载的工具。
 *
 * 支持多种实现类型：
 * - http：发送 HTTP 请求
 * - shell：执行 shell 命令（需 Root/Shizuku）
 * - script：执行内置 JavaScript（预留，待 QuickJS）
 * - kotlin：委托给已注册的 Kotlin 类
 */
class ScriptTool(
    private val descriptor: ToolDescriptor,
    private val toolDef: ToolDescriptor.ToolDef,
    private val envVars: Map<String, String> = emptyMap()
) : AiTool {

    override val name: String get() = toolDef.name
    override val description: String get() = toolDef.description
    override val inputSchema: JSONObject get() = buildInputSchema()
    // 动态加载工具可执行 shell / HTTP / Kotlin 反射 — 默认最高风险
    override val riskLevel: RiskLevel get() = RiskLevel.HIGH

    private fun buildInputSchema(): JSONObject {
        val schema = JSONObject()
        schema.put("type", "object")
        val props = JSONObject()
        val required = JSONArray()
        for (p in toolDef.parameters) {
            val param = JSONObject()
            param.put("type", p.type)
            param.put("description", p.description)
            props.put(p.name, param)
            if (p.required) required.put(p.name)
        }
        schema.put("properties", props)
        schema.put("required", required)
        return schema
    }

    override suspend fun execute(ctx: Context, args: JSONObject): String {
        // 优先使用工具级别的 impl，回退到包级别的 impl
        val impl = toolDef.impl ?: descriptor.impl ?: return "❌ 工具 ${toolDef.name} 未定义实现"

        return try {
            when (impl.type) {
                "http" -> executeHttp(ctx, impl, args)
                "shell" -> executeShell(ctx, impl, args)
                "script" -> executeScript(ctx, impl, args)
                "kotlin" -> executeKotlin(ctx, impl, args)
                else -> "❌ 不支持的实现类型: ${impl.type}"
            }
        } catch (e: Exception) {
            "❌ 工具执行失败: ${e.message}"
        }
    }

    private fun resolveVars(template: String?, params: JSONObject): String {
        var result = template ?: return ""
        // 替换 ${paramName}
        result = result.replace(Regex("""\$\{(\w+)\}""")) { match ->
            val key = match.groupValues[1]
            when {
                params.has(key) -> params.optString(key, "")
                envVars.containsKey(key) -> envVars[key]!!
                else -> match.value // 保留未匹配的占位符
            }
        }
        // 替换 {paramName} (无 $)
        result = result.replace(Regex("""\{(\w+)}""")) { match ->
            val key = match.groupValues[1]
            params.optString(key, match.value)
        }
        return result
    }

    private fun executeHttp(ctx: Context, impl: ToolDescriptor.ImplDef, args: JSONObject): String {
        val url = resolveVars(impl.url, args)
        val body = resolveVars(impl.body, args)

        val rb = okhttp3.Request.Builder().url(url)
        for ((key, value) in impl.headers) {
            rb.header(key, resolveVars(value, args))
        }

        when (impl.method.uppercase()) {
            "POST" -> rb.post(body.toRequestBody(HttpClient.JSON_MEDIA_TYPE))
            "PUT" -> rb.put(body.toRequestBody(HttpClient.JSON_MEDIA_TYPE))
            "DELETE" -> rb.delete(body.toRequestBody(HttpClient.JSON_MEDIA_TYPE))
            else -> rb.get()
        }

        val response = HttpClient.client.newCall(rb.build()).execute()
        val raw = response.body?.string() ?: ""
        response.close()

        return processResponse(raw, impl.response)
    }

    private fun executeShell(ctx: Context, impl: ToolDescriptor.ImplDef, args: JSONObject): String {
        val cmd = resolveVars(impl.command, args)
        return try {
            val process = Runtime.getRuntime().exec(cmd)
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            if (error.isNotBlank()) "STDERR: $error\n$output" else output
        } catch (e: Exception) {
            "❌ Shell 执行失败: ${e.message}"
        }
    }

    private fun executeScript(ctx: Context, impl: ToolDescriptor.ImplDef, args: JSONObject): String {
        // 预留：QuickJS 引擎集成后使用
        return "⚠️ 脚本引擎尚未集成。请使用 Kotlin 或 HTTP 实现。"
    }

    private suspend fun executeKotlin(ctx: Context, impl: ToolDescriptor.ImplDef, args: JSONObject): String {
        val className = impl.className ?: return "❌ 未指定 Kotlin 实现类"
        return try {
            val clazz = Class.forName(className)
            val instance = clazz.getDeclaredConstructor().newInstance()
            if (instance is AiTool) {
                instance.execute(ctx, args)
            } else "❌ 类 $className 未实现 AiTool 接口"
        } catch (e: Exception) {
            "❌ Kotlin 工具加载失败: ${e.message}"
        }
    }

    private fun processResponse(raw: String, resp: ToolDescriptor.ResponseDef?): String {
        if (resp == null) return raw.take(2000)

        return when (resp.format) {
            "json" -> {
                try {
                    val json = JSONObject(raw)
                    val extracted = resp.extract?.let { path ->
                        extractJsonPath(json, path)
                    } ?: raw
                    resp.template?.let { template ->
                        template.replace("{result}", extracted?.toString() ?: "")
                    } ?: (extracted?.toString() ?: raw)
                } catch (_: Exception) {
                    raw.take(2000)
                }
            }
            else -> raw.take(2000)
        }
    }

    private fun extractJsonPath(json: JSONObject, path: String): Any? {
        val parts = path.removePrefix("$.").removePrefix("$").split(".")
        var current: Any? = json
        for (part in parts) {
            val arrayMatch = Regex("""(\w+)\[(\d+)]""").find(part)
            if (arrayMatch != null) {
                val key = arrayMatch.groupValues[1]
                val index = arrayMatch.groupValues[2].toInt()
                current = when (current) {
                    is JSONObject -> current.optJSONArray(key)?.opt(index)
                    is JSONArray -> current.opt(index)
                    else -> null
                }
            } else {
                current = when (current) {
                    is JSONObject -> current.opt(part)
                    else -> null
                }
            }
        }
        return current
    }
}
