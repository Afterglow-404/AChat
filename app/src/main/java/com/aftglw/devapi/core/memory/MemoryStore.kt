package com.aftglw.devapi.core.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.aftglw.devapi.network.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        android.util.Log.w("MemoryDB", "Upgrade from $old to $new — no migration defined yet")
    }
}

object MemoryStore {
    private var db: SQLiteDatabase? = null
    /** embed 缓存：text.hashCode() -> (timestamp, vector)，线程安全 */
    private val embedCache = LinkedHashMap<Int, Pair<Long, FloatArray>>(64, 0.75f, true)
    private const val EMBED_CACHE_MAX_SIZE = 500

    fun init(context: Context) {
        if (db == null) {
            db = MemoryDB(context).writableDatabase
            // 预热固定 query 的 embedding，供 PromptBuilder 后续命中缓存
            CoroutineScope(Dispatchers.IO).launch {
                embed(context, "情绪")
                embed(context, "最近")
            }
        }
    }

    fun embed(ctx: Context, text: String): FloatArray? {
        val hash = text.hashCode()

        // 线程安全缓存读 + 过期清理（不持锁做网络请求）
        synchronized(embedCache) {
            val cached = embedCache[hash]
            if (cached != null && System.currentTimeMillis() - cached.first < 300_000L) {
                return cached.second  // accessOrder=true 自动更新 LRU 顺序
            }
            embedCache.entries.removeAll { System.currentTimeMillis() - it.value.first > 300_000L }
        }

        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("ai_api_key", "") ?: return null
        val baseUrl = prefs.getString("ai_api_url", "")?.trimEnd('/') ?: return null
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
            val result = if (arr != null) FloatArray(arr.length()) { arr.optDouble(it, 0.0).toFloat() } else null
            if (result != null) {
                synchronized(embedCache) {
                    embedCache[hash] = System.currentTimeMillis() to result
                    if (embedCache.size > EMBED_CACHE_MAX_SIZE) {
                        val it = embedCache.entries.iterator()
                        if (it.hasNext()) { it.next(); it.remove() }
                    }
                }
            }
            result
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
        val vec = embed(ctx, text)
        val cv = ContentValues().apply {
            put("text", text); put("topic", topic); put("timestamp", System.currentTimeMillis())
            if (vec != null) {
                val buf = ByteBuffer.allocate(vec.size * 4)
                vec.forEach { buf.putFloat(it) }
                put("vec", buf.array())
            }
        }
        db?.insert("memories", null, cv)
        if (vec == null) {
            android.util.Log.w("MemoryStore", "Embedding failed, saved text-only: ${text.take(30)}...")
        }
    }

    fun search(ctx: Context, query: String, topK: Int = 5, topicFilter: String? = null): List<MemoryItem> {
        val qVec = embed(ctx, query)
        val now = System.currentTimeMillis()
        val sql = if (topicFilter != null) "SELECT * FROM memories WHERE topic LIKE ? ORDER BY timestamp DESC LIMIT 1000"
            else "SELECT * FROM memories ORDER BY timestamp DESC LIMIT 1000"
        val cursor = if (topicFilter != null) db?.rawQuery(sql, arrayOf("%$topicFilter%")) else db?.rawQuery(sql, null)
        if (cursor == null) return emptyList()

        if (qVec == null) {
            android.util.Log.w("MemoryStore", "Query embedding failed, falling back to text recency")
            val results = mutableListOf<MemoryItem>()
            while (cursor.moveToNext() && results.size < topK) {
                results.add(MemoryItem(cursor.getLong(0), cursor.getString(1), cursor.getString(2) ?: "", cursor.getLong(3)))
            }
            cursor.close()
            return results
        }

        val scored = mutableListOf<Pair<Float, MemoryItem>>()
        while (cursor.moveToNext()) {
            val blob = cursor.getBlob(4) ?: continue
            val buf = ByteBuffer.wrap(blob)
            val mVec = FloatArray(buf.capacity() / 4) { buf.getFloat() }
            val sim = cosine(qVec, mVec)
            val ageHours = (now - cursor.getLong(3)) / 3600000f
            val timeWeight = when {
                ageHours < 24 -> 1.0f
                ageHours < 72 -> 0.7f
                ageHours < 168 -> 0.4f
                ageHours < 720 -> 0.2f
                else -> 0.1f
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
