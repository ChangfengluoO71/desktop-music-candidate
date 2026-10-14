package com.qing.hachimi.desktop

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

enum class LocalMusicChangeKind { CREATED, MODIFIED, DELETED, RESCAN_ROOT }

data class LocalMusicChange(val root: Path, val path: Path, val kind: LocalMusicChangeKind)

/** Watches only explicitly enabled roots and coalesces filesystem bursts by canonical path. */
class LocalMusicDirectoryWatcher(
    private val debounceMillis: Long = 750,
    private val reconnectCheckMillis: Long = 10_000L,
    private val onChange: (LocalMusicChange) -> Unit,
) : AutoCloseable {
    private val keyDirectories = ConcurrentHashMap<WatchKey, Path>()
    private val registeredDirectories = ConcurrentHashMap.newKeySet<String>()
    private val failedDirectories = ConcurrentHashMap<String, Path>()
    private val pendingLock = Any()
    private val pending = mutableMapOf<ChangeKey, PendingChange>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
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
        }.distinctBy(::pathIdentity)
        if (configuredRoots == roots && (configuredRoots.isEmpty() || service != null)) return
        stopWatching()
        if (configuredRoots.isEmpty()) return
        val watchService = FileSystems.getDefault().newWatchService()
        service = watchService
        roots = configuredRoots
        configuredRoots.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { root ->
            if (!registerTree(root)) scheduleRootRescan(root)
        }
        worker = Thread({ eventLoop(watchService) }, "dmc-library-directory-watcher").apply {
            isDaemon = true
            start()
        }
        val recoveryInterval = reconnectCheckMillis.coerceAtLeast(1L)
        reconnectTask = scheduler.scheduleWithFixedDelay(::recoverDisconnectedRoots, recoveryInterval, recoveryInterval, TimeUnit.MILLISECONDS)
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
        registeredDirectories.clear()
        failedDirectories.clear()
        roots = emptyList()
        synchronized(pendingLock) {
            pending.values.forEach { it.future?.cancel(false) }
            pending.clear()
        }
    }

    private fun registerTree(start: Path): Boolean {
        var complete = true
        return runCatching {
            Files.walk(start).use { paths ->
                paths.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                    .forEach { if (!registerDirectory(it)) complete = false }
            }
            complete
        }.getOrDefault(false)
    }

    private fun registerDirectory(directory: Path): Boolean {
        val watchService = service ?: return false
        val canonical = directory.toAbsolutePath().normalize()
        val identity = pathIdentity(canonical)
        return runCatching {
            if (identity in registeredDirectories) return@runCatching true
            val key = canonical.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
            keyDirectories[key] = canonical
            registeredDirectories += identity
            failedDirectories.remove(identity)
            true
        }.getOrElse {
            failedDirectories[identity] = canonical
            false
        }
    }

    private fun recoverDisconnectedRoots() {
        if (closed) return
        if (worker?.isAlive != true) {
            val configured = roots
            if (configured.isNotEmpty()) {
                synchronized(this) {
                    if (!closed && worker?.isAlive != true) {
                        stopWatching()
                        runCatching { start(configured) }
                        configured.filter { Files.isDirectory(it) }.forEach(::scheduleRootRescan)
                    }
                }
            }
            return
        }
        roots.filter { root -> Files.isDirectory(root) && pathIdentity(root) !in registeredDirectories }.forEach { root ->
            if (registerTree(root)) scheduleRootRescan(root)
        }
        failedDirectories.values.toList().forEach { directory ->
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                failedDirectories.remove(pathIdentity(directory))
            } else if (registerTree(directory)) {
                matchingRoots(directory).forEach(::scheduleRootRescan)
            }
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
                if (!closed) roots.forEach(::scheduleRootRescan)
                break
            }
            val directory = keyDirectories[key]
            if (directory == null) {
                key.reset()
                continue
            }
            val events = key.pollEvents()
            val keyIsValid = key.reset()
            processWatchEvents(directory, events, keyIsValid, key)
        }
    }

    internal fun processWatchEvents(directory: Path, events: List<WatchEvent<*>>, keyIsValid: Boolean, key: WatchKey? = null) {
            var overflow = false
            events.forEach { event ->
                if (event.kind() == OVERFLOW) {
                    overflow = true
                } else {
                    val relative = event.context() as? Path ?: return@forEach
                    val changed = directory.resolve(relative).normalize()
                    val eventKind = event.kind()
                    val matchedRoots = matchingRoots(changed)
                    if (Files.isDirectory(changed, LinkOption.NOFOLLOW_LINKS)) {
                        if (eventKind == ENTRY_CREATE) registerTree(changed)
                        matchedRoots.forEach(::scheduleRootRescan)
                    } else {
                        val kind = when (eventKind) {
                            ENTRY_CREATE -> LocalMusicChangeKind.CREATED
                            ENTRY_DELETE -> LocalMusicChangeKind.DELETED
                            ENTRY_MODIFY -> LocalMusicChangeKind.MODIFIED
                            else -> null
                        }
                        if (kind != null) matchedRoots.forEach { root -> scheduleChange(LocalMusicChange(root, changed, kind)) }
                    }
                }
            }
            if (overflow) roots.forEach(::scheduleRootRescan)
            if (!keyIsValid) {
                key?.let(keyDirectories::remove)
                registeredDirectories.remove(pathIdentity(directory))
                matchingRoots(directory).forEach { root ->
                    scheduleRootRescan(root)
                    if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) registerTree(directory)
                }
            }
    }

    internal fun scheduleRootRescan(rootPath: Path) {
        val root = roots.firstOrNull { pathIdentity(it) == pathIdentity(rootPath) }
            ?: runCatching { rootPath.toRealPath() }.getOrElse { rootPath.toAbsolutePath().normalize() }
        scheduleChange(LocalMusicChange(root, root, LocalMusicChangeKind.RESCAN_ROOT))
    }

    private fun matchingRoots(path: Path): List<Path> {
        val changed = pathIdentity(path)
        return roots.filter { root ->
            val rootKey = pathIdentity(root).trimEnd('/')
            changed == rootKey || changed.startsWith("$rootKey/")
        }
    }

    private fun scheduleChange(change: LocalMusicChange) {
        val key = ChangeKey(pathIdentity(change.root), pathIdentity(change.path))
        synchronized(pendingLock) {
            val previous = pending[key]
            previous?.future?.cancel(false)
            val mergedKind = if (previous == null) change.kind else mergeChangeKinds(previous.change.kind, change.kind)
            if (mergedKind == null) {
                pending.remove(key)
                return
            }
            val merged = change.copy(kind = mergedKind)
            val scheduled = PendingChange(merged)
            scheduled.future = scheduler.schedule({ flushChange(key, scheduled) }, debounceMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            pending[key] = scheduled
        }
    }

    private fun flushChange(key: ChangeKey, expected: PendingChange) {
        val change = synchronized(pendingLock) {
            if (pending[key] !== expected) return
            pending.remove(key)
            expected.change
        }
        runCatching { onChange(change) }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            stopWatching()
        }
        scheduler.shutdownNow()
    }

    private data class ChangeKey(val root: String, val path: String)
    private class PendingChange(val change: LocalMusicChange) { var future: ScheduledFuture<*>? = null }

    private companion object {
        fun pathIdentity(path: Path): String = normalizedLibraryPath(path.toAbsolutePath().normalize().toString())

        fun mergeChangeKinds(previous: LocalMusicChangeKind, next: LocalMusicChangeKind): LocalMusicChangeKind? = when {
            next == LocalMusicChangeKind.RESCAN_ROOT || previous == LocalMusicChangeKind.RESCAN_ROOT -> LocalMusicChangeKind.RESCAN_ROOT
            previous == LocalMusicChangeKind.CREATED && next == LocalMusicChangeKind.MODIFIED -> LocalMusicChangeKind.CREATED
            previous == LocalMusicChangeKind.CREATED && next == LocalMusicChangeKind.DELETED -> null
            previous == LocalMusicChangeKind.DELETED && next == LocalMusicChangeKind.CREATED -> LocalMusicChangeKind.MODIFIED
            else -> next
        }
    }
}
