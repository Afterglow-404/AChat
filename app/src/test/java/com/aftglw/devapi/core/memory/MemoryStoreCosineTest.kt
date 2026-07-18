package com.aftglw.devapi.core.memory

import org.junit.Assert.*
import org.junit.Test

/**
 * MemoryStore.cosine 单元测试 — 纯数学计算，无 Android 依赖。
 *
 * 验证：
 * - 相同向量得 1.0
 * - 正交向量得 0.0
 * - 相反向量得 -1.0
 * - 零向量返回 0（避免 NaN）
 * - 不同维度但同方向 → 按向量长度归一化后仍为 1
 * - 典型相似度（0.866 = cos 30°）
 */
class MemoryStoreCosineTest {

    private val eps = 1e-5f

    @Test
    fun `相同向量 余弦相似度为 1`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(1f, 0f, 0f)
        assertEquals(1f, MemoryStore.cosine(a, b), eps)
    }

    @Test
    fun `正交向量 余弦相似度为 0`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0f, MemoryStore.cosine(a, b), eps)
    }

    @Test
    fun `相反向量 余弦相似度为 -1`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(-1f, 0f)
        assertEquals(-1f, MemoryStore.cosine(a, b), eps)
    }

    @Test
    fun `零向量 a 返回 0 避免 NaN`() {
        val a = floatArrayOf(0f, 0f, 0f)
        val b = floatArrayOf(1f, 2f, 3f)
        assertEquals(0f, MemoryStore.cosine(a, b), eps)
    }

    @Test
    fun `零向量 b 返回 0 避免 NaN`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(0f, 0f, 0f)
        assertEquals(0f, MemoryStore.cosine(a, b), eps)
    }

    @Test
    fun `双零向量 返回 0`() {
        val a = floatArrayOf(0f, 0f)
        val b = floatArrayOf(0f, 0f)
        assertEquals(0f, MemoryStore.cosine(a, b), eps)
    }

    @Test
    fun `同方向不同模长 归一化后为 1`() {
        val a = floatArrayOf(3f, 4f)  // 模长 5
        val b = floatArrayOf(6f, 8f)  // 模长 10，同方向
        assertEquals(1f, MemoryStore.cosine(a, b), eps)
    }

    @Test
    fun `30度夹角 余弦值约为 0_866`() {
        // cos(30°) ≈ 0.8660254
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(Math.cos(Math.PI / 6).toFloat(), Math.sin(Math.PI / 6).toFloat())
        assertEquals(0.8660254f, MemoryStore.cosine(a, b), 1e-4f)
    }

    @Test
    fun `45度夹角 余弦值约为 0_707`() {
        // cos(45°) ≈ 0.70710678
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(1f, 1f)  // 45 度
        assertEquals(Math.cos(Math.PI / 4).toFloat(), MemoryStore.cosine(a, b), 1e-5f)
    }

    @Test
    fun `三维向量 正确计算相似度`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(2f, 4f, 6f)  // 同方向
        assertEquals(1f, MemoryStore.cosine(a, b), eps)
    }

    @Test
    fun `负相关向量 相似度为负`() {
        val a = floatArrayOf(1f, 1f)
        val b = floatArrayOf(-1f, 1f)  // 90 度... 不对，应是 0
        // (1*−1 + 1*1) / (√2 * √2) = 0 / 2 = 0
        assertEquals(0f, MemoryStore.cosine(a, b), eps)
    }

    @Test
    fun `负相关向量 135度夹角`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(-1f, 1f)  // 135 度
        // cos(135°) = -√2/2 ≈ -0.707
        assertEquals(-0.70710678f, MemoryStore.cosine(a, b), 1e-5f)
    }

    @Test
    fun `对称性 cosine a b 等于 cosine b a`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(4f, 5f, 6f)
        assertEquals(MemoryStore.cosine(a, b), MemoryStore.cosine(b, a), eps)
    }

    @Test
    fun `单维向量 正确计算`() {
        val a = floatArrayOf(5f)
        val b = floatArrayOf(3f)
        assertEquals(1f, MemoryStore.cosine(a, b), eps)
    }

    @Test
    fun `单维向量 一正一负`() {
        val a = floatArrayOf(5f)
        val b = floatArrayOf(-3f)
        assertEquals(-1f, MemoryStore.cosine(a, b), eps)
    }

    @Test
    fun `高维向量 不崩溃且结果在 -1 到 1 之间`() {
        val a = FloatArray(100) { it.toFloat() }
        val b = FloatArray(100) { (it * 2 + 1).toFloat() }
        val result = MemoryStore.cosine(a, b)
        assertTrue(result in -1f..1f)
        assertTrue(result > 0f)  // 正相关
    }
}
