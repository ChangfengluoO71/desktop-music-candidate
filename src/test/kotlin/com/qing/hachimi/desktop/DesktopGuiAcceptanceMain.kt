package com.qing.hachimi.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/** Test-source-only window. It is not included in the production application or installer. */
fun main() = application {
    val harness = remember { LocalGuiAcceptanceHarness.create() }
    val scope = rememberCoroutineScope()
    var closeStarted by remember { mutableStateOf(false) }
    LaunchedEffect(harness) {
        harness.viewModel.navigate(DesktopPage.COLLECTIONS)
        harness.viewModel.updateCollectionLink("https://music.163.com/playlist?id=163000001")
        harness.viewModel.loadCollection()
    }
    Window(
        onCloseRequest = {
            if (!closeStarted) {
                closeStarted = true
                scope.launch {
                    harness.viewModel.shutdown()
                    harness.close()
                    exitApplication()
                }
            }
        },
        title = "Desktop Music Candidate — LOCAL GUI ACCEPTANCE",
        state = rememberWindowState(width = 1280.dp, height = 820.dp),
        resizable = true,
    ) {
        HachimiDesktopApp(harness.viewModel)
    }
}

private class LocalGuiAcceptanceHarness(
    val viewModel: DesktopViewModel,
    private val server: LocalAudioServer,
) : AutoCloseable {
    override fun close() = server.close()

    companion object {
        fun create(): LocalGuiAcceptanceHarness {
            val dataDirectory = Path.of(
                System.getProperty("hachimi.gui.acceptance.dir")
                    ?: Path.of(System.getProperty("java.io.tmpdir"), "DesktopMusicCandidateLocalGuiAcceptance").toString(),
            ).toAbsolutePath().normalize()
            Files.createDirectories(dataDirectory)
            // Give each GUI run fresh songs so a prior completed task cannot mask a transfer.
            val firstSongId = System.currentTimeMillis() * 10L
            val audioServer = LocalAudioServer()
            val settings = DesktopSettings(
                defaultDownloadDirectory = dataDirectory.resolve("downloads").toString(),
                maxConcurrentDownloads = 2,
                defaultQuality = "exhigh",
                downloadLyrics = true,
                saveTranslatedLyrics = true,
                saveRomanizedLyrics = true,
                saveWordLevelLyrics = true,
                embedLyrics = true,
                writeAudioTags = true,
                embedCoverArt = true,
            )
            val store = DownloadTaskStore(dataDirectory.resolve("tasks.json"))
            val gateway = LocalAudioGateway(audioServer, firstSongId)
            val session = SessionManager(
                credentialStore = object : CredentialStore {
                    override fun save(value: String) = Unit
                    override fun read(): String? = null
                    override fun delete() = Unit
                    override fun exists(): Boolean = false
                },
                validator = SessionValidator { SessionValidation.VALID },
            )
            val manager = DownloadTaskManager(
                sourceResolver = gateway,
                transport = DesktopDownloader(allowHttpForLoopbackTests = true),
                store = store,
                dispatcher = Dispatchers.IO,
                sessionManager = session,
            )
            runBlocking { manager.updateSettings(settings) }
            val viewModel = DesktopViewModel(
                service = gateway,
                taskManager = manager,
                sessionManager = session,
                ioDispatcher = Dispatchers.IO,
                collectionStore = CollectionImportStore(dataDirectory.resolve("collections.json")),
            )
            val expectedBytes = audioServer.audioBytes
            val expectedHash = MessageDigest.getInstance("SHA-256")
                .digest(expectedBytes)
                .joinToString("") { "%02x".format(it) }
            println("LOCAL_GUI_DATA_DIR=$dataDirectory")
            println("LOCAL_GUI_AUDIO_LENGTH=${expectedBytes.size}")
            println("LOCAL_GUI_AUDIO_SHA256=$expectedHash")
            return LocalGuiAcceptanceHarness(viewModel, audioServer)
        }
    }
}

private class LocalAudioGateway(
    private val server: LocalAudioServer,
    private val firstSongId: Long,
) : DesktopGateway {
    override suspend fun searchSongs(query: String, musicU: String): List<SongHit> = listOf(
        SongHit(firstSongId, "Local Acceptance 01", "Test Artist", "Generated WAV"),
        SongHit(firstSongId + 1, "Local Acceptance 02", "Test Artist", "Generated WAV"),
        SongHit(firstSongId + 2, "Local Acceptance 03", "Test Artist", "Generated WAV"),
        SongHit(firstSongId + 3, "Local Acceptance Failure", "Test Artist", "HTTP error fixture"),
        SongHit(firstSongId + 4, "Local Acceptance 05", "Test Artist", "Generated WAV"),
    )

    override suspend fun validateSession(musicU: String): SessionValidation = SessionValidation.VALID

    override suspend fun resolveCollection(reference: CollectionReference, musicU: String): CollectionResolveResult {
        val songs = (0L..4L).map { offset ->
            SongHit(
                id = firstSongId + offset,
                name = if (offset == 3L) "Local Acceptance Failure" else "Local Acceptance ${offset + 1}",
                artists = "Test Artist",
                album = "Generated Synthetic Tone",
                coverUrl = "https://p1.music.126.net/local/mock-cover",
                trackNumber = offset.toInt() + 1,
                trackTotal = 5,
            )
        }
        val descriptor = CollectionDescriptor(
            reference = reference,
            name = "Local Mock Playlist",
            creator = "Offline Test Harness",
            coverUrl = "https://p1.music.126.net/local/mock-cover",
            trackCount = songs.size,
            trackIds = songs.map { it.id },
            embeddedSongs = songs,
            description = "Local mock only; no third-party collection API is called.",
        )
        return CollectionResolveResult(
            descriptor,
            songs.mapIndexed { index, song ->
                CollectionResolvedItem(index + 1, song.id, CollectionItemStatus.RESOLVED, song)
            },
            countMismatch = false,
        )
    }

    override suspend fun fetchArtwork(url: String): ByteArray = onePixelPng()

    override suspend fun loadPostProcessingData(
        task: DownloadTask,
        options: AudioPostProcessingOptions,
        musicU: String?,
    ) = DesktopPostProcessingData(
        lyrics = DesktopLyrics(
            lrc = "[00:00.00]synthetic tone for local acceptance",
            translation = "[00:00.00]本地测试合成音",
            romanization = "[00:00.00]ben di ce shi he cheng yin",
            wordLevel = "[0,1000](0,1000,0)synthetic tone",
        ),
        tags = DesktopTagMetadata(
            albumArtist = "Test Artist",
            trackNumber = task.trackNumber,
            trackTotal = task.trackTotal,
            releaseYear = 2026,
        ),
        coverBytes = onePixelPng(),
        coverMimeType = "image/png",
    )

    override suspend fun resolve(task: DownloadTask, musicU: String?): ResolvedSongUrl = ResolvedSongUrl(
        url = if (task.songName == "Local Acceptance Failure") server.failureUrl() else server.url(task.songId),
        type = "mp3",
        actualQuality = "exhigh",
    )
}

private class LocalAudioServer : AutoCloseable {
    val audioBytes: ByteArray = requireNotNull(javaClass.getResourceAsStream("/audio-fixtures/synthetic-tone.mp3"))
        .use { it.readBytes() }
    private val rangeRequests = CopyOnWriteArrayList<String>()
    private val activeTransfers = AtomicInteger()
    private val maxConcurrentTransfers = AtomicInteger()
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@LocalAudioServer.executor
        createContext("/audio/") { exchange -> serve(exchange) }
        start()
    }

    fun url(songId: Long): String = "http://127.0.0.1:${server.address.port}/audio/$songId"
    fun failureUrl(): String = "http://127.0.0.1:${server.address.port}/audio/failure"

    private fun serve(exchange: HttpExchange) {
        if (exchange.requestURI.path.endsWith("/failure")) {
            reply(exchange, 503, "application/json", "{\"error\":\"local failure fixture\"}".encodeToByteArray())
            return
        }
        val active = activeTransfers.incrementAndGet()
        maxConcurrentTransfers.updateAndGet { maxOf(it, active) }
        try {
            val etag = "\"local-mp3-v1\""
            exchange.responseHeaders.add("Content-Type", "audio/mpeg")
            exchange.responseHeaders.add("ETag", etag)
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            rangeHeader?.let(rangeRequests::add)
            val offset = rangeHeader?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
            if (offset != null && offset >= audioBytes.size) {
                exchange.responseHeaders.add("Content-Range", "bytes */${audioBytes.size}")
                exchange.sendResponseHeaders(416, -1)
                exchange.close()
                return
            }
            val start = offset ?: 0
            if (start > 0) {
                exchange.responseHeaders.add("Content-Range", "bytes $start-${audioBytes.lastIndex}/${audioBytes.size}")
                exchange.sendResponseHeaders(206, (audioBytes.size - start).toLong())
            } else {
                exchange.sendResponseHeaders(200, audioBytes.size.toLong())
            }
            try {
                exchange.responseBody.use { output ->
                    var cursor = start
                    while (cursor < audioBytes.size) {
                        val count = minOf(8 * 1024, audioBytes.size - cursor)
                        output.write(audioBytes, cursor, count)
                        output.flush()
                        cursor += count
                        if (cursor < audioBytes.size) Thread.sleep(900)
                    }
                }
            } catch (_: Exception) {
                exchange.close()
            }
        } finally {
            activeTransfers.decrementAndGet()
        }
    }

    private fun reply(exchange: HttpExchange, status: Int, type: String, bytes: ByteArray) {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        println("LOCAL_GUI_RANGE_REQUESTS=${rangeRequests.size}")
        println("LOCAL_GUI_RANGE_HEADERS=${rangeRequests.joinToString(",")}")
        println("LOCAL_GUI_MAX_CONCURRENT_TRANSFERS=${maxConcurrentTransfers.get()}")
        server.stop(0)
        executor.shutdownNow()
    }
}

private fun onePixelPng() = java.util.Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=",
)
