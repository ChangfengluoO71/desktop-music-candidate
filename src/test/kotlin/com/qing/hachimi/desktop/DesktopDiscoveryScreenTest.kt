package com.qing.hachimi.desktop

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

            onNodeWithTag("local-music-status").assertIsDisplayed()
        } finally {
            localMusicViewModel.shutdown()
            harness.close()
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

    private class MemoryCredentialStore : CredentialStore {
        override fun save(value: String) = Unit
        override fun read(): String? = null
        override fun delete() = Unit
        override fun exists(): Boolean = false
    }
}
