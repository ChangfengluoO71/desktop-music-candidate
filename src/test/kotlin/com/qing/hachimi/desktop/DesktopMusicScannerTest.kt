package com.qing.hachimi.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopMusicScannerTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `empty folder produces an empty persistent index`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("empty"))
        LocalMusicStore(temp.resolve("library.db")).use { store ->
            val result = DesktopMusicScanner(store, EmptyMetadataReader).scan(listOf(root))
            assertTrue(result.tracks.isEmpty())
            assertEquals(0, result.scannedFiles)
            assertEquals(root.toRealPath().toString(), store.loadRoots().single().path)
        }
    }

    @Test
    fun `reads mp3 metadata and never modifies the audio file`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("music"))
        val song = copyFixture("synthetic-tone.mp3", root.resolve("Tone.mp3"))
        val before = sha256(song)
        LocalMusicStore(temp.resolve("library.db")).use { store ->
            val result = DesktopMusicScanner(store).scan(listOf(root))
            val track = result.tracks.single()
            assertEquals(LocalAudioFormat.MP3, track.format)
            assertTrue(track.metadataStatus != LocalMetadataStatus.DAMAGED)
            assertEquals(Files.size(song), track.fileSize)
            assertNotNull(track.codec)
            assertTrue(track.durationSeconds != null && track.durationSeconds > 0.0)
            assertTrue(track.bitrateKbps != null && track.bitrateKbps > 0)
            assertTrue(track.sampleRateHz != null && track.sampleRateHz > 0)
            assertEquals(before, sha256(song))
        }
    }

    @Test
    fun `reads tagged AAC in a valid M4A container without modifying it`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("m4a"))
        val song = copyFixture("synthetic-tagged-aac.m4a", root.resolve("fixture.m4a"))
        val before = sha256(song)

        LocalMusicStore(temp.resolve("m4a-library.db")).use { store ->
            val result = DesktopMusicScanner(store).scan(listOf(root))
            val track = result.tracks.single()

            assertEquals(LocalAudioFormat.M4A, track.format)
            assertEquals(LocalMetadataStatus.READABLE, track.metadataStatus)
            assertEquals("Hachimi M4A Fixture", track.title)
            assertEquals(listOf("Fixture Artist"), track.artists)
            assertEquals("Fixture Album", track.album)
            assertEquals("Fixture Artist", track.albumArtist)
            assertTrue(track.codec?.isNotBlank() == true)
            assertTrue(track.durationSeconds != null && track.durationSeconds in 1.9..2.1)
            assertTrue(track.sampleRateHz != null && track.sampleRateHz > 0)
            assertTrue(track.bitrateKbps != null && track.bitrateKbps > 0)
            assertEquals(before, sha256(song))
        }
    }

    @Test
    fun `damaged M4A is isolated from readable audio`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("damaged-m4a"))
        val damaged = copyFixture("synthetic-damaged.m4a", root.resolve("broken.m4a"))
        copyFixture("synthetic-tagged-aac.m4a", root.resolve("good.m4a"))
        val before = sha256(damaged)

        LocalMusicStore(temp.resolve("damaged-m4a-library.db")).use { store ->
            val result = DesktopMusicScanner(store).scan(listOf(root))

            assertEquals(2, result.tracks.size)
            assertEquals(LocalMetadataStatus.DAMAGED, result.tracks.single { it.filePath.endsWith("broken.m4a") }.metadataStatus)
            assertEquals(LocalScanErrorKind.DAMAGED_AUDIO, result.tracks.single { it.filePath.endsWith("broken.m4a") }.error?.kind)
            assertEquals(LocalMetadataStatus.READABLE, result.tracks.single { it.filePath.endsWith("good.m4a") }.metadataStatus)
            assertEquals(before, sha256(damaged))
        }
    }

    @Test
    fun `scans mixed known and unsupported audio formats honestly`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("mixed"))
        copyFixture("synthetic-tone.mp3", root.resolve("tone.mp3"))
        copyFixture("synthetic-tone.flac", root.resolve("tone.flac"))
        Files.write(root.resolve("raw.aac"), byteArrayOf(1, 2, 3, 4))
        Files.write(root.resolve("compressed.ogg"), byteArrayOf(5, 6, 7))
        createWave(root.resolve("pcm.wav"))

        LocalMusicStore(temp.resolve("library.db")).use { store ->
            val result = DesktopMusicScanner(store).scan(listOf(root))
            assertEquals(5, result.tracks.size)
            assertEquals(setOf(LocalAudioFormat.MP3, LocalAudioFormat.FLAC, LocalAudioFormat.WAV, LocalAudioFormat.AAC, LocalAudioFormat.OTHER),
                result.tracks.map { it.format }.toSet())
            assertEquals(2, result.unsupportedFiles)
            assertTrue(result.tracks.filter { it.format in setOf(LocalAudioFormat.AAC, LocalAudioFormat.OTHER) }
                .all { it.metadataStatus == LocalMetadataStatus.UNSUPPORTED })
        }
    }

    @Test
    fun `damaged audio is isolated and reported`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("damaged"))
        Files.write(root.resolve("bad.mp3"), "not an audio file".toByteArray())
        copyFixture("synthetic-tone.mp3", root.resolve("good.mp3"))
        LocalMusicStore(temp.resolve("library.db")).use { store ->
            val result = DesktopMusicScanner(store).scan(listOf(root))
            assertEquals(2, result.tracks.size)
            assertEquals(LocalMetadataStatus.DAMAGED, result.tracks.single { it.filePath.endsWith("bad.mp3") }.metadataStatus)
            assertEquals(LocalScanErrorKind.DAMAGED_AUDIO, result.tracks.single { it.filePath.endsWith("bad.mp3") }.error?.kind)
            assertEquals(1, result.tracks.count { it.metadataStatus != LocalMetadataStatus.DAMAGED })
        }
    }

    @Test
    fun `unchanged files reuse parsed metadata and filename fallback is marked weak`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("incremental"))
        Files.write(root.resolve("Track name - Artist.mp3"), byteArrayOf(1, 2, 3))
        val reader = CountingMetadataReader(EmptyMetadataReader)
        LocalMusicStore(temp.resolve("library.db")).use { store ->
            val scanner = DesktopMusicScanner(store, reader)
            val first = scanner.scan(listOf(root)).tracks.single()
            val second = scanner.scan(listOf(root))
            assertEquals(1, reader.reads)
            assertEquals(1, second.reusedMetadata)
            assertEquals("Track name", first.title)
            assertEquals(LocalTitleSource.FILENAME, first.titleSource)
            assertEquals(LocalMetadataStatus.PARTIAL, first.metadataStatus)
        }
    }

    @Test
    fun `moving and deleting files updates path instances without deleting source data`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("moves"))
        val firstPath = copyFixture("synthetic-tone.mp3", root.resolve("first.mp3"))
        LocalMusicStore(temp.resolve("library.db")).use { store ->
            val scanner = DesktopMusicScanner(store)
            val first = scanner.scan(listOf(root)).tracks.single()
            val moved = root.resolve("renamed.mp3")
            Files.move(firstPath, moved)
            val second = scanner.scan(listOf(root)).tracks.single()
            assertNotEquals(first.id, second.id)
            assertEquals(moved.toRealPath().toString(), second.filePath)
            Files.delete(moved)
            assertTrue(scanner.scan(listOf(root)).tracks.isEmpty())
            assertFalse(Files.exists(moved))
        }
    }

    @Test
    fun `watch changes reparse only touched files and respect overlapping root memberships`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("watch-root"))
        val nested = Files.createDirectory(root.resolve("nested"))
        val untouched = Files.write(root.resolve("untouched.mp3"), byteArrayOf(1, 2, 3))
        val touched = Files.write(nested.resolve("touched.mp3"), byteArrayOf(1, 2, 3))
        val reader = CountingMetadataReader(EmptyMetadataReader)
        LocalMusicStore(temp.resolve("watch-incremental.db")).use { store ->
            val scanner = DesktopMusicScanner(store, reader)
            scanner.scan(listOf(root, nested))
            assertEquals(2, reader.reads)

            Files.write(touched, byteArrayOf(1, 2, 3, 4))
            val change = LocalMusicChange(nested, touched, LocalMusicChangeKind.MODIFIED)
            val result = scanner.applyChanges(listOf(change))
            assertEquals(
                3,
                reader.reads,
                "registeredRoots=${store.loadRoots().map { it.path to normalizedLibraryPath(it.path)}}; " +
                    "eventRoot=${change.root}; eventRootReal=${change.root.toRealPath()}; " +
                    "eventRootKey=${normalizedLibraryPath(change.root.toString())}; path=${change.path}; " +
                    "retries=${result.retryChanges}; scanned=${result.scannedFiles}; failed=${result.failedFiles}",
            )
            assertEquals(2, store.loadTracks().size)

            scanner.applyChanges(listOf(LocalMusicChange(root, touched, LocalMusicChangeKind.DELETED)))
            assertTrue(store.loadTracks().any { it.filePath == touched.toRealPath().toString() })

            Files.delete(touched)
            scanner.applyChanges(listOf(LocalMusicChange(nested, touched, LocalMusicChangeKind.DELETED)))
            assertFalse(store.loadTracks().any { it.filePath == touched.toString() })
            assertTrue(Files.exists(untouched))
            assertEquals(3, reader.reads)
        }
    }

    @Test
    fun `stale watcher events do not recreate a removed root`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("removed-watch-root"))
        val song = Files.write(root.resolve("song.mp3"), byteArrayOf(1, 2, 3))
        LocalMusicStore(temp.resolve("removed-watch-root.db")).use { store ->
            val scanner = DesktopMusicScanner(store, EmptyMetadataReader, stabilitySampleMillis = 1)
            scanner.scan(listOf(root))
            val registeredRoot = store.loadRoots().single()
            store.removeRoot(registeredRoot.path)

            scanner.applyChanges(
                listOf(
                    LocalMusicChange(root, root, LocalMusicChangeKind.RESCAN_ROOT),
                    LocalMusicChange(root, song, LocalMusicChangeKind.MODIFIED),
                ),
            )

            assertTrue(store.loadRoots().isEmpty())
            assertTrue(store.loadTracks().isEmpty())
        }
    }

    @Test
    fun `attribute access failures preserve indexed file and request retry`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("unreadable-watch-root"))
        val song = Files.write(root.resolve("song.mp3"), byteArrayOf(1, 2, 3))
        LocalMusicStore(temp.resolve("unreadable-watch-root.db")).use { store ->
            val initialScanner = DesktopMusicScanner(store, EmptyMetadataReader, stabilitySampleMillis = 1)
            initialScanner.scan(listOf(root))
            val oldTrack = store.loadTracks().single()
            val scanner = DesktopMusicScanner(
                store,
                EmptyMetadataReader,
                stabilitySampleMillis = 1,
                fileAttributesReader = { path ->
                    if (path == song) throw AccessDeniedException(path.toString())
                    Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                },
            )

            val result = scanner.applyChanges(listOf(LocalMusicChange(root, song, LocalMusicChangeKind.MODIFIED)))

            assertEquals(
                1,
                result.retryChanges.size,
                "registeredRoots=${store.loadRoots().map { it.path to normalizedLibraryPath(it.path)}}; " +
                    "eventRoot=$root; eventRootReal=${root.toRealPath()}; " +
                    "eventRootKey=${normalizedLibraryPath(root.toString())}; song=$song; result=$result",
            )
            assertEquals(oldTrack, store.loadTracks().single())
        }
    }

    @Test
    fun `watch create indexes a stable file and unstable writes preserve prior record`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("stable-watch"))
        val song = Files.write(root.resolve("song.mp3"), byteArrayOf(1, 2, 3))
        val reader = CountingMetadataReader(EmptyMetadataReader)
        LocalMusicStore(temp.resolve("stable-watch.db")).use { store ->
            val scanner = DesktopMusicScanner(store, reader, stabilitySampleMillis = 1, stabilityTimeoutMillis = 100)
            scanner.scan(listOf(root))
            val oldTrack = store.loadTracks().single()

            val created = Files.write(root.resolve("new.mp3"), byteArrayOf(9, 8))
            val createChange = LocalMusicChange(root, created, LocalMusicChangeKind.CREATED)
            val createResult = scanner.applyChanges(listOf(createChange))
            assertEquals(
                setOf("song", "new"),
                store.loadTracks().map { it.title.substringBeforeLast('.') }.toSet(),
                "registeredRoots=${store.loadRoots().map { it.path to normalizedLibraryPath(it.path)}}; " +
                    "eventRoot=$root; eventRootReal=${root.toRealPath()}; path=$created; " +
                    "key=${normalizedLibraryPath(created.toString())}; result=$createResult; tracks=${store.loadTracks()}",
            )

            var writeCount = 0
            val unstableScanner = DesktopMusicScanner(
                store, reader, stabilitySampleMillis = 5, stabilityTimeoutMillis = 60,
                stabilityDelay = { millis ->
                    kotlinx.coroutines.delay(millis)
                    val size = 2 + (writeCount++ % 7)
                    Files.write(song, ByteArray(size) { 1 })
                },
            )
            val result = unstableScanner.applyChanges(listOf(LocalMusicChange(root, song, LocalMusicChangeKind.MODIFIED)))
            assertEquals(1, result.retryChanges.size)
            assertEquals(oldTrack.fileSize, store.findTrack(oldTrack.pathKey)?.fileSize)
        }
    }

    @Test
    fun `permission errors are isolated from other files`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("permissions"))
        Files.write(root.resolve("denied.mp3"), byteArrayOf(1, 2, 3))
        Files.write(root.resolve("allowed.mp3"), byteArrayOf(4, 5, 6))
        val reader = LocalAudioMetadataReader { path ->
            if (path.fileName.toString() == "denied.mp3") throw AccessDeniedException(path.toString())
            EmptyMetadataReader.read(path)
        }
        LocalMusicStore(temp.resolve("library.db")).use { store ->
            val result = DesktopMusicScanner(store, reader).scan(listOf(root))
            assertEquals(LocalMetadataStatus.ACCESS_DENIED, result.tracks.single { it.filePath.endsWith("denied.mp3") }.metadataStatus)
            assertEquals(2, result.tracks.size)
        }
    }

    @Test
    fun `directory link back to root does not loop`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("links"))
        Files.write(root.resolve("one.mp3"), byteArrayOf(1, 2, 3))
        val link = root.resolve("loop")
        try {
            Files.createSymbolicLink(link, root)
        } catch (_: Exception) {
            assumeTrue(false, "Windows does not permit creating a symbolic link in this environment")
        }
        LocalMusicStore(temp.resolve("library.db")).use { store ->
            val result = DesktopMusicScanner(store, EmptyMetadataReader).scan(listOf(root))
            assertEquals(1, result.tracks.size)
            assertEquals(1, result.scannedFiles)
        }
    }

    @Test
    fun `large directory scan streams file paths and returns progress`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("large"))
        repeat(250) { index -> Files.write(root.resolve("track-$index.mp3"), byteArrayOf(1, 2, 3)) }
        var lastProgress: LocalScanProgress? = null
        LocalMusicStore(temp.resolve("library.db")).use { store ->
            val result = DesktopMusicScanner(store, EmptyMetadataReader).scan(listOf(root)) { lastProgress = it }
            assertEquals(250, result.tracks.size)
            assertEquals(250, result.scannedFiles)
            assertEquals(250, lastProgress?.completedFiles)
            assertEquals(250, lastProgress?.totalFiles)
        }
    }

    @Test
    fun `scan cancellation is observed between files`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("cancel"))
        repeat(80) { index -> Files.write(root.resolve("track-$index.mp3"), byteArrayOf(1, 2, 3)) }
        val slowReader = LocalAudioMetadataReader {
            Thread.sleep(15)
            EmptyMetadataReader.read(it)
        }
        LocalMusicStore(temp.resolve("library.db")).use { store ->
            val job = launch(Dispatchers.IO) { DesktopMusicScanner(store, slowReader).scan(listOf(root)) }
            delay(100)
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
    }

    @Test
    fun `missing scan roots are classified without losing existing database`() = runBlocking {
        LocalMusicStore(temp.resolve("library.db")).use { store ->
            val result = DesktopMusicScanner(store, EmptyMetadataReader).scan(listOf(temp.resolve("gone")))
            assertEquals(LocalScanErrorKind.ROOT_MISSING, result.rootErrors.single().kind)
            assertTrue(result.tracks.isEmpty())
        }
    }

    private fun copyFixture(name: String, target: Path): Path {
        javaClass.getResourceAsStream("/audio-fixtures/$name")!!.use { input -> Files.copy(input, target) }
        return target
    }

    private fun createWave(path: Path) {
        val sampleRate = 44_100
        val sampleCount = 4_410
        val dataSize = sampleCount * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        buffer.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataSize)
        repeat(sampleCount) { buffer.putShort(0) }
        Files.write(path, buffer.array())
    }

    private fun sha256(path: Path): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private object EmptyMetadataReader : LocalAudioMetadataReader {
        override fun read(path: Path) = LocalAudioMetadata(
            codec = "MP3", lossiness = AudioLossiness.LOSSY, durationSeconds = null,
            bitrateKbps = null, sampleRateHz = null, bitDepth = null, title = "",
            artists = emptyList(), album = "", albumArtist = "", trackNumber = null, discNumber = null,
        )
    }

    private class CountingMetadataReader(private val delegate: LocalAudioMetadataReader) : LocalAudioMetadataReader {
        var reads = 0
        override fun read(path: Path): LocalAudioMetadata {
            reads++
            return delegate.read(path)
        }
    }
}
