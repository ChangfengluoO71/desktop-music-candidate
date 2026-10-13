package com.qing.hachimi.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalMusicStoreTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `roots tracks and metadata survive store restart`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("music"))
        javaClass.getResourceAsStream("/audio-fixtures/synthetic-tone.mp3")!!.use {
            Files.copy(it, root.resolve("tone.mp3"))
        }
        val database = temp.resolve("library.db")
        LocalMusicStore(database).use { store ->
            DesktopMusicScanner(store).scan(listOf(root))
        }

        LocalMusicStore(database).use { restored ->
            assertEquals(root.toRealPath().toString(), restored.loadRoots().single().path)
            assertEquals("tone", restored.loadTracks().single().title)
            assertTrue(restored.loadTracks().single().fileSize > 0)
        }
    }

    @Test
    fun `removing a root removes only its index and leaves audio intact`() = runBlocking {
        val root = Files.createDirectory(temp.resolve("remove-root"))
        javaClass.getResourceAsStream("/audio-fixtures/synthetic-tone.mp3")!!.use {
            Files.copy(it, root.resolve("tone.mp3"))
        }
        val audio = root.resolve("tone.mp3")
        LocalMusicStore(temp.resolve("library.db")).use { store ->
            DesktopMusicScanner(store).scan(listOf(root))
            store.removeRoot(root.toRealPath().toString())
            assertTrue(store.loadTracks().isEmpty())
            assertFalse(store.loadRoots().any { it.path == root.toRealPath().toString() })
            assertTrue(Files.exists(audio))
        }
    }

    @Test
    fun `corrupt index is preserved and a fresh index can start`() {
        val database = temp.resolve("library.db")
        Files.writeString(database, "not a sqlite database")
        LocalMusicStore(database).use { store ->
            assertNotNull(store.recoveryWarning)
            assertTrue(store.loadTracks().isEmpty())
            assertTrue(Files.list(temp).use { paths -> paths.anyMatch { it.fileName.toString().startsWith("library.db.corrupt.") } })
        }
    }
}
