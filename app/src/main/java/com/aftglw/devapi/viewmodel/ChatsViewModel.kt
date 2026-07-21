package com.aftglw.devapi.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.aftglw.devapi.core.character.BuiltInCharacterLoader
import com.aftglw.devapi.core.storage.room.AppDatabase
import com.aftglw.devapi.core.storage.room.ChatEntity
import com.aftglw.devapi.model.ChatItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatsViewModel(app: Application) : AndroidViewModel(app) {
    private val _chats = MutableLiveData<List<ChatItem>>(emptyList())
    val chats: LiveData<List<ChatItem>> = _chats

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _chats.postValue(loadChatsWithBuiltin())
        }
    }

    suspend fun setSearchQuery(query: String) {
        _chats.value = if (query.isEmpty()) loadChats()
        else {
            val q = query.trim().lowercase()
            val app = getApplication<Application>()
            val all = loadChats()
            // 一次 SQL LIKE 查询所有命中 chatId，替代「遍历 chat → getMessages → 内存过滤」N+1
            val hitIds = withContext(Dispatchers.IO) {
                AppDatabase.get(app).messageDao().searchChatIds("%$q%")
            }.toSet()
            all.filter { chat ->
                // 名字匹配优先
                if (chat.name.lowercase().contains(q)) return@filter true
                // 历史文本匹配：用 SQL 结果集合，避免逐个 chat 再查 messages
                hitIds.contains(chat.name)
            }
        }
    }

    suspend fun refresh() { _chats.value = loadChats() }

    suspend fun deleteChat(id: String) {
        val chats = loadChats()
        val target = chats.find { it.id == id } ?: return
        withContext(Dispatchers.IO) {
            val db = AppDatabase.get(getApplication())
            db.runInTransaction {
                db.chatDao().deleteById(id)
                db.messageDao().deleteForChat(target.name, isGroup = false)
            }
        }
        // 清除相关设置（proactive/mood/affinity 等仍用 SharedPreferences）
        val settings = getApplication<Application>().getSharedPreferences("wechat_settings", android.content.Context.MODE_PRIVATE)
        settings.edit().apply {
            remove("proactive_enabled_${target.name}")
            remove("proactive_daily_limit_${target.name}")
            remove("proactive_idle_hours_${target.name}")
            remove("proactive_last_${target.name}")
            remove("proactive_silence_${target.name}")
            remove("proactive_count_${target.name}")
            remove("proactive_need_care_${target.name}")
            remove("last_active_${target.name}")
            remove("last_mood_${target.name}")
            remove("persona_optimized_${target.name}")
            remove("persona_dialogue_traits_${target.name}")
            remove("persona_dialogue_traits_ts_${target.name}")
            remove("dialogue_optimization_${target.name}")
            remove("reflection_${target.name}")
            remove("affinity_v2_${target.name}")
            remove("affinity_mode_${target.name}")
            remove("affinity_lock_${target.name}")
            apply()
        }
        refresh()
    }

    suspend fun togglePin(id: String) {
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(getApplication()).chatDao()
            val e = dao.getById(id) ?: return@withContext
            dao.upsert(e.copy(pinned = !e.pinned))
        }
        refresh()
    }

    private suspend fun saveChats(chats: List<ChatItem>) {
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(getApplication()).chatDao()
            dao.deleteAll()
            if (chats.isNotEmpty()) dao.insertAll(chats.map { it.toEntity() })
        }
    }

    /**
     * 启动时加载：首次启动（无已存角色）自动预装内置角色。
     * 之后用户删除内置角色不再自动加回（prefs 标记 builtin_seeded）。
     */
    private suspend fun loadChatsWithBuiltin(): List<ChatItem> {
        val app = getApplication<Application>()
        val prefs = app.getSharedPreferences("wechat_chats", android.content.Context.MODE_PRIVATE)
        val existing = loadChats()
        if (existing.isNotEmpty()) return existing
        // 首次启动：加载内置角色并持久化
        val seedFlag = "builtin_seeded"
        if (prefs.getBoolean(seedFlag, false)) return existing
        val builtIns = BuiltInCharacterLoader.listAll(app)
        if (builtIns.isEmpty()) {
            prefs.edit().putBoolean(seedFlag, true).apply()
            return existing
        }
        saveChats(builtIns)
        prefs.edit().putBoolean(seedFlag, true).apply()
        return loadChats()
    }

    private suspend fun loadChats(): List<ChatItem> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val db = AppDatabase.get(app)
        // 一次 JOIN 取所有 chat + 最后一条消息文本，替代 N+1（每个 chat 单独查 messages）
        db.chatDao().getAllWithLastMessage().map { row ->
            row.chat.toModel(row.lastText ?: "")
        }.sortedByDescending { it.pinned }
    }
}

private fun ChatEntity.toModel(lastMsg: String): ChatItem = ChatItem(
    id = id,
    name = name,
    lastMessage = lastMsg,
    time = time,
    unreadCount = unreadCount,
    avatarColor = avatarColor,
    pinned = pinned,
    persona = persona,
    avatarUri = avatarUri,
    characterFolder = characterFolder,
    thinkingMessage = thinkingMessage
)

private fun ChatItem.toEntity(): ChatEntity = ChatEntity(
    id = id,
    name = name,
    lastMessage = lastMessage,
    time = time,
    unreadCount = unreadCount,
    avatarColor = avatarColor,
    pinned = pinned,
    persona = persona,
    avatarUri = avatarUri,
    characterFolder = characterFolder,
    thinkingMessage = thinkingMessage
)
