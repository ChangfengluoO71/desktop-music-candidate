package com.qing.hachimi.desktop

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** A process-scoped OS lock; the lock file itself intentionally remains after exit. */
class DesktopInstanceLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        channel.close()
    }

    companion object {
        fun tryAcquire(path: Path): DesktopInstanceLock? {
            val normalized = path.toAbsolutePath().normalize()
            Files.createDirectories(normalized.parent)
            val channel = FileChannel.open(
                normalized,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            return try {
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    channel.close()
                    null
                } else {
                    DesktopInstanceLock(channel, lock)
                }
            } catch (failure: Throwable) {
                channel.close()
                throw failure
            }
        }
    }
}
