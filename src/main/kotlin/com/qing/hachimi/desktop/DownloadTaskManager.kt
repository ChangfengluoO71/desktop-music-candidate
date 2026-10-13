package com.qing.hachimi.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.net.SocketTimeoutException
import java.util.logging.Logger

internal const val SHUTDOWN_JOB_JOIN_TIMEOUT_MILLIS = 5_000L
private val AUTOMATIC_RETRY_BACKOFF_MILLIS = longArrayOf(1_000L, 2_000L, 4_000L)

data class DownloadHistoryClearResult(
    val removedRecords: Int,
    val deletedFiles: Int,
    val deletionFailures: Int,
    val retainedRecords: Int,
    val persistenceSucceeded: Boolean,
)

private enum class CompletedFileRemoval { DELETED, MISSING, UNSAFE_OR_FAILED }

/** Owns task transitions, queue order, persistence, and per-task coroutine lifetimes. */
class DownloadTaskManager(
    private val sourceResolver: SongSourceResolver,
    private val transport: DownloadTransport,
    private val store: DownloadTaskStore,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sessionManager: SessionManager? = null,
    private val postProcessor: DownloadPostProcessor = DownloadPostProcessor { _, _ ->
        PostProcessingResult(PostProcessingStatus.WARNING, "附加信息处理器不可用；已下载音频仍然保留。")
    },
    private val diagnostics: DownloadDiagnosticsStore? = null,
) {
    private val logger = Logger.getLogger(DownloadTaskManager::class.java.name)
    private val parentJob = SupervisorJob()
    private val ioDispatcher = dispatcher
    private val scope = CoroutineScope(parentJob + dispatcher)
    private val stateLock = Mutex()
    private val schedulerLock = Mutex()
    private val mutableTasks = linkedMapOf<String, DownloadTask>()
    private val activeJobs = linkedMapOf<String, Job>()
    private val mutableSettings: MutableStateFlow<DesktopSettings>
    private val mutableTasksFlow = MutableStateFlow<List<DownloadTask>>(emptyList())
    private val mutablePersistenceWarning = MutableStateFlow<String?>(null)

    val tasks: StateFlow<List<DownloadTask>> = mutableTasksFlow.asStateFlow()
    val settings: StateFlow<DesktopSettings>
        get() = mutableSettings.asStateFlow()
    val persistenceWarning: StateFlow<String?> = mutablePersistenceWarning.asStateFlow()

    @Volatile private var shuttingDown = false
    @Volatile private var sessionSchedulePaused = false
    private var lastProgressSaveMillis = 0L

    init {
        val loaded = store.load()
        mutableSettings = MutableStateFlow(loaded.settings)
        loaded.tasks.forEach { original ->
            val restored = when (original.status) {
                DownloadTaskStatus.RESOLVING, DownloadTaskStatus.DOWNLOADING ->
                    DownloadTaskStateMachine.transition(
                        original.copy(bytesDownloaded = partialSize(original.filePath)),
                        DownloadTaskStatus.PAUSED,
                        errorType = null,
                        errorSummary = null,
                    )
                DownloadTaskStatus.PAUSED -> original.copy(
                    bytesDownloaded = partialSize(original.filePath),
                    speedBytesPerSecond = 0L,
                )
                DownloadTaskStatus.COMPLETED -> when {
                    !Files.isRegularFile(Path.of(original.filePath)) -> DownloadTaskStateMachine.transition(
                        original,
                        DownloadTaskStatus.FAILED,
                        errorType = DownloadErrorType.MISSING_FILE,
                        errorSummary = "已完成文件不存在，请重新下载。",
                    )
                    original.postProcessingStatus == PostProcessingStatus.PROCESSING -> original.copy(
                        postProcessingStatus = PostProcessingStatus.PENDING,
                        postProcessingMessage = null,
                    )
                    else -> original
                }
                else -> original
            }
            mutableTasks[restored.id] = restored
        }
        publishLocked()
        if (loaded.tasks != taskListLocked()) {
            runCatching { store.save(taskListLocked(), mutableSettings.value) }
                .onFailure(::recordPersistenceFailure)
        }
        sessionManager?.let { manager ->
            scope.launch {
                manager.state.collect { session ->
                    when (session.status) {
                        SessionStatus.SIGNED_IN -> resumeAuthorizationWaitingTasks()
                        SessionStatus.SESSION_EXPIRED -> pauseForSessionChange()
                        else -> Unit
                    }
                }
            }
        }
        scope.launch { schedule() }
    }

    /** Duplicate identity is song + requested quality + output directory; the existing task is returned. */
    suspend fun addSongs(
        songs: List<SongHit>,
        requestedQuality: String = settings.value.defaultQuality,
        downloadDirectory: String = settings.value.defaultDownloadDirectory,
        collectionImportId: String? = null,
        destinationDirectory: ((SongHit) -> Path)? = null,
    ): List<String> {
        if (shuttingDown) return emptyList()
        require(requestedQuality in setOf("standard", "exhigh", "lossless", "hires")) {
            "不支持该音质"
        }
        val rootDirectory = Path.of(downloadDirectory).toAbsolutePath().normalize()
        val songsWithDirectories = songs.map { song ->
            song to (destinationDirectory?.invoke(song) ?: rootDirectory).toAbsolutePath().normalize()
        }
        withContext(ioDispatcher) {
            Files.createDirectories(rootDirectory)
            songsWithDirectories.map { it.second }.distinct().forEach(Files::createDirectories)
        }
        val ids = stateLock.withLock {
            if (shuttingDown) return emptyList()
            val result = ArrayList<String>(songs.size)
            for ((song, directory) in songsWithDirectories) {
                val duplicate = mutableTasks.values.firstOrNull { task ->
                    task.songId == song.id &&
                        task.requestedQuality == requestedQuality &&
                        Path.of(task.filePath).parent == directory &&
                        task.status != DownloadTaskStatus.CANCELLED
                }
                if (duplicate != null) {
                    if (collectionImportId != null && collectionImportId !in duplicate.collectionImportIds) {
                        mutableTasks[duplicate.id] = duplicate.copy(
                            collectionImportIds = duplicate.collectionImportIds + collectionImportId,
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                    }
                    result += duplicate.id
                    continue
                }
                val reserved = mutableTasks.values
                    .filter { it.status != DownloadTaskStatus.CANCELLED }
                    .mapTo(mutableSetOf()) { Path.of(it.filePath).toAbsolutePath().normalize() }
                val target = FileNamePolicy.destination(
                    directory,
                    song,
                    type = null,
                    reservedPaths = reserved,
                    conflictPolicy = mutableSettings.value.fileConflictPolicy,
                )
                val task = DownloadTask(
                    songId = song.id,
                    songName = song.name.ifBlank { "Unknown title" },
                    artists = song.artists,
                    album = song.album,
                    requestedQuality = requestedQuality,
                    filePath = target.toString(),
                    collectionImportIds = listOfNotNull(collectionImportId),
                    coverUrl = song.coverUrl,
                    trackNumber = song.trackNumber,
                    trackTotal = song.trackTotal,
                    discNumber = song.discNumber,
                    discTotal = song.discTotal,
                    releaseYear = song.releaseYear,
                    albumArtist = song.albumArtist,
                    aliases = song.aliases,
                    translatedTitles = song.translatedTitles,
                    postProcessingOptions = mutableSettings.value.postProcessingOptions(),
                )
                mutableTasks[task.id] = task
                result += task.id
            }
            publishLocked()
            persistLocked()
            result
        }
        ids.forEach { id ->
            tasks.value.firstOrNull { it.id == id }?.let { task ->
                diagnostics?.record(
                    DownloadDiagnosticEvent(
                        taskId = task.id,
                        songId = task.songId,
                        requestedQuality = task.requestedQuality,
                        stage = DownloadDiagnosticStage.SONG_SELECTED,
                        elapsedMillis = 0L,
                        outcome = DownloadDiagnosticOutcome.SUCCEEDED,
                    ),
                )
            }
        }
        schedule()
        return ids
    }

    suspend fun updateSettings(settings: DesktopSettings) {
        if (shuttingDown) return
        stateLock.withLock {
            if (shuttingDown) return
            mutableSettings.value = settings
            publishLocked()
            persistLocked()
        }
        schedule()
    }

    /** Stops active transfers before a user logout; resumable bytes are kept and reauth tasks wait. */
    suspend fun pauseForSessionChange() {
        if (shuttingDown) return
        val active = schedulerLock.withLock scheduler@{
            if (shuttingDown) return@scheduler emptyMap()
            sessionSchedulePaused = true
            stateLock.withLock state@{
                if (shuttingDown) return@state emptyMap()
                val jobs = activeJobs.toMap()
                mutableTasks.values.toList().forEach { task ->
                    if (task.status in setOf(DownloadTaskStatus.RESOLVING, DownloadTaskStatus.DOWNLOADING)) {
                        mutableTasks[task.id] = DownloadTaskStateMachine.transition(
                            task.copy(bytesDownloaded = partialSize(task.filePath)),
                            DownloadTaskStatus.PAUSED,
                            errorType = DownloadErrorType.AUTHORIZATION_REQUIRED,
                            errorSummary = "登录状态已更改，请重新登录后继续。",
                        )
                    }
                }
                publishLocked()
                persistLocked()
                jobs
            }
        }
        active.forEach { (id, job) ->
            sourceResolver.cancel(id)
            transport.cancel(id)
            job.cancel(CancellationException("登录状态已更改"))
        }
        active.values.forEach { it.join() }
    }

    suspend fun pause(taskId: String) {
        val job = stateLock.withLock {
            if (shuttingDown) return
            val task = mutableTasks[taskId] ?: return
            if (task.status !in setOf(
                    DownloadTaskStatus.QUEUED,
                    DownloadTaskStatus.RESOLVING,
                    DownloadTaskStatus.DOWNLOADING,
                )
            ) return
            mutableTasks[taskId] = DownloadTaskStateMachine.transition(task, DownloadTaskStatus.PAUSED)
            publishLocked()
            persistLocked()
            activeJobs[taskId]
        }
        sourceResolver.cancel(taskId)
        transport.cancel(taskId)
        job?.cancel(CancellationException("任务已暂停"))
        job?.join()
    }

    suspend fun resume(taskId: String): Boolean {
        val resumed = stateLock.withLock {
            if (shuttingDown) return false
            val task = mutableTasks[taskId] ?: return false
            if (task.status != DownloadTaskStatus.PAUSED) return false
            if (task.errorType == DownloadErrorType.AUTHORIZATION_REQUIRED &&
                sessionManager != null && sessionManager.credentialForRequest().isNullOrBlank()
            ) {
                return false
            }
            mutableTasks[taskId] = DownloadTaskStateMachine.transition(task, DownloadTaskStatus.QUEUED)
            publishLocked()
            persistLocked()
            true
        }
        if (resumed) schedule()
        return resumed
    }

    suspend fun cancel(taskId: String) {
        val cancelled = stateLock.withLock {
            if (shuttingDown) return
            val task = mutableTasks[taskId] ?: return
            if (task.status in setOf(DownloadTaskStatus.COMPLETED, DownloadTaskStatus.CANCELLED)) return
            mutableTasks[taskId] = DownloadTaskStateMachine.transition(task, DownloadTaskStatus.CANCELLED)
            publishLocked()
            persistLocked()
            task to activeJobs[taskId]
        }
        sourceResolver.cancel(taskId)
        transport.cancel(taskId)
        cancelled.second?.cancel(CancellationException("任务已取消"))
        cancelled.second?.join()
        withContext(Dispatchers.IO) { transport.deletePartial(Path.of(cancelled.first.filePath)) }
        stateLock.withLock {
            if (shuttingDown) return
            mutableTasks[taskId]?.let { task ->
                mutableTasks[taskId] = task.copy(
                    bytesDownloaded = 0L,
                    totalBytes = null,
                    speedBytesPerSecond = 0L,
                    etag = null,
                    lastModified = null,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
            publishLocked()
            persistLocked()
        }
    }

    /** Clears only terminal history; active jobs and resumable tasks always remain untouched. */
    suspend fun clearHistory(deleteCompletedFiles: Boolean): DownloadHistoryClearResult = stateLock.withLock {
        if (shuttingDown) {
            return DownloadHistoryClearResult(0, 0, 0, mutableTasks.size, persistenceSucceeded = false)
        }

        val candidates = mutableTasks.values.filter { task ->
            task.status in setOf(DownloadTaskStatus.COMPLETED, DownloadTaskStatus.FAILED, DownloadTaskStatus.CANCELLED) &&
                task.id !in activeJobs
        }
        val removable = mutableListOf<DownloadTask>()
        var deletedFiles = 0
        var deletionFailures = 0

        candidates.forEach { task ->
            if (!deleteCompletedFiles || task.status != DownloadTaskStatus.COMPLETED) {
                removable += task
                return@forEach
            }

            when (withContext(ioDispatcher) { removeCompletedAudioFile(task.filePath) }) {
                CompletedFileRemoval.DELETED -> {
                    deletedFiles++
                    removable += task
                }
                CompletedFileRemoval.MISSING -> removable += task
                CompletedFileRemoval.UNSAFE_OR_FAILED -> deletionFailures++
            }
        }

        removable.forEach { mutableTasks.remove(it.id) }
        publishLocked()
        persistLocked()
        val persistenceSucceeded = mutablePersistenceWarning.value == null
        if (!persistenceSucceeded) {
            // Keep the in-memory history consistent with the last durable snapshot.
            removable.forEach { mutableTasks[it.id] = it }
            publishLocked()
        }
        DownloadHistoryClearResult(
            removedRecords = if (persistenceSucceeded) removable.size else 0,
            deletedFiles = deletedFiles,
            deletionFailures = deletionFailures,
            retainedRecords = mutableTasks.size,
            persistenceSucceeded = persistenceSucceeded,
        )
    }

    suspend fun removeCollectionMembership(taskId: String, importId: String): Boolean = stateLock.withLock {
        if (shuttingDown) return false
        val task = mutableTasks[taskId] ?: return false
        if (importId !in task.collectionImportIds) return false
        mutableTasks[taskId] = task.copy(
            collectionImportIds = task.collectionImportIds - importId,
            updatedAtMillis = System.currentTimeMillis(),
        )
        publishLocked()
        persistLocked()
        true
    }

    suspend fun retry(taskId: String): Boolean {
        val queued = stateLock.withLock {
            if (shuttingDown) return false
            val task = mutableTasks[taskId] ?: return false
            if (task.status !in setOf(DownloadTaskStatus.FAILED, DownloadTaskStatus.CANCELLED)) return false
            val next = DownloadTaskStateMachine.transition(
                task.copy(automaticRetriesUsed = 0),
                DownloadTaskStatus.QUEUED,
            )
            mutableTasks[taskId] = if (task.status == DownloadTaskStatus.CANCELLED) {
                next.copy(bytesDownloaded = 0L, totalBytes = null, etag = null, lastModified = null)
            } else next
            publishLocked()
            persistLocked()
            true
        }
        if (queued) schedule()
        return queued
    }

    suspend fun retryPostProcessing(taskId: String): Boolean {
        val eligible = stateLock.withLock {
            if (shuttingDown) return false
            val task = mutableTasks[taskId] ?: return false
            if (task.status != DownloadTaskStatus.COMPLETED ||
                task.postProcessingStatus !in setOf(PostProcessingStatus.FAILED, PostProcessingStatus.WARNING)
            ) return false
            mutableTasks[taskId] = task.copy(
                postProcessingStatus = PostProcessingStatus.PENDING,
                postProcessingMessage = null,
                updatedAtMillis = System.currentTimeMillis(),
            )
            publishLocked()
            persistLocked()
            true
        }
        if (eligible) schedule()
        return eligible
    }

    /** Flushes state and pauses active work before the window exits. */
    suspend fun shutdown() {
        if (shuttingDown) return
        shuttingDown = true
        val managerJobsBeforeSnapshot = scope.coroutineContext[Job]?.children?.toList().orEmpty()
        var active: Map<String, Job> = emptyMap()
        try {
            val persistedActive = withTimeoutOrNull(SHUTDOWN_JOB_JOIN_TIMEOUT_MILLIS) {
                stateLock.withLock {
                    mutableTasks.values.toList().forEach { task ->
                        if (task.status in setOf(DownloadTaskStatus.RESOLVING, DownloadTaskStatus.DOWNLOADING)) {
                            mutableTasks[task.id] = DownloadTaskStateMachine.transition(
                                task.copy(bytesDownloaded = partialSize(task.filePath)),
                                DownloadTaskStatus.PAUSED,
                            )
                        }
                    }
                    publishLocked()
                    persistLocked()
                    activeJobs.toMap()
                }
            }
            active = persistedActive.orEmpty()
            if (persistedActive == null) {
                logger.warning("Shutdown timed out saving the paused task snapshot; startup recovery will pause active tasks.")
            }
            val jobsToStop = (managerJobsBeforeSnapshot + active.values).distinct()
            active.forEach { (id, job) ->
                runCatching { sourceResolver.cancel(id) }
                runCatching { transport.cancel(id) }
                job.cancel(CancellationException("应用正在关闭"))
            }
            jobsToStop.forEach { it.cancel(CancellationException("应用正在关闭")) }
            val allStopped = withTimeoutOrNull(SHUTDOWN_JOB_JOIN_TIMEOUT_MILLIS) {
                jobsToStop.joinAll()
                true
            } ?: false
            if (!allStopped) {
                val stillActive = jobsToStop.count { it.isActive }
                if (stillActive > 0) {
                    logger.warning("Shutdown timed out waiting for $stillActive download manager job(s) to stop.")
                }
            }
        } finally {
            active.forEach { (id, job) ->
                runCatching { sourceResolver.cancel(id) }
                runCatching { transport.cancel(id) }
                job.cancel(CancellationException("应用正在关闭"))
            }
            scope.cancel()
        }
    }

    private suspend fun schedule() {
        schedulerLock.withLock {
            if (shuttingDown || sessionSchedulePaused) return@withLock
            stateLock.withLock stateLock@{
                // A caller may have passed the outer check and waited for stateLock
                // while shutdown persisted the paused task snapshot.
                if (shuttingDown || sessionSchedulePaused) return@stateLock
                val available = (mutableSettings.value.maxConcurrentDownloads - activeJobs.size).coerceAtLeast(0)
                val pendingPostProcessing = mutableTasks.values
                    .filter {
                        it.status == DownloadTaskStatus.COMPLETED &&
                            it.postProcessingStatus == PostProcessingStatus.PENDING
                    }
                    .take(available)
                pendingPostProcessing.forEach { task ->
                    mutableTasks[task.id] = task.copy(
                        postProcessingStatus = PostProcessingStatus.PROCESSING,
                        postProcessingMessage = null,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                    val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                        runPostProcessing(task.id)
                    }
                    activeJobs[task.id] = job
                    job.start()
                }
                val remaining = (available - pendingPostProcessing.size).coerceAtLeast(0)
                val selected = mutableTasks.values
                    .filter { it.status == DownloadTaskStatus.QUEUED }
                    .take(remaining)
                selected.forEach { task ->
                    mutableTasks[task.id] = DownloadTaskStateMachine.transition(task, DownloadTaskStatus.RESOLVING)
                    val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { runTask(task.id) }
                    activeJobs[task.id] = job
                    // Start while holding stateLock. Otherwise pause() can cancel a lazy job
                    // before it starts; a never-started coroutine has no finally block to
                    // release its concurrency slot from activeJobs.
                    job.start()
                }
                if (selected.isNotEmpty() || pendingPostProcessing.isNotEmpty()) {
                    publishLocked()
                    persistLocked()
                }
            }
        }
    }

    private suspend fun runTask(taskId: String) {
        var diagnosticTaskRegistered = false
        try {
            val initial = stateLock.withLock { mutableTasks[taskId] } ?: return
            val sessionCheckStarted = System.nanoTime()
            diagnostics?.record(
                DownloadDiagnosticEvent(
                    taskId = initial.id,
                    songId = initial.songId,
                    requestedQuality = initial.requestedQuality,
                    stage = DownloadDiagnosticStage.SESSION_CHECK,
                    elapsedMillis = 0L,
                    outcome = DownloadDiagnosticOutcome.STARTED,
                ),
            )
            sessionManager?.awaitReady()
            val sessionPresent = !sessionManager?.credentialForRequest().isNullOrBlank()
            diagnostics?.record(
                DownloadDiagnosticEvent(
                    taskId = initial.id,
                    songId = initial.songId,
                    requestedQuality = initial.requestedQuality,
                    stage = DownloadDiagnosticStage.SESSION_CHECK,
                    elapsedMillis = elapsedSince(sessionCheckStarted),
                    outcome = if (sessionPresent) DownloadDiagnosticOutcome.SUCCEEDED else DownloadDiagnosticOutcome.FAILED,
                    error = if (sessionPresent) null else SourceResolutionFailure.SessionAbsent,
                    sessionPresent = sessionPresent,
                ),
            )
            diagnostics?.registerActiveTask(
                DownloadDiagnosticTaskContext(initial.id, initial.songId, initial.requestedQuality, sessionPresent),
            )
            diagnosticTaskRegistered = diagnostics != null
            var retriesUsed = initial.automaticRetriesUsed
            while (true) {
                try {
                    if (!runDownloadAttempt(taskId)) return
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (retriesUsed >= MAX_AUTOMATIC_DOWNLOAD_RETRIES || !isAutomaticRetryable(error)) {
                        throw error
                    }
                    val retryNumber = retriesUsed + 1
                    val retrySummary = "${userMessage(error, waitForCredentials = false)} " +
                        "正在自动重试 $retryNumber/$MAX_AUTOMATIC_DOWNLOAD_RETRIES。"
                    val retryScheduled = stateLock.withLock {
                        val current = mutableTasks[taskId] ?: return@withLock false
                        if (current.status !in setOf(DownloadTaskStatus.RESOLVING, DownloadTaskStatus.DOWNLOADING)) {
                            return@withLock false
                        }
                        val partialBytes = partialSize(current.filePath)
                        mutableTasks[taskId] = if (current.status == DownloadTaskStatus.DOWNLOADING) {
                            DownloadTaskStateMachine.transition(
                                current.copy(bytesDownloaded = partialBytes),
                                DownloadTaskStatus.RESOLVING,
                                errorType = automaticRetryErrorType(error),
                                errorSummary = retrySummary,
                            )
                        } else {
                            current.copy(
                                bytesDownloaded = partialBytes,
                                speedBytesPerSecond = 0L,
                                errorType = automaticRetryErrorType(error),
                                errorSummary = DownloadTaskStore.sanitizeSummary(retrySummary),
                                updatedAtMillis = System.currentTimeMillis(),
                            )
                        }
                        publishLocked()
                        persistLocked()
                        true
                    }
                    if (!retryScheduled) return

                    // delay is cancellable, so pause, cancel, logout, and shutdown interrupt it.
                    delay(AUTOMATIC_RETRY_BACKOFF_MILLIS[retryNumber - 1])
                    val retryStarted = stateLock.withLock {
                        val current = mutableTasks[taskId] ?: return@withLock false
                        if (current.status != DownloadTaskStatus.RESOLVING) return@withLock false
                        mutableTasks[taskId] = current.copy(
                            automaticRetriesUsed = retryNumber,
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                        publishLocked()
                        persistLocked()
                        true
                    }
                    if (!retryStarted) return
                    retriesUsed = retryNumber
                }
            }
        } catch (cancelled: CancellationException) {
            stateLock.withLock {
                val task = mutableTasks[taskId] ?: return
                if (task.status in setOf(DownloadTaskStatus.RESOLVING, DownloadTaskStatus.DOWNLOADING)) {
                    mutableTasks[taskId] = DownloadTaskStateMachine.transition(
                        task.copy(bytesDownloaded = partialSize(task.filePath)),
                        DownloadTaskStatus.PAUSED,
                    )
                    publishLocked()
                    persistLocked()
                }
            }
        } catch (error: Exception) {
            val unavailable = error as? SourceUnavailableException
            val sourceFailure = (error as? SourceResolutionException)?.failure
            val noCredential = sessionManager?.credentialForRequest().isNullOrBlank()
            val waitForCredentials = error is SessionExpiredException ||
                sourceFailure == SourceResolutionFailure.SessionExpired ||
                (sourceFailure == SourceResolutionFailure.SessionAbsent && noCredential) ||
                (unavailable?.authorizationMayBeRequired == true && noCredential)
            val errorType = when {
                waitForCredentials -> DownloadErrorType.AUTHORIZATION_REQUIRED
                error is FileAlreadyExistsException -> DownloadErrorType.FILE_EXISTS
                error is AudioUrlPolicyException -> if (error.failure in setOf(
                    AudioUrlFailure.DNS_RESOLUTION_FAILED,
                    AudioUrlFailure.DNS_SYNTHETIC_ADDRESS_SUSPECTED,
                    AudioUrlFailure.TLS_VALIDATION_FAILED,
                    AudioUrlFailure.HTTP_TRANSFER_FAILED,
                )) DownloadErrorType.NETWORK else DownloadErrorType.SOURCE_UNAVAILABLE
                error is InvalidDownloadException -> DownloadErrorType.INVALID_RESPONSE
                error is DownloadHttpException -> DownloadErrorType.HTTP
                sourceFailure is SourceResolutionFailure.HttpStatus -> DownloadErrorType.HTTP
                sourceFailure is SourceResolutionFailure.CdnHttpError -> DownloadErrorType.HTTP
                sourceFailure is SourceResolutionFailure.ApiBusinessCode ||
                    sourceFailure is SourceResolutionFailure.DataItemCode ||
                    sourceFailure == SourceResolutionFailure.UrlMissing ||
                    sourceFailure == SourceResolutionFailure.DataMissing ||
                    sourceFailure == SourceResolutionFailure.InvalidJson ||
                    sourceFailure == SourceResolutionFailure.UnsafeUrl -> DownloadErrorType.SOURCE_UNAVAILABLE
                sourceFailure is SourceResolutionFailure.AudioUrlPolicy -> if (sourceFailure.failure in setOf(
                    AudioUrlFailure.DNS_RESOLUTION_FAILED,
                    AudioUrlFailure.DNS_SYNTHETIC_ADDRESS_SUSPECTED,
                    AudioUrlFailure.TLS_VALIDATION_FAILED,
                    AudioUrlFailure.HTTP_TRANSFER_FAILED,
                )) DownloadErrorType.NETWORK else DownloadErrorType.SOURCE_UNAVAILABLE
                sourceFailure == SourceResolutionFailure.InvalidAudio -> DownloadErrorType.INVALID_RESPONSE
                sourceFailure == SourceResolutionFailure.FileCommitFailure ||
                    sourceFailure == SourceResolutionFailure.StorageFailure -> DownloadErrorType.STORAGE
                sourceFailure == SourceResolutionFailure.RequestTimeout ||
                    sourceFailure == SourceResolutionFailure.DnsResolutionFailed ||
                    sourceFailure == SourceResolutionFailure.ProxyOrRouteUnavailable ||
                    sourceFailure == SourceResolutionFailure.TlsFailure ||
                    sourceFailure == SourceResolutionFailure.ConnectionFailure ||
                    sourceFailure == SourceResolutionFailure.SessionCheckUnavailable ||
                    sourceFailure == SourceResolutionFailure.TransferFailure -> DownloadErrorType.NETWORK
                sourceFailure == SourceResolutionFailure.SourceUnavailable -> DownloadErrorType.SOURCE_UNAVAILABLE
                sourceFailure == SourceResolutionFailure.Unknown -> DownloadErrorType.UNKNOWN
                error is SocketTimeoutException -> DownloadErrorType.NETWORK
                error is IOException -> DownloadErrorType.NETWORK
                else -> DownloadErrorType.SOURCE_UNAVAILABLE
            }
            stateLock.withLock {
                val task = mutableTasks[taskId] ?: return
                if (task.status !in setOf(DownloadTaskStatus.RESOLVING, DownloadTaskStatus.DOWNLOADING)) return
                val targetStatus = if (waitForCredentials) DownloadTaskStatus.PAUSED else DownloadTaskStatus.FAILED
                mutableTasks[taskId] = DownloadTaskStateMachine.transition(
                    task.copy(
                        bytesDownloaded = partialSize(task.filePath),
                        actualQuality = (error as? SourceResolutionException)?.actualQuality ?: task.actualQuality,
                    ),
                    targetStatus,
                    errorType = errorType,
                    errorSummary = userMessage(error, waitForCredentials),
                )
                publishLocked()
                persistLocked()
            }
            if (error is SessionExpiredException) sessionManager?.markExpired()
        } finally {
            withContext(NonCancellable) {
                stateLock.withLock { activeJobs.remove(taskId) }
                if (diagnosticTaskRegistered) diagnostics?.clearActiveTask(taskId)
                schedule()
            }
        }
    }

    private suspend fun runDownloadAttempt(taskId: String): Boolean {
        val initial = stateLock.withLock { mutableTasks[taskId] } ?: return false
        if (initial.status != DownloadTaskStatus.RESOLVING) return false
        val currentSettings = settings.value
        val target = Path.of(initial.filePath)
        if (currentSettings.fileConflictPolicy == FileConflictPolicy.FAIL_IF_EXISTS && Files.exists(target)) {
            throw FileAlreadyExistsException(target.toString())
        }
        val firstSource = resolveSource(initial, sessionManager?.credentialForRequest())
        val resolvedTask = stateLock.withLock {
            val current = mutableTasks[taskId] ?: return@withLock null
            if (current.status != DownloadTaskStatus.RESOLVING) return@withLock null
            val previousPath = Path.of(current.filePath).toAbsolutePath().normalize()
            val resolvedPath = allocateResolvedPath(current, firstSource.type)
            val changedTarget = resolvedPath != previousPath
            if (changedTarget && partialSize(previousPath.toString()) > 0L) {
                // A changed file extension means the previous part cannot safely be resumed there.
                transport.deletePartial(previousPath)
            }
            val updated = current.copy(
                filePath = resolvedPath.toString(),
                actualQuality = firstSource.actualQuality,
                bytesDownloaded = if (changedTarget) 0L else current.bytesDownloaded,
                totalBytes = if (changedTarget) null else current.totalBytes,
                etag = if (changedTarget) null else current.etag,
                lastModified = if (changedTarget) null else current.lastModified,
                updatedAtMillis = System.currentTimeMillis(),
            )
            mutableTasks[taskId] = DownloadTaskStateMachine.transition(
                updated,
                DownloadTaskStatus.DOWNLOADING,
            )
            publishLocked()
            persistLocked()
            mutableTasks.getValue(taskId)
        } ?: return false
        val result = transport.download(
            taskId = taskId,
            target = Path.of(resolvedTask.filePath),
            initialSource = firstSource,
            resolveAgain = {
                val latest = stateLock.withLock { mutableTasks[taskId] } ?: resolvedTask
                resolveSource(latest, sessionManager?.credentialForRequest())
            },
            onProgress = { update -> updateProgress(taskId, update) },
        )
        return stateLock.withLock {
            val current = mutableTasks[taskId] ?: return@withLock false
            if (current.status != DownloadTaskStatus.DOWNLOADING) return@withLock false
            val withResult = DownloadTaskStateMachine.progress(
                current,
                result.bytesDownloaded,
                result.totalBytes,
                0L,
            ).copy(
                actualQuality = result.actualQuality,
                etag = result.etag,
                lastModified = result.lastModified,
                postProcessingStatus = if (current.postProcessingOptions.requested) {
                    PostProcessingStatus.PENDING
                } else PostProcessingStatus.NOT_REQUESTED,
                postProcessingMessage = null,
            )
            mutableTasks[taskId] = DownloadTaskStateMachine.transition(
                withResult,
                DownloadTaskStatus.COMPLETED,
            )
            publishLocked()
            persistLocked()
            true
        }
    }

    private fun isAutomaticRetryable(error: Exception): Boolean {
        if (error is SourceUnavailableException || error is SessionExpiredException ||
            error is InvalidDownloadException || error is java.nio.file.FileSystemException
        ) return false

        return when (val failure = NetworkFailureClassifier.classify(error)) {
            SourceResolutionFailure.RequestTimeout,
            SourceResolutionFailure.DnsResolutionFailed,
            SourceResolutionFailure.ProxyOrRouteUnavailable,
            SourceResolutionFailure.ConnectionFailure,
            SourceResolutionFailure.TransferFailure,
            -> true
            is SourceResolutionFailure.HttpStatus -> isRetryableHttpStatus(failure.statusCode)
            is SourceResolutionFailure.CdnHttpError -> isRetryableHttpStatus(failure.statusCode)
            is SourceResolutionFailure.AudioUrlPolicy -> when (failure.failure) {
                AudioUrlFailure.DNS_RESOLUTION_FAILED,
                AudioUrlFailure.DNS_SYNTHETIC_ADDRESS_SUSPECTED,
                AudioUrlFailure.AUDIO_SOURCE_EXPIRED,
                AudioUrlFailure.HTTP_TRANSFER_FAILED,
                -> true
                AudioUrlFailure.UNTRUSTED_AUDIO_HOST ->
                    failure.validationRule == AudioUrlValidationRule.HTTP_HOST_REQUIRES_VERIFICATION
                else -> false
            }
            else -> false
        }
    }

    private fun isRetryableHttpStatus(statusCode: Int): Boolean =
        statusCode in setOf(408, 425, 429) || statusCode in 500..599

    private fun automaticRetryErrorType(error: Exception): DownloadErrorType =
        when (val failure = NetworkFailureClassifier.classify(error)) {
            is SourceResolutionFailure.HttpStatus,
            is SourceResolutionFailure.CdnHttpError,
            -> DownloadErrorType.HTTP
            is SourceResolutionFailure.AudioUrlPolicy -> if (failure.failure in setOf(
                AudioUrlFailure.DNS_RESOLUTION_FAILED,
                AudioUrlFailure.DNS_SYNTHETIC_ADDRESS_SUSPECTED,
                AudioUrlFailure.HTTP_TRANSFER_FAILED,
            )) DownloadErrorType.NETWORK else DownloadErrorType.SOURCE_UNAVAILABLE
            SourceResolutionFailure.RequestTimeout,
            SourceResolutionFailure.DnsResolutionFailed,
            SourceResolutionFailure.ProxyOrRouteUnavailable,
            SourceResolutionFailure.ConnectionFailure,
            SourceResolutionFailure.TransferFailure,
            -> DownloadErrorType.NETWORK
            else -> DownloadErrorType.UNKNOWN
        }

    private suspend fun resolveSource(task: DownloadTask, musicU: String?): ResolvedSongUrl {
        val startedAt = System.nanoTime()
        diagnostics?.record(
            DownloadDiagnosticEvent(
                taskId = task.id,
                songId = task.songId,
                requestedQuality = task.requestedQuality,
                stage = DownloadDiagnosticStage.SOURCE_RESOLVING,
                elapsedMillis = 0L,
                outcome = DownloadDiagnosticOutcome.STARTED,
                sessionPresent = !musicU.isNullOrBlank(),
            ),
        )
        return try {
            sourceResolver.resolve(task, musicU).also { resolved ->
                diagnostics?.record(
                    DownloadDiagnosticEvent(
                        taskId = task.id,
                        songId = task.songId,
                        requestedQuality = task.requestedQuality,
                        stage = DownloadDiagnosticStage.SOURCE_RESOLVING,
                        elapsedMillis = elapsedSince(startedAt),
                        outcome = DownloadDiagnosticOutcome.SUCCEEDED,
                        attemptQuality = resolved.actualQuality,
                        sessionPresent = !musicU.isNullOrBlank(),
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            diagnostics?.record(
                DownloadDiagnosticEvent(
                    taskId = task.id,
                    songId = task.songId,
                    requestedQuality = task.requestedQuality,
                    stage = DownloadDiagnosticStage.SOURCE_RESOLVING,
                    elapsedMillis = elapsedSince(startedAt),
                    outcome = DownloadDiagnosticOutcome.FAILED,
                    error = SourceResolutionFailure.Cancelled,
                    sessionPresent = !musicU.isNullOrBlank(),
                ),
            )
            throw cancelled
        } catch (error: Exception) {
            diagnostics?.record(
                DownloadDiagnosticEvent(
                    taskId = task.id,
                    songId = task.songId,
                    requestedQuality = task.requestedQuality,
                    stage = DownloadDiagnosticStage.SOURCE_RESOLVING,
                    elapsedMillis = elapsedSince(startedAt),
                    outcome = DownloadDiagnosticOutcome.FAILED,
                    error = (error as? SourceResolutionException)?.failure ?: classifySourceFailure(error),
                    sessionPresent = !musicU.isNullOrBlank(),
                ),
            )
            throw error
        }
    }

    private fun classifySourceFailure(error: Exception): SourceResolutionFailure = when (error) {
        is SessionExpiredException -> SourceResolutionFailure.SessionExpired
        else -> NetworkFailureClassifier.classify(error)
    }

    private fun elapsedSince(startedAtNanos: Long): Long =
        ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)

    private suspend fun runPostProcessing(taskId: String) {
        try {
            val task = stateLock.withLock { mutableTasks[taskId] } ?: return
            if (task.status != DownloadTaskStatus.COMPLETED ||
                task.postProcessingStatus != PostProcessingStatus.PROCESSING
            ) return
            val result = postProcessor.process(task, sessionManager?.credentialForRequest())
            stateLock.withLock {
                val current = mutableTasks[taskId] ?: return
                if (current.status != DownloadTaskStatus.COMPLETED) return
                mutableTasks[taskId] = current.copy(
                    postProcessingStatus = result.status,
                    postProcessingMessage = DownloadTaskStore.sanitizeSummary(result.message),
                    updatedAtMillis = System.currentTimeMillis(),
                )
                publishLocked()
                persistLocked()
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                stateLock.withLock {
                    val current = mutableTasks[taskId] ?: return@withLock
                    if (current.status == DownloadTaskStatus.COMPLETED &&
                        current.postProcessingStatus == PostProcessingStatus.PROCESSING
                    ) {
                        mutableTasks[taskId] = current.copy(
                            postProcessingStatus = PostProcessingStatus.PENDING,
                            postProcessingMessage = null,
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                        publishLocked()
                        persistLocked()
                    }
                }
            }
        } catch (error: Exception) {
            if (error is SessionExpiredException) sessionManager?.markExpired()
            stateLock.withLock {
                val current = mutableTasks[taskId] ?: return
                if (current.status != DownloadTaskStatus.COMPLETED) return
                mutableTasks[taskId] = current.copy(
                    postProcessingStatus = PostProcessingStatus.FAILED,
                    postProcessingMessage = if (error is SessionExpiredException) {
                        "登录状态已过期。重新登录后可重试歌词和标签处理；音频无需重新下载。"
                    } else "附加信息处理失败。已下载的音频保留，可在任务页重试。",
                    updatedAtMillis = System.currentTimeMillis(),
                )
                publishLocked()
                persistLocked()
            }
        } finally {
            withContext(NonCancellable) {
                stateLock.withLock { activeJobs.remove(taskId) }
                schedule()
            }
        }
    }

    private suspend fun updateProgress(taskId: String, update: DownloadProgressUpdate) {
        stateLock.withLock {
            val task = mutableTasks[taskId] ?: return
            if (task.status != DownloadTaskStatus.DOWNLOADING) return
            mutableTasks[taskId] = DownloadTaskStateMachine.progress(
                task.copy(etag = update.etag ?: task.etag, lastModified = update.lastModified ?: task.lastModified),
                update.bytesDownloaded,
                update.totalBytes,
                update.speedBytesPerSecond,
            )
            publishLocked()
            val now = System.currentTimeMillis()
            if (now - lastProgressSaveMillis >= 1_000L) {
                lastProgressSaveMillis = now
                persistLocked()
            }
        }
    }

    private fun allocateResolvedPath(task: DownloadTask, type: String?): Path {
        val oldPath = Path.of(task.filePath).toAbsolutePath().normalize()
        val candidate = FileNamePolicy.withExtension(oldPath, type)
        val reserved = mutableTasks.values
            .filter { it.id != task.id && it.status != DownloadTaskStatus.CANCELLED }
            .mapTo(mutableSetOf()) { Path.of(it.filePath).toAbsolutePath().normalize() }
        if (settings.value.fileConflictPolicy == FileConflictPolicy.FAIL_IF_EXISTS) {
            if (candidate in reserved || Files.exists(candidate)) throw FileAlreadyExistsException(candidate.toString())
            return candidate
        }
        if (candidate !in reserved && !Files.exists(candidate)) return candidate
        val song = SongHit(task.songId, task.songName, task.artists, task.album)
        return FileNamePolicy.destination(
            candidate.parent,
            song,
            type,
            reservedPaths = reserved,
            conflictPolicy = FileConflictPolicy.KEEP_BOTH,
        )
    }

    private suspend fun persistLocked() {
        try {
            withContext(Dispatchers.IO) { store.save(taskListLocked(), mutableSettings.value) }
            mutablePersistenceWarning.value = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordPersistenceFailure(error)
        }
    }

    private fun recordPersistenceFailure(error: Throwable) {
        mutablePersistenceWarning.value = "无法保存下载任务状态：${safeStorageMessage(error)}"
    }

    private fun publishLocked() {
        mutableTasksFlow.value = taskListLocked()
    }

    private fun taskListLocked(): List<DownloadTask> = mutableTasks.values
        .sortedWith(compareBy<DownloadTask> { it.createdAtMillis }.thenBy { it.id })

    private fun partialSize(target: String): Long = runCatching {
        val part = transport.partialPath(Path.of(target))
        if (Files.isRegularFile(part)) Files.size(part) else 0L
    }.getOrDefault(0L)

    private fun removeCompletedAudioFile(filePath: String): CompletedFileRemoval {
        val path = runCatching { Path.of(filePath).toAbsolutePath().normalize() }.getOrNull()
            ?: return CompletedFileRemoval.UNSAFE_OR_FAILED
        val attributes = try {
            Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return CompletedFileRemoval.MISSING
        } catch (_: Exception) {
            return CompletedFileRemoval.UNSAFE_OR_FAILED
        }
        // Never follow or delete a link, directory, or other unexpected target.
        if (!attributes.isRegularFile) return CompletedFileRemoval.UNSAFE_OR_FAILED
        return try {
            Files.delete(path)
            CompletedFileRemoval.DELETED
        } catch (_: Exception) {
            CompletedFileRemoval.UNSAFE_OR_FAILED
        }
    }

    private fun userMessage(error: Exception, waitForCredentials: Boolean): String = when {
        error is SourceResolutionException -> error.message.orEmpty()
        error is AudioUrlPolicyException -> error.message.orEmpty()
        waitForCredentials -> "此曲目可能需要登录授权。请在搜索页提供凭据后继续。"
        error is FileAlreadyExistsException -> "目标文件已存在；请更改重名策略或移走已有文件。"
        error is InvalidDownloadException -> "服务返回的内容不是有效音频，任务已停止。"
        error is DownloadHttpException -> "下载服务器返回 HTTP ${error.statusCode}，可重试。"
        error is SocketTimeoutException -> "网络请求超时；已保留可恢复的下载数据。"
        error is IOException -> when (NetworkFailureClassifier.category(error)) {
            "DNS_RESOLUTION_FAILED" -> "DNS 无法解析音源主机；已保留可恢复的下载数据。"
            "PROXY_OR_ROUTE_UNAVAILABLE" -> "网络代理或路由暂不可用；已保留可恢复的下载数据。"
            "TLS_FAILED" -> "TLS 证书验证失败；已保留可恢复的下载数据。"
            else -> "网络或文件操作失败；已保留可恢复的下载数据。"
        }
        else -> "音源解析失败；请检查授权和网络后重试。"
    }

    private fun safeStorageMessage(error: Throwable): String = when (error) {
        is IOException -> "磁盘空间或权限不足"
        else -> "本地存储不可用"
    }

    private suspend fun resumeAuthorizationWaitingTasks() {
        if (sessionManager?.credentialForRequest().isNullOrBlank()) return
        schedulerLock.withLock {
            sessionSchedulePaused = false
            stateLock.withLock {
                var changed = false
                mutableTasks.values.toList().forEach { task ->
                    if (task.status == DownloadTaskStatus.PAUSED &&
                        task.errorType == DownloadErrorType.AUTHORIZATION_REQUIRED
                    ) {
                        mutableTasks[task.id] = DownloadTaskStateMachine.transition(
                            task,
                            DownloadTaskStatus.QUEUED,
                        )
                        changed = true
                    }
                }
                if (changed) {
                    publishLocked()
                    persistLocked()
                }
            }
        }
        schedule()
    }
}
