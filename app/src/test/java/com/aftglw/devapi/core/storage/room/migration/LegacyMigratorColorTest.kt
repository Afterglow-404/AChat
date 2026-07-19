package com.aftglw.devapi.core.storage.room.migration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [LegacyMigrator.parseColor] 的纯逻辑测试。
 *
 * 验证旧 SharedPreferences 中以 "#RRGGBB" 字符串形式存储的颜色值能正确转换为 ARGB Int。
 * 不依赖 Android Runtime，纯 JUnit。
 */
class LegacyMigratorColorTest {

    @Test
    fun `六位十六进制色值转换为 ARGB Int`() {
        // #07C160 (微信绿) → 0xFF07C160
        val result = LegacyMigrator.parseColor("#07C160")
        assertEquals(0xFF07C160.toInt(), result)
    }

    @Test
    fun `黑色转换为 ARGB Int`() {
        assertEquals(0xFF000000.toInt(), LegacyMigrator.parseColor("#000000"))
    }

    @Test
    fun `白色转换为 ARGB Int`() {
        assertEquals(0xFFFFFFFF.toInt(), LegacyMigrator.parseColor("#FFFFFF"))
    }

    @Test
    fun `非井号前缀返回默认绿色`() {
        // 无 # 前缀：返回默认值，不抛异常
        assertEquals(0xFF07C160.toInt(), LegacyMigrator.parseColor("07C160"))
    }

    @Test
    fun `非法字符串返回默认绿色`() {
        assertEquals(0xFF07C160.toInt(), LegacyMigrator.parseColor("not-a-color"))
    }

    @Test
    fun `空字符串返回默认绿色`() {
        assertEquals(0xFF07C160.toInt(), LegacyMigrator.parseColor(""))
    }

    @Test
    fun `带 alpha 通道的八位色值按字面值解析`() {
        // 8 位形式：直接转 Int，不附加 alpha（与迁移前行为一致）
        // #FF07C160 → 0xFF07C160
        assertEquals(0xFF07C160.toInt(), LegacyMigrator.parseColor("#FF07C160"))
    }
}
