package com.aftglw.devapi.core.character

import android.content.Context
import android.util.Log
import com.aftglw.devapi.model.ChatItem
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

    /** 列出全部内置角色 */
    fun listAll(ctx: Context): List<ChatItem> {
        return listFolders(ctx).mapNotNull { load(ctx, it) }
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
     */
    fun loadAvatarBitmap(ctx: Context, uri: String): android.graphics.Bitmap? {
        if (uri.isEmpty()) return null
        return try {
            if (isAssetUri(uri)) {
                val path = assetUriToPath(uri)
                ctx.assets.open(path).use { android.graphics.BitmapFactory.decodeStream(it) }
            } else {
                android.graphics.BitmapFactory.decodeFile(uri)
            }
        } catch (_: Exception) { null }
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
