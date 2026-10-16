package com.qing.hachimi.desktop

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalArtworkRepositoryTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `concurrent requests share one artwork extraction and cached thumbnail`() = runBlocking {
        val audio = audioFile("concurrent.mp3")
        val calls = AtomicInteger()
        val extractionStarted = CompletableDeferred<Unit>()
        val releaseExtraction = CompletableDeferred<Unit>()
        val resolver = LocalArtworkResolver(embeddedArtworkReader = {
            calls.incrementAndGet()
            extractionStarted.complete(Unit)
            runBlocking { releaseExtraction.await() }
            png()
        })
        val repository = LocalArtworkRepository(
            resolver = resolver,
            decoder = ArtworkThumbnailDecoder { _, _ -> ImageBitmap(8, 8) },
        )
        try {
            coroutineScope {
                val first = async { repository.thumbnail(track(audio), 96) }
                extractionStarted.await()
                val second = async { repository.thumbnail(track(audio), 96) }
                releaseExtraction.complete(Unit)
                assertEquals(8, first.await()?.width)
                assertEquals(8, second.await()?.width)
            }
            repository.thumbnail(track(audio), 96)
            assertEquals(1, calls.get())
        } finally {
            repository.close()
        }
    }

    @Test
    fun `file identity change invalidates cached artwork`() = runBlocking {
        val audio = audioFile("identity.mp3")
        val calls = AtomicInteger()
        val repository = repository(audio, calls)
        try {
            assertEquals(1, repository.thumbnail(track(audio), 96)?.width)
            Files.write(audio, byteArrayOf(1, 2, 3, 4))
            Files.setLastModifiedTime(audio, FileTime.fromMillis(Files.getLastModifiedTime(audio).toMillis() + 5_000))

            assertEquals(1, repository.thumbnail(track(audio), 96)?.width)
            assertEquals(2, calls.get())
        } finally {
            repository.close()
        }
    }

    @Test
    fun `explicit invalidation evicts the track and sibling artwork cache`() = runBlocking {
        val first = audioFile("first.mp3")
        val sibling = audioFile("second.mp3")
        val calls = AtomicInteger()
        val repository = LocalArtworkRepository(
            resolver = LocalArtworkResolver(embeddedArtworkReader = { calls.incrementAndGet(); png() }),
            decoder = ArtworkThumbnailDecoder { _, _ -> ImageBitmap(2, 2) },
        )
        try {
            repository.thumbnail(track(first), 96)
            repository.thumbnail(track(sibling), 96)
            repository.invalidate(first)
            repository.thumbnail(track(sibling), 96)
            assertEquals(3, calls.get())
        } finally {
            repository.close()
        }
    }

    @Test
    fun `invalidating one folder preserves unrelated artwork cache`() = runBlocking {
        val firstFolder = Files.createDirectory(temp.resolve("first-folder"))
        val secondFolder = Files.createDirectory(temp.resolve("second-folder"))
        val first = audioFile(firstFolder, "first.mp3")
        val unrelated = audioFile(secondFolder, "unrelated.mp3")
        val calls = AtomicInteger()
        val repository = LocalArtworkRepository(
            resolver = LocalArtworkResolver(embeddedArtworkReader = { calls.incrementAndGet(); png() }),
            decoder = ArtworkThumbnailDecoder { _, _ -> ImageBitmap(2, 2) },
        )
        try {
            repository.thumbnail(track(first), 96)
            repository.thumbnail(track(unrelated), 96)
            repository.invalidate(first)

            repository.thumbnail(track(unrelated), 96)

            assertEquals(2, calls.get())
        } finally {
            repository.close()
        }
    }

    @Test
    fun `missing and malformed artwork return no thumbnail`() = runBlocking {
        val missing = audioFile("missing.mp3")
        val malformed = audioFile("malformed.mp3")
        val repository = LocalArtworkRepository(
            resolver = LocalArtworkResolver(embeddedArtworkReader = { path ->
                if (path.fileName.toString() == "malformed.mp3") byteArrayOf(1, 2, 3) else null
            }),
            decoder = SafeArtworkThumbnailDecoder,
        )
        try {
            assertNull(repository.thumbnail(track(missing), 96))
            assertNull(repository.thumbnail(track(malformed), 96))
        } finally {
            repository.close()
        }
    }

    @Test
    fun `safe decoder rejects images with unsafe dimensions before pixel decode`() {
        val unsafe = pngWithDimensions(width = 50_000, height = 50_000)

        assertNull(SafeArtworkThumbnailDecoder.decode(unsafe, targetEdgePx = 96))
    }

    @Test
    fun `decoded image cache stays within its byte budget using LRU eviction`() = runBlocking {
        val first = audioFile("lru-first.mp3")
        val second = audioFile("lru-second.mp3")
        val calls = AtomicInteger()
        val repository = LocalArtworkRepository(
            resolver = LocalArtworkResolver(embeddedArtworkReader = { calls.incrementAndGet(); png() }),
            decoder = ArtworkThumbnailDecoder { _, _ -> ImageBitmap(2, 2) },
            maxDecodedBytes = 16,
        )
        try {
            repository.thumbnail(track(first), 64)
            repository.thumbnail(track(second), 64)
            assertTrue(repository.cachedDecodedBytes() <= 16)
            repository.thumbnail(track(first), 64)
            assertEquals(3, calls.get())
        } finally {
            repository.close()
        }
    }

    @Test
    fun `backdrop defaults to a bounded 256 pixel edge and shares cache accounting`() = runBlocking {
        val audio = audioFile("backdrop.mp3")
        val calls = AtomicInteger()
        val requestedEdge = AtomicInteger()
        val repository = LocalArtworkRepository(
            resolver = LocalArtworkResolver(embeddedArtworkReader = { calls.incrementAndGet(); png() }),
            decoder = ArtworkThumbnailDecoder { _, edge -> requestedEdge.set(edge); ImageBitmap(edge, edge / 2) },
        )
        try {
            val first = repository.backdrop(track(audio))
            val bytesAfterFirst = repository.cachedDecodedBytes()
            val second = repository.backdrop(track(audio))

            assertEquals(256, requestedEdge.get())
            assertEquals(256, first?.width)
            assertEquals(256, second?.width)
            assertTrue(bytesAfterFirst > 0)
            assertEquals(bytesAfterFirst, repository.cachedDecodedBytes())
            assertEquals(1, calls.get())
        } finally {
            repository.close()
        }
    }

    @Test
    fun `backdrop cache invalidates when source file identity changes`() = runBlocking {
        val audio = audioFile("backdrop-identity.mp3")
        val calls = AtomicInteger()
        val repository = repository(audio, calls)
        try {
            assertEquals(1, repository.backdrop(track(audio))?.width)
            Files.write(audio, byteArrayOf(1, 2, 3, 4))
            Files.setLastModifiedTime(audio, FileTime.fromMillis(Files.getLastModifiedTime(audio).toMillis() + 5_000))

            assertEquals(1, repository.backdrop(track(audio))?.width)
            assertEquals(2, calls.get())
        } finally {
            repository.close()
        }
    }

    @Test
    fun `missing or corrupt artwork returns no backdrop`() = runBlocking {
        val missing = audioFile("missing-backdrop.mp3")
        val malformed = audioFile("malformed-backdrop.mp3")
        val repository = LocalArtworkRepository(
            resolver = LocalArtworkResolver(embeddedArtworkReader = { path ->
                if (path.fileName.toString() == "malformed-backdrop.mp3") byteArrayOf(1, 2, 3) else null
            }),
            decoder = SafeArtworkThumbnailDecoder,
        )
        try {
            assertNull(repository.backdrop(track(missing)))
            assertNull(repository.backdrop(track(malformed)))
        } finally {
            repository.close()
        }
    }

    private fun repository(audio: Path, calls: AtomicInteger) = LocalArtworkRepository(
        resolver = LocalArtworkResolver(embeddedArtworkReader = { calls.incrementAndGet(); png() }),
        decoder = ArtworkThumbnailDecoder { _, _ -> ImageBitmap(1, 1) },
    )

    private fun audioFile(name: String): Path = audioFile(temp, name)

    private fun audioFile(directory: Path, name: String): Path = directory.resolve(name).also { Files.write(it, byteArrayOf(1, 2, 3)) }

    private fun track(path: Path) = LocalTrack(
        id = path.fileName.toString(),
        pathKey = path.toAbsolutePath().normalize().toString().lowercase(),
        filePath = path.toAbsolutePath().normalize().toString(),
        fileSize = Files.size(path),
        lastModifiedMillis = Files.getLastModifiedTime(path).toMillis(),
        format = LocalAudioFormat.MP3,
        codec = "MP3",
        lossiness = AudioLossiness.LOSSY,
        durationSeconds = null,
        bitrateKbps = null,
        sampleRateHz = null,
        bitDepth = null,
        title = path.fileName.toString(),
        titleSource = LocalTitleSource.FILENAME,
        artists = emptyList(),
        album = "Test album",
        albumArtist = "",
        trackNumber = null,
        discNumber = null,
        metadataStatus = LocalMetadataStatus.READABLE,
    )

    private fun png(): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=",
    )

    private fun pngWithDimensions(width: Int, height: Int): ByteArray {
        val bytes = png()
        ByteBuffer.wrap(bytes, 16, 4).order(ByteOrder.BIG_ENDIAN).putInt(width)
        ByteBuffer.wrap(bytes, 20, 4).order(ByteOrder.BIG_ENDIAN).putInt(height)
        val crc = CRC32().apply { update(bytes, 12, 17) }.value.toInt()
        ByteBuffer.wrap(bytes, 29, 4).order(ByteOrder.BIG_ENDIAN).putInt(crc)
        return bytes
    }
}
