package com.qing.hachimi.desktop

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlaybackQueueStoreTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `restores local queue without auto starting and reports missing files`() {
        val first = track("one")
        val missing = track("missing")
        val file = temp.resolve("player.json")
        val store = PlaybackQueueStore(file)
        store.save(
            PlaybackState(
                queue = listOf(first, missing),
                queueIndex = 0,
                currentTrack = first,
                isPlaying = true,
                positionMillis = 4_250,
                volume = 0.6f,
                shuffle = true,
                repeatMode = PlaybackRepeatMode.ALL,
            ),
        )

        val restored = store.load(listOf(first))

        assertEquals(listOf(first), restored.queue)
        assertEquals(0, restored.queueIndex)
        assertEquals(4_250, restored.positionMillis)
        assertEquals(0.6f, restored.volume)
        assertTrue(restored.shuffle)
        assertEquals(PlaybackRepeatMode.ALL, restored.repeatMode)
        assertFalse(restored.autoPlay)
        assertEquals(listOf(missing.filePath), restored.missingFiles)
    }

    @Test
    fun `queue persistence contains only local playback metadata`() {
        val file = temp.resolve("player.json")
        PlaybackQueueStore(file).save(PlaybackState(queue = listOf(track("one"))))
        val json = Files.readString(file)

        assertTrue(json.contains("C:/music/one.mp3"))
        assertFalse(json.contains("songId", ignoreCase = true))
        assertFalse(json.contains("music_u", ignoreCase = true))
        assertFalse(json.contains("token", ignoreCase = true))
    }

    @Test
    fun `corrupt queue file is isolated and produces a recovery warning`() {
        val file = temp.resolve("player.json")
        Files.writeString(file, "{ broken")

        val restored = PlaybackQueueStore(file).load(emptyList())

        assertTrue(restored.queue.isEmpty())
        assertNotNull(restored.warning)
    }

    private fun track(id: String) = LocalTrack(
        id = id, pathKey = "c:/music/$id.mp3", filePath = "C:/music/$id.mp3", fileSize = 1,
        lastModifiedMillis = 1, format = LocalAudioFormat.MP3, codec = "MP3", lossiness = AudioLossiness.LOSSY,
        durationSeconds = 10.0, bitrateKbps = 128, sampleRateHz = 44_100, bitDepth = null,
        title = id, titleSource = LocalTitleSource.TAG, artists = listOf("Artist"), album = "Album",
        albumArtist = "Artist", trackNumber = null, discNumber = null, metadataStatus = LocalMetadataStatus.READABLE,
    )
}
