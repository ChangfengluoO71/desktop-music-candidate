package com.qing.hachimi.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.nio.file.Path

class FileNamePolicyTest {
    @Test fun removesWindowsMetacharacters() {
        val name = FileNamePolicy.component("a/b:c*?<x>|\\y")
        assertFalse(name.contains('/'))
        assertFalse(name.contains(':'))
        assertFalse(name.contains('*'))
        assertFalse(name.contains('?'))
        assertFalse(name.contains('\\'))
        assertTrue(name.isNotBlank())
    }
    @Test fun rejectsReservedNames() {
        assertEquals("_CON", FileNamePolicy.component("CON"))
        assertEquals("_LPT1.txt", FileNamePolicy.component("LPT1.txt"))
        assertEquals("unknown", FileNamePolicy.component("  .. "))
    }
    @Test fun doesNotTrustRemoteExtensions() {
        assertEquals(".flac", FileNamePolicy.extension("FLAC"))
        assertEquals(".audio", FileNamePolicy.extension("exe"))
    }

    @Test fun mapsOnlyVerifiedArtworkMimeTypesToImageExtensions() {
        assertEquals(".jpg", FileNamePolicy.imageExtension("image/jpeg"))
        assertEquals(".png", FileNamePolicy.imageExtension("image/png"))
        assertEquals(".webp", FileNamePolicy.imageExtension("image/webp"))
        assertEquals(null, FileNamePolicy.imageExtension("image/svg+xml"))
        assertEquals(null, FileNamePolicy.imageExtension("application/octet-stream"))
    }

    @Test fun replacesPlaceholderExtensionWithoutDiscardingSongName() {
        val target = FileNamePolicy.withExtension(
            Path.of("C:/downloads/Test Artist - Track [123].audio"),
            "wav",
        )
        assertEquals("Test Artist - Track [123].wav", target.fileName.toString())
    }

    @Test fun sanitizesTraversalAndTrailingDotAfterTruncation() {
        val folder = Path.of("C:/downloads").toAbsolutePath().normalize()
        val target = FileNamePolicy.destination(
            folder,
            SongHit(17, "..\\..\\outside.", "../../artist", "album"),
            "mp3",
        )
        assertEquals(folder, target.parent)
        assertFalse(target.fileName.toString().contains("..\\"))
        assertFalse(FileNamePolicy.component("a".repeat(90) + ".").endsWith('.'))
    }

    @Test fun limitsGeneratedWindowsPathsAndRejectsAnOverlongDirectory() {
        val folder = java.nio.file.Files.createTempDirectory("long-path")
        val longSong = SongHit(88, "Title ".repeat(70), "Artist ".repeat(70), "Album")
        val destination = FileNamePolicy.destination(folder, longSong, "flac")

        assertTrue(destination.toString().length <= 240)
        assertTrue(destination.fileName.toString().contains("[88]"))
        assertFailsWith<IllegalArgumentException> {
            FileNamePolicy.destination(folder.resolve("x".repeat(240)), longSong, "flac")
        }
    }
}
