package com.qing.hachimi.desktop

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.nio.file.Files
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaFxPlaybackEngineTest {
    @Test
    fun `javafx media loads local mp3 fixture and reports its duration`() = runBlocking {
        assumeTrue(hasAudioOutput(), "OpenJFX media-device integration needs an available system audio output endpoint.")
        val fixture = Path.of("src/test/resources/audio-fixtures/synthetic-tone.mp3").toAbsolutePath()
        val engine = JavaFxPlaybackEngine()
        var wav: Path? = null
        try {
            engine.load(localTrack(fixture, LocalAudioFormat.MP3))
            val position = withTimeout(10_000) {
                engine.events.first { it is PlaybackEvent.Position && it.durationMillis > 0 } as PlaybackEvent.Position
            }
            assertTrue(position.durationMillis > 0)

            val m4a = Path.of("src/test/resources/audio-fixtures/synthetic-tagged-aac.m4a").toAbsolutePath()
            engine.load(localTrack(m4a, LocalAudioFormat.M4A))
            val m4aPosition = withTimeout(10_000) {
                engine.events.first { it is PlaybackEvent.Position && it.durationMillis >= 1_000 } as PlaybackEvent.Position
            }
            assertTrue(m4aPosition.durationMillis >= 1_000)

            wav = createPcmWav()
            engine.load(localTrack(wav, LocalAudioFormat.WAV))
            val wavPosition = withTimeout(10_000) {
                engine.events.first { it is PlaybackEvent.Position && it.durationMillis in 200L..500L } as PlaybackEvent.Position
            }
            assertTrue(wavPosition.durationMillis in 200L..500L)
        } finally {
            engine.close()
            wav?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun `javafx backend advertises only formats its media stack can decode`() {
        assertTrue(JavaFxPlaybackEngine.supports(LocalAudioFormat.MP3))
        assertTrue(JavaFxPlaybackEngine.supports(LocalAudioFormat.WAV))
        assertTrue(JavaFxPlaybackEngine.supports(LocalAudioFormat.M4A))
        assertTrue(JavaFxPlaybackEngine.supports(LocalAudioFormat.AAC))
        assertFalse(JavaFxPlaybackEngine.supports(LocalAudioFormat.FLAC))
        assertFalse(JavaFxPlaybackEngine.supports(LocalAudioFormat.OTHER))
    }

    private fun localTrack(path: Path, format: LocalAudioFormat) = LocalTrack(
        id = path.fileName.toString(),
        pathKey = path.toString().lowercase(),
        filePath = path.toString(),
        fileSize = java.nio.file.Files.size(path),
        lastModifiedMillis = java.nio.file.Files.getLastModifiedTime(path).toMillis(),
        format = format,
        codec = null,
        lossiness = AudioLossiness.UNKNOWN,
        durationSeconds = null,
        bitrateKbps = null,
        sampleRateHz = null,
        bitDepth = null,
        title = path.fileName.toString(),
        titleSource = LocalTitleSource.FILENAME,
        artists = emptyList(),
        album = "",
        albumArtist = "",
        trackNumber = null,
        discNumber = null,
        metadataStatus = LocalMetadataStatus.READABLE,
    )

    private fun createPcmWav(): Path {
        val path = Files.createTempFile("dmc-playback-", ".wav")
        val format = AudioFormat(44_100f, 16, 1, true, false)
        val frames = 11_025L
        val pcm = ByteArray(frames.toInt() * format.frameSize)
        AudioInputStream(ByteArrayInputStream(pcm), format, frames).use { stream ->
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile())
        }
        return path
    }

    private fun hasAudioOutput(): Boolean {
        val format = AudioFormat(44_100f, 16, 1, true, false)
        val lineInfo = DataLine.Info(SourceDataLine::class.java, format)
        return AudioSystem.getMixerInfo().any { mixerInfo ->
            runCatching { AudioSystem.getMixer(mixerInfo).isLineSupported(lineInfo) }.getOrDefault(false)
        }
    }
}
