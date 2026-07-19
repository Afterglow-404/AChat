package com.aftglw.devapi

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 首次启动流程 UI 测试。
 *
 * 覆盖路径：
 * 1. 启动 → 显示 Dev 阶段警告对话框（5s 倒计时）
 * 2. 等待倒计时结束 → 点击「哦好的」
 * 3. 显示 onboarding 对话框「欢迎来到 Wisp」
 * 4. 点击「自由探索」→ 进入主界面
 *
 * 运行方式：./gradlew connectedAndroidTest（需要 emulator 或真机）。
 */
@RunWith(AndroidJUnit4::class)
class FirstLaunchUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstLaunch_showsWarningDialog_thenOnboarding_thenMainApp() {
        // 1. 警告对话框显示
        composeRule.onNodeWithText("⚠️ 注意").assertIsDisplayed()

        // 2. 等待 5 秒倒计时结束，按钮文本变为「哦好的」
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("哦好的").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("哦好的").performClick()

        // 3. onboarding 对话框显示
        composeRule.onNodeWithText("欢迎来到 Wisp").assertIsDisplayed()

        // 4. 点击「自由探索」进入主界面
        composeRule.onNodeWithText("自由探索").performClick()
    }
}
