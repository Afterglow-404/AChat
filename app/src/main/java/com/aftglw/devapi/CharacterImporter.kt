package com.aftglw.devapi

import android.content.Context
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.zip.ZipFile

/**
 * LingChat 格式角色导入器。
 * 从 ZIP 文件导入角色（settings.yml + avatar 文件夹）。
 */
object CharacterImporter {

    data class ImportedCharacter(
        val folder: String,       // 文件夹名（角色ID）
        val name: String,         // ai_name
        val subtitle: String,
        val title: String,
        val info: String,
        val systemPrompt: String,
        val hasAvatar: Boolean,
        val avatarCount: Int
    )

    /** 扫描已导入的角色 */
    fun listImported(ctx: Context): List<ImportedCharacter> {
        val dir = File(ctx.filesDir, "characters")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter { it.isDirectory }?.mapNotNull { folder ->
            val settings = File(folder, "settings.yml")
            if (!settings.exists()) return@mapNotNull null
            try {
                val doc = Yaml().load<Map<String, Any>>(settings.readText())
                val avatarDir = File(folder, "avatar")
                ImportedCharacter(
                    folder = folder.name,
                    name = (doc["ai_name"] as? String) ?: folder.name,
                    subtitle = (doc["ai_subtitle"] as? String) ?: "",
                    title = (doc["title"] as? String) ?: "",
                    info = (doc["info"] as? String) ?: "",
                    systemPrompt = (doc["system_prompt"] as? String) ?: "",
                    hasAvatar = avatarDir.exists() && avatarDir.listFiles()?.isNotEmpty() == true,
                    avatarCount = avatarDir.listFiles()?.size ?: 0
                )
            } catch (_: Exception) { null }
        } ?: emptyList()
    }

    /** 从 ZIP 导入角色 */
    fun importFromZip(ctx: Context, zipPath: String): Result<String> = runCatching {
        val zip = ZipFile(zipPath)
        val entries = zip.entries()

        // 找到 settings.yml 所在的顶层目录
        var topFolder = ""
        var settingsEntry = zip.getEntry("settings.yml")
        if (settingsEntry == null) {
            // 可能在子目录中
            val all = zip.entries().asSequence().toList()
            val ymlEntry = all.firstOrNull { it.name.endsWith("settings.yml") && !it.isDirectory }
                ?: throw IllegalArgumentException("ZIP 中未找到 settings.yml")
            val path = ymlEntry.name.replace("\\", "/")
            topFolder = path.substringBefore("/")
            settingsEntry = ymlEntry
        }

        val yamlStr = zip.getInputStream(settingsEntry).bufferedReader().use { it.readText() }
        val doc = Yaml().load<Map<String, Any>>(yamlStr)
        val charName = ((doc["character_folder"] as? String) ?: (doc["ai_name"] as? String))?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("settings.yml 中缺少 character_folder 或 ai_name")

        val targetDir = File(ctx.filesDir, "characters/$charName")
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        // 提取所有文件，保持目录结构
        val prefix = if (topFolder.isNotEmpty()) "$topFolder/" else ""
        for (entry in zip.entries()) {
            if (entry.isDirectory) continue
            val name = entry.name.replace("\\", "/")
            if (!name.startsWith(prefix) && prefix.isNotEmpty()) continue
            val relativeName = if (prefix.isNotEmpty()) name.removePrefix(prefix) else name
            val outFile = File(targetDir, relativeName)
            outFile.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        zip.close()
        charName
    }

    /** 删除已导入的角色 */
    fun deleteImported(ctx: Context, folder: String) {
        val dir = File(ctx.filesDir, "characters/$folder")
        if (dir.exists()) dir.deleteRecursively()
    }

    /** 读取导入角色的 settings.yml */
    fun loadSettings(ctx: Context, folder: String): Map<String, Any>? {
        return try {
            val file = File(ctx.filesDir, "characters/$folder/settings.yml")
            if (!file.exists()) return null
            Yaml().load<Map<String, Any>>(file.readText())
        } catch (_: Exception) { null }
    }
}
