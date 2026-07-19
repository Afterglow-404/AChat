package com.aftglw.devapi.core.storage

import android.content.Context
import com.aftglw.devapi.core.storage.room.AppDatabase
import com.aftglw.devapi.core.storage.room.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 单条聊天历史的持久化表示。
 * @param text 文本内容（图片消息可为占位符如 "[图片]"）
 * @param isMe 是否本人发送
 * @param time HH:mm 时间戳
 * @param imagePath 本地图片绝对路径（仅图片消息有值，重启后仍有效）
 */
data class ChatHistoryEntry(
    val text: String,
    val isMe: Boolean,
    val time: String,
    val imagePath: String? = null,
    val voicePath: String? = null,
    val voiceDuration: Int = 0,
    val voiceTranscript: String? = null
)

/**
 * 单聊历史消息持久化（旧 API 兼容层）。
 *
 * 底层已迁移到 Room 的 messages 表（is_group=0）。
 * 公共 API 保持同步签名，内部通过 `runBlocking(Dispatchers.IO)` 切到 IO 线程调用 Room。
 * 调用方需注意：在主线程调用时会阻塞主线程（行为与迁移前 SharedPreferences 一致）。
 */
object ChatHistory {

    /** 旧 API 保留：纯文本三元组。内部转发到新 API。 */
    fun load(context: Context, chatName: String): List<Triple<String, Boolean, String>> {
        return loadEntries(context, chatName).map { Triple(it.text, it.isMe, it.time) }
    }

    fun loadEntries(context: Context, chatName: String): List<ChatHistoryEntry> = runBlocking {
        withContext(Dispatchers.IO) {
            AppDatabase.get(context).messageDao()
                .getMessages(chatName, isGroup = false)
                .map { it.toEntry() }
        }
    }

    /** 旧 API 保留：纯文本三元组。内部转发到新 API。 */
    fun save(context: Context, chatName: String, messages: List<Triple<String, Boolean, String>>) {
        saveEntries(context, chatName, messages.map { ChatHistoryEntry(it.first, it.second, it.third) })
    }

    fun saveEntries(context: Context, chatName: String, messages: List<ChatHistoryEntry>) = runBlocking {
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(context).messageDao()
            dao.deleteForChat(chatName, isGroup = false)
            if (messages.isNotEmpty()) {
                dao.insertAll(messages.map {
                    MessageEntity(
                        chatId = chatName, isGroup = false, isMe = it.isMe,
                        text = it.text, time = it.time, imagePath = it.imagePath,
                        voicePath = it.voicePath, voiceDuration = it.voiceDuration,
                        voiceTranscript = it.voiceTranscript,
                        fromName = null
                    )
                })
            }
        }
    }
}

private fun MessageEntity.toEntry() = ChatHistoryEntry(
    text = text,
    isMe = isMe,
    time = time,
    imagePath = imagePath,
    voicePath = voicePath,
    voiceDuration = voiceDuration,
    voiceTranscript = voiceTranscript
)
