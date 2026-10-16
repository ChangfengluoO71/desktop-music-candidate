package com.qing.hachimi.desktop

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

internal const val PLAYER_SEEK_STEP_MILLIS = 5_000L

@Composable
internal fun rememberPlayerKeyboardShortcutModifier(onAction: (PlayerShortcutAction) -> Unit): Modifier {
    val focusRequester = remember { FocusRequester() }
    var rootOwnsFocus by remember { mutableStateOf(false) }
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
    return Modifier
        .focusRequester(focusRequester)
        .onFocusChanged { rootOwnsFocus = it.isFocused }
        .playerKeyboardShortcuts(onAction) { rootOwnsFocus }
        .focusable()
}

@Composable
internal fun rememberImmersiveKeyboardShortcutModifier(
    onAction: (PlayerShortcutAction) -> Unit,
    onExit: () -> Unit,
): Modifier {
    val focusRequester = remember { FocusRequester() }
    var rootOwnsFocus by remember { mutableStateOf(false) }
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
    return Modifier
        .focusRequester(focusRequester)
        .onFocusChanged { rootOwnsFocus = it.isFocused }
        .onKeyEvent { event ->
            if (rootOwnsFocus && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onExit()
                true
            } else {
                false
            }
        }
        .playerKeyboardShortcuts(onAction) { rootOwnsFocus }
        .focusable()
}

internal enum class PlayerShortcutAction {
    TOGGLE_PLAYBACK,
    SEEK_BACKWARD,
    SEEK_FORWARD,
    PREVIOUS_TRACK,
    NEXT_TRACK,
}

/** Handles shortcuts only while the root owns focus; descendants keep their normal key behavior. */
internal fun Modifier.playerKeyboardShortcuts(
    onAction: (PlayerShortcutAction) -> Unit,
    isEnabled: () -> Boolean = { true },
): Modifier =
    onKeyEvent { event ->
        if (!isEnabled()) return@onKeyEvent false
        val action = playerShortcutAction(event) ?: return@onKeyEvent false
        onAction(action)
        true
    }

internal fun playerShortcutAction(event: KeyEvent): PlayerShortcutAction? {
    if (event.type != KeyEventType.KeyDown || event.isAltPressed || event.isMetaPressed || event.isShiftPressed) {
        return null
    }
    return if (event.isCtrlPressed) {
        when (event.key) {
            Key.DirectionLeft -> PlayerShortcutAction.PREVIOUS_TRACK
            Key.DirectionRight -> PlayerShortcutAction.NEXT_TRACK
            else -> null
        }
    } else {
        when (event.key) {
            Key.Spacebar -> PlayerShortcutAction.TOGGLE_PLAYBACK
            Key.DirectionLeft -> PlayerShortcutAction.SEEK_BACKWARD
            Key.DirectionRight -> PlayerShortcutAction.SEEK_FORWARD
            else -> null
        }
    }
}

internal fun PlayerShortcutAction.perform(viewModel: PlayerViewModel) {
    when (this) {
        PlayerShortcutAction.TOGGLE_PLAYBACK -> viewModel.togglePlayback()
        PlayerShortcutAction.SEEK_BACKWARD -> viewModel.seekBy(-PLAYER_SEEK_STEP_MILLIS)
        PlayerShortcutAction.SEEK_FORWARD -> viewModel.seekBy(PLAYER_SEEK_STEP_MILLIS)
        PlayerShortcutAction.PREVIOUS_TRACK -> viewModel.previous()
        PlayerShortcutAction.NEXT_TRACK -> viewModel.next()
    }
}
