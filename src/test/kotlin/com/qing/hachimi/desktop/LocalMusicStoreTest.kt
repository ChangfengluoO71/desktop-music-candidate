package com.qing.hachimi.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
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

    @Test
    fun `per root monitoring preference persists across restarts`() {
        val root = Files.createDirectory(temp.resolve("watched"))
        val database = temp.resolve("library.db")
        val path = LocalMusicStore(database).use { store ->
            val registered = store.registerRoot(root)
            assertFalse(registered.autoWatch)
            store.setRootAutoWatch(registered.path, true)
            registered.path
        }

        LocalMusicStore(database).use { store ->
            assertTrue(store.loadRoots().single { it.path == path }.autoWatch)
        }
    }

    @Test
    fun `legacy roots table receives disabled auto watch default`() {
        val root = Files.createDirectory(temp.resolve("legacy"))
        val database = temp.resolve("legacy.db")
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE roots(root_path TEXT PRIMARY KEY, added_at INTEGER NOT NULL)")
                connection.prepareStatement("INSERT INTO roots(root_path, added_at) VALUES(?, 1)").use { insert ->
                    insert.setString(1, root.toString())
                    insert.executeUpdate()
                }
            }
        }

        LocalMusicStore(database).use { store ->
            assertEquals(root.toString(), store.loadRoots().single().path)
            assertFalse(store.loadRoots().single().autoWatch)
        }
    }
}
