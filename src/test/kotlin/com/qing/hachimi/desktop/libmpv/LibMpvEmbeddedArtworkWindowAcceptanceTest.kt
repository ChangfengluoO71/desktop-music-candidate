package com.qing.hachimi.desktop.libmpv

import com.qing.hachimi.desktop.AudioLossiness
import com.qing.hachimi.desktop.LocalAudioFormat
import com.qing.hachimi.desktop.LocalMetadataStatus
import com.qing.hachimi.desktop.LocalTitleSource
import com.qing.hachimi.desktop.LocalTrack
import com.qing.hachimi.desktop.PlaybackEvent
import com.qing.hachimi.desktop.PlaybackSessionId
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.PointerType
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.StdCallLibrary.StdCallCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.images.ArtworkFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import com.sun.jna.ptr.IntByReference
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Windows-only native acceptance for audio files that contain embedded cover art. */
@Tag("native-mpv-shared")
class LibMpvEmbeddedArtworkWindowAcceptanceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `embedded artwork plays through WASAPI without creating a visible native window`() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val library = Path.of(requireNotNull(System.getProperty("dmc.libmpv.path")))
        val audio = createEmbeddedArtworkFixture(tempDir)
        val before = visibleCurrentProcessWindows()
        val engine = LibMpvPlaybackEngine(library)
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collector = collectorScope.launch { engine.events.collect { } }
        val session = PlaybackSessionId(1)
        try {
            engine.load(audio.toTrack(), session)
            engine.setVolume(session, 0.1f)
            engine.play(session)
            val output = withTimeout(3_000) { engine.currentAudioOutput.first { it.equals("wasapi", ignoreCase = true) } }
            assertEquals("wasapi", output, "embedded-art fixture did not select the shared Windows audio output")

            val position = withTimeout(5_000) {
                engine.events.first { it is PlaybackEvent.Position && it.sessionId == session && it.positionMillis > 0 }
            } as PlaybackEvent.Position
            assertTrue(position.positionMillis > 0, "audio position did not advance")
            kotlinx.coroutines.delay(1_000)

            val after = visibleCurrentProcessWindows()
            val unexpected = after - before
            assertTrue(
                unexpected.isEmpty(),
                "libmpv created ${unexpected.size} additional visible top-level window(s) while playing embedded artwork",
            )
            println("R3_EMBEDDED_ART_WINDOW|ao=$output|positionMillis=${position.positionMillis}|newVisibleWindows=${unexpected.size}")
        } finally {
            runCatching { engine.pause(session) }
            engine.close()
            collector.cancel()
            collectorScope.cancel()
        }
    }

    private fun createEmbeddedArtworkFixture(directory: Path): Path {
        val audioPath = directory.resolve("embedded-cover.mp3")
        val source = requireNotNull(javaClass.classLoader.getResourceAsStream("audio-fixtures/synthetic-tone.mp3"))
        source.use { Files.copy(it, audioPath) }

        val audioFile = AudioFileIO.read(audioPath.toFile())
        val artwork = ArtworkFactory.getNew().apply {
            binaryData = coverPng()
            mimeType = "image/png"
            pictureType = 3
        }
        audioFile.getTagOrCreateAndSetDefault().setField(artwork)
        audioFile.commit()
        return audioPath
    }

    private fun Path.toTrack() = LocalTrack(
        id = "r3-embedded-artwork",
        pathKey = toAbsolutePath().normalize().toString().lowercase(),
        filePath = toAbsolutePath().normalize().toString(),
        fileSize = Files.size(this),
        lastModifiedMillis = Files.getLastModifiedTime(this).toMillis(),
        format = LocalAudioFormat.MP3,
        codec = "MP3",
        lossiness = AudioLossiness.LOSSY,
        durationSeconds = null,
        bitrateKbps = null,
        sampleRateHz = null,
        bitDepth = null,
        title = "Embedded artwork acceptance fixture",
        titleSource = LocalTitleSource.FILENAME,
        artists = listOf("DMC"),
        album = "R3 native acceptance",
        albumArtist = "DMC",
        trackNumber = null,
        discNumber = null,
        metadataStatus = LocalMetadataStatus.READABLE,
    )

    private fun visibleCurrentProcessWindows(): Set<Long> {
        val currentPid = ProcessHandle.current().pid().toInt()
        val visible = linkedSetOf<Long>()
        val callback = WindowEnumerator { hwnd, _ ->
            if (hwnd != null) {
                val processId = IntByReference()
                user32.GetWindowThreadProcessId(hwnd, processId)
                if (processId.value == currentPid && user32.IsWindowVisible(hwnd)) {
                    visible += Pointer.nativeValue(hwnd.pointer)
                }
            }
            true
        }
        user32.EnumWindows(callback, null)
        return visible
    }

    private fun coverPng(): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=",
    )

    private class WindowHandle : PointerType()

    private fun interface WindowEnumerator : StdCallCallback {
        fun invoke(hwnd: WindowHandle?, data: Pointer?): Boolean
    }

    private interface User32 : StdCallLibrary {
        fun EnumWindows(callback: WindowEnumerator, data: Pointer?): Boolean
        fun IsWindowVisible(hwnd: WindowHandle): Boolean
        fun GetWindowThreadProcessId(hwnd: WindowHandle, processId: IntByReference): Int
    }

    private val user32: User32 by lazy { Native.load("user32", User32::class.java) }
}
