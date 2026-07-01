package com.aftglw.devapi

import android.content.Context
import org.json.JSONObject
import java.io.InputStream

object CharacterCardParser {
    data class Card(
        val name: String,
        val description: String,
        val personality: String,
        val firstMes: String,
        val systemPrompt: String,
        val postHistoryInstructions: String
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
            Card(
                name = obj.optString("name", ""),
                description = obj.optString("description", ""),
                personality = obj.optString("personality", ""),
                firstMes = obj.optString("first_mes", ""),
                systemPrompt = obj.optString("system_prompt", ""),
                postHistoryInstructions = obj.optString("post_history_instructions", "")
            )
        } catch (_: Exception) { null }
    }

    fun importToChat(ctx: Context, chatName: String, card: Card, avatarPath: String) {
        val prefs = ctx.getSharedPreferences("wechat_chats", Context.MODE_PRIVATE)
        val json = prefs.getString("chats", "[]") ?: "[]"
        val arr = org.json.JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("name") == chatName) {
                val persona = buildString {
                    if (card.description.isNotBlank()) appendLine(card.description)
                    if (card.personality.isNotBlank()) appendLine(card.personality)
                    if (card.systemPrompt.isNotBlank()) appendLine(card.systemPrompt)
                }.trim()
                if (persona.isNotBlank()) o.put("persona", persona)
                if (avatarPath.isNotBlank()) o.put("avatar", avatarPath)
                if (card.firstMes.isNotBlank()) {
                    // 存为首条消息
                    val history = ChatHistory.load(ctx, chatName)
                    val newHistory = history.toMutableList()
                    newHistory.add(0, Triple(card.firstMes, false, java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())))
                    ChatHistory.save(ctx, chatName, newHistory)
                }
                break
            }
        }
        prefs.edit().putString("chats", arr.toString()).apply()
    }

    private fun readInt(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 24) or
               ((buf[offset + 1].toInt() and 0xFF) shl 16) or
               ((buf[offset + 2].toInt() and 0xFF) shl 8) or
               (buf[offset + 3].toInt() and 0xFF)
    }
}
