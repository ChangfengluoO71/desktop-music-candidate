package com.qing.hachimi.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopViewModelTest {
    @Test
    fun `now playing is a navigable main-window destination`() = runBlocking {
        val root = Files.createTempDirectory("dmc-now-playing-navigation")
        val gateway = DesktopGatewayStub()
        val session = SessionManager(MemoryCredentialStore(), SessionValidator { SessionValidation.VALID })
        val harness = createHarness(root, gateway, session)

        try {
            harness.viewModel.navigate(DesktopPage.NOW_PLAYING)

            assertEquals(DesktopPage.NOW_PLAYING, harness.viewModel.ui.value.page)
        } finally {
            harness.transfer.release.complete(Unit)
            harness.viewModel.shutdown()
        }
    }

    @Test
    fun `signed out discovery requests show login guidance`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-discovery-signed-out")
        val gateway = DesktopGatewayStub()
        val session = SessionManager(MemoryCredentialStore(), SessionValidator { SessionValidation.VALID })
        val harness = createHarness(root, gateway, session)

        try {
            harness.viewModel.loadDiscovery()

            val state = withTimeout(3_000) { harness.viewModel.ui.first { it.discoveryMessage.isNotBlank() } }
            assertTrue(state.discoveryMessage.contains("登录"))
            assertTrue(gateway.discoveryCalls.isEmpty())
            assertTrue(state.myPlaylists.isEmpty())
            assertTrue(state.dailyRecommendations.isEmpty())
        } finally {
            harness.transfer.release.complete(Unit)
            harness.viewModel.shutdown()
        }
    }

    @Test
    fun `signed out search still works`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-discovery-anonymous-search")
        val gateway = DesktopGatewayStub().apply {
            searchResult = listOf(SongHit(11, "Public Search", "Artist", "Album"))
        }
        val session = SessionManager(MemoryCredentialStore(), SessionValidator { SessionValidation.VALID })
        val harness = createHarness(root, gateway, session)

        try {
            harness.viewModel.updateQuery("Public Search")
            harness.viewModel.search()

            val state = withTimeout(3_000) { harness.viewModel.ui.first { it.searchResults.isNotEmpty() } }
            assertEquals(listOf(11L), state.searchResults.map { it.id })
            assertEquals(SessionStatus.SIGNED_OUT, state.session.status)
        } finally {
            harness.transfer.release.complete(Unit)
            harness.viewModel.shutdown()
        }
    }

    @Test
    fun `refresh loads playlists and daily songs`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-discovery-refresh")
        val gateway = DesktopGatewayStub().apply {
            playlists = listOf(UserPlaylistSummary(91, "My Playlist", "https://p1.music.126.net/p.jpg", 2, "Owner"))
            dailyResult = listOf(SongHit(71, "Daily Song", "Artist", "Album"))
        }
        val session = SessionManager(MemoryCredentialStore(), SessionValidator { SessionValidation.VALID })
        assertTrue(session.login("synthetic-session", remember = false).accepted)
        val harness = createHarness(root, gateway, session)

        try {
            harness.viewModel.loadDiscovery()

            val state = withTimeout(3_000) {
                harness.viewModel.ui.first { !it.discoveryLoading && it.myPlaylists.isNotEmpty() && it.dailyRecommendations.isNotEmpty() }
            }
            assertEquals(listOf(91L), state.myPlaylists.map { it.id })
            assertEquals(listOf(71L), state.dailyRecommendations.map { it.id })
            assertEquals(setOf("playlists", "daily"), gateway.discoveryCalls.toSet())
        } finally {
            harness.transfer.release.complete(Unit)
            harness.viewModel.shutdown()
        }
    }

    @Test
    fun `expired discovery session clears session and asks for re-login`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-discovery-expired")
        val gateway = DesktopGatewayStub().apply { expireDiscovery = true }
        val session = SessionManager(MemoryCredentialStore(), SessionValidator { SessionValidation.VALID })
        assertTrue(session.login("synthetic-session", remember = false).accepted)
        val harness = createHarness(root, gateway, session)

        try {
            harness.viewModel.loadDiscovery()

            val state = withTimeout(3_000) {
                harness.viewModel.ui.first { it.session.status == SessionStatus.SESSION_EXPIRED }
            }
            assertTrue(state.discoveryMessage.contains("登录"))
            assertEquals(null, session.credentialForRequest())
        } finally {
            harness.transfer.release.complete(Unit)
            harness.viewModel.shutdown()
        }
    }

    @Test
    fun `opening a playlist uses the collection resolver`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-discovery-open-playlist")
        val gateway = DesktopGatewayStub()
        val session = SessionManager(MemoryCredentialStore(), SessionValidator { SessionValidation.VALID })
        assertTrue(session.login("synthetic-session", remember = false).accepted)
        val harness = createHarness(root, gateway, session)

        try {
            harness.viewModel.openUserPlaylist(91L)

            val state = withTimeout(3_000) { harness.viewModel.ui.first { it.activePlaylist != null } }
            assertEquals(CollectionReference(CollectionSourceType.PLAYLIST, 91L), gateway.lastResolvedReference)
            assertEquals("Test Playlist", state.activePlaylist?.descriptor?.name)
            assertEquals(emptySet(), state.selectedUserPlaylistIndices)
        } finally {
            harness.transfer.release.complete(Unit)
            harness.viewModel.shutdown()
        }
    }

    @Test
    fun `daily selection is independent from search selection`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-discovery-selection")
        val gateway = DesktopGatewayStub().apply {
            searchResult = listOf(SongHit(11, "Search Song", "Artist", "Album"))
            dailyResult = listOf(SongHit(22, "Daily Song", "Artist", "Album"))
        }
        val session = SessionManager(MemoryCredentialStore(), SessionValidator { SessionValidation.VALID })
        assertTrue(session.login("synthetic-session", remember = false).accepted)
        val harness = createHarness(root, gateway, session)

        try {
            harness.viewModel.updateQuery("Search Song")
            harness.viewModel.search()
            withTimeout(3_000) { harness.viewModel.ui.first { it.searchResults.isNotEmpty() } }
            harness.viewModel.toggleSelection(11L)
            harness.viewModel.loadDailyRecommendations()
            withTimeout(3_000) { harness.viewModel.ui.first { it.dailyRecommendations.isNotEmpty() } }
            harness.viewModel.toggleDailyRecommendationSelection(22L)

            assertEquals(setOf(11L), harness.viewModel.ui.value.selectedSongIds)
            assertEquals(setOf(22L), harness.viewModel.ui.value.selectedDailyRecommendationIds)
        } finally {
            harness.transfer.release.complete(Unit)
            harness.viewModel.shutdown()
        }
    }

    @Test
    fun `late discovery response cannot replace a newer refresh`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-discovery-stale-refresh")
        val oldStarted = CompletableDeferred<Unit>()
        val oldResponse = CompletableDeferred<List<SongHit>>()
        val calls = AtomicInteger()
        val gateway = DesktopGatewayStub().apply {
            dailyResultProvider = {
                if (calls.incrementAndGet() == 1) {
                    oldStarted.complete(Unit)
                    oldResponse.await()
                } else {
                    listOf(SongHit(22, "New Response", "Artist", "Album"))
                }
            }
        }
        val session = SessionManager(MemoryCredentialStore(), SessionValidator { SessionValidation.VALID })
        assertTrue(session.login("synthetic-session", remember = false).accepted)
        val harness = createHarness(root, gateway, session)

        try {
            harness.viewModel.loadDailyRecommendations()
            withTimeout(3_000) { oldStarted.await() }
            harness.viewModel.loadDailyRecommendations()
            withTimeout(3_000) {
                harness.viewModel.ui.first { it.dailyRecommendations.singleOrNull()?.id == 22L }
            }
            oldResponse.complete(listOf(SongHit(11, "Old Response", "Artist", "Album")))
            delay(50)

            assertEquals(22L, harness.viewModel.ui.value.dailyRecommendations.single().id)
        } finally {
            oldResponse.complete(emptyList())
            harness.transfer.release.complete(Unit)
            harness.viewModel.shutdown()
        }
    }

    @Test
    fun `enqueue adds only selected resolved songs`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-discovery-enqueue")
        val settings = DesktopSettings(
            defaultDownloadDirectory = root.resolve("music").toString(),
            defaultQuality = "lossless",
            folderGrouping = FolderGrouping.PLAYLIST,
        )
        val gateway = DesktopGatewayStub()
        val session = SessionManager(MemoryCredentialStore(), SessionValidator { SessionValidation.VALID })
        assertTrue(session.login("synthetic-session", remember = false).accepted)
        val harness = createHarness(root, gateway, session, settings)

        try {
            harness.viewModel.openUserPlaylist(91L)
            withTimeout(3_000) { harness.viewModel.ui.first { it.activePlaylist != null } }
            harness.viewModel.clearDiscoverySelection()
            harness.viewModel.toggleUserPlaylistSelection(1)
            harness.viewModel.enqueueSelectedDiscoverySongs()

            val tasks = withTimeout(3_000) { harness.manager.tasks.first { it.isNotEmpty() } }
            assertEquals(listOf(7L), tasks.map { it.songId })
            assertEquals("lossless", tasks.single().requestedQuality)
            assertTrue(
                Path.of(tasks.single().filePath).parent.toRealPath()
                    .startsWith(Path.of(settings.defaultDownloadDirectory).toRealPath()),
            )
            assertFalse(harness.viewModel.ui.value.selectedUserPlaylistIndices.contains(2))
        } finally {
            harness.transfer.release.complete(Unit)
            harness.viewModel.shutdown()
        }
    }

    @Test
    fun `collection preview does not enqueue until explicit confirmation`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-collection-view-model")
        val transfer = WaitingTransfer()
        val gateway = DesktopGatewayStub()
        val session = SessionManager(MemoryCredentialStore(), SessionValidator { SessionValidation.VALID })
        val manager = DownloadTaskManager(
            sourceResolver = gateway,
            transport = transfer,
            store = DownloadTaskStore(root.resolve("tasks.json")),
            dispatcher = Dispatchers.Default,
        )
        val collectionStore = CollectionImportStore(root.resolve("collections.json"))
        val viewModel = DesktopViewModel(
            service = gateway,
            taskManager = manager,
            sessionManager = session,
            ioDispatcher = Dispatchers.IO,
            mainDispatcher = Dispatchers.Unconfined,
            collectionStore = collectionStore,
        )

        try {
            viewModel.updateCollectionLink("https://music.163.com/playlist?id=91")
            viewModel.loadCollection()
            withTimeout(3_000) { viewModel.ui.first { it.collectionPreview != null } }
            assertEquals(emptyList(), manager.tasks.value)
            assertEquals(emptySet(), viewModel.ui.value.selectedCollectionIndices)

            viewModel.selectAllResolvedCollectionItems()
            viewModel.addSelectedCollectionItems()
            val savedImport = withTimeout(3_000) {
                viewModel.collectionImports.first {
                    it.isNotEmpty() && it.single().items.all { row -> row.downloadTaskId != null }
                }.single()
            }
            assertEquals(2, manager.tasks.value.size)
            assertEquals(listOf(savedImport.id), manager.tasks.value.first().collectionImportIds)
            withTimeout(3_000) {
                while (collectionStore.load().singleOrNull()?.items?.all { row -> row.status == CollectionItemStatus.QUEUED } != true) {
                    delay(10)
                }
            }
            assertEquals(2, collectionStore.load().single().items.count { it.status == CollectionItemStatus.QUEUED })
        } finally {
            transfer.release.complete(Unit)
            viewModel.shutdown()
        }
    }

    @Test
    fun `preparing collection import is recoverable after process restart`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-collection-recovery")
        val transfer = WaitingTransfer()
        val gateway = DesktopGatewayStub()
        val session = SessionManager(MemoryCredentialStore(), SessionValidator { SessionValidation.VALID })
        val manager = DownloadTaskManager(
            sourceResolver = gateway,
            transport = transfer,
            store = DownloadTaskStore(root.resolve("tasks.json")),
            dispatcher = Dispatchers.Default,
        )
        val import = CollectionImport(
            id = "import-recover",
            sourceType = CollectionSourceType.PLAYLIST,
            sourceId = 91,
            sourceName = "Recoverable Playlist",
            requestedQuality = "lossless",
            downloadDirectory = root.resolve("music").toString(),
            createdAtMillis = 10,
            folderGrouping = FolderGrouping.PLAYLIST,
            items = listOf(
                CollectionImportItem(
                    originalIndex = 1,
                    songId = 71,
                    songName = "Recover Me",
                    artists = "Test Artist",
                    album = "Test Album",
                    status = CollectionItemStatus.PREPARING,
                ),
            ),
        )
        val collectionStore = CollectionImportStore(root.resolve("collections.json"))
        collectionStore.save(listOf(import))
        val viewModel = DesktopViewModel(
            service = gateway,
            taskManager = manager,
            sessionManager = session,
            ioDispatcher = Dispatchers.IO,
            mainDispatcher = Dispatchers.Unconfined,
            collectionStore = collectionStore,
        )

        try {
            withTimeout(3_000) {
                viewModel.collectionImports.first { it.single().items.single().status == CollectionItemStatus.PREPARING }
            }
            viewModel.retryUnqueuedCollectionItems(import.id)

            val recovered = withTimeout(3_000) {
                viewModel.collectionImports.first {
                    it.single().items.single().status == CollectionItemStatus.QUEUED &&
                        it.single().items.single().downloadTaskId != null
                }.single()
            }
            val task = manager.tasks.value.single()
            assertEquals(import.id, task.collectionImportIds.single())
            assertEquals(71L, task.songId)
            assertEquals("lossless", task.requestedQuality)
            assertEquals(FolderGrouping.PLAYLIST, collectionStore.load().single().folderGrouping)
            assertEquals(recovered.items.single().downloadTaskId, task.id)
            assertTrue(Path.of(task.filePath).parent.toString().contains("Playlists"))
            transfer.started.receive()
        } finally {
            transfer.release.complete(Unit)
            viewModel.shutdown()
        }
    }

    @Test
    fun `task added from search appears in the view model task stream as it downloads`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-view-model")
        val transfer = WaitingTransfer()
        val gateway = DesktopGatewayStub()
        val session = SessionManager(MemoryCredentialStore(), SessionValidator { SessionValidation.VALID })
        val manager = DownloadTaskManager(
            sourceResolver = gateway,
            transport = transfer,
            store = DownloadTaskStore(root.resolve("tasks.json")),
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

        try {
            viewModel.addSong(SongHit(42, "UI State Track", "Test Artist", "Local Album"))

            val visibleTask = withTimeout(3_000) {
                viewModel.tasks.first { tasks -> tasks.singleOrNull()?.status == DownloadTaskStatus.DOWNLOADING }
                    .single()
            }
            assertEquals(42L, visibleTask.songId)
            assertEquals(DownloadTaskStatus.DOWNLOADING, manager.tasks.value.single().status)
            assertEquals(DownloadTaskStatus.DOWNLOADING, viewModel.tasks.value.single().status)
            assertEquals(visibleTask.id, transfer.started.receive())
        } finally {
            transfer.release.complete(Unit)
            viewModel.shutdown()
        }
    }

    private class DesktopGatewayStub : DesktopGateway {
        var searchResult: List<SongHit> = emptyList()
        var playlists: List<UserPlaylistSummary> = emptyList()
        var dailyResult: List<SongHit> = emptyList()
        var expireDiscovery = false
        val discoveryCalls = CopyOnWriteArrayList<String>()
        var lastResolvedReference: CollectionReference? = null
        var dailyResultProvider: suspend () -> List<SongHit> = { dailyResult }

        override suspend fun searchSongs(query: String, musicU: String): List<SongHit> = searchResult
        override suspend fun validateSession(musicU: String): SessionValidation = SessionValidation.VALID
        override suspend fun loadMyPlaylists(musicU: String): List<UserPlaylistSummary> {
            discoveryCalls += "playlists"
            if (expireDiscovery) throw SessionExpiredException()
            return playlists
        }
        override suspend fun loadDailyRecommendations(musicU: String): List<SongHit> {
            discoveryCalls += "daily"
            if (expireDiscovery) throw SessionExpiredException()
            return dailyResultProvider()
        }
        override suspend fun resolveCollection(reference: CollectionReference, musicU: String) =
            CollectionResolveResult(
                descriptor = CollectionDescriptor(
                    reference = reference,
                    name = "Test Playlist",
                    trackCount = 2,
                    trackIds = listOf(7L, 8L),
                    embeddedSongs = listOf(
                        SongHit(7L, "Seven", "Artist", "Album"),
                        SongHit(8L, "Eight", "Artist", "Album"),
                    ),
                ),
                items = listOf(
                    CollectionResolvedItem(1, 7L, CollectionItemStatus.RESOLVED, SongHit(7L, "Seven", "Artist", "Album")),
                    CollectionResolvedItem(2, 8L, CollectionItemStatus.RESOLVED, SongHit(8L, "Eight", "Artist", "Album")),
                ),
                countMismatch = false,
            ).also { lastResolvedReference = reference }
        override suspend fun resolve(task: DownloadTask, musicU: String?): ResolvedSongUrl =
            ResolvedSongUrl("https://example.invalid/${task.songId}", "wav", "exhigh")
    }

    private class WaitingTransfer : DownloadTransport {
        val started = Channel<String>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()

        override suspend fun download(
            taskId: String,
            target: Path,
            initialSource: ResolvedSongUrl,
            resolveAgain: suspend () -> ResolvedSongUrl,
            onProgress: suspend (DownloadProgressUpdate) -> Unit,
        ): DownloadTransferResult {
            started.send(taskId)
            onProgress(DownloadProgressUpdate(1, 100, 1, null, null))
            release.await()
            return DownloadTransferResult(100, 100, null, null, initialSource.actualQuality)
        }

        override fun cancel(taskId: String) = Unit
        override fun deletePartial(target: Path) = Unit
    }

    private class MemoryCredentialStore : CredentialStore {
        override fun save(value: String) = Unit
        override fun read(): String? = null
        override fun delete() = Unit
        override fun exists(): Boolean = false
    }

    private data class TestHarness(
        val viewModel: DesktopViewModel,
        val manager: DownloadTaskManager,
        val transfer: WaitingTransfer,
    )

    private fun createHarness(
        root: Path,
        gateway: DesktopGatewayStub,
        session: SessionManager,
        settings: DesktopSettings = DesktopSettings(defaultDownloadDirectory = root.resolve("music").toString()),
    ): TestHarness {
        val transfer = WaitingTransfer()
        val store = DownloadTaskStore(root.resolve("tasks.json"))
        store.save(emptyList(), settings)
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
        return TestHarness(viewModel, manager, transfer)
    }
}
