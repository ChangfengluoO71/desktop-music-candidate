package com.qing.hachimi.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopArtworkViewModelTest {
    @Test
    fun `preview loads validated image bytes`() = runBlocking {
        val harness = createHarness()
        try {
            harness.viewModel.previewSongArtwork(song)
            val preview = awaitPreview(harness)

            assertEquals("Song", preview.title)
            assertEquals("image/png", preview.payload?.mimeType)
            assertContentEquals(png(), preview.payload?.bytes)
            assertFalse(preview.loading)
            assertFalse(preview.toString().contains("signed-token"))
            assertFalse(preview.toString().contains("signature"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `preview failure shows retryable message`() = runBlocking {
        val harness = createHarness().apply { gateway.artwork = null }
        try {
            harness.viewModel.previewSongArtwork(song)
            val preview = awaitPreview(harness)

            assertTrue(preview.payload == null)
            assertFalse(preview.message.orEmpty().contains("signed-token"))
            assertTrue(preview.message?.isNotBlank() == true)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `song cover saves beside matching task file`() = runBlocking {
        val harness = createHarness()
        try {
            harness.manager.addSongs(listOf(song))
            val taskPath = Path.of(harness.manager.tasks.value.first { it.songId == song.id }.filePath)
            harness.viewModel.previewSongArtwork(song)
            awaitPreview(harness)
            harness.viewModel.savePreviewArtwork(ArtworkSaveKind.SONG)
            val preview = awaitSaved(harness)

            assertNotNull(preview.message)
            val expectedCover = taskPath.resolveSibling(taskPath.fileName.toString().substringBeforeLast('.') + ".png")
            assertTrue(preview.message.contains(expectedCover.fileName.toString()))
            assertTrue(Files.isRegularFile(expectedCover))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `song cover predicts path before audio task exists`() = runBlocking {
        val harness = createHarness()
        try {
            harness.viewModel.previewSongArtwork(song)
            awaitPreview(harness)
            harness.viewModel.savePreviewArtwork(ArtworkSaveKind.SONG)
            val preview = awaitSaved(harness)

            assertTrue(preview.message.orEmpty().contains("Artist - Song [42].png"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `album cover uses album destination`() = runBlocking {
        val harness = createHarness()
        try {
            harness.viewModel.previewAlbumArtwork("Album", "Artist", song.coverUrl, song)
            awaitPreview(harness)
            harness.viewModel.savePreviewArtwork(ArtworkSaveKind.ALBUM)
            val preview = awaitSaved(harness)

            assertTrue(preview.message.orEmpty().contains("Artist - Album.png"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `custom cover folder overrides media folder`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-cover-vm-custom")
        val custom = root.resolve("separate covers")
        val harness = createHarness(
            settings = DesktopSettings(
                defaultDownloadDirectory = root.resolve("music").toString(),
                coverDirectoryMode = CoverDirectoryMode.CUSTOM_DIRECTORY,
                customCoverDirectory = custom.toString(),
            ),
        )
        try {
            harness.viewModel.previewSongArtwork(song)
            awaitPreview(harness)
            harness.viewModel.savePreviewArtwork(ArtworkSaveKind.SONG)
            val preview = awaitSaved(harness)

            assertTrue(preview.message.orEmpty().contains(custom.toString()))
            assertTrue(Files.isRegularFile(custom.resolve("Artist - Song [42].png")))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `save message contains neither URL nor credential`() = runBlocking {
        val harness = createHarness()
        try {
            harness.viewModel.previewSongArtwork(song)
            awaitPreview(harness)
            harness.viewModel.savePreviewArtwork(ArtworkSaveKind.SONG)
            val preview = awaitSaved(harness)

            assertFalse(preview.message.orEmpty().contains("https://"))
            assertFalse(preview.message.orEmpty().contains("signed-token"))
        } finally {
            harness.close()
        }
    }

    private suspend fun awaitPreview(harness: Harness) = withTimeout(3_000) {
        harness.viewModel.ui.first { it.artworkPreview != null && !it.artworkPreview.loading }
            .artworkPreview!!
    }

    private suspend fun awaitSaved(harness: Harness) = withTimeout(3_000) {
        harness.viewModel.ui.first { it.artworkPreview?.saving == false && it.artworkPreview?.message != null }
            .artworkPreview!!
    }

    private fun createHarness(settings: DesktopSettings? = null): Harness {
        val root = Files.createTempDirectory("hachimi-cover-vm")
        val gateway = GatewayStub()
        val session = SessionManager(MemoryCredentialStore(), SessionValidator { SessionValidation.VALID })
        val store = DownloadTaskStore(root.resolve("tasks.json"))
        store.save(emptyList(), settings ?: DesktopSettings(defaultDownloadDirectory = root.resolve("music").toString()))
        val manager = DownloadTaskManager(
            sourceResolver = gateway,
            transport = WaitingTransfer(),
            store = store,
            dispatcher = Dispatchers.Default,
            sessionManager = session,
        )
        val viewModel = DesktopViewModel(
            service = gateway,
            taskManager = manager,
            sessionManager = session,
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )
        return Harness(viewModel, manager, gateway)
    }

    private class GatewayStub : DesktopGateway {
        var artwork: ArtworkPayload? = payload()
        override suspend fun searchSongs(query: String, musicU: String) = emptyList<SongHit>()
        override suspend fun validateSession(musicU: String) = SessionValidation.VALID
        override suspend fun resolve(task: DownloadTask, musicU: String?) =
            ResolvedSongUrl("https://example.invalid/${task.songId}", "mp3", "exhigh")
        override suspend fun fetchArtworkPayload(url: String) = artwork
    }

    private class WaitingTransfer : DownloadTransport {
        private val release = CompletableDeferred<Unit>()
        override suspend fun download(
            taskId: String,
            target: Path,
            initialSource: ResolvedSongUrl,
            resolveAgain: suspend () -> ResolvedSongUrl,
            onProgress: suspend (DownloadProgressUpdate) -> Unit,
        ): DownloadTransferResult {
            release.await()
            return DownloadTransferResult(0, 0, null, null, initialSource.actualQuality)
        }
        override fun cancel(taskId: String) = Unit
        override fun deletePartial(target: Path) = Unit
    }

    private class MemoryCredentialStore : CredentialStore {
        override fun save(value: String) = Unit
        override fun read(): String? = null
        override fun delete() = Unit
        override fun exists() = false
    }

    private data class Harness(
        val viewModel: DesktopViewModel,
        val manager: DownloadTaskManager,
        val gateway: GatewayStub,
    ) {
        suspend fun close() {
            viewModel.shutdown()
        }
    }

    companion object {
        private val song = SongHit(
            42, "Song", "Artist", "Album",
            "https://p1.music.126.net/cover.png?signature=signed-token",
        )

        private fun payload() = ArtworkPayload(png(), "image/png", ".png")
        private fun png() = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=",
        )
    }
}
