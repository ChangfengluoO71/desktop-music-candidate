package com.qing.hachimi.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.junit.jupiter.api.Test
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlin.test.assertTrue

class DesktopQrLoginPanelTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `signed out account panel offers phone QR login without a MUSIC_U field`() = runComposeUiTest {
        var started = false
        var remembered = false
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                QrLoginPanel(
                    ui = DesktopUiState(session = SessionState(SessionStatus.SIGNED_OUT)),
                    onStart = { started = true },
                    onCancel = {},
                    onToggleRemember = { remembered = !remembered },
                    onLogout = {},
                )
            }
        }

        onNodeWithText("手机扫码登录").assertIsDisplayed()
        onAllNodesWithText("MUSIC_U").assertCountEquals(0)
        onNodeWithText("记住此设备：否").performClick()
        assertTrue(remembered)
        onNodeWithText("手机扫码登录").performClick()
        assertTrue(started)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `active QR panel shows phone instructions and refresh controls`() = runComposeUiTest {
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                QrLoginPanel(
                    ui = DesktopUiState(
                        session = SessionState(SessionStatus.SIGNED_OUT),
                        qrLoginStatus = DesktopQrLoginStatus.WAITING,
                        qrCode = QrCodeData(2, listOf(true, false, false, true)),
                    ),
                    onStart = {},
                    onCancel = {},
                    onToggleRemember = {},
                    onLogout = {},
                )
            }
        }

        onNodeWithText("等待手机网易云音乐扫码…").assertIsDisplayed()
        onNodeWithText("刷新二维码").assertIsDisplayed()
        onNodeWithText("取消扫码").assertIsDisplayed()
    }
}
