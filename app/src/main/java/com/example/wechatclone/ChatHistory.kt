package com.example.wechatclone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ChatHistory {
    private const val PREFS = "chat_histories"

    fun load(context: Context, chatName: String): List<Triple<String, Boolean, String>> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(chatName, "[]") ?: "[]"
        val arr = JSONArray(json)
        val result = mutableListOf<Triple<String, Boolean, String>>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            result.add(Triple(obj.getString("text"), obj.getBoolean("isMe"), obj.optString("time", "")))
        }
        return result
    }

    fun save(context: Context, chatName: String, messages: List<Triple<String, Boolean, String>>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for ((text, isMe, time) in messages) {
            arr.put(JSONObject().apply {
                put("text", text)
                put("isMe", isMe)
                put("time", time)
            })
        }
        prefs.edit().putString(chatName, arr.toString()).apply()
    }
}
