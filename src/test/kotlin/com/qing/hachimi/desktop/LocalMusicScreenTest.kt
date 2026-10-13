package com.qing.hachimi.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.junit.jupiter.api.Test
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlin.test.assertEquals

class LocalMusicScreenTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `local music screen exposes scan controls and filters lazily rendered rows`() = runComposeUiTest {
        val first = testTrack("first", "Jazz Track", "Blue Group", "Blue Album")
        val second = testTrack("second", "Rock Track", "Red Group", "Red Album")
        val bulkTracks = (0 until 1_200).map { index ->
            testTrack("bulk-$index", "Bulk Track $index", "Bulk Artist", "Bulk Album")
        }
        var state by mutableStateOf(
            LocalMusicUiState(
                roots = listOf(LocalMusicRoot("C:\\fixture", 1L)),
                tracks = listOf(first, second) + bulkTracks,
            ),
        )
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                LocalMusicContent(
                    state = state,
                    onAddDirectories = {},
                    onRemoveRoot = {},
                    onScan = {},
                    onCancelScan = {},
                    onAnalyzeDuplicates = {},
                    onCancelAnalysis = {},
                    onPreviewUpgrades = {},
                    onFormatFilter = { state = state.copy(formatFilter = it) },
                    onArtistFilter = { state = state.copy(artistFilter = it) },
                    onAlbumFilter = { state = state.copy(albumFilter = it) },
                    onSearchFilter = { state = state.copy(searchFilter = it) },
                )
            }
        }

        onNodeWithTag("local-music-add-root").assertIsDisplayed()
        onNodeWithTag("local-music-scan").assertIsDisplayed()
        onNodeWithTag("local-music-table").assertIsDisplayed()
        onNodeWithTag("local-music-album-wall").assertIsDisplayed()
        onNodeWithTag("local-music-search").assertIsDisplayed()
        onNodeWithTag("local-music-watch-${"C:\\fixture".hashCode()}").assertIsDisplayed()
        onNodeWithText("Jazz Track").assertIsDisplayed()
        onAllNodesWithTag("local-track-bulk-1199").assertCountEquals(0)
        onNodeWithTag("local-music-artist-filter").performTextInput("Blue")
        onNodeWithText("Jazz Track").assertIsDisplayed()
        onAllNodesWithText("Rock Track").assertCountEquals(0)
        assertEquals(1, state.visibleTracks.size)
        onNodeWithTag("local-music-search").performTextInput("Rock")
        assertEquals(0, state.visibleTracks.size)
        onNodeWithTag("local-album-${first.pathKey.hashCode()}").performClick()
        assertEquals("Blue Album", state.albumFilter)
    }

    private fun testTrack(id: String, title: String, artist: String, album: String) = LocalTrack(
        id = id,
        pathKey = "c:/fixture/$id.mp3",
        filePath = "C:\\fixture\\$id.mp3",
        fileSize = 100L,
        lastModifiedMillis = 1L,
        format = LocalAudioFormat.MP3,
        codec = "MPEG-1 Layer 3",
        lossiness = AudioLossiness.LOSSY,
        durationSeconds = 120.0,
        bitrateKbps = 192,
        sampleRateHz = 44_100,
        bitDepth = null,
        title = title,
        titleSource = LocalTitleSource.TAG,
        artists = listOf(artist),
        album = album,
        albumArtist = artist,
        trackNumber = null,
        discNumber = null,
        metadataStatus = LocalMetadataStatus.READABLE,
    )
}
