package com.aftglw.devapi

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aftglw.devapi.feature.settings.SettingsActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设置页 UI 冒烟测试。
 *
 * 验证 SettingsActivity 的主菜单包含所有预期条目，并能进入关键子页面。
 * 这一层覆盖「首次启动后用户配置 API/工具安全/数据管理」的核心路径。
 *
 * 运行方式：./gradlew connectedAndroidTest（需要 emulator 或真机）。
 */
@RunWith(AndroidJUnit4::class)
class SettingsUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<SettingsActivity>()

    @Test
    fun mainMenu_showsAllExpectedEntries() {
        // 主要分类
        composeRule.onNodeWithText("个人信息").assertIsDisplayed()
        composeRule.onNodeWithText("通知设置").assertIsDisplayed()
        composeRule.onNodeWithText("AI 接口").assertIsDisplayed()
        composeRule.onNodeWithText("工具安全").assertIsDisplayed()
        composeRule.onNodeWithText("数据管理").assertIsDisplayed()
        composeRule.onNodeWithText("情绪模型").assertIsDisplayed()
        composeRule.onNodeWithText("管理角色").assertIsDisplayed()
        composeRule.onNodeWithText("背景设置").assertIsDisplayed()
        composeRule.onNodeWithText("界面设置").assertIsDisplayed()
        composeRule.onNodeWithText("调试").assertIsDisplayed()
        composeRule.onNodeWithText("关于").assertIsDisplayed()
        composeRule.onNodeWithText("MCP 服务").assertIsDisplayed()
        composeRule.onNodeWithText("工具管理").assertIsDisplayed()
    }

    @Test
    fun clickToolSecurity_opensToolSecurityPage() {
        composeRule.onNodeWithText("工具安全").performClick()
        // ToolSecurityPage 顶部的策略说明卡片标题
        composeRule.onNodeWithText("工具调用安全策略").assertIsDisplayed()
    }

    @Test
    fun clickDataManagement_opendsDataManagementPage() {
        composeRule.onNodeWithText("数据管理").performClick()
        // DataManagementPage 显示当前数据标题
        composeRule.onNodeWithText("当前数据").assertIsDisplayed()
    }

    @Test
    fun clickMoodModel_opensMoodModelPage() {
        composeRule.onNodeWithText("情绪模型").performClick()
        // MoodModelPage 显示模型状态标题
        composeRule.onNodeWithText("模型状态").assertIsDisplayed()
    }
}
