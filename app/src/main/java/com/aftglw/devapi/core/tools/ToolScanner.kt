package com.aftglw.devapi.core.tools

import android.content.Context
import android.content.SharedPreferences
import com.aftglw.devapi.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 工具扫描器 — 扫描 tools/ 目录加载 .wsptool 描述文件。
 *
 * 支持两个来源：
 * 1. assets/tools/ — 内置工具包（随 App 发布）
 * 2. filesDir/tools/ — 用户安装的工具包（通过市场/导入）
 */
object ToolScanner {

    private var scanned = false

    /** 已扫描到的工具描述 */
    val descriptors: MutableList<ToolDescriptor> = mutableListOf()

    /** 扫描并注册所有动态工具 */
    fun scan(ctx: Context) {
        if (scanned) return
        descriptors.clear()

        // 1. 从 assets 加载内置工具
        loadFromAssets(ctx)

        // 2. 从 filesDir 加载用户工具
        loadFromFiles(ctx)

        // 3. 注册所有工具
        for (desc in descriptors) {
            val envVars = loadEnvVars(ctx)
            for (toolDef in desc.tools) {
                if (toolDef.advice) continue // 提示工具不注册为可执行
                val scriptTool = ScriptTool(desc, toolDef, envVars)
                ToolRegistry.register(scriptTool)
            }
        }

        scanned = true
    }

    /** 安装一个新的工具描述文件（从市场下载或用户导入） */
    fun install(ctx: Context, jsonContent: String): Result<String> {
        return try {
            val json = JSONObject(jsonContent)
            val desc = ToolDescriptor.fromJson(json)
            val toolsDir = getToolsDir(ctx)
            toolsDir.mkdirs()
            val file = File(toolsDir, "${desc.name}.wsptool")
            file.writeText(jsonContent)
            // 如果已扫描过，重新加载
            if (scanned) {
                // 移除旧的工具
                // 重新注册
                val envVars = loadEnvVars(ctx)
                for (toolDef in desc.tools) {
                    if (toolDef.advice) continue
                    ToolRegistry.register(ScriptTool(desc, toolDef, envVars))
                }
            }
            descriptors.add(desc)
            Result.success("已安装工具包: ${desc.name}")
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("安装失败: ${e.message}"))
        }
    }

    /** 卸载工具包 */
    fun uninstall(ctx: Context, packageName: String): Boolean {
        val toolsDir = getToolsDir(ctx)
        val file = File(toolsDir, "$packageName.wsptool")
        return if (file.exists()) {
            file.delete()
            descriptors.removeAll { it.name == packageName }
            true
        } else false
    }

    /** 获取所有已安装的工具包名称 */
    fun getInstalledPackages(ctx: Context): List<String> {
        val toolsDir = getToolsDir(ctx)
        if (!toolsDir.exists()) return emptyList()
        return toolsDir.listFiles()
            ?.filter { it.extension == "wsptool" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    private fun loadFromAssets(ctx: Context) {
        try {
            val assetFiles = ctx.assets.list("tools") ?: return
            for (fileName in assetFiles.filter { it.endsWith(".wsptool") }) {
                try {
                    val json = ctx.assets.open(fileName).bufferedReader().use { it.readText() }
                    val desc = ToolDescriptor.fromJson(JSONObject(json))
                    descriptors.add(desc)
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    private fun loadFromFiles(ctx: Context) {
        val toolsDir = getToolsDir(ctx)
        if (!toolsDir.exists()) return
        val files = toolsDir.listFiles { f -> f.extension == "wsptool" } ?: return
        for (file in files) {
            try {
                val json = file.readText()
                val desc = ToolDescriptor.fromJson(JSONObject(json))
                descriptors.add(desc)
            } catch (_: Exception) { }
        }
    }

    private fun loadEnvVars(ctx: Context): Map<String, String> {
        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val raw = prefs.getString("tool_env_vars", "{}") ?: "{}"
        val json = JSONObject(raw)
        return json.keys().asSequence().associateWith { key -> json.optString(key, "") }
    }

    private fun getToolsDir(ctx: Context): File {
        return File(ctx.filesDir, "tools")
    }
}
