package com.qing.hachimi.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnavailablePlaybackEngineTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `reports unavailable backend without a JavaFX fallback`() = runBlocking {
        val message = "Install the local libmpv runtime to enable playback."
        val file = Files.write(tempDir.resolve("sample.mp3"), byteArrayOf(1, 2, 3))
        val engine = UnavailablePlaybackEngine(message)

        val failure = assertFailsWith<IllegalStateException> {
            engine.load(
                LocalTrack(
                    id = "sample",
                    pathKey = file.toString(),
                    filePath = file.toString(),
                    fileSize = Files.size(file),
                    lastModifiedMillis = Files.getLastModifiedTime(file).toMillis(),
                    format = LocalAudioFormat.MP3,
                    codec = null,
                    lossiness = AudioLossiness.UNKNOWN,
                    durationSeconds = null,
                    bitrateKbps = null,
                    sampleRateHz = null,
                    bitDepth = null,
                    title = "sample",
                    titleSource = LocalTitleSource.FILENAME,
                    artists = emptyList(),
                    album = "",
                    albumArtist = "",
                    trackNumber = null,
                    discNumber = null,
                    metadataStatus = LocalMetadataStatus.READABLE,
                ),
                PlaybackSessionId(1),
            )
        }

        assertEquals(message, failure.message)
        engine.close()
    }
}
