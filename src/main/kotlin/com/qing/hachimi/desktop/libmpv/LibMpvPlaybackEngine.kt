package com.qing.hachimi.desktop.libmpv

import com.qing.hachimi.desktop.LocalTrack
import com.qing.hachimi.desktop.PlaybackEngine
import com.qing.hachimi.desktop.PlaybackEvent
import com.qing.hachimi.desktop.PlaybackSessionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Production local-file playback through libmpv. A dedicated worker owns the native handle and
 * serializes every client call with event polling. The only configured audio output is WASAPI.
 */
class LibMpvPlaybackEngine internal constructor(
    private val libraryPath: Path,
    clientFactory: MpvClientFactory = JnaMpvClientFactory,
    private val loadTimeoutMillis: Long = DEFAULT_LOAD_TIMEOUT_MILLIS,
    private val outputTimeoutMillis: Long = DEFAULT_OUTPUT_TIMEOUT_MILLIS,
) : PlaybackEngine {
    private val mutableEvents = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 64)
    override val events: Flow<PlaybackEvent> = mutableEvents.asSharedFlow()

    private val mutableCurrentAudioOutput = MutableStateFlow<String?>(null)
    val currentAudioOutput: StateFlow<String?> = mutableCurrentAudioOutput.asStateFlow()
    private val mutableClientApiVersion = AtomicInteger(0)
    val clientApiVersion: Int get() = mutableClientApiVersion.get()

    private val commands = NativeWorkerCommandQueue<WorkerCommand>()
    private val startup = CompletableDeferred<Unit>()
    private val workerDone = CompletableDeferred<Unit>()
    private val closeRequested = AtomicBoolean(false)
    private val workerStopping = AtomicBoolean(false)
    private val workerFailure = AtomicReference<IllegalStateException?>(null)
    private val nextCommandUserdata = AtomicLong(100L)
    private val workerThread = Thread({ runNativeWorker(clientFactory) }, "dmc-libmpv-native").apply {
        isDaemon = true
    }

    private var client: MpvClient? = null
    private var activeSessionId: PlaybackSessionId? = null
    private var activeEntryId: Long? = null
    private val currentAudioObserverId = CURRENT_AUDIO_OBSERVER_ID
    private val loadsByCommand = mutableMapOf<Long, LoadRequest>()
    private val pendingStarts = ArrayDeque<LoadRequest>()
    private val loadsBySession = mutableMapOf<PlaybackSessionId, LoadRequest>()
    private val sessionsByEntry = mutableMapOf<Long, PlaybackSessionId>()
    private val propertySessions = mutableMapOf<Long, PlaybackSessionId>()
    private val propertyIdsBySession = mutableMapOf<PlaybackSessionId, MutableList<Long>>()
    private val latestPositions = mutableMapOf<PlaybackSessionId, Long>()
    private val latestDurations = mutableMapOf<PlaybackSessionId, Long>()
    private val fileNamesBySession = mutableMapOf<PlaybackSessionId, String>()
    private val pathsBySession = mutableMapOf<PlaybackSessionId, String>()
    private var nextObserverId = 1_000L
    private var lastSessionValue = Long.MIN_VALUE

    init {
        require(loadTimeoutMillis > 0) { "Load timeout must be positive." }
        require(outputTimeoutMillis > 0) { "Audio-output timeout must be positive." }
        workerThread.start()
    }

    override suspend fun load(track: LocalTrack, sessionId: PlaybackSessionId) {
        val path = Path.of(track.filePath).toAbsolutePath().normalize()
        require(Files.isRegularFile(path) && Files.isReadable(path)) {
            "The local audio file is missing or cannot be read."
        }
        awaitStartup()
        val ready = CompletableDeferred<Unit>()
        try {
            submit {
                beginLoad(path, sessionId, ready)
            }
            withTimeout(loadTimeoutMillis) { ready.await() }
        } catch (failure: Throwable) {
            if (failure is CancellationException) {
                withContext(NonCancellable) {
                    runCatching { submit { cancelLoad(sessionId) } }
                }
                if (failure is TimeoutCancellationException) {
                    throw IllegalStateException("Timed out while waiting for libmpv to load the local audio file.", failure)
                }
            }
            throw failure
        }
    }

    override suspend fun play(sessionId: PlaybackSessionId) {
        val started = submit {
            if (activeSessionId != sessionId) return@submit false
            checkNative("resume playback") { it.setPropertyString("pause", "no") }
            true
        }
        if (!started) return
        try {
            withTimeout(outputTimeoutMillis) {
                currentAudioOutput.first { it.equals("wasapi", ignoreCase = true) }
            }
        } catch (failure: TimeoutCancellationException) {
            runCatching { submit { if (activeSessionId == sessionId) client?.setPropertyString("pause", "yes") } }
            throw IllegalStateException("Windows shared audio output (WASAPI) did not become active.", failure)
        }
    }

    override suspend fun pause(sessionId: PlaybackSessionId) {
        submit {
            if (activeSessionId == sessionId) checkNative("pause playback") { it.setPropertyString("pause", "yes") }
        }
    }

    override suspend fun stop(sessionId: PlaybackSessionId?) {
        submit {
            if (sessionId != null && activeSessionId != sessionId) return@submit
            val previousSession = activeSessionId
            val previousRequest = previousSession?.let(loadsBySession::remove)
            previousRequest?.ready?.completeExceptionally(CancellationException("Playback was stopped."))
            loadsBySession.clear()
            activeSessionId = null
            activeEntryId = null
            val stopFailure = runCatching { checkNative("stop playback") { it.command(listOf("stop")) } }.exceptionOrNull()
            runCatching { drainNativeEvents() }
            previousRequest?.let(::forgetLoadCorrelation)
            previousSession?.let {
                removePropertyObservers(it)
                clearTrackState(it)
            }
            stopFailure?.let { throw it }
        }
    }

    override suspend fun seek(sessionId: PlaybackSessionId, positionMillis: Long) {
        submit {
            if (activeSessionId != sessionId) return@submit
            val seconds = positionMillis.coerceAtLeast(0L) / 1_000.0
            checkNative("seek playback") {
                it.setPropertyString("time-pos", String.format(Locale.ROOT, "%.3f", seconds).trimEnd('0').trimEnd('.'))
            }
        }
    }

    override suspend fun setVolume(sessionId: PlaybackSessionId, volume: Float) {
        submit {
            if (activeSessionId != sessionId) return@submit
            val percent = volume.coerceIn(0f, 1f) * 100f
            checkNative("set playback volume") {
                it.setPropertyString("volume", String.format(Locale.ROOT, "%.2f", percent).trimEnd('0').trimEnd('.'))
            }
        }
    }

    override suspend fun close() {
        if (closeRequested.compareAndSet(false, true)) {
            // Reject new calls now; let the native worker process the close marker after its
            // bounded event wait returns, then terminate the context on that same worker.
            commands.offer(WorkerCommand(action = { closeOnWorker() })) { }
        }
        withContext(NonCancellable + Dispatchers.IO) {
            workerDone.await()
            workerThread.join()
        }
    }

    internal fun isWorkerAlive(): Boolean = workerThread.isAlive

    private suspend fun awaitStartup() {
        if (closeRequested.get()) throw IllegalStateException("The libmpv playback engine is closed or unavailable.")
        // Await first so a completed startup exception remains visible to the caller. Checking
        // workerDone before this await masks native initialization failures as a generic close.
        startup.await()
        ensureWorkerAccepting()
    }

    private suspend fun <T> submit(block: () -> T): T {
        awaitStartup()
        ensureWorkerAccepting()
        val result = CompletableFuture<T>()
        commands.offer(WorkerCommand(
                action = {
                    try {
                        result.complete(block())
                    } catch (failure: Throwable) {
                        result.completeExceptionally(failure)
                    }
                },
                reject = result::completeExceptionally,
            )) { failure -> result.completeExceptionally(failure) }
        return result.awaitCancellable()
    }

    private fun ensureWorkerAccepting() {
        workerFailure.get()?.let { failure ->
            throw IllegalStateException("The libmpv native worker stopped: ${failure.message}", failure)
        }
        if (closeRequested.get() || workerStopping.get() || workerDone.isCompleted) {
            throw IllegalStateException("The libmpv playback engine is closed or unavailable.")
        }
    }

    private fun runNativeWorker(clientFactory: MpvClientFactory) {
        var initialized = false
        try {
            val native = clientFactory.create(libraryPath)
            client = native
            initializeClient(native)
            mutableClientApiVersion.set(native.clientApiVersion())
            initialized = true
            startup.complete(Unit)

            while (!workerStopping.get()) {
                drainCommands()
                if (!workerStopping.get()) processNativeEvent(native.waitEvent(EVENT_POLL_MILLIS))
            }
        } catch (failure: Throwable) {
            val safeFailure = safeStartupFailure(failure)
            workerFailure.compareAndSet(null, safeFailure)
            if (!startup.isCompleted) startup.completeExceptionally(safeFailure)
            failActiveLoads(safeFailure)
            if (initialized) {
                activeSessionId?.let { session ->
                    mutableEvents.tryEmit(PlaybackEvent.Failed(session, safeMessage(session, failure.message)))
                }
            }
            commands.stop(safeFailure) { queued, cause -> queued.reject(cause) }
        } finally {
            commands.stop(IllegalStateException("The libmpv playback engine is closed.")) { queued, cause ->
                queued.reject(cause)
            }
            failActiveLoads(CancellationException("The libmpv playback engine is closed."))
            client?.let { runCatching { it.close() } }
            client = null
            if (!startup.isCompleted) startup.completeExceptionally(IllegalStateException("libmpv worker stopped during startup."))
            workerDone.complete(Unit)
        }
    }

    private fun initializeClient(native: MpvClient) {
        listOf(
            "config" to "no",
            "load-scripts" to "no",
            "idle" to "yes",
            "pause" to "yes",
            "ao" to "wasapi",
            "audio-display" to "no",
            "audio-exclusive" to "no",
            "audio-fallback-to-null" to "no",
        ).forEach { (name, value) -> checkNative("configure $name") { it.setOption(name, value) } }
        listOf(
            MpvNativeEventId.COMMAND_REPLY,
            MpvNativeEventId.START_FILE,
            MpvNativeEventId.END_FILE,
            MpvNativeEventId.FILE_LOADED,
            MpvNativeEventId.PROPERTY_CHANGE,
            MpvNativeEventId.SHUTDOWN,
        ).forEach { eventId -> checkNative("subscribe to native events") { it.requestEvent(eventId, true) } }
        checkNative("initialize WASAPI output") { native.initialize() }
        checkNative("observe active Windows audio output") {
            native.observeProperty(currentAudioObserverId, "current-ao", MpvPropertyFormat.STRING)
        }
    }

    private fun drainCommands() {
        while (true) {
            val command = commands.poll() ?: return
            try {
                command.action()
            } catch (failure: Throwable) {
                // A failed UI operation is returned through its future; it must not kill the
                // shared native event loop or prevent the queue from making progress.
            }
            if (workerStopping.get()) return
        }
    }

    private fun beginLoad(path: Path, sessionId: PlaybackSessionId, ready: CompletableDeferred<Unit>) {
        if (sessionId.value <= lastSessionValue) {
            throw IllegalStateException("This playback session ID is stale and cannot be reused.")
        }
        val current = activeSessionId
        if (current != null && sessionId.value <= current.value) {
            throw IllegalStateException("This playback session ID is stale and cannot be reused.")
        }
        current?.let { old ->
            val oldRequest = loadsBySession.remove(old)
            oldRequest?.ready?.completeExceptionally(CancellationException("A newer track replaced this load."))
            removePropertyObservers(old)
            val stopFailure = runCatching {
                checkNative("stop the previous track") { it.command(listOf("stop")) }
            }.exceptionOrNull()
            runCatching { drainNativeEvents() }
            oldRequest?.let(::forgetLoadCorrelation)
            clearTrackState(old)
            stopFailure?.let { throw it }
        }
        lastSessionValue = maxOf(lastSessionValue, sessionId.value)
        activeSessionId = sessionId
        activeEntryId = null
        latestPositions[sessionId] = 0L
        latestDurations[sessionId] = 0L
        fileNamesBySession[sessionId] = path.fileName?.toString() ?: "audio file"
        pathsBySession[sessionId] = path.toString()
        registerProperties(sessionId)
        checkNative("pause before loading") { it.setPropertyString("pause", "yes") }

        val userdata = nextCommandUserdata.incrementAndGet()
        val request = LoadRequest(sessionId, ready, userdata)
        loadsByCommand[userdata] = request
        pendingStarts.addLast(request)
        loadsBySession[sessionId] = request
        val result = client!!.commandAsync(userdata, listOf("loadfile", path.toString(), "replace"))
        if (result < 0) {
            loadsByCommand.remove(userdata)
            pendingStarts.remove(request)
            loadsBySession.remove(sessionId)
            val message = safeMessage(sessionId, "libmpv rejected the local audio load request (${client!!.errorString(result)}).")
            request.ready.completeExceptionally(IllegalStateException(message))
            if (activeSessionId == sessionId) mutableEvents.tryEmit(PlaybackEvent.Failed(sessionId, message))
            throw IllegalStateException(message)
        }
    }

    private fun registerProperties(sessionId: PlaybackSessionId) {
        val ids = mutableListOf<Long>()
        listOf(
            "time-pos" to MpvPropertyFormat.DOUBLE,
            "duration" to MpvPropertyFormat.DOUBLE,
        ).forEach { (name, format) ->
            val id = nextObserverId++
            checkNative("observe playback progress") { it.observeProperty(id, name, format) }
            propertySessions[id] = sessionId
            ids += id
        }
        propertyIdsBySession[sessionId] = ids
    }

    private fun removePropertyObservers(sessionId: PlaybackSessionId) {
        propertyIdsBySession.remove(sessionId).orEmpty().forEach { id ->
            runCatching { client?.unobserveProperty(id) }
            propertySessions.remove(id)
        }
    }

    private fun cancelLoad(sessionId: PlaybackSessionId) {
        val request = loadsBySession.remove(sessionId)
        request?.ready?.completeExceptionally(CancellationException("The local audio load was cancelled."))
        if (activeSessionId == sessionId) {
            activeSessionId = null
            activeEntryId = null
            removePropertyObservers(sessionId)
            runCatching { client?.command(listOf("stop")) }
            drainNativeEvents()
        }
        request?.let(::forgetLoadCorrelation)
        clearTrackState(sessionId)
    }

    private fun forgetLoadCorrelation(request: LoadRequest) {
        loadsByCommand.remove(request.commandUserdata)
        pendingStarts.remove(request)
    }

    /** Drain events caused by a synchronous stop before a new load can enter the native queue. */
    private fun drainNativeEvents() {
        repeat(MAX_STOP_DRAIN_EVENTS) {
            val event = client?.waitEvent(0) ?: return
            if (event == MpvNativeEvent.None) return
            processNativeEvent(event)
        }
    }

    private fun processNativeEvent(event: MpvNativeEvent) {
        when (event) {
            MpvNativeEvent.None -> Unit
            MpvNativeEvent.Shutdown -> {
                workerFailure.compareAndSet(null, IllegalStateException("libmpv shut down unexpectedly."))
                activeSessionId?.let { session ->
                    mutableEvents.tryEmit(PlaybackEvent.Failed(session, "libmpv shut down unexpectedly."))
                }
                failActiveLoads(IllegalStateException("libmpv shut down unexpectedly."))
                workerStopping.set(true)
            }
            is MpvNativeEvent.CommandReply -> processCommandReply(event)
            is MpvNativeEvent.StartFile -> processStartFile(event)
            MpvNativeEvent.FileLoaded -> processFileLoaded()
            is MpvNativeEvent.EndFile -> processEndFile(event)
            is MpvNativeEvent.PropertyChanged -> processProperty(event)
            is MpvNativeEvent.Other -> Unit
        }
    }

    private fun processCommandReply(event: MpvNativeEvent.CommandReply) {
        val request = loadsByCommand.remove(event.userdata) ?: return
        if (event.error < 0) {
            pendingStarts.remove(request)
            loadsBySession.remove(request.sessionId)
            val message = safeMessage(
                request.sessionId,
                "libmpv could not load this local audio file (${client?.errorString(event.error) ?: event.error}).",
            )
            request.ready.completeExceptionally(IllegalStateException(message))
            emitFailureIfCurrent(request.sessionId, message)
            removePropertyObservers(request.sessionId)
            clearTrackState(request.sessionId)
        } else {
            request.commandSucceeded = true
            completeLoadIfReady(request)
        }
    }

    private fun processStartFile(event: MpvNativeEvent.StartFile) {
        val request = pendingStarts.pollFirst() ?: return
        request.playlistEntryId = event.playlistEntryId
        sessionsByEntry[event.playlistEntryId] = request.sessionId
        activeEntryId = event.playlistEntryId
    }

    private fun processFileLoaded() {
        val entryId = activeEntryId ?: return
        val session = sessionsByEntry[entryId] ?: return
        val request = loadsBySession[session] ?: return
        request.fileLoaded = true
        emitPosition(session)
        completeLoadIfReady(request)
    }

    private fun processEndFile(event: MpvNativeEvent.EndFile) {
        val session = sessionsByEntry.remove(event.playlistEntryId) ?: return
        if (activeEntryId == event.playlistEntryId) activeEntryId = null
        val request = loadsBySession.remove(session)
        if (request != null && !request.ready.isCompleted) {
            val detail = if (event.error < 0) client?.errorString(event.error) else null
            val message = safeMessage(
                session,
                detail?.let { "libmpv failed while loading the local audio file ($it)." }
                    ?: "libmpv ended before the local audio file loaded.",
            )
            loadsByCommand.remove(request.commandUserdata)
            request.ready.completeExceptionally(IllegalStateException(message))
            emitFailureIfCurrent(session, message)
            forgetLoadCorrelation(request)
            removePropertyObservers(session)
            clearTrackState(session)
            return
        }
        when (event.reason) {
            MpvEndReason.EOF -> mutableEvents.tryEmit(PlaybackEvent.Ended(session))
            MpvEndReason.ERROR -> {
                val detail = if (event.error < 0) client?.errorString(event.error) else null
                val message = safeMessage(session, detail?.let { "libmpv could not play this local audio file ($it)." }
                    ?: "libmpv could not play this local audio file."
                )
                emitFailureIfCurrent(session, message)
            }
            MpvEndReason.STOP, MpvEndReason.QUIT, MpvEndReason.REDIRECT, MpvEndReason.UNKNOWN -> Unit
        }
        removePropertyObservers(session)
        clearTrackState(session)
    }

    private fun processProperty(event: MpvNativeEvent.PropertyChanged) {
        if (event.userdata == currentAudioObserverId && event.name == "current-ao") {
            mutableCurrentAudioOutput.value = (event.value as? MpvPropertyValue.Text)?.value
            return
        }
        val session = propertySessions[event.userdata] ?: return
        if (session != activeSessionId) return
        when (event.name) {
            "time-pos" -> (event.value as? MpvPropertyValue.Number)?.value?.toMillisOrNull()?.let { latestPositions[session] = it }
            "duration" -> (event.value as? MpvPropertyValue.Number)?.value?.toMillisOrNull()?.let { latestDurations[session] = it }
        }
        emitPosition(session)
    }

    private fun completeLoadIfReady(request: LoadRequest) {
        if (request.fileLoaded && request.commandSucceeded && activeSessionId == request.sessionId) {
            request.ready.complete(Unit)
        }
    }

    private fun emitPosition(sessionId: PlaybackSessionId) {
        if (activeSessionId != sessionId) return
        mutableEvents.tryEmit(
            PlaybackEvent.Position(
                sessionId = sessionId,
                positionMillis = latestPositions[sessionId] ?: 0L,
                durationMillis = latestDurations[sessionId] ?: 0L,
            ),
        )
    }

    private fun emitFailureIfCurrent(sessionId: PlaybackSessionId, message: String) {
        if (activeSessionId == sessionId) mutableEvents.tryEmit(PlaybackEvent.Failed(sessionId, safeMessage(sessionId, message)))
    }

    private fun safeMessage(sessionId: PlaybackSessionId, message: String?): String {
        var sanitized = message?.takeIf(String::isNotBlank) ?: "libmpv could not play this local audio file."
        fileNamesBySession[sessionId]?.let { fileName ->
            sanitized = sanitized.replace(fileName, "the audio file", ignoreCase = true)
        }
        pathsBySession[sessionId]?.let { path ->
            sanitized = sanitized.replace(path, "the audio file", ignoreCase = true)
                .replace(path.replace('\\', '/'), "the audio file", ignoreCase = true)
        }
        return sanitized
    }

    private fun clearTrackState(sessionId: PlaybackSessionId) {
        latestPositions.remove(sessionId)
        latestDurations.remove(sessionId)
        fileNamesBySession.remove(sessionId)
        pathsBySession.remove(sessionId)
    }

    private fun checkNative(operation: String, action: (MpvClient) -> Int) {
        val native = client ?: error("libmpv is not initialized.")
        val status = action(native)
        if (status < 0) {
            val message = "libmpv could not $operation (${native.errorString(status)})."
            throw IllegalStateException(activeSessionId?.let { safeMessage(it, message) } ?: message)
        }
    }

    private fun failActiveLoads(failure: Throwable) {
        loadsBySession.values.toList().forEach { request -> request.ready.completeExceptionally(failure) }
        loadsBySession.clear()
        loadsByCommand.clear()
        pendingStarts.clear()
    }

    private fun closeOnWorker() {
        failActiveLoads(CancellationException("The playback engine is closed."))
        loadsBySession.clear()
        activeSessionId = null
        activeEntryId = null
        workerStopping.set(true)
    }

    private fun safeStartupFailure(failure: Throwable): IllegalStateException {
        val message = failure.message?.takeIf(String::isNotBlank) ?: "The libmpv playback library could not be initialized."
        return IllegalStateException(message.replace(libraryPath.toString(), libraryPath.fileName?.toString() ?: "libmpv"))
    }

    private data class LoadRequest(
        val sessionId: PlaybackSessionId,
        val ready: CompletableDeferred<Unit>,
        val commandUserdata: Long,
        var commandSucceeded: Boolean = false,
        var fileLoaded: Boolean = false,
        var playlistEntryId: Long? = null,
    )

    private class WorkerCommand(
        val action: () -> Unit,
        val reject: (Throwable) -> Unit = {},
    )

    private suspend fun <T> CompletableFuture<T>.awaitCancellable(): T = suspendCancellableCoroutine { continuation ->
        whenComplete { value, failure ->
            if (failure == null) continuation.resume(value)
            else continuation.resumeWithException(failure.cause ?: failure)
        }
        continuation.invokeOnCancellation { cancel(true) }
    }

    private fun Double.toMillisOrNull(): Long? =
        if (isFinite() && this >= 0.0) (this * 1_000.0).toLong() else null

    private companion object {
        const val DEFAULT_LOAD_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_OUTPUT_TIMEOUT_MILLIS = 5_000L
        const val EVENT_POLL_MILLIS = 50L
        const val CURRENT_AUDIO_OBSERVER_ID = 1L
        const val MAX_STOP_DRAIN_EVENTS = 256
    }
}
