package com.qing.hachimi.desktop

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class LocalMusicPathNormalizationTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `extended drive path matches ordinary drive path`() {
        assertEquals("d:/music/song.mp3", normalizedLibraryPath("\\\\?\\D:\\Music\\Song.mp3"))
    }

    @Test
    fun `extended UNC path matches ordinary UNC path`() {
        assertEquals(
            normalizedLibraryPath("\\\\server\\share\\Music"),
            normalizedLibraryPath("\\\\?\\UNC\\server\\share\\Music"),
        )
    }

    @Test
    fun `UNC prefix is preserved while repeated separators are collapsed`() {
        assertEquals("//server/share/music", normalizedLibraryPath("\\\\server\\\\share\\Music\\"))
    }

    @Test
    fun `existing and missing paths through a symbolic alias normalize to canonical paths`() {
        val realDirectory = Files.createDirectory(temp.resolve("real-directory"))
        val aliasDirectory = temp.resolve("directory-alias")
        try {
            Files.createSymbolicLink(aliasDirectory, realDirectory)
        } catch (failure: Exception) {
            assumeTrue(false, "Directory symbolic links are unavailable: ${failure.message}")
        }

        assertEquals(normalizedLibraryPath(realDirectory), normalizedLibraryPath(aliasDirectory))
        assertEquals(
            normalizedLibraryPath(realDirectory.resolve("deleted.mp3")),
            normalizedLibraryPath(aliasDirectory.resolve("deleted.mp3")),
        )
    }
}
