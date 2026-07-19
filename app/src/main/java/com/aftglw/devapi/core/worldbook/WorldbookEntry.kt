package com.aftglw.devapi.core.worldbook

import org.json.JSONObject

/**
 * 世界书条目。仿 SillyTavern Lorebook 模型：
 * - keywords：触发关键词，任一命中即注入 prompt
 * - content：条目正文
 * - constant：常驻条目，无视关键词始终注入
 * - priority：优先级，数字越大越优先（prompt 长度超限时先保留高优先级）
 * - enabled：禁用后不参与匹配
 */
data class WorldbookEntry(
    val id: Long,
    val keywords: List<String>,
    val content: String,
    val priority: Int = 0,
    val constant: Boolean = false,
    val enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("keywords", keywords.joinToString(","))
        put("content", content)
        put("priority", priority)
        put("constant", constant)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(obj: JSONObject): WorldbookEntry {
            val kwRaw = obj.optString("keywords", "")
            val keywords = if (kwRaw.isBlank()) emptyList()
            else kwRaw.split(",", "，", "、").map { it.trim() }.filter { it.isNotEmpty() }
            return WorldbookEntry(
                id = obj.optLong("id", 0L),
                keywords = keywords,
                content = obj.optString("content", ""),
                priority = obj.optInt("priority", 0),
                constant = obj.optBoolean("constant", false),
                enabled = obj.optBoolean("enabled", true)
            )
        }
    }
}
