package com.qing.hachimi.desktop

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocalArtworkResolverTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `prefers embedded image over sibling folder cover`() {
        val trackPath = temp.resolve("song.mp3")
        Files.write(trackPath, byteArrayOf(1))
        Files.write(temp.resolve("cover.jpg"), pngBytes())
        val resolver = LocalArtworkResolver(embeddedArtworkReader = { pngBytes() })

        val cover = resolver.resolve(trackPath)

        assertNotNull(cover)
        assertEquals("image/png", cover.mimeType)
    }

    @Test
    fun `falls back to folder cover and rejects non image payload`() {
        val trackPath = temp.resolve("song.mp3")
        Files.write(trackPath, byteArrayOf(1))
        Files.write(temp.resolve("front.jpg"), byteArrayOf(1, 2, 3))
        Files.write(temp.resolve("folder.png"), pngBytes())

        val cover = LocalArtworkResolver(embeddedArtworkReader = { null }).resolve(trackPath)

        assertNotNull(cover)
        assertEquals("image/png", cover.mimeType)
    }

    @Test
    fun `never scans over size limit`() {
        val trackPath = temp.resolve("song.mp3")
        Files.write(trackPath, byteArrayOf(1))
        Files.write(temp.resolve("cover.png"), ByteArray(100))

        assertNull(LocalArtworkResolver({ null }, maxImageBytes = 50).resolve(trackPath))
    }

    private fun pngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0, 0, 0, 0,
    )
}
