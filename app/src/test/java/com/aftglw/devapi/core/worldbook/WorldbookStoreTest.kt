package com.aftglw.devapi.core.worldbook

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldbookStoreTest {

    private fun entry(
        id: Long,
        keywords: List<String>,
        content: String,
        priority: Int = 0,
        constant: Boolean = false,
        enabled: Boolean = true
    ) = WorldbookEntry(id, keywords, content, priority, constant, enabled)

    // ===== matchEntries 纯函数测试 =====

    @Test
    fun `空条目列表返回空`() {
        val result = WorldbookStore.matchEntries(emptyList(), "任意文本")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `常驻条目始终命中`() {
        val e = entry(1L, emptyList(), "世界观总述", constant = true)
        val result = WorldbookStore.matchEntries(listOf(e), "")
        assertEquals(listOf(e), result)
    }

    @Test
    fun `禁用的常驻条目不命中`() {
        val e = entry(1L, emptyList(), "禁用条目", constant = true, enabled = false)
        val result = WorldbookStore.matchEntries(listOf(e), "任意文本")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `关键词命中触发注入`() {
        val e = entry(1L, listOf("魔法", "咒语"), "魔法体系说明")
        val result = WorldbookStore.matchEntries(listOf(e), "今天学了一个新魔法")
        assertEquals(listOf(e), result)
    }

    @Test
    fun `关键词未命中不注入`() {
        val e = entry(1L, listOf("魔法"), "魔法体系说明")
        val result = WorldbookStore.matchEntries(listOf(e), "今天天气不错")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `空关键词的非常驻条目不注入`() {
        val e = entry(1L, emptyList(), "无关键词")
        val result = WorldbookStore.matchEntries(listOf(e), "任意文本")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `关键词大小写不敏感`() {
        val e = entry(1L, listOf("Hogwarts"), "霍格沃茨学校")
        val result = WorldbookStore.matchEntries(listOf(e), "I love hogwarts!")
        assertEquals(listOf(e), result)
    }

    @Test
    fun `多关键词任一命中即注入`() {
        val e = entry(1L, listOf("魔法", "魔兽", "魔王"), "奇幻设定")
        val result = WorldbookStore.matchEntries(listOf(e), "魔王军来袭")
        assertEquals(listOf(e), result)
    }

    @Test
    fun `中文关键词直接包含匹配`() {
        val e = entry(1L, listOf("艾尔登法环"), "交界地设定")
        val result = WorldbookStore.matchEntries(listOf(e), "我在玩艾尔登法环")
        assertEquals(listOf(e), result)
    }

    @Test
    fun `按 priority 降序排序`() {
        val low = entry(1L, listOf("test"), "低优先级", priority = 1)
        val high = entry(2L, listOf("test"), "高优先级", priority = 10)
        val mid = entry(3L, listOf("test"), "中优先级", priority = 5)
        val result = WorldbookStore.matchEntries(listOf(low, high, mid), "test")
        assertEquals(listOf(high, mid, low), result)
    }

    @Test
    fun `maxEntries 截断超量条目`() {
        val entries = (1..20).map { entry(it.toLong(), listOf("kw$it"), "内容$it") }
        val result = WorldbookStore.matchEntries(entries, "kw1 kw2 kw3", maxEntries = 3)
        assertEquals(3, result.size)
    }

    @Test
    fun `常驻与关键词命中混合`() {
        val constant = entry(1L, emptyList(), "常驻设定", constant = true)
        val matched = entry(2L, listOf("魔法"), "魔法设定")
        val unmatched = entry(3L, listOf("剑"), "剑的设定")
        val result = WorldbookStore.matchEntries(listOf(constant, matched, unmatched), "今天学了魔法")
        assertEquals(2, result.size)
        assertTrue(result.contains(constant))
        assertTrue(result.contains(matched))
        assertFalse(result.contains(unmatched))
    }

    @Test
    fun `空白关键词不触发误匹配`() {
        val e = entry(1L, listOf("", "  "), "空关键词条目")
        val result = WorldbookStore.matchEntries(listOf(e), "任意文本")
        assertTrue(result.isEmpty())
    }

    // ===== JSON 序列化往返测试 =====

    @Test
    fun `entry JSON 往返保持字段`() {
        val original = entry(42L, listOf("魔法", "咒语"), "魔法体系", priority = 5, constant = true, enabled = false)
        val json = original.toJson()
        val restored = WorldbookEntry.fromJson(json)
        assertEquals(original.id, restored.id)
        assertEquals(original.keywords, restored.keywords)
        assertEquals(original.content, restored.content)
        assertEquals(original.priority, restored.priority)
        assertEquals(original.constant, restored.constant)
        assertEquals(original.enabled, restored.enabled)
    }

    @Test
    fun `parseEntries 解析合法 JSONArray`() {
        val arr = JSONArray()
        arr.put(entry(1L, listOf("kw1"), "c1").toJson())
        arr.put(entry(2L, listOf("kw2"), "c2").toJson())
        val result = WorldbookStore.parseEntries(arr.toString())
        assertEquals(2, result.size)
        assertEquals(1L, result[0].id)
        assertEquals("c2", result[1].content)
    }

    @Test
    fun `parseEntries 空字符串返回空列表`() {
        assertTrue(WorldbookStore.parseEntries("").isEmpty())
    }

    @Test
    fun `parseEntries 非法 JSON 返回空列表`() {
        assertTrue(WorldbookStore.parseEntries("not a json").isEmpty())
    }

    @Test
    fun `parseEntries 空 JSONArray 返回空列表`() {
        assertTrue(WorldbookStore.parseEntries("[]").isEmpty())
    }

    @Test
    fun `关键词分隔符支持中英文逗号和顿号`() {
        val json = JSONObject().apply {
            put("id", 1L)
            put("keywords", "魔法,咒语，魔兽、魔王")
            put("content", "test")
        }
        val entry = WorldbookEntry.fromJson(json)
        assertEquals(listOf("魔法", "咒语", "魔兽", "魔王"), entry.keywords)
    }

    @Test
    fun `缺失字段使用默认值`() {
        val json = JSONObject().apply {
            put("id", 1L)
            put("content", "只有内容")
        }
        val entry = WorldbookEntry.fromJson(json)
        assertEquals(1L, entry.id)
        assertTrue(entry.keywords.isEmpty())
        assertEquals("只有内容", entry.content)
        assertEquals(0, entry.priority)
        assertFalse(entry.constant)
        assertTrue(entry.enabled)
    }
}
