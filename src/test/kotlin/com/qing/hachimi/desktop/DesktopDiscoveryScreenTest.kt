package com.qing.hachimi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopDiscoveryScreenTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `application routes local music navigation to supplied library view model`() = runComposeUiTest {
        val harness = createHarness()
        val localRoot = Files.createTempDirectory("dmc-local-library")
        val localMusicViewModel = LocalMusicViewModel(
            LocalMusicStore(localRoot.resolve("library.db")),
            mainDispatcher = Dispatchers.Default,
        )
        try {
            setContent { HachimiDesktopApp(harness.viewModel, localMusicViewModel) }

            onNodeWithTag("nav-local-music").performClick()

            onNodeWithTag("local-album-grid").assertIsDisplayed()
            onNodeWithText("设置").performClick()
            waitUntil(conditionDescription = "settings initialized") { harness.viewModel.ui.value.actionMessage != null }
            onNodeWithTag("settings-section-本地音乐").performClick()
            onNodeWithTag("local-music-status").assertIsDisplayed()
        } finally {
            localMusicViewModel.shutdown()
            harness.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `player state and queue survive shell page navigation`() = runComposeUiTest {
        val harness = createHarness()
        val root = Files.createTempDirectory("dmc-player-navigation")
        val audio = root.resolve("navigation-track.mp3")
        Files.write(audio, byteArrayOf(1, 2, 3))
        val track = LocalTrack(
            id = "navigation-track.mp3",
            pathKey = audio.toString().lowercase(),
            filePath = audio.toString(),
            fileSize = 3,
            lastModifiedMillis = 1,
            format = LocalAudioFormat.MP3,
            codec = "MP3",
            lossiness = AudioLossiness.LOSSY,
            durationSeconds = 10.0,
            bitrateKbps = 128,
            sampleRateHz = 44_100,
            bitDepth = null,
            title = "Navigation Track",
            titleSource = LocalTitleSource.TAG,
            artists = listOf("Test Artist"),
            album = "Test Album",
            albumArtist = "Test Artist",
            trackNumber = null,
            discNumber = null,
            metadataStatus = LocalMetadataStatus.READABLE,
        )
        val localStore = LocalMusicStore(root.resolve("library.db"))
        val libraryRoot = localStore.registerRoot(root)
        localStore.saveTrack(libraryRoot.path, "navigation-test", track)
        val local = LocalMusicViewModel(localStore, mainDispatcher = Dispatchers.Default)
        val engine = FakePlaybackEngine()
        val player = PlayerViewModel(
            PlaybackCoordinator(engine, Dispatchers.Default),
            local,
            PlaybackQueueStore(root.resolve("queue.json")),
            ioDispatcher = Dispatchers.Default,
            mainDispatcher = Dispatchers.Default,
        )
        try {
            setContent { HachimiDesktopApp(harness.viewModel, local, player) }
            player.play(track)
            waitUntil(timeoutMillis = 5_000) { player.state.value.playback.isPlaying && player.state.value.playback.queue == listOf(track) }
            assertEquals(listOf(track), engine.loaded)

            harness.viewModel.navigate(DesktopPage.LOCAL_MUSIC)
            onNodeWithTag("nav-local-music").assertIsDisplayed()
            onNodeWithTag("player-mini-bar").assertIsDisplayed()
            onNodeWithText("Navigation Track").assertIsDisplayed()
            onNodeWithText("暂停").assertIsDisplayed()

            harness.viewModel.navigate(DesktopPage.DISCOVERY)
            onNodeWithTag("nav-discovery").assertIsDisplayed()
            onNodeWithTag("player-mini-bar").assertIsDisplayed()
            onNodeWithText("Navigation Track").assertIsDisplayed()
            onNodeWithText("暂停").assertIsDisplayed()

            harness.viewModel.navigate(DesktopPage.NOW_PLAYING)
            onNodeWithTag("now-playing-screen").assertIsDisplayed()
            onNodeWithTag("now-playing-toggle-queue").performClick()
            onNodeWithText("播放队列 · 1 首").assertIsDisplayed()
            assertEquals(listOf(track), player.state.value.playback.queue)
            assertEquals(listOf(track), engine.loaded)
            assertTrue(player.state.value.playback.isPlaying)
            onNodeWithText("暂停").assertIsDisplayed()
        } finally {
            runBlocking { player.close() }
            local.shutdown()
            harness.close()
            root.toFile().deleteRecursively()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `page viewport stays above content sized player when window height changes`() = runComposeUiTest {
        val harness = createHarness()
        val root = Files.createTempDirectory("dmc-shell-layout")
        val local = LocalMusicViewModel(LocalMusicStore(root.resolve("library.db")), mainDispatcher = Dispatchers.Default)
        val player = PlayerViewModel(
            PlaybackCoordinator(UnavailablePlaybackEngine("layout test"), Dispatchers.Default),
            local, PlaybackQueueStore(root.resolve("queue.json")),
            ioDispatcher = Dispatchers.Default, mainDispatcher = Dispatchers.Default,
        )
        val height = mutableStateOf(700.dp)
        try {
            setContent {
                Box(Modifier.requiredSize(900.dp, height.value)) {
                    HachimiDesktopApp(harness.viewModel, local, player)
                }
            }
            fun assertLayout(): Int {
                val viewport = onNodeWithTag("page-viewport").fetchSemanticsNode().boundsInRoot
                val bar = onNodeWithTag("player-mini-bar").fetchSemanticsNode().boundsInRoot
                assertTrue(viewport.bottom <= bar.top, "page viewport overlaps player: $viewport / $bar")
                return onNodeWithTag("player-mini-bar").fetchSemanticsNode().size.height
            }
            val shortHeight = assertLayout()
            runOnIdle { height.value = 1_100.dp }
            assertEquals(shortHeight, assertLayout(), "player height changed with window height")
        } finally {
            runBlocking { player.close(); local.shutdown() }
            harness.close()
            root.toFile().deleteRecursively()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `discovery nav shows daily and playlist cards`() = runComposeUiTest {
        val harness = createHarness()
        try {
            harness.loadDiscovery()
            setContent { HachimiDesktopApp(harness.viewModel) }

            onNodeWithTag("nav-discovery").performClick()

            onNodeWithTag("discovery-daily").assertIsDisplayed()
            onNodeWithTag("discovery-playlist-91").assertIsDisplayed()
            onNodeWithText("My Playlist").assertIsDisplayed()
            onAllNodesWithText("排行榜").assertCountEquals(0)
            onAllNodesWithText("播客").assertCountEquals(0)
            onAllNodesWithText("歌手").assertCountEquals(0)
            onAllNodesWithText("新碟").assertCountEquals(0)
            onAllNodesWithText("新歌").assertCountEquals(0)
        } finally {
            harness.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `daily card opens selectable song rows`() = runComposeUiTest {
        val harness = createHarness()
        try {
            harness.loadDiscovery()
            setContent { HachimiDesktopApp(harness.viewModel) }
            onNodeWithTag("nav-discovery").performClick()
            onNodeWithTag("discovery-daily").performClick()

            waitUntil(conditionDescription = "daily recommendations opened") {
                harness.viewModel.ui.value.discoveryDetail == DiscoveryDetail.DAILY_RECOMMENDATIONS &&
                    !harness.viewModel.ui.value.dailyRecommendationsLoading
            }
            onNodeWithTag("discovery-song-22-0").assertIsDisplayed()
            onNodeWithTag("discovery-add-selected").assertIsNotEnabled()
            onNodeWithTag("discovery-song-22-0").performClick()
            onNodeWithTag("discovery-add-selected").assertIsEnabled()
        } finally {
            harness.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `playlist card opens and returns from track list`() = runComposeUiTest {
        val harness = createHarness()
        try {
            harness.loadDiscovery()
            setContent { HachimiDesktopApp(harness.viewModel) }
            onNodeWithTag("nav-discovery").performClick()
            onNodeWithTag("discovery-playlist-91").performClick()

            waitUntil(conditionDescription = "playlist tracks loaded") { harness.viewModel.ui.value.activePlaylist != null }
            onNodeWithTag("discovery-song-71-1").assertIsDisplayed()
            onNodeWithTag("discovery-playlist-back").performClick()
            onNodeWithTag("discovery-daily").assertIsDisplayed()
            onNodeWithTag("discovery-playlist-91").assertIsDisplayed()
        } finally {
            harness.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `empty discovery state exposes retry`() = runComposeUiTest {
        val harness = createHarness(playlists = emptyList(), dailySongs = emptyList())
        try {
            harness.loadDiscovery()
            setContent { HachimiDesktopApp(harness.viewModel) }
            onNodeWithTag("nav-discovery").performClick()

            onNodeWithText("暂时没有个人歌单。").assertIsDisplayed()
            onNodeWithTag("discovery-retry").assertIsDisplayed()
        } finally {
            harness.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `discovery error state exposes retry`() = runComposeUiTest {
        val harness = createHarness(failDiscovery = true)
        try {
            harness.loadDiscovery()
            setContent { HachimiDesktopApp(harness.viewModel) }
            onNodeWithTag("nav-discovery").performClick()

            onNodeWithText("发现服务暂不可用。").assertIsDisplayed()
            onNodeWithTag("discovery-retry").assertIsEnabled()
        } finally {
            harness.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `enqueue button reflects selection`() = runComposeUiTest {
        val harness = createHarness()
        try {
            harness.loadDiscovery()
            setContent { HachimiDesktopApp(harness.viewModel) }
            onNodeWithTag("nav-discovery").performClick()
            onNodeWithTag("discovery-daily").performClick()
            waitUntil(conditionDescription = "daily recommendations opened") {
                harness.viewModel.ui.value.discoveryDetail == DiscoveryDetail.DAILY_RECOMMENDATIONS
            }

            onNodeWithTag("discovery-add-selected").assertIsNotEnabled()
            onNodeWithTag("discovery-song-22-0").performClick()
            onNodeWithTag("discovery-add-selected").assertIsEnabled()
        } finally {
            harness.close()
        }
    }

    private fun createHarness(
        playlists: List<UserPlaylistSummary> = listOf(
            UserPlaylistSummary(91, "My Playlist", "", 1, "Owner"),
        ),
        dailySongs: List<SongHit> = listOf(SongHit(22, "Daily Song", "Artist", "Album")),
        failDiscovery: Boolean = false,
    ): Harness {
        val root = Files.createTempDirectory("hachimi-discovery-ui")
        val gateway = DiscoveryGateway(playlists, dailySongs, failDiscovery)
        val session = SessionManager(MemoryCredentialStore(), SessionValidator { SessionValidation.VALID })
        runBlocking { assertTrue(session.login("synthetic-session", remember = false).accepted) }
        val settings = DesktopSettings(defaultDownloadDirectory = root.resolve("music").toString())
        val store = DownloadTaskStore(root.resolve("tasks.json"))
        store.save(emptyList(), settings)
        val transfer = IdleTransfer()
        val manager = DownloadTaskManager(
            sourceResolver = gateway,
            transport = transfer,
            store = store,
            dispatcher = Dispatchers.Default,
            sessionManager = session,
        )
        val viewModel = DesktopViewModel(
            service = gateway,
            taskManager = manager,
            sessionManager = session,
            ioDispatcher = Dispatchers.IO,
            mainDispatcher = Dispatchers.Unconfined,
        )
        return Harness(viewModel, gateway)
    }

    private class Harness(val viewModel: DesktopViewModel, private val gateway: DiscoveryGateway) {
        fun loadDiscovery() = runBlocking {
            viewModel.loadDiscovery()
            withTimeout(3_000) {
                viewModel.ui.first {
                    !it.discoveryLoading && gateway.playlistsFinished.isCompleted && gateway.dailyFinished.isCompleted
                }
            }
        }

        fun close() = runBlocking { viewModel.shutdown() }
    }

    private class DiscoveryGateway(
        private val playlists: List<UserPlaylistSummary>,
        private val songs: List<SongHit>,
        private val fail: Boolean,
    ) : DesktopGateway {
        val playlistsFinished = CompletableDeferred<Unit>()
        val dailyFinished = CompletableDeferred<Unit>()

        override suspend fun searchSongs(query: String, musicU: String): List<SongHit> = emptyList()
        override suspend fun validateSession(musicU: String): SessionValidation = SessionValidation.VALID

        override suspend fun loadMyPlaylists(musicU: String): List<UserPlaylistSummary> = try {
            if (fail) throw DiscoveryApiException(DiscoveryFailureKind.HTTP_STATUS, "发现服务暂不可用。")
            playlists
        } finally {
            playlistsFinished.complete(Unit)
        }

        override suspend fun loadDailyRecommendations(musicU: String): List<SongHit> = try {
            if (fail) throw DiscoveryApiException(DiscoveryFailureKind.HTTP_STATUS, "发现服务暂不可用。")
            songs
        } finally {
            dailyFinished.complete(Unit)
        }

        override suspend fun resolveCollection(reference: CollectionReference, musicU: String) =
            CollectionResolveResult(
                descriptor = CollectionDescriptor(
                    reference = reference,
                    name = "My Playlist",
                    trackCount = 1,
                    trackIds = listOf(71L),
                    embeddedSongs = listOf(SongHit(71, "Playlist Song", "Artist", "Album")),
                ),
                items = listOf(
                    CollectionResolvedItem(1, 71L, CollectionItemStatus.RESOLVED, SongHit(71, "Playlist Song", "Artist", "Album")),
                ),
                countMismatch = false,
            )

        override suspend fun resolve(task: DownloadTask, musicU: String?): ResolvedSongUrl =
            ResolvedSongUrl("https://example.invalid/${task.songId}", "mp3", "exhigh")
    }

    private class IdleTransfer : DownloadTransport {
        override suspend fun download(
            taskId: String,
            target: Path,
            initialSource: ResolvedSongUrl,
            resolveAgain: suspend () -> ResolvedSongUrl,
            onProgress: suspend (DownloadProgressUpdate) -> Unit,
        ): DownloadTransferResult = DownloadTransferResult(0, 0, null, null, initialSource.actualQuality)

        override fun cancel(taskId: String) = Unit
        override fun deletePartial(target: Path) = Unit
    }

    private class FakePlaybackEngine : PlaybackEngine {
        override val events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
        val loaded = mutableListOf<LocalTrack>()

        override suspend fun load(track: LocalTrack, sessionId: PlaybackSessionId) { loaded += track }
        override suspend fun play(sessionId: PlaybackSessionId) = Unit
        override suspend fun pause(sessionId: PlaybackSessionId) = Unit
        override suspend fun stop(sessionId: PlaybackSessionId?) = Unit
        override suspend fun seek(sessionId: PlaybackSessionId, positionMillis: Long) = Unit
        override suspend fun setVolume(sessionId: PlaybackSessionId, volume: Float) = Unit
        override suspend fun close() = Unit
    }

    private class MemoryCredentialStore : CredentialStore {
        override fun save(value: String) = Unit
        override fun read(): String? = null
        override fun delete() = Unit
        override fun exists(): Boolean = false
    }
}
