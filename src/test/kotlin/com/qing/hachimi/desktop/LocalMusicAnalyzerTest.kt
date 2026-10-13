package com.qing.hachimi.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalMusicAnalyzerTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `exact duplicates require identical streamed sha256 content`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("dupes"))
        val fixture = javaClass.getResourceAsStream("/audio-fixtures/synthetic-tone.mp3")!!
        fixture.use { Files.copy(it, root.resolve("first.mp3")) }
        Files.copy(root.resolve("first.mp3"), root.resolve("second.mp3"))
        Files.write(root.resolve("different.mp3"), byteArrayOf(9, 8, 7, 6))
        LocalMusicStore(temp.resolve("library.db")).use { store ->
            val tracks = DesktopMusicScanner(store, LocalAudioMetadataReader { path ->
                LocalAudioMetadata("MP3", AudioLossiness.LOSSY, 1.0, 128, 44_100, 16,
                    path.fileName.toString(), listOf("Artist"), "Album", "Artist", 1, 1)
            }).scan(listOf(root)).tracks
            val result = LocalDuplicateAnalyzer(store).analyze(tracks)
            assertEquals(1, result.exactDuplicates.size)
            assertEquals(2, result.exactDuplicates.single().tracks.size)
            assertEquals(3, result.hashedFiles)
            assertEquals(0, result.failedHashes)
            assertTrue(result.exactDuplicates.single().tracks.all { it.contentSha256 == result.exactDuplicates.single().sha256 })
        }
    }

    @Test
    fun `similar metadata reports live and duration conflicts instead of claiming identity`() {
        val studio = track("one", "Night Drive", 180.0, "Studio")
        val live = track("two", "Night Drive Live", 204.0, "Live")
        val group = LocalMusicAnalyzer.analyzeSimilarDuplicates(listOf(studio, live)).single()
        assertTrue(group.matchingEvidence.any { it.contains("标题") })
        assertTrue(group.conflicts.any { it.contains("版本标记") })
        assertTrue(group.conflicts.any { it.contains("时长") })
    }

    @Test
    fun `same title and artist are only a candidate and filename evidence is downgraded`() {
        val first = track("one", "A Song", 120.0, "Album").copy(titleSource = LocalTitleSource.FILENAME)
        val second = track("two", "A-Song", 121.0, "Album")
        val candidate = LocalMusicAnalyzer.analyzeSimilarDuplicates(listOf(first, second)).single()
        assertTrue(candidate.matchingEvidence.any { it.contains("文件名") })
        assertTrue(candidate.conflicts.isEmpty())
        assertFalse(candidate.tracks.first().id == candidate.tracks.last().id)
    }

    @Test
    fun `quality comparison does not score unknown values or equate lossless container with better source`() {
        val unknown = LocalAudioQuality("MP3", AudioLossiness.LOSSY, null, 44_100, null, null, 1_000)
        val known = LocalAudioQuality("MP3", AudioLossiness.LOSSY, 320, 44_100, 16, 10.0, 2_000)
        val differentCodec = known.copy(codec = "FLAC", lossiness = AudioLossiness.LOSSLESS, bitDepth = 24)
        assertEquals(QualityParameterComparison.UNKNOWN, compareAudioParameters(unknown, known))
        assertEquals(QualityParameterComparison.INCOMPARABLE_CODECS, compareAudioParameters(known, differentCodec))
    }

    @Test
    fun `upgrade preview stays unknown without a confirmed online candidate`() {
        val preview = LocalMusicAnalyzer.previewWithoutOnlineCandidate(track("local", "Title", 190.0, "Album"))
        assertEquals(null, preview.candidateSongId)
        assertEquals(IdentityStatus.UNKNOWN, preview.identityStatus)
        assertEquals(UpgradePreviewStatus.NO_CANDIDATE, preview.upgradeStatus)
        assertTrue(preview.reason.contains("不会下载或替换"))
    }

    @Test
    fun `live candidate with length or version mismatch requires review`() {
        val local = track("local", "Title", 180.0, "Album")
        val liveCandidate = UpgradeCandidateRecord(
            songId = 998L,
            title = "Title Live",
            artists = listOf("Artist"),
            durationSeconds = 205.0,
            quality = local.audioQuality.copy(bitrateKbps = 320),
        )
        val preview = LocalMusicAnalyzer.compareCandidate(local, liveCandidate)
        assertEquals(IdentityStatus.CONFLICT, preview.identityStatus)
        assertEquals(UpgradePreviewStatus.NEEDS_REVIEW, preview.upgradeStatus)
        assertTrue(preview.identityEvidence.any { it.contains("冲突") || it.contains("差异") })
    }

    @Test
    fun `changing file after scan is not accepted as an exact hash result`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("changed"))
        val audio = root.resolve("changed.mp3")
        Files.write(audio, byteArrayOf(1, 2, 3))
        LocalMusicStore(temp.resolve("library.db")).use { store ->
            val track = DesktopMusicScanner(store, LocalAudioMetadataReader { path ->
                LocalAudioMetadata("MP3", AudioLossiness.LOSSY, 1.0, 128, 44_100, 16,
                    "Changed", listOf("Artist"), "Album", "Artist", null, null)
            }).scan(listOf(root)).tracks.single()
            Files.write(audio, byteArrayOf(1, 2, 3, 4, 5))
            val result = LocalDuplicateAnalyzer(store).analyze(listOf(track))
            assertEquals(0, result.hashedFiles)
            assertEquals(1, result.failedHashes)
            assertTrue(result.exactDuplicates.isEmpty())
        }
    }

    private fun track(id: String, title: String, duration: Double, album: String): LocalTrack {
        val file = temp.resolve("$id.mp3")
        Files.write(file, byteArrayOf(id.length.toByte()))
        return LocalTrack(
            id = id, pathKey = file.toString().lowercase(), filePath = file.toString(),
            fileSize = Files.size(file), lastModifiedMillis = Files.getLastModifiedTime(file).toMillis(),
            format = LocalAudioFormat.MP3, codec = "MP3", lossiness = AudioLossiness.LOSSY,
            durationSeconds = duration, bitrateKbps = 128, sampleRateHz = 44_100, bitDepth = null,
            title = title, titleSource = LocalTitleSource.TAG, artists = listOf("Artist"), album = album,
            albumArtist = "Artist", trackNumber = 1, discNumber = 1, metadataStatus = LocalMetadataStatus.READABLE,
        )
    }
}
