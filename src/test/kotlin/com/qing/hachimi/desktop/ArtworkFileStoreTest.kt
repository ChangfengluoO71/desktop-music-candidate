package com.qing.hachimi.desktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtworkFileStoreTest {
    @Test
    fun `song cover matches existing audio stem`() {
        val root = Files.createTempDirectory("hachimi-cover-song")
        val audio = root.resolve("Artist - Song [42].flac")

        val cover = ArtworkDestinationResolver.songCoverPath(
            SongHit(42, "Song", "Artist", "Album"), audio,
            DesktopSettings(defaultDownloadDirectory = root.toString()), null, ".png",
        )

        assertEquals("Artist - Song [42].png", cover.fileName.toString())
        assertEquals(root, cover.parent)
    }

    @Test
    fun `predicted song path uses configured grouping`() {
        val root = Files.createTempDirectory("hachimi-cover-grouping")
        val song = SongHit(42, "Song", "Artist", "Album")

        val cover = ArtworkDestinationResolver.songCoverPath(
            song, null,
            DesktopSettings(defaultDownloadDirectory = root.toString(), folderGrouping = FolderGrouping.ARTIST_ALBUM),
            null, ".jpg",
        )

        assertEquals("Artist - Album", cover.parent.fileName.toString())
        assertTrue(cover.fileName.toString().startsWith("Artist - Song [42]"))
    }

    @Test
    fun `custom cover directory keeps song stem`() {
        val media = Files.createTempDirectory("hachimi-cover-media")
        val custom = Files.createTempDirectory("hachimi-cover-custom")
        val cover = ArtworkDestinationResolver.songCoverPath(
            SongHit(42, "Song", "Artist", "Album"), media.resolve("Artist - Song [42].mp3"),
            DesktopSettings(
                defaultDownloadDirectory = media.toString(),
                coverDirectoryMode = CoverDirectoryMode.CUSTOM_DIRECTORY,
                customCoverDirectory = custom.toString(),
            ),
            null, ".webp",
        )

        assertEquals(custom, cover.parent)
        assertEquals("Artist - Song [42].webp", cover.fileName.toString())
    }

    @Test
    fun `album cover uses sanitized album title`() {
        val root = Files.createTempDirectory("hachimi-cover-album")

        val cover = ArtworkDestinationResolver.albumCoverPath(
            "../Album?", "CON", DesktopSettings(defaultDownloadDirectory = root.toString()), null, ".jpg",
        )

        assertEquals(root.toRealPath(), cover.parent.toRealPath())
        assertFalse(cover.fileName.toString().contains("/"))
        assertTrue(cover.fileName.toString().endsWith(".jpg"))
        assertFalse(cover.fileName.toString().contains("?"))
    }

    @Test
    fun `album cover follows an existing downloaded track directory`() {
        val root = Files.createTempDirectory("hachimi-cover-existing-album")
        val audio = root.resolve("Playlists").resolve("My Album").resolve("Track.mp3")

        val cover = ArtworkDestinationResolver.albumCoverPath(
            "Album", "Artist", DesktopSettings(defaultDownloadDirectory = root.resolve("default").toString()),
            "My Album", ".png", audio,
        )

        assertEquals(audio.parent, cover.parent)
        assertEquals("Artist - Album.png", cover.fileName.toString())
    }

    @Test
    fun `reserved and traversal names stay under root`() {
        val root = Files.createTempDirectory("hachimi-cover-safe-root")
        val song = SongHit(42, "..\\..\\outside", "NUL", "Album")

        val cover = ArtworkDestinationResolver.songCoverPath(
            song, null, DesktopSettings(defaultDownloadDirectory = root.toString()), null, ".png",
        )

        assertEquals(root.toRealPath(), cover.parent.toRealPath())
        assertFalse(cover.fileName.toString().contains("..\\"))
        assertTrue(cover.fileName.toString().contains("_NUL"))
    }

    @Test
    fun `identical cover is reused`() {
        val root = Files.createTempDirectory("hachimi-cover-reuse")
        val destination = root.resolve("cover.png")
        val payload = pngPayload()
        val store = ArtworkFileStore()

        val first = store.save(payload, destination)
        val second = store.save(payload.copy(bytes = payload.bytes.copyOf()), destination)

        assertEquals(first, second)
        assertEquals(1, Files.list(root).use { it.count() })
    }

    @Test
    fun `different same-name cover gets a suffix`() {
        val root = Files.createTempDirectory("hachimi-cover-conflict")
        val destination = root.resolve("cover.png")
        val store = ArtworkFileStore()

        val first = store.save(pngPayload(), destination)
        val second = store.save(pngPayload(byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 2,
        )), destination)

        assertEquals("cover.png", first.fileName.toString())
        assertEquals("cover (2).png", second.fileName.toString())
    }

    @Test
    fun `failed atomic write leaves no final or temp file`() {
        val root = Files.createTempDirectory("hachimi-cover-failed-write")
        val destination = root.resolve("cover.png")
        val store = ArtworkFileStore { _, _ -> throw IOException("synthetic write failure") }

        runCatching { store.save(pngPayload(), destination) }

        assertFalse(Files.exists(destination))
        assertEquals(0, Files.list(root).use { it.count() })
    }

    private fun pngPayload(bytes: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1,
    )) = ArtworkPayload(bytes, "image/png", ".png")
}
