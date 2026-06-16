package com.example.wechatclone.viewmodel

import android.app.Application
import android.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.wechatclone.model.ChatItem
import org.json.JSONArray
class ChatsViewModel(app: Application) : AndroidViewModel(app) {
    private val _chats = MutableLiveData<List<ChatItem>>(loadChats())
    val chats: LiveData<List<ChatItem>> = _chats

    fun setSearchQuery(query: String) {
        _chats.value = if (query.isEmpty()) loadChats()
        else {
            val q = query.trim().lowercase()
            val app = getApplication<Application>()
            val histPrefs = app.getSharedPreferences("chat_histories", android.content.Context.MODE_PRIVATE)
            loadChats().filter { chat ->
                // Check name match
                if (chat.name.lowercase().contains(q)) return@filter true
                // Check message content in history
                val histJson = histPrefs.getString(chat.name, "[]") ?: "[]"
                val histArr = JSONArray(histJson)
                for (j in 0 until histArr.length()) {
                    val text = histArr.getJSONObject(j).optString("text", "")
                    if (text.lowercase().contains(q)) return@filter true
                }
                false
            }
        }
    }

    fun refresh() { _chats.value = loadChats() }

    fun deleteChat(id: String) {
        saveChats(loadChats().filter { it.id != id })
        refresh()
    }

    fun togglePin(id: String) {
        saveChats(loadChats().map { if (it.id == id) ChatItem(it.id, it.name, it.lastMessage, it.time, it.unreadCount, it.avatarColor, !it.pinned, it.persona, it.avatarUri) else it })
        refresh()
    }

    private fun saveChats(chats: List<ChatItem>) {
        val prefs = getApplication<Application>().getSharedPreferences("wechat_chats", android.content.Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (c in chats) {
            val obj = org.json.JSONObject()
            obj.put("id", c.id)
            obj.put("name", c.name)
            obj.put("lastMessage", c.lastMessage)
            obj.put("time", c.time)
            obj.put("unreadCount", c.unreadCount)
            obj.put("avatarColor", String.format("#%06X", 0xFFFFFF and c.avatarColor))
            obj.put("pinned", c.pinned)
            obj.put("persona", c.persona)
            obj.put("avatarUri", c.avatarUri)
            arr.put(obj)
        }
        prefs.edit().putString("chats", arr.toString()).apply()
    }

    private fun loadChats(): List<ChatItem> {
        val app = getApplication<Application>()
        val prefs = app.getSharedPreferences("wechat_chats", android.content.Context.MODE_PRIVATE)
        val histPrefs = app.getSharedPreferences("chat_histories", android.content.Context.MODE_PRIVATE)
        val json = prefs.getString("chats", "[]") ?: "[]"
        val arr = JSONArray(json)
        val result = mutableListOf<ChatItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val name = obj.getString("name")
            // Read last message from history
            val histJson = histPrefs.getString(name, "[]") ?: "[]"
            val histArr = JSONArray(histJson)
            var lastMsg = ""
            if (histArr.length() > 0) {
                lastMsg = histArr.getJSONObject(histArr.length() - 1).optString("text", "")
            }
            result.add(ChatItem(
                obj.getString("id"), name,
                lastMsg, obj.optString("time", ""),
                obj.optInt("unreadCount", 0),
                Color.parseColor(obj.optString("avatarColor", "#07C160")),
                obj.optBoolean("pinned", false),
                obj.optString("persona", ""),
                obj.optString("avatarUri", "")
            ))
        }
        return result.sortedByDescending { it.pinned }
    }
}
