package com.qing.hachimi.desktop

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LocalMusicPathNormalizationTest {
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
}
