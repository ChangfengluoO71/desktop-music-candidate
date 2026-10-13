package com.qing.hachimi.desktop

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalMusicDirectoryWatcherTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `watches registered root and nested folders with burst debounce`() {
        val root = Files.createDirectory(temp.resolve("music"))
        val nested = Files.createDirectory(root.resolve("Album"))
        val latch = CountDownLatch(1)
        val events = AtomicInteger()
        val watcher = LocalMusicDirectoryWatcher(
            debounceMillis = 150,
            onChange = { changed ->
                assertEquals(root.toRealPath(), changed.toRealPath())
                events.incrementAndGet()
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
            assertEquals(1, events.get())
        } finally {
            watcher.close()
        }
    }

    @Test
    fun `new nested directories are registered for later changes`() {
        val root = Files.createDirectory(temp.resolve("music"))
        val latch = CountDownLatch(1)
        val watcher = LocalMusicDirectoryWatcher(debounceMillis = 50) { latch.countDown() }
        try {
            watcher.start(listOf(root))
            val nested = Files.createDirectory(root.resolve("new-album"))
            Thread.sleep(150)
            Files.write(nested.resolve("song.mp3"), byteArrayOf(1))

            assertTrue(latch.await(3, TimeUnit.SECONDS))
        } finally {
            watcher.close()
        }
    }
}
