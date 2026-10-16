package com.qing.hachimi.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesktopArtworkScreenTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `thumbnail opens preview dialog and close button dismisses it`() = runComposeUiTest {
        val harness = createHarness()
        try {
            setContent {
                val ui by harness.viewModel.ui.collectAsState()
                ArtworkThumbnail(
                    viewModel = harness.viewModel,
                    url = song.coverUrl,
                    tag = "artwork-thumbnail-song-42",
                    modifier = Modifier,
                    onClick = { harness.viewModel.previewSongArtwork(song) },
                )
                ui.artworkPreview?.let { ArtworkPreviewDialog(harness.viewModel, it) }
            }

            onNodeWithTag("artwork-thumbnail-song-42").assertIsDisplayed().performClick()
            waitUntil(conditionDescription = "artwork preview loaded") { harness.viewModel.ui.value.artworkPreview?.loading == false }
            onNodeWithTag("artwork-preview-dialog").assertIsDisplayed()
            onNodeWithTag("artwork-preview-close").performClick()
            waitUntil(conditionDescription = "artwork preview closed") { harness.viewModel.ui.value.artworkPreview == null }
            waitForIdle()
        } finally {
            harness.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `failed image shows retry state`() = runComposeUiTest {
        val harness = createHarness().apply { gateway.image = null }
        try {
            setContent {
                val ui by harness.viewModel.ui.collectAsState()
                ArtworkThumbnail(harness.viewModel, song.coverUrl, "artwork-thumbnail-failed", Modifier) {
                    harness.viewModel.previewSongArtwork(song)
                }
                ui.artworkPreview?.let { ArtworkPreviewDialog(harness.viewModel, it) }
            }

            onNodeWithTag("artwork-thumbnail-failed").performClick()
            waitUntil(conditionDescription = "failed artwork preview loaded") { harness.viewModel.ui.value.artworkPreview?.loading == false }
            onNodeWithTag("artwork-preview-retry").assertIsDisplayed().assertIsEnabled()
        } finally {
            harness.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `song and album save actions render independently`() = runComposeUiTest {
        val harness = createHarness()
        try {
            setContent {
                val ui by harness.viewModel.ui.collectAsState()
                ui.artworkPreview?.let { ArtworkPreviewDialog(harness.viewModel, it) }
            }
            harness.viewModel.previewSongArtwork(song)
            waitUntil(conditionDescription = "artwork action state loaded") { harness.viewModel.ui.value.artworkPreview?.loading == false }

            onNodeWithTag("artwork-save-song").assertIsDisplayed().assertIsEnabled()
            onNodeWithTag("artwork-save-album").assertIsDisplayed().assertIsEnabled()
        } finally {
            harness.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `settings selects custom cover folder`() = runComposeUiTest {
        val root = Files.createTempDirectory("hachimi-artwork-screen-settings")
        val custom = root.resolve("covers").toString()
        val harness = createHarness(DesktopSettings(defaultDownloadDirectory = root.resolve("music").toString()))
        try {
            setContent { SettingsScreen(harness.viewModel, harness.viewModel.settings.value, diagnosticsEnabled = false) }
            onNodeWithTag("cover-mode-custom").performScrollTo().performClick()
            onNodeWithTag("settings-cover-directory").performScrollTo().performTextInput(custom)
            onNodeWithTag("settings-cover-directory").assertIsDisplayed()
            waitUntil(timeoutMillis = 5_000, conditionDescription = "cover settings auto-save immediately") {
                harness.viewModel.settings.value.coverDirectoryMode == CoverDirectoryMode.CUSTOM_DIRECTORY &&
                    harness.viewModel.settings.value.customCoverDirectory == custom
            }
            assertEquals(CoverDirectoryMode.CUSTOM_DIRECTORY, harness.viewModel.settings.value.coverDirectoryMode)
            assertEquals(custom, harness.viewModel.settings.value.customCoverDirectory)
        } finally {
            harness.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `settings can check updates and toggle preview auto download`() = runComposeUiTest {
        val root = Files.createTempDirectory("dmc-updater-settings")
        val harness = createHarness(DesktopSettings(defaultDownloadDirectory = root.toString()))
        var automaticDownload = true
        var manualChecks = 0
        try {
            setContent {
                SettingsScreen(
                    viewModel = harness.viewModel,
                    settings = harness.viewModel.settings.value,
                    diagnosticsEnabled = false,
                    onAutoDownloadPreviewChanged = { automaticDownload = it },
                    onCheckForUpdates = { manualChecks += 1 },
                )
            }

            onNodeWithTag("update-manual-check").performScrollTo().performClick()
            onNodeWithTag("update-auto-download-preview").performScrollTo().performClick()

            assertEquals(1, manualChecks)
            assertFalse(automaticDownload)
        } finally {
            harness.close()
        }
    }

    private fun createHarness(settings: DesktopSettings? = null): Harness {
        val root = Files.createTempDirectory("hachimi-artwork-screen")
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
        return Harness(viewModel, gateway)
    }

    private class GatewayStub : DesktopGateway {
        var image: ArtworkPayload? = payload()
        override suspend fun searchSongs(query: String, musicU: String) = emptyList<SongHit>()
        override suspend fun validateSession(musicU: String) = SessionValidation.VALID
        override suspend fun resolve(task: DownloadTask, musicU: String?) =
            ResolvedSongUrl("https://example.invalid/${task.songId}", "mp3", "exhigh")
        override suspend fun fetchArtworkPayload(url: String) = image
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

    private data class Harness(val viewModel: DesktopViewModel, val gateway: GatewayStub) {
        fun close() = runBlocking { viewModel.shutdown() }
    }

    companion object {
        private val song = SongHit(42, "Song", "Artist", "Album", "https://p1.music.126.net/cover.png")
        private fun payload() = ArtworkPayload(
            java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=",
            ),
            "image/png",
            ".png",
        )
    }
}
