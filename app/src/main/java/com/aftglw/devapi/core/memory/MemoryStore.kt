package com.aftglw.devapi.core.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.aftglw.devapi.network.HttpClient
import kotlin.math.sqrt
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

data class MemoryItem(
    val id: Long = 0,
    val text: String,
    val topic: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

class MemoryDB(ctx: Context) : SQLiteOpenHelper(ctx, "memory.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE memories(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, topic TEXT, timestamp INTEGER, vec BLOB)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
}

object MemoryStore {
    private var db: SQLiteDatabase? = null

    fun init(context: Context) { if (db == null) db = MemoryDB(context).writableDatabase }

    fun embed(ctx: Context, text: String): FloatArray? {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("ai_api_key", "") ?: return null
        val baseUrl = prefs.getString("ai_api_url", "")?.trimEnd('/') ?: return null
        // 从 URL 自动推断 embedding 模型名
        val embedModel = when {
            baseUrl.contains("deepseek", ignoreCase = true) -> "text-embedding-v2"
            baseUrl.contains("openai", ignoreCase = true) -> "text-embedding-3-small"
            baseUrl.contains("dashscope", ignoreCase = true) || baseUrl.contains("aliyun", ignoreCase = true) -> "text-embedding-v1"
            else -> prefs.getString("embedding_model", "text-embedding-v2") ?: "text-embedding-v2"
        }
        return try {
            val body = """{"model":"$embedModel","input":${JSONObject.quote(text)}}"""
            val request = okhttp3.Request.Builder()
                .url("$baseUrl/embeddings")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody(HttpClient.JSON_MEDIA_TYPE))
                .build()
            val response = HttpClient.client.newCall(request).execute()
            val resp = response.body?.string() ?: "{}"
            response.close()
            val arr = JSONObject(resp).optJSONArray("data")?.optJSONObject(0)?.optJSONArray("embedding")
            if (arr != null) FloatArray(arr.length()) { arr.optDouble(it, 0.0).toFloat() } else null
        } catch (_: Exception) { null }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na > 0 && nb > 0) dot / (sqrt(na) * sqrt(nb)) else 0f
    }

    fun deleteByText(text: String, topic: String? = null) {
        val where = if (topic != null) "text = ? AND topic LIKE ?" else "text = ?"
        val args = if (topic != null) arrayOf(text, "%$topic%") else arrayOf(text)
        db?.delete("memories", where, args)
    }

    fun save(ctx: Context, text: String, topic: String = "") {
        val vec = embed(ctx, text) ?: return
        val buf = ByteBuffer.allocate(vec.size * 4)
        vec.forEach { buf.putFloat(it) }
        val bytes = buf.array()
        val cv = ContentValues().apply {
            put("text", text); put("topic", topic); put("timestamp", System.currentTimeMillis())
            put("vec", bytes)
        }
        db?.insert("memories", null, cv)
    }

    fun search(ctx: Context, query: String, topK: Int = 5, topicFilter: String? = null): List<MemoryItem> {
        val qVec = embed(ctx, query) ?: return emptyList()
        val now = System.currentTimeMillis()
        val sql = if (topicFilter != null) "SELECT * FROM memories WHERE topic LIKE ? ORDER BY timestamp DESC LIMIT 1000"
            else "SELECT * FROM memories ORDER BY timestamp DESC LIMIT 1000"
        val cursor = if (topicFilter != null) db?.rawQuery(sql, arrayOf("%$topicFilter%")) else db?.rawQuery(sql, null)
        if (cursor == null) return emptyList()
        val scored = mutableListOf<Pair<Float, MemoryItem>>()
        while (cursor.moveToNext()) {
            val blob = cursor.getBlob(4) ?: continue
            val buf = ByteBuffer.wrap(blob)
            val mVec = FloatArray(buf.capacity() / 4) { buf.getFloat() }
            val sim = cosine(qVec, mVec)
            // 时间衰减：越旧的记忆权重越低
            val ageHours = (now - cursor.getLong(3)) / 3600000f
            val timeWeight = when {
                ageHours < 24 -> 1.0f       // 今天 → 全重
                ageHours < 72 -> 0.7f       // 3 天内 → 0.7
                ageHours < 168 -> 0.4f      // 一周内 → 0.4
                ageHours < 720 -> 0.2f      // 一个月内 → 0.2
                else -> 0.1f                // 更久 → 只能模糊想起
            }
            val weighted = sim * timeWeight
            if (weighted > 0.2f) {
                scored.add(weighted to MemoryItem(
                    id = cursor.getLong(0), text = cursor.getString(1),
                    topic = cursor.getString(2) ?: "", timestamp = cursor.getLong(3)
                ))
            }
        }
        cursor.close()
        scored.sortByDescending { it.first }
        return scored.take(topK).map { it.second }
    }
}
