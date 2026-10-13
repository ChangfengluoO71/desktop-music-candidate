package com.qing.hachimi.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
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
