package com.aftglw.devapi

import android.content.Context
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * 剧本加载器：从 assets/ 或文件系统加载剧本 + 角色设定。
 */
object ScriptLoader {

    data class ScriptInfo(
        val id: String,
        val name: String,
        val description: String,
        val source: ScriptSource,
        val script: LingChatScript? = null,
        val characterPrompt: String = ""
    )

    data class ScriptSource(
        val type: String,  // "asset" or "file"
        val path: String
    )

    data class CharacterInfo(
        val name: String,
        val prompt: String,
        val userName: String = ""
    )

    /** 从 assets 扫描所有可用剧本 */
    fun loadFromAssets(ctx: Context): List<ScriptInfo> {
        val result = mutableListOf<ScriptInfo>()
        try {
            val scriptsDir = "scripts"
            val entries = ctx.assets.list(scriptsDir) ?: return result

            for (entry in entries) {
                val configPath = "$scriptsDir/$entry/story_config.yaml"
                try {
                    val yamlStr = ctx.assets.open(configPath).bufferedReader().use { it.readText() }
                    val config = Yaml().load<Map<String, Any>>(yamlStr)
                    val name = (config["script_name"] as? String) ?: entry
                    val desc = (config["description"] as? String) ?: ""

                    // 读取角色设定（通过 bound_character_folder）
                    var charPrompt = ""
                    val adventure = config["adventure"] as? Map<String, Any>
                    val charFolder = adventure?.get("bound_character_folder") as? String
                    if (charFolder != null) {
                        val charInfo = loadCharacterFromAssets(ctx, charFolder)
                        if (charInfo != null) {
                            charPrompt = charInfo.prompt
                            ScriptEngine.playerName = charInfo.userName.ifEmpty { "你" }
                        }
                    }

                    result.add(ScriptInfo(
                        id = entry,
                        name = name,
                        description = desc,
                        source = ScriptSource("asset", configPath),
                        characterPrompt = charPrompt
                    ))
                } catch (_: Exception) { /* 跳过无效目录 */ }
            }
        } catch (_: Exception) { }
        return result
    }

    /** 从 assets 加载角色设定 */
    fun loadCharacterFromAssets(ctx: Context, folder: String): CharacterInfo? {
        return try {
            val path = "characters/$folder/settings.yml"
            val yamlStr = ctx.assets.open(path).bufferedReader().use { it.readText() }
            val doc = Yaml().load<Map<String, Any>>(yamlStr)
            CharacterInfo(
                name = (doc["ai_name"] as? String) ?: folder,
                prompt = (doc["system_prompt"] as? String) ?: "",
                userName = (doc["user_name"] as? String) ?: ""
            )
        } catch (_: Exception) { null }
    }

    /** 从 assets 加载完整剧本数据 */
    fun loadScriptFromAssets(ctx: Context, scriptId: String): LingChatScript? {
        return try {
            val basePath = "scripts/$scriptId"
            val configYaml = ctx.assets.open("$basePath/story_config.yaml")
                .bufferedReader().use { it.readText() }
            val config = Yaml().load<Map<String, Any>>(configYaml)
            val intro = (config["intro_chapter"] as? String) ?: "main"

            // 扫描 Chapters/
            val chaptersDir = "$basePath/Chapters"
            val chapterFiles = ctx.assets.list(chaptersDir) ?: return null
            val chapters = mutableMapOf<String, List<ScriptEvent>>()

            for (f in chapterFiles) {
                if (!f.endsWith(".yaml") && !f.endsWith(".yml")) continue
                val yaml = ctx.assets.open("$chaptersDir/$f").bufferedReader().use { it.readText() }
                val doc = Yaml().load<Map<String, Any>>(yaml)
                val eventsRaw = doc?.get("events") as? List<Map<String, Any>> ?: continue

                ScriptEngine::class.java // 初始化
                val events = eventsRaw.mapNotNull { ScriptEngine.parseEventDirect(it) }
                val key = f.removeSuffix(".yaml").removeSuffix(".yml")
                chapters[key] = events
            }

            LingChatScript(
                name = (config["script_name"] as? String) ?: scriptId,
                description = (config["description"] as? String) ?: "",
                introChapter = intro,
                chapters = chapters
            )
        } catch (_: Exception) { null }
    }
}
