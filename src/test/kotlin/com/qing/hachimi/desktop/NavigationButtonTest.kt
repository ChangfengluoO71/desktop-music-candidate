package com.qing.hachimi.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class NavigationButtonTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `expanded and collapsed navigation retain click target`() = runComposeUiTest {
        var clicks = 0
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                androidx.compose.foundation.layout.Column {
                    NavigationButton(false, "发现", "◇", false, Modifier.testTag("expanded")) { clicks++ }
                    NavigationButton(true, "设置", "⚙", true, Modifier.testTag("collapsed")) { clicks++ }
                }
            }
        }
        onNodeWithTag("expanded").assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag("expanded").assertIsFocused().performClick()
        onNodeWithTag("collapsed").assertIsDisplayed().assertContentDescriptionEquals("设置").assertIsSelected().performClick()
        val expanded = onNodeWithTag("expanded").getUnclippedBoundsInRoot()
        val collapsed = onNodeWithTag("collapsed").getUnclippedBoundsInRoot()
        assertEquals(42.dp, expanded.bottom - expanded.top)
        assertEquals(42.dp, collapsed.bottom - collapsed.top)
        assertEquals(2, clicks)
    }
}
