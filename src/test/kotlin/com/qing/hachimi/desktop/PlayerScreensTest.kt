package com.qing.hachimi.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Test
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlin.test.assertEquals

class PlayerScreensTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `mini player exposes track controls and forwards user actions`() = runComposeUiTest {
        val track = testTrack()
        val state = PlayerUiState(
            playback = PlaybackState(
                queue = listOf(track),
                queueIndex = 0,
                currentTrack = track,
                durationMillis = 60_000,
                volume = 0.8f,
            ),
        )
        var toggles = 0
        var opens = 0
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                PlayerMiniBarContent(
                    state = state,
                    onOpenNowPlaying = { opens++ },
                    onPrevious = {},
                    onTogglePlayback = { toggles++ },
                    onNext = {},
                    onSeek = {},
                )
            }
        }

        onNodeWithTag("player-mini-bar").assertIsDisplayed()
        onNodeWithText("A Local Song").assertIsDisplayed()
        onNodeWithTag("player-toggle").assertIsEnabled().performClick()
        onNodeWithTag("player-open-now-playing").performClick()
        assertEquals(1, toggles)
        assertEquals(1, opens)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `queue controls request an adjacent move for the selected item`() = runComposeUiTest {
        val first = testTrack()
        val second = testTrack().copy(id = "second", pathKey = "c:/music/second.mp3", title = "Second")
        val third = testTrack().copy(id = "third", pathKey = "c:/music/third.mp3", title = "Third")
        var requestedMove: Pair<Int, Int>? = null
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                PlayerQueueContent(
                    playback = PlaybackState(queue = listOf(first, second, third), queueIndex = 0, currentTrack = first),
                    onPlayIndex = {},
                    onMoveQueueItem = { from, to -> requestedMove = from to to },
                    onRemove = {},
                    onClear = {},
                )
            }
        }

        onNodeWithTag("queue-move-up-second").assertIsDisplayed().performClick()
        assertEquals(1 to 0, requestedMove)
        onNodeWithTag("queue-move-down-second").assertIsDisplayed().performClick()
        assertEquals(1 to 2, requestedMove)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `queue move buttons are disabled at their respective ends`() = runComposeUiTest {
        val first = testTrack()
        val second = testTrack().copy(id = "second", pathKey = "c:/music/second.mp3", title = "Second")
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                PlayerQueueContent(
                    playback = PlaybackState(queue = listOf(first, second), queueIndex = 0, currentTrack = first),
                    onPlayIndex = {},
                    onMoveQueueItem = { _, _ -> },
                    onRemove = {},
                    onClear = {},
                )
            }
        }

        onNodeWithTag("queue-move-up-${first.id}").assertIsNotEnabled()
        onNodeWithTag("queue-move-down-${second.id}").assertIsNotEnabled()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `long pressing and dragging a queue handle requests reorder`() = runComposeUiTest {
        val first = testTrack()
        val second = testTrack().copy(id = "second", pathKey = "c:/music/second.mp3", title = "Second")
        val third = testTrack().copy(id = "third", pathKey = "c:/music/third.mp3", title = "Third")
        var requestedMove: Pair<Int, Int>? = null
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                PlayerQueueContent(
                    playback = PlaybackState(queue = listOf(first, second, third), queueIndex = 0, currentTrack = first),
                    onPlayIndex = {},
                    onMoveQueueItem = { from, to -> requestedMove = from to to },
                    onRemove = {},
                    onClear = {},
                )
            }
        }

        onNodeWithTag("queue-drag-handle-${second.id}").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis)
            moveBy(Offset(0f, 110f))
            up()
        }

        assertEquals(1 to 2, requestedMove)
    }

    private fun testTrack() = LocalTrack(
        id = "player-test-track",
        pathKey = "c:/music/player-test-track.mp3",
        filePath = "C:\\music\\player-test-track.mp3",
        fileSize = 1024,
        lastModifiedMillis = 1,
        format = LocalAudioFormat.MP3,
        codec = "MP3",
        lossiness = AudioLossiness.LOSSY,
        durationSeconds = 60.0,
        bitrateKbps = 192,
        sampleRateHz = 44_100,
        bitDepth = null,
        title = "A Local Song",
        titleSource = LocalTitleSource.TAG,
        artists = listOf("DMC Artist"),
        album = "Local Album",
        albumArtist = "DMC Artist",
        trackNumber = 1,
        discNumber = null,
        metadataStatus = LocalMetadataStatus.READABLE,
    )
}
