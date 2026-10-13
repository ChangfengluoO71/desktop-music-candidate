package com.qing.hachimi.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class CollectionDirectoryTest {
    @Test
    fun playlistGroupingSanitizesWindowsNamesAndStaysUnderRoot() {
        val root = Files.createTempDirectory("music-root")

        val directory = FileNamePolicy.groupedDirectory(
            root,
            FolderGrouping.PLAYLIST,
            "..\\CON\\Playlists: <2026>",
            SongHit(1, "Song", "Artist", "Album"),
        )

        assertTrue(directory.toRealPath().startsWith(root.toRealPath()))
        assertTrue(Files.isDirectory(directory))
        assertTrue(directory.toString().contains("_CON"))
    }

    @Test
    fun artistAlbumGroupingUsesSanitizedComponents() {
        val root = Files.createTempDirectory("album-root")

        val directory = FileNamePolicy.groupedDirectory(
            root,
            FolderGrouping.ARTIST_ALBUM,
            "ignored",
            SongHit(2, "Song", "CON", "Album/Disc"),
        )

        assertTrue(directory.toRealPath().startsWith(root.toRealPath()))
        assertTrue(directory.toString().contains("CON - Album_Disc"))
    }
}
