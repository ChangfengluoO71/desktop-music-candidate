package com.qing.hachimi.desktop

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.WatchEvent
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalMusicDirectoryWatcherTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `watches registered root and nested folders with burst debounce`() {
        val root = Files.createDirectory(temp.resolve("music"))
        val nested = Files.createDirectory(root.resolve("Album"))
        val latch = CountDownLatch(3)
        val events = CopyOnWriteArrayList<LocalMusicChange>()
        val watcher = LocalMusicDirectoryWatcher(
            debounceMillis = 150,
            onChange = { change ->
                assertEquals(root.toRealPath(), change.root.toRealPath())
                events += change
                latch.countDown()
            },
        )
        try {
            watcher.start(listOf(root))
            Files.write(root.resolve("one.mp3"), byteArrayOf(1))
            Files.write(root.resolve("two.mp3"), byteArrayOf(2))
            Files.write(nested.resolve("three.flac"), byteArrayOf(3))

            assertTrue(latch.await(3, TimeUnit.SECONDS))
            Thread.sleep(300)
            val fileEvents = events.filter { it.path.fileName.toString().contains('.') }
            assertEquals(setOf("one.mp3", "two.mp3", "three.flac"), fileEvents.map { it.path.fileName.toString() }.toSet())
            assertTrue(fileEvents.all { it.kind == LocalMusicChangeKind.CREATED })
        } finally {
            watcher.close()
        }
    }

    @Test
    fun `new nested directories are registered for later changes`() {
        val root = Files.createDirectory(temp.resolve("music"))
        val latch = CountDownLatch(1)
        val changes = CopyOnWriteArrayList<LocalMusicChange>()
        val watcher = LocalMusicDirectoryWatcher(debounceMillis = 50) { change -> changes += change; latch.countDown() }
        try {
            watcher.start(listOf(root))
            val nested = Files.createDirectory(root.resolve("new-album"))
            Thread.sleep(150)
            Files.write(nested.resolve("song.mp3"), byteArrayOf(1))

            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertTrue(changes.any { it.kind == LocalMusicChangeKind.RESCAN_ROOT || it.path.fileName.toString() == "song.mp3" })
        } finally {
            watcher.close()
        }
    }

    @Test
    fun `root recovery emits a root rescan event`() {
        val root = Files.createDirectory(temp.resolve("recovery"))
        val latch = CountDownLatch(1)
        var change: LocalMusicChange? = null
        val watcher = LocalMusicDirectoryWatcher(debounceMillis = 10) { value -> change = value; latch.countDown() }
        try {
            watcher.start(listOf(root))
            watcher.scheduleRootRescan(root)
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals(LocalMusicChangeKind.RESCAN_ROOT, change?.kind)
            assertEquals(root.toRealPath(), change?.root?.toRealPath())
        } finally {
            watcher.close()
        }
    }

    @Test
    fun `overflow and invalid watch keys request a full root rescan`() {
        val root = Files.createDirectory(temp.resolve("overflow"))
        val changes = CopyOnWriteArrayList<LocalMusicChange>()
        val watcher = LocalMusicDirectoryWatcher(debounceMillis = 10) { changes += it }
        val overflow = object : WatchEvent<Any> {
            override fun kind(): WatchEvent.Kind<Any> = OVERFLOW as WatchEvent.Kind<Any>
            override fun count(): Int = 1
            override fun context(): Any? = null
        }
        try {
            watcher.start(listOf(root))
            watcher.processWatchEvents(root, listOf(overflow), keyIsValid = true)
            val overflowEvent = awaitRescan(
                changes,
                "inputRoot=$root; realRoot=${root.toRealPath()}; inputKey=${normalizedLibraryPath(root.toString())}",
            )
            assertEquals(LocalMusicChangeKind.RESCAN_ROOT, overflowEvent.kind)

            changes.clear()
            watcher.processWatchEvents(root, emptyList(), keyIsValid = false)
            val invalidKeyEvent = awaitRescan(
                changes,
                "inputRoot=$root; realRoot=${root.toRealPath()}; inputKey=${normalizedLibraryPath(root.toString())}",
            )
            assertEquals(LocalMusicChangeKind.RESCAN_ROOT, invalidKeyEvent.kind)
        } finally {
            watcher.close()
        }
    }

    @Test
    fun `missing watched root is reconnected and rescanned when it appears`() {
        val root = temp.resolve("late-root")
        val latch = CountDownLatch(1)
        val changes = CopyOnWriteArrayList<LocalMusicChange>()
        val watcher = LocalMusicDirectoryWatcher(debounceMillis = 10, reconnectCheckMillis = 25) {
            changes += it
            latch.countDown()
        }
        try {
            watcher.start(listOf(root))
            Files.createDirectory(root)

            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertTrue(changes.any { it.kind == LocalMusicChangeKind.RESCAN_ROOT })
        } finally {
            watcher.close()
        }
    }

    private fun awaitRescan(changes: CopyOnWriteArrayList<LocalMusicChange>, diagnostic: String): LocalMusicChange {
        repeat(100) {
            changes.firstOrNull { it.kind == LocalMusicChangeKind.RESCAN_ROOT }?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("Expected a full-root rescan event: $diagnostic; captured=$changes")
    }
}
