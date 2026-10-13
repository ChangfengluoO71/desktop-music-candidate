package com.qing.hachimi.desktop.libmpv

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LibMpvLibraryLocatorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `accepts an explicit existing absolute libmpv dll`() {
        val dll = tempDir.resolve("custom-runtime").resolve("libmpv-2.dll")
        Files.createDirectories(dll.parent)
        Files.write(dll, byteArrayOf(1))

        assertEquals(dll.toRealPath(), LibMpvLibraryLocator.locate(dll.toString(), tempDir.resolve("app-data")))
    }

    @Test
    fun `rejects relative explicit library paths`() {
        assertFailsWith<IllegalArgumentException> {
            LibMpvLibraryLocator.locate("runtime/libmpv-2.dll", tempDir.resolve("app-data"))
        }
    }

    @Test
    fun `accepts only the known app private library location when no explicit path is given`() {
        val appData = tempDir.resolve("profile")
        val privateDll = appData.resolve("libmpv").resolve("libmpv-2.dll")
        Files.createDirectories(privateDll.parent)
        Files.write(privateDll, byteArrayOf(1))

        assertEquals(privateDll.toRealPath(), LibMpvLibraryLocator.locate(null, appData))
    }

    @Test
    fun `does not search arbitrary path entries when private library is missing`() {
        val appData = tempDir.resolve("empty-profile")

        val failure = assertFailsWith<LibMpvUnavailableException> {
            LibMpvLibraryLocator.locate(null, appData)
        }

        assertTrue(failure.userMessage.contains("libmpv-2.dll"))
        assertTrue(Files.notExists(appData.resolve("libmpv").resolve("libmpv-2.dll")))
    }

    @Test
    fun `rejects a different native library name`() {
        val wrong = tempDir.resolve("mpv.dll")
        Files.write(wrong, byteArrayOf(1))

        assertFailsWith<IllegalArgumentException> {
            LibMpvLibraryLocator.locate(wrong.toString(), tempDir.resolve("app-data"))
        }
    }
}
