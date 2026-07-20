package com.aftglw.devapi.core.character

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.aftglw.devapi.model.ChatItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.Yaml

/**
 * 内置角色加载器 — 从 assets/characters/ 加载预置角色。
 *
 * 目录结构：
 *   assets/characters/{folder}/settings.yml
 *   assets/characters/{folder}/avatar/{情绪}.webp
 *
 * 头像引用使用 `asset://` 伪协议（如 "asset://characters/诺一钦灵/avatar/正常.webp"），
 * 加载端用 [assetUriToPath] 还原 assets 路径后用 AssetManager.open() 读取。
 */
object BuiltInCharacterLoader {

    private const val TAG = "BuiltInChar"
    private const val ASSETS_ROOT = "characters"
    const val ASSET_PREFIX = "asset://"

    /** listAll 内存缓存，避免每次扫描 assets 目录。内置角色不可变，无需失效。 */
    @Volatile
    private var listAllCache: List<ChatItem>? = null

    /**
     * 头像 Bitmap LruCache（容量 16MB）。
     * LruCache 内部已用 synchronized 保护，多线程读写安全，无需额外同步。
     * key = 头像 uri（asset:// 伪协议或文件路径），value = 解码后的 Bitmap。
     */
    private val avatarCache = LruCache<String, Bitmap>(16 * 1024 * 1024)

    /** assets/characters 下的角色文件夹列表 */
    fun listFolders(ctx: Context): List<String> {
        return try {
            ctx.assets.list(ASSETS_ROOT)?.toList()?.filter { folder ->
                try {
                    ctx.assets.list("$ASSETS_ROOT/$folder/settings.yml") != null ||
                        ctx.assets.list("$ASSETS_ROOT/$folder")?.any { it == "settings.yml" } == true
                } catch (_: Exception) { false }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "listFolders failed", e)
            emptyList()
        }
    }

    /** 加载单个内置角色为 ChatItem（不带历史/未读数，由调用方填充） */
    fun load(ctx: Context, folder: String): ChatItem? {
        return try {
            val ymlText = ctx.assets.open("$ASSETS_ROOT/$folder/settings.yml").bufferedReader().use { it.readText() }
            val doc = Yaml().load<Map<String, Any>>(ymlText)
            val name = doc["ai_name"]?.toString() ?: folder
            val subtitle = doc["ai_subtitle"]?.toString() ?: ""
            val title = doc["title"]?.toString() ?: ""
            val info = doc["info"]?.toString() ?: ""
            val systemPrompt = doc["system_prompt"]?.toString() ?: ""
            val thinkingMessage = doc["thinking_message"]?.toString() ?: ""
            val defaultAvatar = findDefaultAvatar(ctx, folder)

            ChatItem(
                id = "builtin_$folder",
                name = name,
                lastMessage = "",
                time = "",
                unreadCount = 0,
                avatarColor = 0xFF07C160.toInt(),
                pinned = false,
                persona = systemPrompt.ifBlank { buildFallbackPersona(name, title, info) },
                avatarUri = defaultAvatar,
                characterFolder = folder,
                thinkingMessage = thinkingMessage
            )
        } catch (e: Exception) {
            Log.w(TAG, "load($folder) failed", e)
            null
        }
    }

    /** 列出全部内置角色（带内存缓存） */
    fun listAll(ctx: Context): List<ChatItem> {
        listAllCache?.let { return it }
        val result = listFolders(ctx).mapNotNull { load(ctx, it) }
        listAllCache = result
        return result
    }

    /** 获取某个角色的情绪头像路径（moodName 为头像文件名，如"高兴"/"伤心"） */
    fun getMoodAvatarUri(folder: String, moodAvatarName: String): String {
        return "$ASSET_PREFIX$ASSETS_ROOT/$folder/avatar/$moodAvatarName.webp"
    }

    /** 判断 uri 是否是 assets 伪协议 */
    fun isAssetUri(uri: String): Boolean = uri.startsWith(ASSET_PREFIX)

    /** 把 asset://xxx 转换为 assets 相对路径 */
    fun assetUriToPath(uri: String): String {
        return if (uri.startsWith(ASSET_PREFIX)) uri.removePrefix(ASSET_PREFIX) else uri
    }

    /**
     * 统一头像加载：支持 asset:// 伪协议和普通文件路径。
     * 返回 Bitmap 或 null（失败时调用方用文字头像兜底）。
     *
     * Task 2.5: 改为 suspend，先查 [avatarCache]；未命中时切到 [Dispatchers.IO]
     * 解码后写入缓存再返回。LruCache 内部已 synchronized，多线程安全。
     */
    suspend fun loadAvatarBitmap(ctx: Context, uri: String): Bitmap? {
        if (uri.isEmpty()) return null
        // 先查内存缓存：命中直接返回，避免重复解码
        avatarCache.get(uri)?.let { return it }
        // 未命中：切 IO 线程解码，避免阻塞主线程
        val bmp = withContext(Dispatchers.IO) {
            try {
                if (isAssetUri(uri)) {
                    val path = assetUriToPath(uri)
                    ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }
                } else {
                    BitmapFactory.decodeFile(uri)
                }
            } catch (_: Exception) { null }
        } ?: return null
        // 写入缓存供后续复用
        avatarCache.put(uri, bmp)
        return bmp
    }

    /** 默认头像：优先 "正常.webp"，其次 "头像.webp"，最后用 "高兴.webp" */
    private fun findDefaultAvatar(ctx: Context, folder: String): String {
        val avatarDir = "$ASSETS_ROOT/$folder/avatar"
        val files = try { ctx.assets.list(avatarDir) } catch (_: Exception) { null } ?: return ""
        val candidate = listOf("正常.webp", "头像.webp", "高兴.webp").firstOrNull { it in files }
            ?: files.firstOrNull { it.endsWith(".webp") || it.endsWith(".png") }
        return if (candidate != null) "$ASSET_PREFIX$avatarDir/$candidate" else ""
    }

    /** settings.yml 缺 system_prompt 时的兜底人设 */
    private fun buildFallbackPersona(name: String, title: String, info: String): String {
        return buildString {
            appendLine("以下是你的人设：")
            if (title.isNotBlank()) appendLine("你叫$name，$title。")
            else appendLine("你叫$name。")
            if (info.isNotBlank()) appendLine(info)
        }
    }
}
