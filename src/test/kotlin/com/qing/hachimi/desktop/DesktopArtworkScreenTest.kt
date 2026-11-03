package com.qing.hachimi.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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
    fun `settings expands local scanning controls for an empty library`() = runComposeUiTest {
        val harness = createHarness()
        val root = Files.createTempDirectory("local-settings-test")
        val local = LocalMusicViewModel(LocalMusicStore(root.resolve("library.db")), mainDispatcher = Dispatchers.Unconfined)
        try {
            setContent {
                SettingsScreen(harness.viewModel, harness.viewModel.settings.value, diagnosticsEnabled = false, localMusicViewModel = local)
            }
            onAllNodesWithTag("local-music-add-root").assertCountEquals(0)
            onNodeWithTag("settings-section-本地音乐").performScrollTo().performClick()
            onNodeWithTag("local-music-add-root").performScrollTo().assertIsDisplayed()
            onNodeWithTag("local-music-scan").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            onNodeWithTag("local-music-hash-duplicates").performScrollTo().assertIsNotEnabled()
            onNodeWithTag("local-music-status").performScrollTo().assertIsDisplayed()
        } finally {
            runBlocking { local.shutdown() }
            harness.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `settings scan indexes the selected directory without modifying files`() = runComposeUiTest {
        val harness = createHarness()
        val temp = Files.createTempDirectory("local-settings-scan-test")
        val music = Files.createDirectory(temp.resolve("music"))
        val song = music.resolve("song.mp3")
        val original = byteArrayOf(1, 2, 3, 4)
        Files.write(song, original)
        val store = LocalMusicStore(temp.resolve("library.db"))
        val scanner = DesktopMusicScanner(store, LocalAudioMetadataReader {
            LocalAudioMetadata("MP3", AudioLossiness.LOSSY, 180.0, 128, 44_100, 16,
                "Settings Scan", listOf("Artist"), "Album", "Artist", 1, 1)
        })
        val local = LocalMusicViewModel(store, scanner, mainDispatcher = Dispatchers.Default)
        try {
            local.addRoots(listOf(music))
            waitUntil(timeoutMillis = 5_000, conditionDescription = "directory added") { local.state.value.roots.size == 1 }
            setContent {
                SettingsScreen(harness.viewModel, harness.viewModel.settings.value, diagnosticsEnabled = false, localMusicViewModel = local)
            }
            onNodeWithTag("settings-section-本地音乐").performScrollTo().performClick()
            onNodeWithTag("local-music-scan").performScrollTo().assertIsEnabled().performClick()
            waitUntil(timeoutMillis = 10_000, conditionDescription = "settings scan completed") {
                !local.state.value.isScanning && local.state.value.tracks.size == 1
            }
            assertEquals("Settings Scan", local.state.value.tracks.single().title)
            assertEquals(original.toList(), Files.readAllBytes(song).toList())
        } finally {
            runBlocking { local.shutdown() }
            harness.close()
            temp.toFile().deleteRecursively()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `thumbnail opens preview dialog and close button dismisses it`() = runComposeUiTest {
        val harness = createHarness(mainDispatcher = Dispatchers.Main.immediate)
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
            onNodeWithTag("artwork-preview-close").performSemanticsAction(SemanticsActions.OnClick)
            assertEquals(null, harness.viewModel.ui.value.artworkPreview)
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
            onAllNodesWithTag("cover-mode-custom").assertCountEquals(0)
            onNodeWithTag("settings-section-下载与媒体").performScrollTo().performClick()
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

            onNodeWithTag("settings-section-应用更新").performScrollTo().performClick()
            onNodeWithTag("update-manual-check").performScrollTo().performClick()
            onNodeWithTag("update-auto-download-preview").performScrollTo().performClick()

            assertEquals(1, manualChecks)
            assertFalse(automaticDownload)
        } finally {
            harness.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `update check and installer mirrors save independently`() = runComposeUiTest {
        val root = Files.createTempDirectory("dmc-updater-mirror-settings")
        val harness = createHarness(DesktopSettings(defaultDownloadDirectory = root.toString()))
        val savedMirrors = mutableListOf<Pair<String?, String?>>()
        try {
            setContent {
                SettingsScreen(
                    viewModel = harness.viewModel,
                    settings = harness.viewModel.settings.value,
                    diagnosticsEnabled = false,
                    onUpdateMirrorTemplatesChanged = { check, installer ->
                        savedMirrors += check to installer
                    },
                )
            }

            onNodeWithTag("settings-section-应用更新").performScrollTo().performClick()
            repeat(20) {
                onNodeWithTag("settings-screen-scroll").performTouchInput { swipeUp() }
            }
            onNodeWithTag("update-check-mirror-gh-proxy-com")
                .assertIsDisplayed()
                .assertIsEnabled()
                .performClick()
            assertEquals("https://gh-proxy.com/{url}", savedMirrors.lastOrNull()?.first)
            repeat(2) {
                onNodeWithTag("settings-screen-scroll").performTouchInput { swipeUp() }
            }
            onNodeWithTag("update-installer-mirror-ghfast-top").assertIsDisplayed().performClick()

            assertEquals("https://gh-proxy.com/{url}", savedMirrors.lastOrNull()?.first)
            assertEquals("https://ghfast.top/{url}", savedMirrors.lastOrNull()?.second)
            onNodeWithTag("update-check-mirror-template").assertIsDisplayed()
            onNodeWithTag("update-installer-mirror-template").assertIsDisplayed()

            onNodeWithTag("update-installer-mirror-direct").performScrollTo().performClick()
            onNodeWithTag("update-installer-mirror-template")
                .performTextInput("https://mirror.example/{url}")
            assertEquals("https://gh-proxy.com/{url}", savedMirrors.lastOrNull()?.first)
            assertEquals("https://mirror.example/{url}", savedMirrors.lastOrNull()?.second)
        } finally {
            harness.close()
        }
    }

    private fun createHarness(
        settings: DesktopSettings? = null,
        mainDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): Harness {
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
            mainDispatcher = mainDispatcher,
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
