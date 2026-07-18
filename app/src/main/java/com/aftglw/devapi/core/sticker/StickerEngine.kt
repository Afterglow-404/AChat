package com.aftglw.devapi.core.sticker

import android.content.Context
import org.json.JSONObject

object StickerEngine {
    private var loaded = false
    private val tagIndex = mutableMapOf<String, MutableMap<String, String>>()

    fun init(ctx: Context) {
        if (loaded) return
        try {
            val packs = ctx.assets.list("stickers") ?: return
            for (pack in packs) {
                val jsonPath = "stickers/$pack/stickers.json"
                val jsonString = try {
                    ctx.assets.open(jsonPath).bufferedReader().use { it.readText() }
                } catch (e: Exception) { android.util.Log.w("StickerEngine", "Failed to load $jsonPath", e); null } ?: continue

                val root = JSONObject(jsonString)
                val stickers = root.optJSONArray("stickers") ?: continue
                val packMap = mutableMapOf<String, String>()

                for (i in 0 until stickers.length()) {
                    val s = stickers.getJSONObject(i)
                    val file = s.optString("file", "")
                    val tags = s.optJSONArray("tags") ?: continue
                    for (j in 0 until tags.length()) {
                        val tag = tags.getString(j).trim().lowercase()
                        if (tag.isNotEmpty() && file.isNotEmpty()) {
                            packMap[tag] = "stickers/$pack/$file"
                        }
                    }
                }
                if (packMap.isNotEmpty()) {
                    tagIndex[pack] = packMap
                }
            }
            loaded = true
        } catch (e: Exception) { android.util.Log.w("StickerEngine", "init failed", e) }
    }

    fun match(packName: String, tag: String): String? {
        val pack = tagIndex[packName] ?: return null
        val key = tag.trim().lowercase()
        pack[key]?.let { return it }
        // 模糊匹配：按 tag 长度排序，较短的 tag 优先（更精确）
        val candidates = pack.filter { (t, _) -> t.contains(key) || key.contains(t) }
            .entries.sortedBy { it.key.length }
        candidates.firstOrNull()?.value?.let { return it }
        return null
    }

    fun getPackNames(): Set<String> = tagIndex.keys

    fun hasPack(name: String): Boolean = tagIndex.containsKey(name)

    fun getTags(packName: String): List<String> = tagIndex[packName]?.keys?.toList() ?: emptyList()
}
