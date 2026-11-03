package com.qing.hachimi.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import javax.imageio.ImageIO

/** Test-only native window acceptance harness. It never opens production app data. */
fun main() = application {
    val harness = remember { GuiAcceptanceHarness.create() }
    val windowState = rememberWindowState(width = 1280.dp, height = 820.dp)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val measuredBounds = remember { mutableMapOf<String, Rect>() }

    LaunchedEffect(Unit) {
        delay(900)
        listOf(
            ResizeCase("wide", 1440, 900),
            ResizeCase("wide-tall", 1440, 1200),
            ResizeCase("tall", 1000, 1200),
            ResizeCase("narrow", 760, 900),
        ).forEach { resize ->
            measuredBounds.clear()
            windowState.size = DpSize(resize.width.dp, resize.height.dp)
            delay(700)
            val viewport = requireNotNull(measuredBounds["page-viewport"]) { "Missing measured page viewport after ${resize.name}" }
            val miniPlayer = requireNotNull(measuredBounds["mini-player"]) { "Missing measured mini-player after ${resize.name}" }
            println("GUI_ACCEPTANCE_BOUNDS=${resize.name}|viewport=$viewport|miniPlayer=$miniPlayer")
            check(viewport.bottom <= miniPlayer.top + 1f) {
                "Measured viewport overlaps mini-player: viewportBottom=${viewport.bottom}, miniPlayerTop=${miniPlayer.top}"
            }
            harness.assertStableMiniPlayerHeight(resize.width, miniPlayer.height)
            println("GUI_ACCEPTANCE_RESIZE=${resize.name}|measuredViewport=${viewport.width}x${viewport.height}|measuredMiniPlayer=${miniPlayer.width}x${miniPlayer.height}")
            harness.capture(resize.name)
        }
        delay(500)
        closeOnce(scope, harness, { closing = true }, ::exitApplication)
    }

    Window(
        title = "DMC GUI Acceptance",
        state = windowState,
        resizable = true,
        onCloseRequest = { closeOnce(scope, harness, { closing = true }, ::exitApplication) },
    ) {
        HachimiDesktopApp(
            viewModel = harness.desktopViewModel,
            localMusicViewModel = harness.localMusicViewModel,
            playerViewModel = harness.playerViewModel,
            artworkRepository = harness.artworkRepository,
            windowState = windowState,
            layoutBoundsReporter = { name, bounds -> measuredBounds[name] = bounds },
        )
    }
}

private fun closeOnce(scope: kotlinx.coroutines.CoroutineScope, harness: GuiAcceptanceHarness, markClosing: () -> Unit, exit: () -> Unit) {
    markClosing()
    scope.launch { harness.close(); exit() }
}

private data class ResizeCase(val name: String, val width: Int, val height: Int)

private class GuiAcceptanceHarness private constructor(
    val desktopViewModel: DesktopViewModel,
    val localMusicViewModel: LocalMusicViewModel,
    val playerViewModel: PlayerViewModel,
    val artworkRepository: LocalArtworkRepository,
    private val root: Path,
    private val evidenceDirectory: Path = Path.of(System.getProperty("hachimi.gui.acceptance.evidenceDir", root.toString())),
) {
    private val miniPlayerHeightsByWidth = mutableMapOf<Int, Float>()

    fun assertStableMiniPlayerHeight(width: Int, height: Float) {
        val previous = miniPlayerHeightsByWidth.putIfAbsent(width, height)
        if (previous != null) {
            check(abs(previous - height) <= 1f) {
                "Mini-player height changed for width=$width: previous=$previous, current=$height"
            }
        }
    }

    fun capture(label: String) {
        Files.createDirectories(evidenceDirectory)
        runCatching {
            if (GraphicsEnvironment.isHeadless()) return@runCatching
            val screen = Toolkit.getDefaultToolkit().screenSize
            val image = Robot().createScreenCapture(Rectangle(0, 0, screen.width, screen.height))
            val file = evidenceDirectory.resolve("evidence-${label}.png")
            ImageIO.write(image, "png", file.toFile())
            println("GUI_ACCEPTANCE_SCREENSHOT=${label}|${file}|${image.width}x${image.height}")
        }.onFailure { println("GUI_ACCEPTANCE_SCREENSHOT=${label}|unavailable|${it.message}") }
    }

    suspend fun close() {
        playerViewModel.close()
        localMusicViewModel.shutdown()
        artworkRepository.close()
        desktopViewModel.shutdown()
        val deleted = root.toFile().deleteRecursively()
        println("GUI_ACCEPTANCE_CLEANUP=${if (deleted) "isolated-temp-deleted" else "failed"}")
    }

    companion object {
        fun create(): GuiAcceptanceHarness {
            val root = Files.createTempDirectory("dmc-gui-acceptance-")
            val session = SessionManager(object : CredentialStore {
                override fun save(value: String) = Unit
                override fun read(): String? = null
                override fun delete() = Unit
                override fun exists() = false
            }, SessionValidator { SessionValidation.VALID })
            val gateway = AcceptanceGateway()
            val settings = DesktopSettings(defaultDownloadDirectory = root.resolve("downloads").toString())
            val store = DownloadTaskStore(root.resolve("tasks.json"))
            store.save(emptyList(), settings)
            val manager = DownloadTaskManager(gateway, AcceptanceTransfer(), store, Dispatchers.Default, session)
            val desktop = DesktopViewModel(gateway, manager, session, Dispatchers.Default, Dispatchers.Default, CollectionImportStore(root.resolve("collections.json")))
            val local = LocalMusicViewModel(
                store = LocalMusicStore(root.resolve("local-music.sqlite")),
                ioDispatcher = Dispatchers.Default,
                mainDispatcher = Dispatchers.Default,
            )
            val artwork = LocalArtworkRepository()
            val player = PlayerViewModel(
                coordinator = PlaybackCoordinator(AcceptancePlaybackEngine(), Dispatchers.Default),
                localMusicViewModel = local,
                queueStore = PlaybackQueueStore(root.resolve("queue.json")),
                ioDispatcher = Dispatchers.Default,
                mainDispatcher = Dispatchers.Default,
            )
            val tracks = (1..2).map { n -> syntheticTrack(root, n) }
            runBlocking { player.playTracks(tracks) }
            println("GUI_ACCEPTANCE_DATA_DIR=${root}")
            println("GUI_ACCEPTANCE_ENGINE=fake")
            println("GUI_ACCEPTANCE_TRACKS=${tracks.size}")
            val evidenceDirectory = Path.of(System.getProperty("hachimi.gui.acceptance.evidenceDir", root.toString()))
            return GuiAcceptanceHarness(desktop, local, player, artwork, root, evidenceDirectory)
        }

        private fun syntheticTrack(root: Path, n: Int) = LocalTrack(
            id = "gui-acceptance-${n}", pathKey = root.resolve("track-${n}.fake").toString().lowercase(), filePath = root.resolve("track-${n}.fake").toString(),
            fileSize = 1_024L, lastModifiedMillis = 1_700_000_000_000L + n, format = LocalAudioFormat.OTHER, codec = "synthetic", lossiness = AudioLossiness.UNKNOWN,
            durationSeconds = 180.0, bitrateKbps = 128, sampleRateHz = 44_100, bitDepth = 16, title = "GUI Acceptance ${n}", titleSource = LocalTitleSource.TAG,
            artists = listOf("DMC Test"), album = "Synthetic Fixture", albumArtist = "DMC Test", trackNumber = n, discNumber = 1, metadataStatus = LocalMetadataStatus.READABLE,
        )
    }
}

private class AcceptanceGateway : DesktopGateway {
    override suspend fun searchSongs(query: String, musicU: String) = emptyList<SongHit>()
    override suspend fun validateSession(musicU: String) = SessionValidation.VALID
    override suspend fun resolve(task: DownloadTask, musicU: String?) = ResolvedSongUrl("https://127.0.0.1/unused", "mp3", "exhigh")
}

private class AcceptanceTransfer : DownloadTransport {
    override suspend fun download(taskId: String, target: Path, initialSource: ResolvedSongUrl, resolveAgain: suspend () -> ResolvedSongUrl, onProgress: suspend (DownloadProgressUpdate) -> Unit) = DownloadTransferResult(0L, 0L, null, null, initialSource.actualQuality)
    override fun cancel(taskId: String) = Unit
    override fun partialPath(target: Path) = target.resolveSibling("${target.fileName}.part")
    override fun deletePartial(target: Path) { Files.deleteIfExists(partialPath(target)) }
}

private class AcceptancePlaybackEngine : PlaybackEngine {
    private val eventFlow = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
    override val events: Flow<PlaybackEvent> = eventFlow
    override suspend fun load(track: LocalTrack, sessionId: PlaybackSessionId) { eventFlow.emit(PlaybackEvent.Position(sessionId, 0L, 180_000L)) }
    override suspend fun play(sessionId: PlaybackSessionId) = Unit
    override suspend fun pause(sessionId: PlaybackSessionId) = Unit
    override suspend fun stop(sessionId: PlaybackSessionId?) = Unit
    override suspend fun seek(sessionId: PlaybackSessionId, positionMillis: Long) { eventFlow.emit(PlaybackEvent.Position(sessionId, positionMillis, 180_000L)) }
    override suspend fun setVolume(sessionId: PlaybackSessionId, volume: Float) = Unit
    override suspend fun close() = Unit
}
