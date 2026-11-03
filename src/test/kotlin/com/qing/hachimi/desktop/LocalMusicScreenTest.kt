package com.qing.hachimi.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.v2.runComposeUiTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalMusicScreenTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `local search filters results independently of stale browse selection`() = runComposeUiTest {
        val root = Files.createTempDirectory("local-search-test")
        val store = LocalMusicStore(root.resolve("library.db"))
        val registeredRoot = store.registerRoot(root)
        val first = testTrack("first", "Jazz Track", "Blue Group", "Blue Album")
        val second = testTrack("second", "Rock Track", "Red Group", "Red Album")
        store.saveTrack(registeredRoot.path, "fixture", first)
        store.saveTrack(registeredRoot.path, "fixture", second)
        val viewModel = LocalMusicViewModel(store, mainDispatcher = Dispatchers.Unconfined)
        try {
            viewModel.setSearchFilter("stale")
            viewModel.setFormatFilter("FLAC")
            viewModel.setArtistFilter("stale")
            viewModel.setAlbumFilter("stale")
            viewModel.setAlbumTracks(listOf(first))
            viewModel.setFolderFilter(root.resolve("other").toString())
            setContent {
                MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                    LocalLibrarySearchControls(viewModel, playerViewModel = null)
                }
            }
            onNodeWithText("2 / 2 首").assertIsDisplayed()
            onNodeWithTag("local-track-first").assertIsDisplayed()
            onNodeWithTag("local-track-second").assertIsDisplayed()
            onNodeWithTag("local-music-search").performTextInput("rock")
            for (tag in listOf("local-music-format-filter", "local-music-artist-filter", "local-music-album-filter")) {
                onNodeWithTag(tag).performTextInput("no-match")
                onNodeWithText("0 / 2 首").assertIsDisplayed()
                onAllNodesWithTag("local-track-second").assertCountEquals(0)
                onNodeWithTag(tag).performTextClearance()
            }
            onNodeWithTag("local-music-format-filter").performTextInput("mp3")
            onNodeWithTag("local-music-artist-filter").performTextInput("red")
            onNodeWithTag("local-music-album-filter").performTextInput("red")
            onNodeWithText("1 / 2 首").assertIsDisplayed()
            onNodeWithTag("local-track-second").assertIsDisplayed()
            onAllNodesWithTag("local-track-first").assertCountEquals(0)
            onNodeWithTag("local-music-play-filtered").assertIsNotEnabled()
            onNodeWithTag("local-music-queue-filtered").assertIsNotEnabled()
            assertEquals("stale", viewModel.state.value.searchFilter)
        } finally {
            runBlocking { viewModel.shutdown() }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `browse tabs expose album artist song and folder views`() = runComposeUiTest {
        var state by mutableStateOf(LocalMusicUiState(
            roots = listOf(LocalMusicRoot("C:\\fixture", 1L)),
            tracks = listOf(
                testTrack("one", "Song One", "Artist One", "Album One"),
                testTrack("two", "Song Two", "Artist One", "Album Two").copy(
                    pathKey = "c:/fixture/Album/two.mp3", filePath = "C:\\fixture\\Album\\two.mp3",
                ),
            ),
        ))
        var nextTracks: List<LocalTrack>? = null
        var startedTrack: LocalTrack? = null
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                LocalMusicContent(
                    state = state,
                    onAddDirectories = {}, onRemoveRoot = {}, onScan = {}, onCancelScan = {},
                    onAnalyzeDuplicates = {}, onCancelAnalysis = {}, onPreviewUpgrades = {},
                    onFormatFilter = {}, onArtistFilter = {}, onAlbumFilter = {}, onSearchFilter = {},
                    onBrowseMode = { state = state.copy(browseMode = it) },
                    onSortMode = { state = state.copy(sortMode = it) },
                    onFolderFilter = { state = state.copy(folderFilter = it) },
                    onPlayNextTracks = { nextTracks = it },
                    onPlayTrack = { startedTrack = it },
                )
            }
        }
        onNodeWithTag("local-browse-albums").assertIsDisplayed()
        onNodeWithTag("local-browse-artists").assertIsDisplayed()
        onNodeWithTag("local-browse-songs").assertIsDisplayed()
        onNodeWithTag("local-browse-folders").assertIsDisplayed()
        onNodeWithTag("local-album-grid").assertIsDisplayed()
        onNodeWithTag("album-more-${state.tracks.first().pathKey.hashCode()}").performClick()
        onNodeWithTag("album-next-${state.tracks.first().pathKey.hashCode()}").performClick()
        assertEquals(listOf("one"), nextTracks?.map(LocalTrack::id))
        onNodeWithTag("local-browse-artists").performClick()
        onNodeWithTag("local-artist-grid").assertIsDisplayed()
        onNodeWithText("2 张专辑 · 2 首").assertIsDisplayed()
        onNodeWithTag("artist-more-${"Artist One".hashCode()}").performClick()
        onNodeWithTag("artist-next-${"Artist One".hashCode()}").performClick()
        assertEquals(2, nextTracks?.size)
        onNodeWithTag("local-browse-songs").performClick()
        onNodeWithTag("local-music-table").assertIsDisplayed()
        onNodeWithTag("local-track-one").performTouchInput {
            down(center)
            up()
            advanceEventTime(100)
            down(center)
            up()
        }
        assertEquals("one", startedTrack?.id)
        listOf("title", "artist", "album", "format", "duration").forEach { onNodeWithTag("local-sort-$it").assertIsDisplayed() }
        onNodeWithTag("local-sort-artist").performClick()
        assertEquals(LocalMusicSortMode.ARTIST, state.sortMode)
        onNodeWithTag("local-browse-folders").performClick()
        onNodeWithTag("local-folder-list").assertIsDisplayed()
        val nestedFolderKey = normalizedLibraryPath("C:\\fixture\\Album")
        onNodeWithTag("local-folder-${nestedFolderKey.hashCode()}").performClick()
        assertEquals("C:/fixture/Album", state.folderFilter)
        assertEquals(listOf("two"), state.visibleTracks.map(LocalTrack::id))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `selecting a compilation album shows every album track`() = runComposeUiTest {
        val first = testTrack("first", "Opening", "Singer One", "Soundtrack").copy(albumArtist = "Various Artists")
        val second = testTrack("second", "Ending", "Singer Two", "Soundtrack").copy(albumArtist = "Various Artists")
        var state by mutableStateOf(LocalMusicUiState(tracks = listOf(first, second)))
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                LocalMusicContent(
                    state = state,
                    onAddDirectories = {}, onRemoveRoot = {}, onScan = {}, onCancelScan = {},
                    onAnalyzeDuplicates = {}, onCancelAnalysis = {}, onPreviewUpgrades = {},
                    onFormatFilter = {},
                    onArtistFilter = { state = state.copy(artistFilter = it, selectedAlbumTrackKeys = null) },
                    onAlbumFilter = { state = state.copy(albumFilter = it, selectedAlbumTrackKeys = null) },
                    onAlbumSelection = { tracks ->
                        state = state.copy(selectedAlbumTrackKeys = tracks.mapTo(linkedSetOf()) { it.pathKey })
                    },
                    onBrowseMode = { state = state.copy(browseMode = it) },
                )
            }
        }

        onNodeWithTag("local-album-${first.pathKey.hashCode()}").performClick()

        assertEquals(setOf("first", "second"), state.visibleTracks.map(LocalTrack::id).toSet())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `local music browse keeps playback and lazy rows without search or scan controls`() = runComposeUiTest {
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
        var playedTrack: LocalTrack? = null
        var startedTracks: List<LocalTrack>? = null
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
                    onBrowseMode = { state = state.copy(browseMode = it) },
                    onSortMode = { state = state.copy(sortMode = it) },
                    onFolderFilter = { state = state.copy(folderFilter = it) },
                    onPlayTrack = { playedTrack = it },
                    onStartTracks = { startedTracks = it },
                )
            }
        }

        onAllNodesWithTag("local-music-add-root").assertCountEquals(0)
        onAllNodesWithTag("local-music-scan").assertCountEquals(0)
        onAllNodesWithTag("local-music-search").assertCountEquals(0)
        onNodeWithTag("local-music-shuffle").performClick()
        assertEquals(state.tracks.map(LocalTrack::id).toSet(), startedTracks?.map(LocalTrack::id)?.toSet())
        assertEquals(state.tracks.size, startedTracks?.size)
        onNodeWithTag("local-album-grid").assertIsDisplayed()
        onNodeWithTag("local-browse-songs").performClick()
        onNodeWithTag("local-music-table").assertIsDisplayed()
        onAllNodesWithTag("local-track-bulk-1199").assertCountEquals(0)
        onNodeWithTag("local-browse-albums").performClick()
        onNodeWithTag("local-album-${first.pathKey.hashCode()}").performClick()
        assertEquals("Blue Album", state.albumFilter)
        onNodeWithText("Jazz Track").assertIsDisplayed()
        onNodeWithTag("local-track-play-first").performClick()
        assertEquals("first", playedTrack?.id)
        onAllNodesWithText("Rock Track").assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `library scan controls fit within 400dp without overlap`(@TempDir root: Path) = runComposeUiTest {
        val viewModel = LocalMusicViewModel(
            LocalMusicStore(root.resolve("library.db")),
            mainDispatcher = Dispatchers.Unconfined,
        )
        try {
            setContent {
                MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                    Box(Modifier.width(400.dp).testTag("local-scan-controls-parent")) {
                        LocalLibraryScanControls(viewModel)
                    }
                }
            }

            assertControlsFitWithinParent(
                onNodeWithTag("local-scan-controls-parent"),
                listOf(
                    onNodeWithTag("local-music-add-root"),
                    onNodeWithTag("local-music-scan"),
                    onNodeWithTag("local-music-hash-duplicates"),
                    onNodeWithTag("local-music-upgrade-preview"),
                ),
            )
        } finally {
            runBlocking { viewModel.shutdown() }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `library search controls fit within 400dp without overlap`(@TempDir root: Path) = runComposeUiTest {
        val viewModel = LocalMusicViewModel(
            LocalMusicStore(root.resolve("library.db")),
            mainDispatcher = Dispatchers.Unconfined,
        )
        try {
            setContent {
                MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                    Box(Modifier.width(400.dp).testTag("local-search-controls-parent")) {
                        LocalLibrarySearchControls(viewModel, playerViewModel = null)
                    }
                }
            }

            assertControlsFitWithinParent(
                onNodeWithTag("local-search-controls-parent"),
                listOf(
                    onNodeWithTag("local-music-search"),
                    onNodeWithTag("local-music-format-filter"),
                    onNodeWithTag("local-music-artist-filter"),
                    onNodeWithTag("local-music-album-filter"),
                    onNodeWithTag("local-music-play-filtered"),
                    onNodeWithTag("local-music-queue-filtered"),
                ),
            )
        } finally {
            runBlocking { viewModel.shutdown() }
        }
    }

    private fun assertControlsFitWithinParent(
        parent: SemanticsNodeInteraction,
        controls: List<SemanticsNodeInteraction>,
    ) {
        val parentBounds = parent.fetchSemanticsNode().boundsInRoot
        val controlBounds = controls.map { it.fetchSemanticsNode().boundsInRoot }

        controlBounds.forEachIndexed { index, bounds ->
            assertTrue(
                bounds.left >= parentBounds.left && bounds.top >= parentBounds.top &&
                    bounds.right <= parentBounds.right && bounds.bottom <= parentBounds.bottom,
                "Control $index ($bounds) is outside parent bounds $parentBounds",
            )
        }

        controlBounds.forEachIndexed { index, bounds ->
            controlBounds.drop(index + 1).forEach { other ->
                val overlaps = bounds.left < other.right && other.left < bounds.right &&
                    bounds.top < other.bottom && other.top < bounds.bottom
                assertTrue(!overlaps, "Controls overlap: $bounds and $other")
            }
        }
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
