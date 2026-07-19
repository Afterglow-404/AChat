package com.aftglw.devapi.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 集中式敏感凭据存储 —— 基于 EncryptedSharedPreferences。
 *
 * 背景：
 * - 旧版本将 API Key、TTS Key、Web 搜索 Key 等以明文存于 `wechat_settings` SharedPreferences。
 * - 现迁移到 EncryptedSharedPreferences（AES-GCM 256，密钥由 Android Keystore 托管）。
 *
 * 使用约定：
 * - 所有敏感 key 必须经 [SecureKeyStore.getString]/[putString] 读写，禁止散落各处直接读 SharedPreferences。
 * - 非敏感配置（URL、模型名、温度等）仍走普通 SharedPreferences。
 * - 首次调用任何方法时自动执行一次明文迁移（[migrateFromPlainPrefsIfNeeded]）。
 *
 * 已识别的敏感 key：
 * - `ai_api_key`         主 API Key（OpenAI/Claude/DeepSeek 通用）
 * - `tts_cloud_key`      云端 TTS Key（留空复用 ai_api_key）
 * - `web_search_api_key` Web 搜索 API Key
 */
object SecureKeyStore {

    private const val TAG = "SecureKeyStore"
    private const val PREFS_FILE = "secure_credentials"
    private const val MIGRATION_DONE_KEY = "_migration_done_v1"

    /** 已识别为敏感数据、需加密存储的 key 列表 */
    private val SENSITIVE_KEYS = listOf(
        "ai_api_key",
        "tts_cloud_key",
        "web_search_api_key"
    )

    /** 旧明文 SharedPreferences 文件名（与原代码一致） */
    private const val PLAIN_PREFS_FILE = "wechat_settings"

    @Volatile private var prefsCache: SharedPreferences? = null
    @Volatile private var migrationDone = false

    /**
     * 获取 EncryptedSharedPreferences 实例（懒加载 + 缓存）。
     *
     * 失败兜底：若 Keystore 在某些设备上初始化失败（极少见），返回 null，
     * 调用方应回退到空串而非崩溃。
     */
    private fun prefs(ctx: Context): SharedPreferences? {
        prefsCache?.let { return it }
        synchronized(this) {
            prefsCache?.let { return it }
            return try {
                val masterKey = MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val p = EncryptedSharedPreferences.create(
                    ctx,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                prefsCache = p
                p
            } catch (e: Exception) {
                Log.e(TAG, "EncryptedSharedPreferences init failed; falling back to empty", e)
                null
            }
        }
    }

    /** 一次性明文迁移：把 wechat_settings 中的敏感 key 复制到加密存储并清空原值 */
    private fun migrateFromPlainPrefsIfNeeded(ctx: Context) {
        if (migrationDone) return
        synchronized(this) {
            if (migrationDone) return
            val secure = prefs(ctx) ?: return
            if (secure.getBoolean(MIGRATION_DONE_KEY, false)) {
                migrationDone = true
                return
            }
            val plain = ctx.getSharedPreferences(PLAIN_PREFS_FILE, Context.MODE_PRIVATE)
            val editor = secure.edit()
            var migrated = 0
            for (key in SENSITIVE_KEYS) {
                if (secure.contains(key)) continue  // 已有则不覆盖
                val plainValue = plain.getString(key, null) ?: continue
                if (plainValue.isEmpty()) continue
                editor.putString(key, plainValue)
                migrated++
            }
            editor.putBoolean(MIGRATION_DONE_KEY, true)
            editor.apply()

            // 清空明文中的敏感值（仅在我们确认已写入加密存储后）
            if (migrated > 0) {
                val plainEditor = plain.edit()
                for (key in SENSITIVE_KEYS) plainEditor.remove(key)
                plainEditor.apply()
                Log.i(TAG, "Migrated $migrated sensitive key(s) from plain prefs to encrypted store")
            }
            migrationDone = true
        }
    }

    /** 读取敏感 key；不存在或加密存储初始化失败时返回空串 */
    @JvmOverloads
    fun getString(ctx: Context, key: String, default: String = ""): String {
        migrateFromPlainPrefsIfNeeded(ctx)
        val p = prefs(ctx) ?: return default
        return p.getString(key, default) ?: default
    }

    /** 写入敏感 key；加密存储初始化失败时静默回退到明文（保可用性） */
    fun putString(ctx: Context, key: String, value: String) {
        migrateFromPlainPrefsIfNeeded(ctx)
        val p = prefs(ctx)
        if (p != null) {
            p.edit().putString(key, value).apply()
        } else {
            // 兜底：Keystore 失败时退回明文，至少 app 能用
            Log.w(TAG, "Encrypted store unavailable, falling back to plain prefs for $key")
            ctx.getSharedPreferences(PLAIN_PREFS_FILE, Context.MODE_PRIVATE)
                .edit().putString(key, value).apply()
        }
    }

    /** 删除敏感 key */
    fun remove(ctx: Context, key: String) {
        migrateFromPlainPrefsIfNeeded(ctx)
        prefs(ctx)?.edit()?.remove(key)?.apply()
    }

    /** 是否包含某个敏感 key */
    fun contains(ctx: Context, key: String): Boolean {
        migrateFromPlainPrefsIfNeeded(ctx)
        return prefs(ctx)?.contains(key) ?: false
    }

    /**
     * 仅供测试：注入 mock 的 SharedPreferences，绕过 Keystore。
     * 测试结束后调用 [resetForTest] 清理。
     */
    @JvmStatic
    fun setPrefsForTest(mockPrefs: SharedPreferences?) {
        prefsCache = mockPrefs
        migrationDone = true  // 测试时跳过迁移
    }

    /** 仅供测试：重置内部状态 */
    @JvmStatic
    fun resetForTest() {
        prefsCache = null
        migrationDone = false
    }
}
