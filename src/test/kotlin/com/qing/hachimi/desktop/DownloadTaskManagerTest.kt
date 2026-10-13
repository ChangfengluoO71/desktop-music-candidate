package com.qing.hachimi.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.awaitCancellation
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadTaskManagerTest {
    @Test
    fun `queue advances and respects configured concurrency`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-manager")
        val transfer = ControlledTransfer()
        val manager = manager(root, transfer)
        try {
            val ids = manager.addSongs(
                songs = listOf(song(1), song(2), song(3)),
                requestedQuality = "exhigh",
                downloadDirectory = root.toString(),
            )

            val first = withTimeout(3_000) { transfer.started.receive() }
            val second = withTimeout(3_000) { transfer.started.receive() }
            delay(100)
            assertEquals(2, transfer.maximumActive.get())
            assertEquals(2, transfer.startedCount.get())

            transfer.complete(first)
            transfer.complete(second)
            val third = withTimeout(3_000) { transfer.started.receive() }
            assertEquals(ids.last(), third)
            transfer.complete(third)

            withTimeout(3_000) {
                manager.tasks.first { tasks -> tasks.all { it.status == DownloadTaskStatus.COMPLETED } }
            }
            assertEquals(2, transfer.maximumActive.get())
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `diagnostics follow selected task and completed task state`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-manager-diagnostics")
        val diagnostics = DownloadDiagnosticsStore(root.resolve("diagnostics.json")).apply { setEnabled(true) }
        val transport = object : DownloadTransport {
            override suspend fun download(
                taskId: String,
                target: Path,
                initialSource: ResolvedSongUrl,
                resolveAgain: suspend () -> ResolvedSongUrl,
                onProgress: suspend (DownloadProgressUpdate) -> Unit,
            ): DownloadTransferResult {
                Files.write(target, byteArrayOf(1, 2, 3))
                onProgress(DownloadProgressUpdate(3, 3, 0, null, null))
                return DownloadTransferResult(3, 3, null, null, initialSource.actualQuality)
            }

            override fun cancel(taskId: String) = Unit
            override fun deletePartial(target: Path) = Unit
        }
        val manager = DownloadTaskManager(
            sourceResolver = SongSourceResolver { task, _ ->
                ResolvedSongUrl("https://cdn.invalid/${task.songId}.mp3", "mp3", "standard")
            },
            transport = transport,
            store = DownloadTaskStore(root.resolve("tasks.json")),
            dispatcher = Dispatchers.Default,
            diagnostics = diagnostics,
        )
        try {
            val id = manager.addSongs(listOf(song(919)), "standard", root.toString()).single()
            val completed = withTimeout(3_000) {
                manager.tasks.first { tasks -> tasks.singleOrNull()?.status == DownloadTaskStatus.COMPLETED }
            }.single()

            assertEquals(id, completed.id)
            assertEquals("standard", completed.actualQuality)
            assertTrue(Files.isRegularFile(Path.of(completed.filePath)))
            assertTrue(diagnostics.snapshot().any { it.stage == DownloadDiagnosticStage.SONG_SELECTED })
            assertTrue(diagnostics.snapshot().any { it.stage == DownloadDiagnosticStage.SESSION_CHECK })
            assertTrue(diagnostics.snapshot().any {
                it.stage == DownloadDiagnosticStage.SOURCE_RESOLVING && it.outcome == DownloadDiagnosticOutcome.SUCCEEDED
            })
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `adding the same song and quality twice returns one task`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-manager")
        val transfer = ControlledTransfer()
        val credentialStore = MemoryCredentialStore()
        val session = SessionManager(credentialStore, SessionValidator { SessionValidation.VALID })
        val manager = DownloadTaskManager(
            sourceResolver = SongSourceResolver { task, _ ->
                ResolvedSongUrl("https://example.invalid/${task.songId}", "mp3", "exhigh")
            },
            transport = transfer,
            store = DownloadTaskStore(root.resolve("tasks.json")),
            dispatcher = Dispatchers.Default,
            sessionManager = session,
        )
        try {
            val secret = "cookie-value-that-must-not-be-written"
            session.login(secret, remember = true)
            val first = manager.addSongs(listOf(song(1)), "exhigh", root.toString())
            val duplicate = manager.addSongs(listOf(song(1)), "exhigh", root.toString())

            assertEquals(first, duplicate)
            assertEquals(1, manager.tasks.value.size)
            val stored = Files.readString(root.resolve("tasks.json"))
            assertFalse(stored.contains(secret))
            assertFalse(stored.contains("https://example.invalid"))
            assertFalse(stored.contains("MUSIC_U"))
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `shared file task retains membership in multiple collections`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-collection-membership")
        val transfer = ControlledTransfer()
        val manager = manager(root, transfer)
        try {
            val first = manager.addSongs(listOf(song(44)), "exhigh", root.toString(), "playlist-a")
            val second = manager.addSongs(listOf(song(44)), "exhigh", root.toString(), "album-b")

            assertEquals(first, second)
            assertEquals(listOf("playlist-a", "album-b"), manager.tasks.value.single().collectionImportIds)
            assertEquals(
                listOf("playlist-a", "album-b"),
                DownloadTaskStore(root.resolve("tasks.json")).load().tasks.single().collectionImportIds,
            )
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `post processing failure can retry without downloading audio again`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-post-process-retry")
        val transfer = ControlledTransfer()
        val attempts = AtomicInteger()
        val postProcessor = DownloadPostProcessor { _, _ ->
            if (attempts.incrementAndGet() == 1) {
                PostProcessingResult(PostProcessingStatus.FAILED, "标签写入失败")
            } else {
                PostProcessingResult(PostProcessingStatus.COMPLETED)
            }
        }
        val manager = DownloadTaskManager(
            sourceResolver = SongSourceResolver { task, _ ->
                ResolvedSongUrl("https://example.invalid/${task.songId}", "mp3", "exhigh")
            },
            transport = transfer,
            store = DownloadTaskStore(root.resolve("tasks.json")),
            dispatcher = Dispatchers.Default,
            postProcessor = postProcessor,
        )
        try {
            manager.updateSettings(DesktopSettings(
                defaultDownloadDirectory = root.toString(),
                writeAudioTags = true,
            ))
            val id = manager.addSongs(listOf(song(79)), "exhigh", root.toString()).single()
            assertEquals(id, withTimeout(3_000) { transfer.started.receive() })
            transfer.complete(id)
            withTimeout(3_000) {
                manager.tasks.first { it.single().postProcessingStatus == PostProcessingStatus.FAILED }
            }
            assertEquals(DownloadTaskStatus.COMPLETED, manager.tasks.value.single().status)
            assertTrue(manager.retryPostProcessing(id))
            withTimeout(3_000) {
                manager.tasks.first { it.single().postProcessingStatus == PostProcessingStatus.COMPLETED }
            }
            assertEquals(2, attempts.get())
            assertEquals(1, transfer.startedCount.get())
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `interrupted post processing resumes from completed audio after restart`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-post-process-restart")
        val storeFile = root.resolve("tasks.json")
        val enteredProcessing = CompletableDeferred<Unit>()
        val transfer = object : DownloadTransport {
            override suspend fun download(
                taskId: String,
                target: Path,
                initialSource: ResolvedSongUrl,
                resolveAgain: suspend () -> ResolvedSongUrl,
                onProgress: suspend (DownloadProgressUpdate) -> Unit,
            ): DownloadTransferResult {
                Files.writeString(target, "local audio payload")
                onProgress(DownloadProgressUpdate(18, 18, 0, null, null))
                return DownloadTransferResult(18, 18, null, null, initialSource.actualQuality)
            }

            override fun cancel(taskId: String) = Unit
            override fun deletePartial(target: Path) = Unit
        }
        val firstManager = DownloadTaskManager(
            sourceResolver = SongSourceResolver { task, _ -> ResolvedSongUrl("https://example.invalid/${task.songId}", "wav", "exhigh") },
            transport = transfer,
            store = DownloadTaskStore(storeFile),
            dispatcher = Dispatchers.Default,
            postProcessor = DownloadPostProcessor { _, _ ->
                enteredProcessing.complete(Unit)
                awaitCancellation()
            },
        )
        val secondAttempts = AtomicInteger()
        try {
            firstManager.updateSettings(DesktopSettings(defaultDownloadDirectory = root.toString(), downloadLyrics = true))
            firstManager.addSongs(listOf(song(86)), "exhigh", root.toString())
            withTimeout(3_000) { enteredProcessing.await() }
            assertEquals(PostProcessingStatus.PROCESSING, firstManager.tasks.value.single().postProcessingStatus)
            firstManager.shutdown()
            assertEquals(PostProcessingStatus.PENDING, DownloadTaskStore(storeFile).load().tasks.single().postProcessingStatus)

            val secondManager = DownloadTaskManager(
                sourceResolver = SongSourceResolver { _, _ -> error("completed audio must not resolve a new URL") },
                transport = transfer,
                store = DownloadTaskStore(storeFile),
                dispatcher = Dispatchers.Default,
                postProcessor = DownloadPostProcessor { _, _ ->
                    secondAttempts.incrementAndGet()
                    PostProcessingResult(PostProcessingStatus.COMPLETED)
                },
            )
            try {
                withTimeout(3_000) {
                    secondManager.tasks.first { it.single().postProcessingStatus == PostProcessingStatus.COMPLETED }
                }
                assertEquals(DownloadTaskStatus.COMPLETED, secondManager.tasks.value.single().status)
                assertEquals(1, secondAttempts.get())
                assertEquals("local audio payload", Files.readString(Path.of(secondManager.tasks.value.single().filePath)))
            } finally {
                secondManager.shutdown()
            }
        } finally {
            firstManager.shutdown()
        }
    }

    @Test
    fun `source failure does not prevent the next queued task`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-manager")
        val transfer = ControlledTransfer()
        val manager = DownloadTaskManager(
            sourceResolver = SongSourceResolver { task, _ ->
                if (task.songId == 2L) throw IllegalStateException("source unavailable")
                ResolvedSongUrl("https://example.invalid/${task.songId}", "mp3", "exhigh")
            },
            transport = transfer,
            store = DownloadTaskStore(root.resolve("tasks.json")),
            dispatcher = Dispatchers.Default,
        )
        try {
            val ids = manager.addSongs(
                listOf(song(1), song(2), song(3)),
                "exhigh",
                root.toString(),
            )
            val first = withTimeout(3_000) { transfer.started.receive() }
            val third = withTimeout(3_000) { transfer.started.receive() }
            assertEquals(ids[0], first)
            assertEquals(ids[2], third)
            assertEquals(DownloadTaskStatus.FAILED, manager.tasks.value.single { it.songId == 2L }.status)
            transfer.complete(first)
            transfer.complete(third)
            withTimeout(3_000) {
                manager.tasks.first { it.count { task -> task.status == DownloadTaskStatus.COMPLETED } == 2 }
            }
            Unit
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `pause cancels the active request and resume requeues it`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-manager")
        val transfer = ControlledTransfer()
        val manager = manager(root, transfer)
        try {
            val id = manager.addSongs(listOf(song(1)), "exhigh", root.toString()).single()
            assertEquals(id, withTimeout(3_000) { transfer.started.receive() })

            manager.pause(id)
            withTimeout(3_000) { manager.tasks.first { it.single().status == DownloadTaskStatus.PAUSED } }
            assertTrue(transfer.cancelledCount.get() > 0)

            manager.resume(id)
            assertEquals(id, withTimeout(3_000) { transfer.started.receive() })
            transfer.complete(id)
            withTimeout(3_000) { manager.tasks.first { it.single().status == DownloadTaskStatus.COMPLETED } }
            Unit
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `manual pause remains safe when session expires at the same time`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-manager-session-race")
        val transfer = ControlledTransfer()
        val session = SessionManager(MemoryCredentialStore(), SessionValidator { SessionValidation.VALID })
        session.login("synthetic-session", remember = false)
        val manager = DownloadTaskManager(
            sourceResolver = SongSourceResolver { task, _ ->
                ResolvedSongUrl("https://example.invalid/${task.songId}", "mp3", "exhigh")
            },
            transport = transfer,
            store = DownloadTaskStore(root.resolve("tasks.json")),
            dispatcher = Dispatchers.Default,
            sessionManager = session,
        )
        try {
            val id = manager.addSongs(listOf(song(1)), "exhigh", root.toString()).single()
            assertEquals(id, withTimeout(3_000) { transfer.started.receive() })

            manager.pause(id)
            session.markExpired()

            val task = withTimeout(3_000) {
                manager.tasks.first { it.single().status == DownloadTaskStatus.PAUSED }.single()
            }
            assertEquals(DownloadTaskStatus.PAUSED, task.status)
            assertTrue(transfer.cancelledCount.get() > 0)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `logout holds queued tasks until a new session is validated`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-manager-logout-queue")
        val transfer = ControlledTransfer()
        val session = SessionManager(MemoryCredentialStore(), SessionValidator { SessionValidation.VALID })
        session.login("synthetic-session", remember = false)
        val manager = DownloadTaskManager(
            sourceResolver = SongSourceResolver { task, _ ->
                ResolvedSongUrl("https://example.invalid/${task.songId}", "mp3", "exhigh")
            },
            transport = transfer,
            store = DownloadTaskStore(root.resolve("tasks.json")),
            dispatcher = Dispatchers.Default,
            sessionManager = session,
        )
        try {
            val ids = manager.addSongs(listOf(song(1), song(2), song(3)), "exhigh", root.toString())
            val firstActive = withTimeout(3_000) { transfer.started.receive() }
            val secondActive = withTimeout(3_000) { transfer.started.receive() }

            manager.pauseForSessionChange()
            session.logout()
            delay(100)

            assertEquals(2, transfer.startedCount.get())
            assertEquals(DownloadTaskStatus.PAUSED, manager.tasks.value.single { it.id == firstActive }.status)
            assertEquals(DownloadTaskStatus.PAUSED, manager.tasks.value.single { it.id == secondActive }.status)
            assertEquals(DownloadTaskStatus.QUEUED, manager.tasks.value.single { it.id == ids.last() }.status)
            assertFalse(transfer.started.tryReceive().isSuccess)

            assertTrue(session.login("synthetic-session", remember = false).accepted)
            val resumed1 = withTimeout(3_000) { transfer.started.receive() }
            val resumed2 = withTimeoutOrNull(3_000) { transfer.started.receive() }
            assertTrue(
                resumed2 != null,
                "Only ${transfer.startedCount.get()} transfers started; " +
                    "tasks=${manager.tasks.value.map { it.id to it.status }}, " +
                    "resumedFirst=$resumed1",
            )
            assertTrue(resumed1 in ids)
            assertTrue(resumed2 in ids)
            assertEquals(2, transfer.maximumActive.get())
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `reauthentication resumes authorization waiting queue`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-manager-reauth")
        val transfer = ControlledTransfer()
        val session = SessionManager(MemoryCredentialStore(), SessionValidator { SessionValidation.VALID })
        session.restore()
        val manager = DownloadTaskManager(
            sourceResolver = SongSourceResolver { task, musicU ->
                if (musicU.isNullOrBlank()) {
                    throw SourceUnavailableException("requires authorization", authorizationMayBeRequired = true)
                }
                ResolvedSongUrl("https://example.invalid/${task.songId}", "mp3", "exhigh")
            },
            transport = transfer,
            store = DownloadTaskStore(root.resolve("tasks.json")),
            dispatcher = Dispatchers.Default,
            sessionManager = session,
        )
        try {
            val id = manager.addSongs(listOf(song(2)), "exhigh", root.toString()).single()
            withTimeout(3_000) {
                manager.tasks.first {
                    it.single().status == DownloadTaskStatus.PAUSED &&
                        it.single().errorType == DownloadErrorType.AUTHORIZATION_REQUIRED
                }
            }

            assertTrue(session.login("synthetic-session", remember = false).accepted)
            assertEquals(id, withTimeout(3_000) { transfer.started.receive() })
            transfer.complete(id)
            withTimeout(3_000) {
                manager.tasks.first { it.single().status == DownloadTaskStatus.COMPLETED }
            }
            Unit
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `retry resolves the source again after an earlier resolution failure`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-manager-retry")
        val transfer = ControlledTransfer()
        val resolveCount = AtomicInteger()
        val manager = DownloadTaskManager(
            sourceResolver = SongSourceResolver { task, _ ->
                if (resolveCount.incrementAndGet() == 1) {
                    throw IllegalStateException("temporary source failure")
                }
                ResolvedSongUrl("https://example.invalid/refreshed/${task.songId}", "mp3", "exhigh")
            },
            transport = transfer,
            store = DownloadTaskStore(root.resolve("tasks.json")),
            dispatcher = Dispatchers.Default,
        )
        try {
            val id = manager.addSongs(listOf(song(9)), "exhigh", root.toString()).single()
            withTimeout(3_000) {
                manager.tasks.first { it.single().status == DownloadTaskStatus.FAILED }
            }

            assertEquals(1, resolveCount.get())
            assertTrue(manager.retry(id))
            assertEquals(id, withTimeout(3_000) { transfer.started.receive() })
            assertEquals(2, resolveCount.get())
            assertEquals("https://example.invalid/refreshed/9", withTimeout(3_000) { transfer.sources.receive() }.url)

            transfer.complete(id)
            withTimeout(3_000) {
                manager.tasks.first { it.single().status == DownloadTaskStatus.COMPLETED }
            }
            Unit
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `cancel marks task and deletes its partial file`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-manager-cancel")
        val transfer = ControlledTransfer()
        val manager = manager(root, transfer)
        try {
            val id = manager.addSongs(listOf(song(1)), "exhigh", root.toString()).single()
            assertEquals(id, withTimeout(3_000) { transfer.started.receive() })
            val target = Path.of(manager.tasks.value.single().filePath)
            val partial = target.resolveSibling("${target.fileName}.hachimi.part")
            Files.write(partial, byteArrayOf(1, 2, 3))

            manager.cancel(id)

            assertEquals(DownloadTaskStatus.CANCELLED, manager.tasks.value.single().status)
            assertFalse(Files.exists(partial))
            assertTrue(transfer.cancelledCount.get() > 0)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `shutdown pauses active work and persists its partial size`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-manager-shutdown")
        val store = DownloadTaskStore(root.resolve("tasks.json"))
        val transfer = ControlledTransfer()
        val manager = DownloadTaskManager(
            sourceResolver = SongSourceResolver { task, _ ->
                ResolvedSongUrl("https://example.invalid/${task.songId}", "mp3", "exhigh")
            },
            transport = transfer,
            store = store,
            dispatcher = Dispatchers.Default,
        )
        val id = manager.addSongs(listOf(song(1)), "exhigh", root.toString()).single()
        assertEquals(id, withTimeout(3_000) { transfer.started.receive() })
        val target = Path.of(manager.tasks.value.single().filePath)
        val partial = target.resolveSibling("${target.fileName}.hachimi.part")
        Files.write(partial, ByteArray(19))

        manager.shutdown()

        val inMemory = manager.tasks.value.single()
        val onDisk = store.load().tasks.single()
        assertEquals(DownloadTaskStatus.PAUSED, inMemory.status)
        assertEquals(19L, inMemory.bytesDownloaded)
        assertEquals(DownloadTaskStatus.PAUSED, onDisk.status)
        assertEquals(19L, onDisk.bytesDownloaded)
        Unit
    }

    @Test
    fun `restart pauses active tasks restores part size and marks missing completed files`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-manager-restart")
        val activeTarget = root.resolve("active.mp3")
        val partial = activeTarget.resolveSibling("${activeTarget.fileName}.hachimi.part")
        Files.write(partial, ByteArray(23))
        val missingTarget = root.resolve("moved-or-deleted.mp3")
        DownloadTaskStore(root.resolve("tasks.json")).save(
            listOf(
                task("active", 1, activeTarget, DownloadTaskStatus.DOWNLOADING, bytes = 5),
                task("missing", 2, missingTarget, DownloadTaskStatus.COMPLETED, bytes = 100),
            ),
            DesktopSettings(defaultDownloadDirectory = root.toString()),
        )
        val transfer = ControlledTransfer()
        val manager = manager(root, transfer)
        try {
            val restoredActive = manager.tasks.value.single { it.id == "active" }
            val restoredMissing = manager.tasks.value.single { it.id == "missing" }

            assertEquals(DownloadTaskStatus.PAUSED, restoredActive.status)
            assertEquals(23L, restoredActive.bytesDownloaded)
            assertEquals(DownloadTaskStatus.FAILED, restoredMissing.status)
            assertEquals(DownloadErrorType.MISSING_FILE, restoredMissing.errorType)
            assertEquals(0, transfer.startedCount.get())
        } finally {
            manager.shutdown()
        }
    }

    private fun manager(root: Path, transfer: ControlledTransfer) = DownloadTaskManager(
        sourceResolver = SongSourceResolver { task, _ ->
            ResolvedSongUrl("https://example.invalid/${task.songId}", "mp3", "exhigh")
        },
        transport = transfer,
        store = DownloadTaskStore(root.resolve("tasks.json")),
        dispatcher = Dispatchers.Default,
    )

    private fun song(id: Long) = SongHit(id, "Song $id", "Artist", "Album")

    private fun task(
        id: String,
        songId: Long,
        target: Path,
        status: DownloadTaskStatus,
        bytes: Long,
    ) = DownloadTask(
        id = id,
        songId = songId,
        songName = "Song $songId",
        artists = "Artist",
        album = "Album",
        requestedQuality = "exhigh",
        actualQuality = "exhigh",
        filePath = target.toString(),
        bytesDownloaded = bytes,
        totalBytes = 100L,
        status = status,
        createdAtMillis = songId,
        updatedAtMillis = songId,
    )

    private class ControlledTransfer : DownloadTransport {
        val started = Channel<String>(Channel.UNLIMITED)
        val sources = Channel<ResolvedSongUrl>(Channel.UNLIMITED)
        val startedCount = AtomicInteger()
        val cancelledCount = AtomicInteger()
        val maximumActive = AtomicInteger()
        private val active = AtomicInteger()
        private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        override suspend fun download(
            taskId: String,
            target: Path,
            initialSource: ResolvedSongUrl,
            resolveAgain: suspend () -> ResolvedSongUrl,
            onProgress: suspend (DownloadProgressUpdate) -> Unit,
        ): DownloadTransferResult {
            val nowActive = active.incrementAndGet()
            maximumActive.updateAndGet { maxOf(it, nowActive) }
            startedCount.incrementAndGet()
            started.send(taskId)
            sources.send(initialSource)
            val gate = gates.computeIfAbsent(taskId) { CompletableDeferred() }
            try {
                gate.await()
                onProgress(DownloadProgressUpdate(10, 10, 0, null, null))
                return DownloadTransferResult(10, 10, "\"test\"", null, initialSource.actualQuality)
            } finally {
                active.decrementAndGet()
            }
        }

        override fun cancel(taskId: String) {
            cancelledCount.incrementAndGet()
        }

        override fun deletePartial(target: Path) {
            Files.deleteIfExists(target.resolveSibling("${target.fileName}.hachimi.part"))
        }

        fun complete(taskId: String) {
            gates.computeIfAbsent(taskId) { CompletableDeferred() }.complete(Unit)
        }
    }

    private class MemoryCredentialStore : CredentialStore {
        private var value: String? = null
        override fun save(value: String) { this.value = value }
        override fun read(): String? = value
        override fun delete() { value = null }
        override fun exists(): Boolean = value != null
    }
}
