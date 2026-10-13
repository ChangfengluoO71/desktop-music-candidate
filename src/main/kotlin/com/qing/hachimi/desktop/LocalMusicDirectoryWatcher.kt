package com.qing.hachimi.desktop

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Watches only explicitly enabled roots and coalesces filesystem bursts before rescanning. */
class LocalMusicDirectoryWatcher(
    private val debounceMillis: Long = 750,
    private val onChange: (Path) -> Unit,
) : AutoCloseable {
    private val keyDirectories = ConcurrentHashMap<WatchKey, Path>()
    private val pending = ConcurrentHashMap<Path, ScheduledFuture<*>>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dmc-library-watch-debounce").apply { isDaemon = true }
    }
    @Volatile private var service: WatchService? = null
    @Volatile private var roots: List<Path> = emptyList()
    @Volatile private var worker: Thread? = null
    @Volatile private var reconnectTask: ScheduledFuture<*>? = null
    @Volatile private var closed = false

    @Synchronized
    fun start(rootPaths: List<Path>) {
        check(!closed) { "Directory watcher has been closed" }
        val configuredRoots = rootPaths.map { path ->
            runCatching { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }
        }.distinct()
        if (configuredRoots == roots && (configuredRoots.isEmpty() || service != null)) return
        stopWatching()
        if (configuredRoots.isEmpty()) return
        val watchService = FileSystems.getDefault().newWatchService()
        service = watchService
        roots = configuredRoots
        configuredRoots.filter(Files::isDirectory).forEach(::registerTree)
        worker = Thread({ eventLoop(watchService) }, "dmc-library-directory-watcher").apply {
            isDaemon = true
            start()
        }
        reconnectTask = scheduler.scheduleWithFixedDelay(
            {
                keyDirectories.keys.removeIf { !it.isValid }
                roots.filter { root ->
                    Files.isDirectory(root) && keyDirectories.values.none { it == root || it.startsWith(root) }
                }.forEach { root ->
                    registerTree(root)
                    scheduleChange(root)
                }
            },
            RECONNECT_CHECK_MILLIS,
            RECONNECT_CHECK_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    @Synchronized
    private fun stopWatching() {
        reconnectTask?.cancel(false)
        reconnectTask = null
        worker?.interrupt()
        worker = null
        service?.let { runCatching { it.close() } }
        service = null
        keyDirectories.clear()
        roots = emptyList()
        pending.values.forEach { it.cancel(false) }
        pending.clear()
    }

    private fun registerTree(start: Path) {
        runCatching {
            Files.walk(start).use { paths ->
                paths.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                    .forEach(::registerDirectory)
            }
        }
    }

    private fun registerDirectory(directory: Path) {
        val watchService = service ?: return
        runCatching {
            val key = directory.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
            keyDirectories[key] = directory
        }
    }

    private fun eventLoop(watchService: WatchService) {
        while (!closed && service === watchService && !Thread.currentThread().isInterrupted) {
            val key = try {
                watchService.poll(500, TimeUnit.MILLISECONDS) ?: continue
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (_: Exception) {
                break
            }
            val directory = keyDirectories[key]
            if (directory == null) {
                key.reset()
                continue
            }
            var overflow = false
            key.pollEvents().forEach { event ->
                if (event.kind() == OVERFLOW) {
                    overflow = true
                } else {
                    val relative = event.context() as? Path ?: return@forEach
                    val changed = directory.resolve(relative).normalize()
                    if (event.kind() == ENTRY_CREATE && Files.isDirectory(changed, LinkOption.NOFOLLOW_LINKS)) {
                        registerTree(changed)
                    }
                    matchingRoots(changed).forEach(::scheduleChange)
                }
            }
            if (overflow) roots.forEach(::scheduleChange)
            if (!key.reset()) {
                keyDirectories.remove(key)
                matchingRoots(directory).forEach(::scheduleChange)
            }
        }
    }

    private fun matchingRoots(path: Path): List<Path> = roots.filter { path.normalize().startsWith(it) }

    private fun scheduleChange(root: Path) {
        val key = root.normalize()
        pending.compute(key) { _, previous ->
            previous?.cancel(false)
            scheduler.schedule({
                pending.remove(key)
                runCatching { onChange(key) }
            }, debounceMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            stopWatching()
        }
        scheduler.shutdownNow()
    }

    private companion object {
        const val RECONNECT_CHECK_MILLIS = 10_000L
    }
}
