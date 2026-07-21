package com.aftglw.devapi.core.character
import com.aftglw.devapi.core.storage.ChatHistory
import com.aftglw.devapi.core.storage.room.AppDatabase
import com.aftglw.devapi.core.worldbook.WorldbookEntry
import com.aftglw.devapi.core.worldbook.WorldbookStore

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

object CharacterCardParser {
    data class Card(
        val name: String,
        val description: String,
        val personality: String,
        val firstMes: String,
        val systemPrompt: String,
        val postHistoryInstructions: String,
        /** V2 角色卡的 character_book.entries；V1 卡为空 */
        val characterBookEntries: List<WorldbookEntry> = emptyList()
    )

    fun parsePng(input: InputStream): Card? {
        val bytes = input.readBytes()
        var offset = 8 // PNG 头 8 字节，跳过
        while (offset + 8 <= bytes.size) {
            val length = readInt(bytes, offset)
            val type = String(bytes, offset + 4, 4)
            if (type == "tEXt" || type == "iTXt") {
                val data = bytes.copyOfRange(offset + 8, offset + 8 + length)
                // tEXt: keyword \0 text
                val nullIdx = data.indexOf(0)
                if (nullIdx >= 0) {
                    val keyword = String(data, 0, nullIdx)
                    val text = String(data, nullIdx + 1, data.size - nullIdx - 1)
                    if (keyword == "chara" || keyword == "Chara") {
                        val jsonStr = if (type == "iTXt") {
                            // iTXt: keyword \0 compression \0 language \0 translated \0 text
                            // Find the text after first 3 nulls
                            val parts = text.split('\u0000')
                            parts.getOrElse(3) { parts.last() }
                        } else text
                        return parseJson(jsonStr.trim())
                    }
                }
            }
            offset += 8 + length + 4 // chunk header + data + CRC
        }
        return null
    }

    private fun parseJson(json: String): Card? {
        return try {
            val obj = JSONObject(json)
            // V2 卡：spec=chara_card_v2，主要字段在 data 节点下
            val spec = obj.optString("spec", "")
            val dataObj = if (spec == "chara_card_v2") obj.optJSONObject("data") ?: obj else obj
            val bookEntries = parseCharacterBook(dataObj.optJSONObject("character_book"))
            Card(
                name = dataObj.optString("name", obj.optString("name", "")),
                description = dataObj.optString("description", obj.optString("description", "")),
                personality = dataObj.optString("personality", obj.optString("personality", "")),
                firstMes = dataObj.optString("first_mes", obj.optString("first_mes", "")),
                systemPrompt = dataObj.optString("system_prompt", obj.optString("system_prompt", "")),
                postHistoryInstructions = dataObj.optString("post_history_instructions", obj.optString("post_history_instructions", "")),
                characterBookEntries = bookEntries
            )
        } catch (_: Exception) { null }
    }

    /**
     * 解析 SillyTavern V2 character_book。结构：
     * { "name": "...", "description": "...", "entries": [ {keys, content, constant, priority, enabled, ...} ] }
     * entries 也可能是按 id 索引的对象：{ "0": {...}, "1": {...} }
     */
    private fun parseCharacterBook(book: JSONObject?): List<WorldbookEntry> {
        if (book == null) return emptyList()
        val entriesArr = book.optJSONArray("entries") ?: return emptyList()
        val out = mutableListOf<WorldbookEntry>()
        for (i in 0 until entriesArr.length()) {
            val item = entriesArr.optJSONObject(i) ?: continue
            out.add(parseBookEntry(item, i.toLong()))
        }
        // 兼容对象形式 entries：尝试一次 keys()
        if (out.isEmpty()) {
            val entriesObj = book.optJSONObject("entries")
            if (entriesObj != null) {
                val keys = entriesObj.keys()
                var idx = 0L
                while (keys.hasNext()) {
                    val k = keys.next()
                    val item = entriesObj.optJSONObject(k) ?: continue
                    out.add(parseBookEntry(item, idx++))
                }
            }
        }
        return out
    }

    private fun parseBookEntry(o: JSONObject, fallbackId: Long): WorldbookEntry {
        val keywords = mutableListOf<String>()
        when (val keysVal = o.opt("keys")) {
            is JSONArray -> {
                for (i in 0 until keysVal.length()) {
                    val s = keysVal.optString(i).trim()
                    if (s.isNotEmpty()) keywords.add(s)
                }
            }
            is String -> {
                keywords.addAll(keysVal.split(",", "，", "、").map { it.trim() }.filter { it.isNotEmpty() })
            }
        }
        o.optJSONArray("secondary_keys")?.let { sk ->
            for (i in 0 until sk.length()) {
                val s = sk.optString(i).trim()
                if (s.isNotEmpty()) keywords.add(s)
            }
        }
        val priority = o.optInt("priority", o.optInt("insertion_order", 0))
        return WorldbookEntry(
            id = o.optLong("id", fallbackId),
            keywords = keywords.distinct(),
            content = o.optString("content", ""),
            priority = priority,
            constant = o.optBoolean("constant", false),
            enabled = o.optBoolean("enabled", true)
        )
    }

    fun importToChat(ctx: Context, chatName: String, card: Card, avatarPath: String) {
        runBlocking {
            withContext(Dispatchers.IO) {
                val dao = AppDatabase.get(ctx).chatDao()
                val e = dao.getAll().firstOrNull { it.name == chatName } ?: return@withContext
                val persona = buildString {
                    if (card.description.isNotBlank()) appendLine(card.description)
                    if (card.personality.isNotBlank()) appendLine(card.personality)
                    if (card.systemPrompt.isNotBlank()) appendLine(card.systemPrompt)
                }.trim()
                val updated = e.copy(
                    persona = if (persona.isNotBlank()) persona else e.persona,
                    avatarUri = if (avatarPath.isNotBlank()) avatarPath else e.avatarUri
                )
                dao.upsert(updated)
                if (card.firstMes.isNotBlank()) {
                    // 存为首条消息
                    val history = ChatHistory.load(ctx, chatName)
                    val newHistory = history.toMutableList()
                    newHistory.add(0, Triple(card.firstMes, false, java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())))
                    ChatHistory.save(ctx, chatName, newHistory)
                }
                // V2 角色卡：导入 character_book 为世界书条目（merge 模式，避免覆盖用户手动创建的条目）
                if (card.characterBookEntries.isNotEmpty()) {
                    val existing = WorldbookStore.load(ctx, chatName)
                    if (existing.isEmpty()) {
                        // 当前角色世界书为空 → 直接 import（覆盖空表 = 等价于批量插入）
                        val json = JSONArray().apply {
                            card.characterBookEntries.forEach { e ->
                                put(JSONObject().apply {
                                    put("id", e.id)
                                    put("keys", JSONArray(e.keywords))
                                    put("content", e.content)
                                    put("constant", e.constant)
                                    put("priority", e.priority)
                                    put("enabled", e.enabled)
                                })
                            }
                        }.toString()
                        WorldbookStore.importJson(ctx, chatName, json, merge = false)
                    }
                    // 已有条目则跳过自动导入，避免覆盖；用户可通过「导入 JSON」手动合并
                }
            }
        }
    }

    private fun readInt(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 24) or
               ((buf[offset + 1].toInt() and 0xFF) shl 16) or
               ((buf[offset + 2].toInt() and 0xFF) shl 8) or
               (buf[offset + 3].toInt() and 0xFF)
    }
}
