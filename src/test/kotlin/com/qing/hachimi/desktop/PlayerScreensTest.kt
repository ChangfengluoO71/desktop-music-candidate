package com.qing.hachimi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerScreensTest {
    @TempDir
    lateinit var tempDir: Path

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `lyric rows seek by timestamp and manual scrolling pauses follow`() = runComposeUiTest {
        val lines = (0 until 24).map { index -> TimedLyricLine(index * 1_000L, "line $index") }
        var requestedSeek: Long? = null
        val activeIndex = mutableIntStateOf(0)
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                PlayerLyricsContent(
                    lines = lines,
                    activeIndex = activeIndex.intValue,
                    trackKey = "test-track",
                    variant = LyricVariant.ORIGINAL,
                    onSeek = { requestedSeek = it },
                )
            }
        }

        runOnIdle { activeIndex.intValue = 12 }
        waitForIdle()
        onAllNodesWithTag("lyric-follow-resume").assertCountEquals(0)
        onNodeWithTag("now-playing-lyrics").performScrollToNode(hasTestTag("lyric-line-5"))
        onNodeWithTag("lyric-line-5").performClick()
        assertEquals(5_000L, requestedSeek)
        onNodeWithTag("now-playing-lyrics").performTouchInput { swipeUp() }
        onNodeWithTag("lyric-follow-resume").assertIsDisplayed().performClick()
        onAllNodesWithTag("lyric-follow-resume").assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `now playing content stays in app and exposes lyrics and queue panels`() = runComposeUiTest {
        val localMusic = LocalMusicViewModel(
            LocalMusicStore(tempDir.resolve("library.db")),
            mainDispatcher = Dispatchers.Default,
        )
        val viewModel = PlayerViewModel(
            PlaybackCoordinator(UnavailablePlaybackEngine("synthetic missing player"), Dispatchers.Default),
            localMusic,
            PlaybackQueueStore(tempDir.resolve("playback-queue.json")),
            ioDispatcher = Dispatchers.IO,
            mainDispatcher = Dispatchers.Default,
        )
        var immersiveEntries = 0
        try {
            setContent {
                MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                    NowPlayingScreen(viewModel, onOpenImmersive = { immersiveEntries++ })
                }
            }

            onNodeWithTag("now-playing-screen").assertIsDisplayed()
            onNodeWithTag("now-playing-lyrics-panel").assertIsDisplayed()
            onNodeWithTag("now-playing-open-immersive").performClick()
            assertEquals(1, immersiveEntries)
            onNodeWithTag("now-playing-toggle-queue").performClick()
            onNodeWithTag("player-queue-empty").assertIsDisplayed()
        } finally {
            runBlocking {
                viewModel.close()
                localMusic.shutdown()
            }
        }
    }

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
        var immersiveEntries = 0
        var queueEntries = 0
        var shuffle: Boolean? = null
        var repeat: PlaybackRepeatMode? = null
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                PlayerMiniBarContent(
                    state = state,
                    onOpenNowPlaying = { opens++ },
                    onPrevious = {},
                    onTogglePlayback = { toggles++ },
                    onNext = {},
                    onSeek = {},
                    onOpenImmersive = { immersiveEntries++ },
                    onOpenQueue = { queueEntries++ },
                    onSetShuffle = { shuffle = it },
                    onSetRepeatMode = { repeat = it },
                )
            }
        }

        onNodeWithTag("player-mini-bar").assertIsDisplayed()
        onNodeWithText("A Local Song").assertIsDisplayed().performClick()
        assertEquals(1, opens)
        onNodeWithTag("player-toggle").assertIsEnabled().performClick()
        onNodeWithTag("player-open-immersive").performClick()
        onNodeWithTag("player-open-queue").performClick()
        onNodeWithText("随机：关").performClick()
        onNodeWithText("循环：关").performClick()
        assertEquals(1, queueEntries)
        assertEquals(true, shuffle)
        assertEquals(PlaybackRepeatMode.ALL, repeat)
        assertEquals(1, toggles)
        assertEquals(1, opens)
        assertEquals(1, immersiveEntries)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `mini player controls remain visible at narrow widths`() = runComposeUiTest {
        var queueEntries = 0
        var immersiveEntries = 0
        var seeks = 0
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                Box(Modifier.width(400.dp)) {
                    PlayerMiniBarContent(
                        state = PlayerUiState(),
                        onOpenNowPlaying = {}, onPrevious = {}, onTogglePlayback = {},
                        onNext = {}, onSeek = { seeks++ },
                        onOpenImmersive = { immersiveEntries++ }, onOpenQueue = { queueEntries++ },
                    )
                }
            }
        }
        listOf("player-previous", "player-toggle", "player-next", "player-open-queue", "player-open-immersive", "player-seek", "player-volume")
            .forEach { onNodeWithTag(it).assertIsDisplayed() }
        val bar = onNodeWithTag("player-mini-bar").fetchSemanticsNode().boundsInRoot
        val controls = listOf("player-previous", "player-toggle", "player-next", "player-open-queue", "player-open-immersive", "player-seek", "player-volume")
            .map { onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        controls.forEachIndexed { index, bounds ->
            assertTrue(bounds.left >= bar.left && bounds.top >= bar.top && bounds.right <= bar.right && bounds.bottom <= bar.bottom, "Control $index is outside the player: $bounds")
            controls.drop(index + 1).forEach { other ->
                assertTrue(!(bounds.left < other.right && other.left < bounds.right && bounds.top < other.bottom && other.top < bounds.bottom), "Player controls overlap: $bounds and $other")
            }
        }
        onNodeWithTag("player-seek").assertIsNotEnabled()
        onAllNodesWithText("--:--").assertCountEquals(2)
        onNodeWithTag("player-open-queue").performClick()
        onNodeWithTag("player-open-immersive").performClick()
        assertEquals(1, queueEntries)
        assertEquals(1, immersiveEntries)
        assertEquals(0, seeks)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `mini player stays content sized in a tall window`() = runComposeUiTest {
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                Box(Modifier.requiredSize(900.dp, 1_200.dp)) {
                    PlayerMiniBarContent(
                        state = PlayerUiState(),
                        onOpenNowPlaying = {}, onPrevious = {}, onTogglePlayback = {},
                        onNext = {}, onSeek = {},
                    )
                }
            }
        }

        val barHeight = onNodeWithTag("player-mini-bar").fetchSemanticsNode().size.height
        assertTrue(barHeight < 240)
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
    fun `immersive player presents artwork fallback, aligned lyric layers, controls and layout action`() = runComposeUiTest {
        val track = testTrack()
        val state = PlayerUiState(
            playback = PlaybackState(
                queue = listOf(track),
                queueIndex = 0,
                currentTrack = track,
                durationMillis = 10_000,
                volume = 0.7f,
            ),
            lyrics = LocalLyricsBundle(
                original = listOf(TimedLyricLine(2_000, "Original phrase")),
                translation = listOf(TimedLyricLine(2_100, "Translated phrase")),
                romanization = listOf(TimedLyricLine(1_900, "Romanized phrase")),
            ),
        )
        var requestedSeek: Long? = null
        var toggles = 0
        var exits = 0
        var selectedLayout: ImmersivePlayerLayout? = null
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                ImmersivePlayerContent(
                    state = state,
                    artworkRepository = null,
                    layout = ImmersivePlayerLayout.COVER_AND_LYRICS,
                    reducedMotion = true,
                    onExit = { exits++ },
                    onLayoutChange = { selectedLayout = it },
                    onPrevious = {},
                    onTogglePlayback = { toggles++ },
                    onNext = {},
                    onSeek = { requestedSeek = it },
                    onSetShuffle = {},
                    onSetRepeatMode = {},
                    onSetVolume = {},
                    onPlayQueueIndex = {},
                    onMoveQueueItem = { _, _ -> },
                    onRemoveQueueItem = {},
                    onClearQueue = {},
                    onSelectLyricVariant = {},
                )
            }
        }

        onNodeWithTag("immersive-player").assertIsDisplayed()
        onNodeWithTag("immersive-artwork-fallback").assertIsDisplayed()
        onNodeWithText("Original phrase").assertIsDisplayed()
        onNodeWithText("Translated phrase").assertIsDisplayed()
        onNodeWithText("Romanized phrase").assertIsDisplayed()
        onNodeWithTag("immersive-lyric-0").performClick()
        onNodeWithTag("immersive-play-pause").performClick()
        onNodeWithTag("immersive-layout-lyrics-first").performClick()
        onNodeWithTag("immersive-exit").performClick()
        assertEquals(2_000L, requestedSeek)
        assertEquals(1, toggles)
        assertEquals(1, exits)
        assertEquals(ImmersivePlayerLayout.LYRICS_FIRST, selectedLayout)
    }


    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `immersive controls hide after three seconds on the test clock`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                ImmersivePlayerContent(
                    state = PlayerUiState(), artworkRepository = null,
                    layout = ImmersivePlayerLayout.COVER_AND_LYRICS, reducedMotion = true,
                    onExit = {}, onLayoutChange = {}, onPrevious = {}, onTogglePlayback = {},
                    onNext = {}, onSeek = {}, onSetShuffle = {}, onSetRepeatMode = {},
                    onSetVolume = {}, onPlayQueueIndex = {}, onMoveQueueItem = { _, _ -> },
                    onRemoveQueueItem = {}, onClearQueue = {}, onSelectLyricVariant = {},
                )
            }
        }

        onNodeWithTag("immersive-exit").assertIsDisplayed()
        mainClock.advanceTimeBy(2_800)
        waitForIdle()
        onNodeWithTag("immersive-exit").assertIsDisplayed()
        mainClock.advanceTimeBy(240)
        waitForIdle()
        onNodeWithTag("immersive-exit").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `immersive controls reappear for pointer and keyboard activity`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                ImmersivePlayerContent(
                    state = PlayerUiState(), artworkRepository = null,
                    layout = ImmersivePlayerLayout.COVER_AND_LYRICS, reducedMotion = true,
                    onExit = {}, onLayoutChange = {}, onPrevious = {}, onTogglePlayback = {},
                    onNext = {}, onSeek = {}, onSetShuffle = {}, onSetRepeatMode = {},
                    onSetVolume = {}, onPlayQueueIndex = {}, onMoveQueueItem = { _, _ -> },
                    onRemoveQueueItem = {}, onClearQueue = {}, onSelectLyricVariant = {},
                    onKeyboardAction = {},
                )
            }
        }

        mainClock.advanceTimeBy(3_000)
        waitForIdle()
        onNodeWithTag("immersive-exit").assertDoesNotExist()

        onNodeWithTag("immersive-player").performTouchInput { down(center); up() }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onNodeWithTag("immersive-exit").assertIsDisplayed()

        mainClock.advanceTimeBy(3_000)
        waitForIdle()
        onNodeWithTag("immersive-exit").assertDoesNotExist()
        onNodeWithTag("immersive-player").performKeyInput { pressKey(Key.DirectionRight) }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onNodeWithTag("immersive-exit").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `wide lyrics first layout expands lyrics and reduces artwork`() = runComposeUiTest {
        val track = testTrack()
        val state = PlayerUiState(
            playback = PlaybackState(queue = listOf(track), queueIndex = 0, currentTrack = track),
            lyrics = LocalLyricsBundle(original = listOf(TimedLyricLine(0, "Wide layout lyric"))),
        )
        val layout = mutableStateOf(ImmersivePlayerLayout.COVER_AND_LYRICS)
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                Box(Modifier.requiredSize(900.dp, 700.dp)) {
                    ImmersivePlayerContent(
                        state = state, artworkRepository = null, layout = layout.value, reducedMotion = true,
                        onExit = {}, onLayoutChange = { layout.value = it },
                        onPrevious = {}, onTogglePlayback = {}, onNext = {}, onSeek = {},
                        onSetShuffle = {}, onSetRepeatMode = {}, onSetVolume = {},
                        onPlayQueueIndex = {}, onMoveQueueItem = { _, _ -> },
                        onRemoveQueueItem = {}, onClearQueue = {}, onSelectLyricVariant = {},
                    )
                }
            }
        }

        onNodeWithTag("immersive-lyrics").assertIsDisplayed()
        onNodeWithTag("immersive-artwork-fallback").assertIsDisplayed()
        val coverLyricsWidth = onNodeWithTag("immersive-lyrics").fetchSemanticsNode().size.width
        val coverArtworkWidth = onNodeWithTag("immersive-artwork-fallback").fetchSemanticsNode().size.width

        onNodeWithTag("immersive-layout-lyrics-first").performClick()

        onNodeWithTag("immersive-lyrics").assertIsDisplayed()
        onNodeWithText("Wide layout lyric").assertIsDisplayed()
        onNodeWithTag("immersive-artwork-fallback").assertIsDisplayed()
        val lyricsFirstWidth = onNodeWithTag("immersive-lyrics").fetchSemanticsNode().size.width
        val lyricsFirstArtworkWidth = onNodeWithTag("immersive-artwork-fallback").fetchSemanticsNode().size.width
        assertTrue(lyricsFirstWidth > coverLyricsWidth, "Lyrics pane should expand in lyrics-first mode")
        assertTrue(lyricsFirstArtworkWidth < coverArtworkWidth, "Artwork should shrink in lyrics-first mode")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `compact lyrics first layout expands lyrics and reduces artwork`() = runComposeUiTest {
        val track = testTrack()
        val state = PlayerUiState(
            playback = PlaybackState(queue = listOf(track), queueIndex = 0, currentTrack = track),
            lyrics = LocalLyricsBundle(original = listOf(TimedLyricLine(0, "Compact layout lyric"))),
        )
        val layout = mutableStateOf(ImmersivePlayerLayout.COVER_AND_LYRICS)
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                Box(Modifier.requiredSize(700.dp, 700.dp)) {
                    ImmersivePlayerContent(
                        state = state, artworkRepository = null, layout = layout.value, reducedMotion = true,
                        onExit = {}, onLayoutChange = { layout.value = it },
                        onPrevious = {}, onTogglePlayback = {}, onNext = {}, onSeek = {},
                        onSetShuffle = {}, onSetRepeatMode = {}, onSetVolume = {},
                        onPlayQueueIndex = {}, onMoveQueueItem = { _, _ -> },
                        onRemoveQueueItem = {}, onClearQueue = {}, onSelectLyricVariant = {},
                    )
                }
            }
        }

        val coverLyricsHeight = onNodeWithTag("immersive-lyrics").fetchSemanticsNode().size.height
        val coverArtworkWidth = onNodeWithTag("immersive-artwork-fallback").fetchSemanticsNode().size.width
        onNodeWithTag("immersive-layout-lyrics-first").performClick()

        onNodeWithText("Compact layout lyric").assertIsDisplayed()
        onNodeWithTag("immersive-play-pause").assertIsDisplayed()
        val lyricsFirstHeight = onNodeWithTag("immersive-lyrics").fetchSemanticsNode().size.height
        val lyricsFirstArtworkWidth = onNodeWithTag("immersive-artwork-fallback").fetchSemanticsNode().size.width
        assertTrue(lyricsFirstHeight > coverLyricsHeight, "Lyrics pane should expand in compact lyrics-first mode")
        assertTrue(lyricsFirstArtworkWidth < coverArtworkWidth, "Artwork should shrink in compact lyrics-first mode")

        onNodeWithTag("immersive-queue-toggle").performClick()
        onNodeWithTag("immersive-queue-toggle").performClick()
        onNodeWithTag("immersive-lyrics").assertIsDisplayed()
        onNodeWithTag("immersive-layout-cover-lyrics").performClick()
        assertEquals(coverArtworkWidth, onNodeWithTag("immersive-artwork-fallback").fetchSemanticsNode().size.width)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `immersive queue panel opens and closes without leaving the player`() = runComposeUiTest {
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                ImmersivePlayerContent(
                    state = PlayerUiState(),
                    artworkRepository = null,
                    layout = ImmersivePlayerLayout.LYRICS_FIRST,
                    reducedMotion = true,
                    onExit = {},
                    onLayoutChange = {},
                    onPrevious = {},
                    onTogglePlayback = {},
                    onNext = {},
                    onSeek = {},
                    onSetShuffle = {},
                    onSetRepeatMode = {},
                    onSetVolume = {},
                    onPlayQueueIndex = {},
                    onMoveQueueItem = { _, _ -> },
                    onRemoveQueueItem = {},
                    onClearQueue = {},
                    onSelectLyricVariant = {},
                )
            }
        }

        onNodeWithTag("immersive-queue-toggle").performClick()
        onNodeWithTag("player-queue-empty").assertIsDisplayed()
        onNodeWithTag("immersive-queue-toggle").performClick()
        onNodeWithTag("immersive-lyrics").assertIsDisplayed()
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
