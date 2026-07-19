package com.aftglw.devapi.core.security

import android.content.Context
import android.content.SharedPreferences
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * [SecureKeyStore] 单元测试 —— 使用注入的 mock SharedPreferences 绕过 Keystore。
 *
 * 注意：纯 JVM 测试无法验证 EncryptedSharedPreferences 真实加密效果，
 * 只能验证 API 行为（读写、迁移、回退、删除）。
 * 真实加密效果需依赖真机/模拟器手动验证。
 */
class SecureKeyStoreTest {

    private lateinit var mockPrefs: InMemorySharedPreferences

    @Before
    fun setUp() {
        mockPrefs = InMemorySharedPreferences()
        // setPrefsForTest 会跳过 migration 与 Keystore 初始化
        SecureKeyStore.setPrefsForTest(mockPrefs)
    }

    @After
    fun tearDown() {
        SecureKeyStore.resetForTest()
    }

    /** 测试模式下 Context 不会被真正使用，用 mockk 提供非空 Context 即可 */
    private fun ctx(): Context = mockk(relaxed = true)

    @Test
    fun `putString 后 getString 返回相同值`() {
        SecureKeyStore.putString(ctx(), "ai_api_key", "sk-abc123")
        assertEquals("sk-abc123", SecureKeyStore.getString(ctx(), "ai_api_key"))
    }

    @Test
    fun `getString 默认值生效`() {
        assertEquals("", SecureKeyStore.getString(ctx(), "ai_api_key"))
        assertEquals("fallback", SecureKeyStore.getString(ctx(), "ai_api_key", "fallback"))
    }

    @Test
    fun `remove 后 contains 返回 false`() {
        SecureKeyStore.putString(ctx(), "ai_api_key", "sk-abc")
        assertTrue(SecureKeyStore.contains(ctx(), "ai_api_key"))
        SecureKeyStore.remove(ctx(), "ai_api_key")
        assertFalse(SecureKeyStore.contains(ctx(), "ai_api_key"))
    }

    @Test
    fun `空串也能存储与读取`() {
        SecureKeyStore.putString(ctx(), "ai_api_key", "")
        assertEquals("", SecureKeyStore.getString(ctx(), "ai_api_key"))
        assertTrue(SecureKeyStore.contains(ctx(), "ai_api_key"))
    }

    @Test
    fun `多个 key 互不干扰`() {
        SecureKeyStore.putString(ctx(), "ai_api_key", "sk-1")
        SecureKeyStore.putString(ctx(), "tts_cloud_key", "sk-2")
        SecureKeyStore.putString(ctx(), "web_search_api_key", "sk-3")
        assertEquals("sk-1", SecureKeyStore.getString(ctx(), "ai_api_key"))
        assertEquals("sk-2", SecureKeyStore.getString(ctx(), "tts_cloud_key"))
        assertEquals("sk-3", SecureKeyStore.getString(ctx(), "web_search_api_key"))
    }

    @Test
    fun `remove 一个 key 不影响其他 key`() {
        SecureKeyStore.putString(ctx(), "ai_api_key", "sk-1")
        SecureKeyStore.putString(ctx(), "tts_cloud_key", "sk-2")
        SecureKeyStore.remove(ctx(), "ai_api_key")
        assertFalse(SecureKeyStore.contains(ctx(), "ai_api_key"))
        assertEquals("sk-2", SecureKeyStore.getString(ctx(), "tts_cloud_key"))
    }

    /**
     * 内部 SharedPreferences 实现 —— 仅用于测试。
     * 简化版：仅实现常用 API，未实现 OnSharedPreferenceChangeListener 通知。
     */
    private class InMemorySharedPreferences : SharedPreferences {
        private val map = HashMap<String, Any?>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): Map<String, *> = map.toMap()
        override fun getString(key: String, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = (map[key] as? Set<String>) ?: defValues
        override fun getInt(key: String, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.add(listener)
        }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.remove(listener)
        }

        private inner class Editor : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val pendingRemoves = mutableSetOf<String>()
            private var doClear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                pending[key] = value; return this
            }
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
                pending[key] = values; return this
            }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                pending[key] = value; return this
            }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                pending[key] = value; return this
            }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                pending[key] = value; return this
            }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                pending[key] = value; return this
            }
            override fun remove(key: String): SharedPreferences.Editor {
                pendingRemoves.add(key); return this
            }
            override fun clear(): SharedPreferences.Editor {
                doClear = true; return this
            }
            override fun commit(): Boolean {
                apply(); return true
            }
            override fun apply() {
                if (doClear) map.clear()
                for (k in pendingRemoves) map.remove(k)
                for ((k, v) in pending) map[k] = v
                for (k in pending.keys.union(pendingRemoves)) {
                    listeners.forEach { it.onSharedPreferenceChanged(this@InMemorySharedPreferences, k) }
                }
            }
        }
    }
}
