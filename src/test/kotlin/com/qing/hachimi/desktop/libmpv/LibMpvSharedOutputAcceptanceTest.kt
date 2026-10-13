package com.qing.hachimi.desktop.libmpv

import com.qing.hachimi.desktop.AudioLossiness
import com.qing.hachimi.desktop.LocalAudioFormat
import com.qing.hachimi.desktop.LocalMetadataStatus
import com.qing.hachimi.desktop.LocalTitleSource
import com.qing.hachimi.desktop.LocalTrack
import com.qing.hachimi.desktop.PlaybackEvent
import com.qing.hachimi.desktop.PlaybackSessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in test that plays generated local samples through the real Windows shared audio device. */
@Tag("native-mpv-shared")
class LibMpvSharedOutputAcceptanceTest {
    @Test
    fun `plays MP3 FLAC M4A and WAV through active WASAPI output`(@TempDir tempDir: Path) = runBlocking {
        val library = Path.of(requireNotNull(System.getProperty("dmc.libmpv.path")))
        val archive = Path.of(requireNotNull(System.getProperty("dmc.libmpv.archive")))
        assertTrue(library.isAbsolute && Files.isRegularFile(library), "explicit libmpv DLL is unavailable")
        assertTrue(archive.isAbsolute && Files.isRegularFile(archive), "pinned mpv archive is unavailable")

        val fixtures = listOf(
            AcceptanceFixture("MP3", LocalAudioFormat.MP3, resource("audio-fixtures/synthetic-tone.mp3")),
            AcceptanceFixture("FLAC", LocalAudioFormat.FLAC, resource("audio-fixtures/synthetic-progress.flac")),
            AcceptanceFixture("M4A/AAC", LocalAudioFormat.M4A, resource("audio-fixtures/synthetic-tagged-aac.m4a")),
            AcceptanceFixture(
                "WAV",
                LocalAudioFormat.WAV,
                PcmWaveFixture.write(tempDir.resolve("shared-output-tone.wav"), durationMillis = 3_000),
            ),
        )
        val apiVersion = withNativeApi(library) { it.mpv_client_api_version() }
        println(
            "R2_NATIVE_ARTIFACT|clientApi=${apiVersion ushr 16}.${apiVersion and 0xffff}" +
                "|archiveSha256=${sha256(archive)}|dllSha256=${sha256(library)}",
        )

        val engine = LibMpvPlaybackEngine(library)
        val events = Channel<PlaybackEvent>(Channel.UNLIMITED)
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collector = collectorScope.launch { engine.events.collect(events::send) }
        try {
            fixtures.forEachIndexed { index, fixture ->
                val session = PlaybackSessionId(index + 1L)
                val track = fixture.toTrack()
                engine.load(track, session)
                engine.setVolume(session, 0.12f)
                engine.play(session)
                val activeOutput = withTimeout(3_000) {
                    engine.currentAudioOutput.first { it.equals("wasapi", ignoreCase = true) }
                }
                assertEquals("wasapi", activeOutput, "${fixture.label} did not select WASAPI shared output")

                val position = withTimeout(5_000) {
                    while (true) {
                        when (val event = events.receive()) {
                            is PlaybackEvent.Failed -> if (event.sessionId == session) {
                                throw AssertionError("${fixture.label} playback failed: ${event.message}")
                            }
                            is PlaybackEvent.Position -> if (event.sessionId == session && event.positionMillis > 0) {
                                return@withTimeout event.positionMillis
                            }
                            is PlaybackEvent.Ended -> if (event.sessionId == session) {
                                throw AssertionError("${fixture.label} reached EOF before a position advance was observed")
                            }
                        }
                    }
                    error("unreachable")
                }
                println("R2_WASAPI_PLAYBACK|format=${fixture.label}|ao=$activeOutput|positionMillis=$position|started=true")
                engine.pause(session)
            }
        } finally {
            runCatching { engine.close() }
            collector.cancel()
            collectorScope.cancel()
            events.close()
        }
    }

    private data class AcceptanceFixture(val label: String, val format: LocalAudioFormat, val path: Path) {
        fun toTrack(): LocalTrack = LocalTrack(
            id = "r2-$label",
            pathKey = path.toAbsolutePath().normalize().toString().lowercase(),
            filePath = path.toAbsolutePath().normalize().toString(),
            fileSize = Files.size(path),
            lastModifiedMillis = Files.getLastModifiedTime(path).toMillis(),
            format = format,
            codec = null,
            lossiness = AudioLossiness.UNKNOWN,
            durationSeconds = null,
            bitrateKbps = null,
            sampleRateHz = null,
            bitDepth = null,
            title = label,
            titleSource = LocalTitleSource.FILENAME,
            artists = listOf("DMC R2 synthetic fixture"),
            album = "Local acceptance fixture",
            albumArtist = "DMC R2 synthetic fixture",
            trackNumber = null,
            discNumber = null,
            metadataStatus = LocalMetadataStatus.READABLE,
        )
    }

    private fun resource(name: String): Path =
        Path.of(requireNotNull(javaClass.classLoader.getResource(name)) { "test fixture missing: $name" }.toURI())

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }

    private fun <T> withNativeApi(library: Path, block: (MpvFunctions) -> T): T {
        val api = com.sun.jna.Native.load(
            library.toAbsolutePath().normalize().toString(),
            MpvFunctions::class.java,
            mapOf(com.sun.jna.Library.OPTION_STRING_ENCODING to "UTF-8"),
        )
        return block(api)
    }
}
