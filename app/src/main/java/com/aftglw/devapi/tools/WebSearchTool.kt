package com.aftglw.devapi.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 联网搜索工具。
 *
 * 支持多个后端：
 * 1. DuckDuckGo Instant Answer API（免费，无需 Key，返回知识摘要）
 * 2. 用户自定义搜索 API（可配置任何 JSON 返回的搜索服务）
 */
class WebSearchTool : AiTool {
    override val name = "web_search"
    override val description = "搜索互联网获取最新信息、新闻、知识。需要联网。如果需要搜索用户本地笔记请用 recall 工具。"

    override val inputSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("q", JSONObject().apply {
                put("type", "string")
                put("description", "搜索关键词或问题")
            })
            put("count", JSONObject().apply {
                put("type", "number")
                put("description", "返回结果数量（默认 5，最多 10）")
            })
        })
        put("required", JSONArray().apply { put("q") })
    }

    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val query = args.optString("q", "").ifEmpty { return "请提供搜索关键词" }
        val count = args.optInt("count", 5).coerceIn(1, 10)

        // 1. 尝试 DuckDuckGo Instant Answer
        val ddgResult = searchDuckDuckGo(query)
        if (ddgResult != null) return ddgResult

        // 2. 尝试 DuckDuckGo HTML 搜索作为后备
        val htmlResult = searchDuckDuckGoHtml(query, count)
        if (htmlResult != null) return htmlResult

        // 3. 尝试用户配置的自定义搜索 API
        val customResult = searchCustom(ctx, query, count)
        if (customResult != null) return customResult

        return "搜索结果不可用（请检查网络连接）"
    }

    /**
     * DuckDuckGo Instant Answer API（免费，无需 Key）
     * 返回知识卡片 + 相关话题摘要
     */
    private fun searchDuckDuckGo(query: String): String? = try {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        val raw = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val json = JSONObject(raw)
        val abstractText = json.optString("AbstractText", "")
        val abstractSource = json.optString("AbstractSource", "")
        val heading = json.optString("Heading", "")

        val related = json.optJSONArray("RelatedTopics") ?: JSONArray()
        val relatedTexts = (0 until related.length()).mapNotNull { i ->
            val item = related.getJSONObject(i)
            val text = item.optString("Text", "")
            val firstUrl = item.optString("FirstURL", "")
            if (text.isNotBlank()) "• $text" else null
        }

        if (abstractText.isNotBlank() || relatedTexts.isNotEmpty()) {
            buildString {
                if (heading.isNotBlank()) appendLine("$heading\n")
                if (abstractText.isNotBlank()) {
                    appendLine(abstractText)
                    if (abstractSource.isNotBlank()) appendLine("—— 来源：$abstractSource")
                    appendLine()
                }
                if (relatedTexts.isNotEmpty()) {
                    appendLine("相关结果：")
                    relatedTexts.take(5).forEach { appendLine(it) }
                }
            }.trimEnd()
        } else null
    } catch (_: Exception) { null }

    /**
     * DuckDuckGo HTML 搜索后备方案
     * 解析 Lite 版本搜索结果页
     */
    private fun searchDuckDuckGoHtml(query: String, count: Int): String? = try {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://lite.duckduckgo.com/lite/?q=$encoded")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        val html = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        // 用正则提取结果标题和摘要（简单解析，不含 HTML parser）
        val resultRegex = Regex("""<a[^>]*href="([^"]*)"[^>]*class="result-link"[^>]*>([^<]*)</a>""",
            RegexOption.DOT_MATCHES_ALL)
        val snippetRegex = Regex("""<td[^>]*class="result-snippet"[^>]*>([^<]*)</td>""",
            RegexOption.DOT_MATCHES_ALL)

        val links = resultRegex.findAll(html).map {
            val url2 = it.groupValues[1].trim()
            val title = it.groupValues[2].trim().replace(Regex("<[^>]*>"), "")
            "$title — $url2"
        }.toList()

        val snippets = snippetRegex.findAll(html).map {
            it.groupValues[1].trim().replace(Regex("<[^>]*>"), "")
        }.toList()

        if (links.isNotEmpty()) {
            links.take(count).withIndex().joinToString("\n") { (i, link) ->
                val snippet = snippets.getOrElse(i) { "" }
                "${i + 1}. $link"
            }
        } else null
    } catch (_: Exception) { null }

    /**
     * 用户自定义搜索 API
     * 从 SharedPreferences 读取配置的搜索 API URL + Key
     */
    private fun searchCustom(ctx: Context, query: String, count: Int): String? {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val apiUrl = prefs.getString("web_search_api_url", "")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val apiKey = prefs.getString("web_search_api_key", "")?.trim()?.takeIf { it.isNotBlank() } ?: ""
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val finalUrl = apiUrl
                .replace("{q}", encoded)
                .replace("{count}", count.toString())
                .replace("{key}", apiKey)
            val conn = URL(finalUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("User-Agent", "AChat/1.0")
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            raw.take(1000) // 限制长度，防止 AI context 爆炸
        } catch (_: Exception) { null }
    }
}
