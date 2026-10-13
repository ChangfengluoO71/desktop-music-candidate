package com.qing.hachimi.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopInstanceLockTest {
    @Test
    fun `only one live owner can acquire the same lock`() {
        val root = Files.createTempDirectory("hachimi-instance-lock")
        val path = root.resolve("instance.lock")
        val first = DesktopInstanceLock.tryAcquire(path)
        assertNotNull(first)
        try {
            assertNull(DesktopInstanceLock.tryAcquire(path))
        } finally {
            first.close()
        }
        val third = DesktopInstanceLock.tryAcquire(path)
        assertNotNull(third)
        third.close()
    }

    @Test
    fun `stale lock file does not block a new process`() {
        val root = Files.createTempDirectory("hachimi-instance-lock-stale")
        val path = root.resolve("instance.lock")
        Files.writeString(path, "left behind by a previous process")

        val lock = DesktopInstanceLock.tryAcquire(path)

        assertNotNull(lock)
        assertTrue(Files.exists(path))
        lock.close()
    }

    @Test
    fun `lock file is not deleted when releasing the operating system lock`() {
        val root = Files.createTempDirectory("hachimi-instance-lock-file")
        val path = root.resolve("instance.lock")
        val lock = DesktopInstanceLock.tryAcquire(path)
        assertNotNull(lock)

        lock.close()

        assertTrue(Files.exists(path))
        assertFalse(Files.isDirectory(path))
    }

    @Test
    fun `operating system releases the instance lock after an abrupt process exit`() {
        val root = Files.createTempDirectory("hachimi-instance-lock-crash")
        val path = root.resolve("instance.lock")
        val helperClassPath = Path.of(
            FileLockHolder::class.java.protectionDomain.codeSource.location.toURI(),
        ).toString()
        val javaName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "java.exe"
        } else {
            "java"
        }
        val java = Path.of(System.getProperty("java.home"), "bin", javaName).toString()
        val process = ProcessBuilder(
            java,
            "-cp",
            helperClassPath,
            "com.qing.hachimi.desktop.FileLockHolder",
            path.toString(),
        ).redirectErrorStream(true).start()
        try {
            val reader = process.inputStream.bufferedReader()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!reader.ready() && process.isAlive && System.nanoTime() < deadline) Thread.sleep(25)
            assertTrue(reader.ready(), "child process did not acquire the lock")
            assertEquals("LOCKED", reader.readLine())
            assertNull(DesktopInstanceLock.tryAcquire(path))
        } finally {
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        }

        var recovered: DesktopInstanceLock? = null
        val recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (recovered == null && System.nanoTime() < recoveryDeadline) {
            recovered = DesktopInstanceLock.tryAcquire(path)
            if (recovered == null) Thread.sleep(25)
        }
        assertNotNull(recovered, "the operating system should release the lock after the owner exits")
            .close()
    }
}
