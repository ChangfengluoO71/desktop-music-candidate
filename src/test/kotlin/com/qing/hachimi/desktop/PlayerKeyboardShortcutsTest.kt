package com.qing.hachimi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.v2.runComposeUiTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PlayerKeyboardShortcutsTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `space toggles playback and arrows seek by direction`() = runComposeUiTest {
        val actions = mutableListOf<PlayerShortcutAction>()
        setContent { ShortcutSurface(actions::add) }

        onNodeWithTag("shortcut-root").performKeyInput {
            pressKey(Key.Spacebar)
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionRight)
        }

        assertEquals(
            listOf(PlayerShortcutAction.TOGGLE_PLAYBACK, PlayerShortcutAction.SEEK_BACKWARD, PlayerShortcutAction.SEEK_FORWARD),
            actions,
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `control and arrow changes tracks instead of seeking`() = runComposeUiTest {
        val actions = mutableListOf<PlayerShortcutAction>()
        setContent { ShortcutSurface(actions::add) }

        onNodeWithTag("shortcut-root").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionLeft) }
            withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionRight) }
        }

        assertEquals(listOf(PlayerShortcutAction.PREVIOUS_TRACK, PlayerShortcutAction.NEXT_TRACK), actions)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `text input keeps arrow and space keys without invoking player shortcuts`() = runComposeUiTest {
        val actions = mutableListOf<PlayerShortcutAction>()
        val text = mutableStateOf("")
        setContent {
            Column(Modifier.then(rememberPlayerKeyboardShortcutModifier(actions::add))) {
                BasicTextField(
                    value = text.value,
                    onValueChange = { text.value = it },
                    modifier = Modifier.testTag("search-input"),
                )
            }
        }

        onNodeWithTag("search-input").performClick().performTextInput("search")
        onNodeWithTag("search-input").performKeyInput {
            pressKey(Key.DirectionLeft)
            pressKey(Key.Spacebar)
        }
        onNodeWithTag("search-input").performTextInput(" ")

        assertEquals(emptyList(), actions)
        assertEquals("searc h", text.value, "The left-arrow editing key must reach the focused text input")
    }

    @Composable
    private fun ShortcutSurface(onAction: (PlayerShortcutAction) -> Unit) {
        Box(
            Modifier.testTag("shortcut-root")
                .then(rememberPlayerKeyboardShortcutModifier(onAction)),
        )
    }
}
