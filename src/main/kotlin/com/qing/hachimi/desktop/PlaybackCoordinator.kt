package com.qing.hachimi.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PlaybackSessionId(val value: Long)

sealed interface PlaybackEvent {
    val sessionId: PlaybackSessionId

    data class Position(
        override val sessionId: PlaybackSessionId,
        val positionMillis: Long,
        val durationMillis: Long,
    ) : PlaybackEvent

    data class Ended(override val sessionId: PlaybackSessionId) : PlaybackEvent
    data class Failed(override val sessionId: PlaybackSessionId, val message: String) : PlaybackEvent
}

interface PlaybackEngine {
    val events: Flow<PlaybackEvent>
    suspend fun load(track: LocalTrack, sessionId: PlaybackSessionId)
    suspend fun play(sessionId: PlaybackSessionId)
    suspend fun pause(sessionId: PlaybackSessionId)
    suspend fun stop(sessionId: PlaybackSessionId? = null)
    suspend fun seek(sessionId: PlaybackSessionId, positionMillis: Long)
    suspend fun setVolume(sessionId: PlaybackSessionId, volume: Float)
    suspend fun close()
}

enum class PlaybackRepeatMode { OFF, ALL, ONE }

data class PlaybackState(
    val queue: List<LocalTrack> = emptyList(),
    val queueIndex: Int = -1,
    val currentTrack: LocalTrack? = null,
    val sessionId: PlaybackSessionId? = null,
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val volume: Float = 1f,
    val shuffle: Boolean = false,
    val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
    val error: String? = null,
)

/** Serializes player state changes while keeping asynchronous media loading outside the state lock. */
class PlaybackCoordinator(
    private val engine: PlaybackEngine,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + dispatcher)
    private val stateMutex = Mutex()
    private val engineMutex = Mutex()
    private val mutableState = MutableStateFlow(PlaybackState())
    private var loadedPathKey: String? = null
    private var nextSessionValue = 0L
    private val playWhenLoaded = mutableMapOf<PlaybackSessionId, Boolean>()

    private val eventJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        engine.events.collect { event ->
            val work = stateMutex.withLock {
                if (event.sessionId != mutableState.value.sessionId) return@withLock null
                when (event) {
                    is PlaybackEvent.Ended -> advanceLocked(naturalEnd = true)
                    is PlaybackEvent.Failed -> {
                        loadedPathKey = null
                        playWhenLoaded.remove(event.sessionId)
                        mutableState.value = mutableState.value.copy(
                            isLoading = false,
                            isPlaying = false,
                            error = event.message,
                        )
                        null
                    }
                    is PlaybackEvent.Position -> {
                        mutableState.value = mutableState.value.copy(
                            positionMillis = event.positionMillis.coerceAtLeast(0),
                            durationMillis = event.durationMillis.coerceAtLeast(0),
                        )
                        null
                    }
                }
            }
            execute(work)
        }
    }

    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    suspend fun enqueue(tracks: List<LocalTrack>, startIfIdle: Boolean = true) {
        val work = stateMutex.withLock {
            val current = mutableState.value
            val known = current.queue.mapTo(HashSet()) { it.pathKey }
            val additions = tracks.filter { it.pathKey.isNotBlank() && known.add(it.pathKey) }
            if (additions.isEmpty()) return@withLock null
            mutableState.value = current.copy(queue = current.queue + additions, error = null)
            if (current.currentTrack == null && startIfIdle) prepareStartLocked(0) else null
        }
        execute(work)
    }

    suspend fun play(track: LocalTrack) = playTracks(listOf(track))

    suspend fun playTracks(tracks: List<LocalTrack>) {
        val work = stateMutex.withLock {
            val queue = tracks.asSequence().filter { it.pathKey.isNotBlank() }.distinctBy { it.pathKey }.toList()
            if (queue.isEmpty()) return@withLock null
            playWhenLoaded.clear()
            loadedPathKey = null
            mutableState.value = mutableState.value.copy(
                queue = queue,
                queueIndex = -1,
                currentTrack = null,
                sessionId = null,
                isLoading = false,
                isPlaying = false,
                positionMillis = 0L,
                durationMillis = 0L,
                error = null,
            )
            prepareStartLocked(0)
        }
        execute(work)
    }

    suspend fun playIndex(index: Int) {
        val work = stateMutex.withLock { prepareStartLocked(index) }
        execute(work)
    }

    suspend fun playCurrent() {
        val work = stateMutex.withLock {
            val current = mutableState.value
            val track = current.currentTrack
            if (track == null) {
                prepareStartLocked(current.queueIndex.coerceAtLeast(0))
            } else if (current.isLoading && current.sessionId != null) {
                playWhenLoaded[current.sessionId] = true
                null
            } else if (current.sessionId != null && loadedPathKey == track.pathKey) {
                EngineWork.Play(current.sessionId, current.positionMillis)
            } else {
                prepareCurrentLocked(
                    track = track,
                    index = current.queueIndex,
                    positionMillis = current.positionMillis,
                    shouldPlay = true,
                )
            }
        }
        execute(work)
    }

    suspend fun restoreQueue(snapshot: RestoredPlaybackQueue) = stateMutex.withLock {
        playWhenLoaded.clear()
        loadedPathKey = null
        mutableState.value = PlaybackState(
            queue = snapshot.queue,
            queueIndex = snapshot.queueIndex,
            currentTrack = snapshot.queue.getOrNull(snapshot.queueIndex),
            positionMillis = snapshot.positionMillis,
            volume = snapshot.volume,
            shuffle = snapshot.shuffle,
            repeatMode = snapshot.repeatMode,
            isPlaying = false,
            isLoading = false,
            error = snapshot.warning,
        )
    }

    suspend fun pause() {
        val work = stateMutex.withLock {
            val current = mutableState.value
            val sessionId = current.sessionId ?: return@withLock null
            if (current.isLoading) {
                playWhenLoaded[sessionId] = false
                mutableState.value = current.copy(isPlaying = false)
                null
            } else {
                EngineWork.Pause(sessionId)
            }
        }
        execute(work)
    }

    suspend fun togglePlayback() {
        val work = stateMutex.withLock {
            val current = mutableState.value
            val sessionId = current.sessionId
            when {
                current.isPlaying && sessionId != null -> EngineWork.Pause(sessionId)
                current.isLoading && sessionId != null -> {
                    playWhenLoaded[sessionId] = true
                    null
                }
                current.currentTrack != null && sessionId != null && loadedPathKey == current.currentTrack.pathKey ->
                    EngineWork.Play(sessionId, current.positionMillis)
                current.currentTrack != null -> prepareCurrentLocked(
                    current.currentTrack,
                    current.queueIndex,
                    current.positionMillis,
                    shouldPlay = true,
                )
                current.queue.isNotEmpty() -> prepareStartLocked(current.queueIndex.coerceAtLeast(0))
                else -> null
            }
        }
        execute(work)
    }

    suspend fun next() {
        val work = stateMutex.withLock { advanceLocked(naturalEnd = false) }
        execute(work)
    }

    suspend fun previous() {
        val work = stateMutex.withLock {
            val current = mutableState.value
            val track = current.currentTrack ?: return@withLock null
            if (current.positionMillis > PREVIOUS_RESTART_THRESHOLD_MILLIS) {
                val sessionId = current.sessionId
                if (sessionId != null && current.isLoading) {
                    mutableState.value = current.copy(positionMillis = 0L)
                    null
                } else if (sessionId != null && loadedPathKey == track.pathKey) {
                    mutableState.value = current.copy(positionMillis = 0L)
                    EngineWork.Seek(sessionId, 0L)
                } else {
                    prepareCurrentLocked(track, current.queueIndex, 0L, shouldPlay = current.isPlaying)
                }
            } else {
                val previousIndex = when {
                    current.queueIndex > 0 -> current.queueIndex - 1
                    current.repeatMode == PlaybackRepeatMode.ALL && current.queue.isNotEmpty() -> current.queue.lastIndex
                    else -> current.queueIndex
                }
                if (previousIndex >= 0) prepareStartLocked(previousIndex) else null
            }
        }
        execute(work)
    }

    suspend fun seek(positionMillis: Long) {
        val work = stateMutex.withLock { seekLocked(positionMillis) }
        execute(work)
    }

    suspend fun seekBy(deltaMillis: Long) {
        val position = stateMutex.withLock { mutableState.value.positionMillis.coerceAtLeast(0L) }
        val target = try {
            Math.addExact(position, deltaMillis)
        } catch (_: ArithmeticException) {
            if (deltaMillis >= 0L) Long.MAX_VALUE else 0L
        }
        seek(target)
    }

    suspend fun setVolume(volume: Float) {
        val work = stateMutex.withLock {
            val safeVolume = volume.coerceIn(0f, 1f)
            val current = mutableState.value
            mutableState.value = current.copy(volume = safeVolume)
            val sessionId = current.sessionId
            if (sessionId != null && !current.isLoading) EngineWork.Volume(sessionId, safeVolume) else null
        }
        execute(work)
    }

    suspend fun setRepeatMode(mode: PlaybackRepeatMode) = stateMutex.withLock {
        mutableState.value = mutableState.value.copy(repeatMode = mode)
    }

    suspend fun setShuffle(enabled: Boolean) = stateMutex.withLock {
        val state = mutableState.value
        if (state.shuffle == enabled) return@withLock
        val current = state.currentTrack
        val upcoming = state.queue.drop((state.queueIndex + 1).coerceAtLeast(0))
        val reordered = if (enabled) upcoming.shuffled() else upcoming.sortedBy { state.queue.indexOf(it) }
        val retained = if (current == null) state.queue.take(state.queueIndex.coerceAtLeast(0))
        else state.queue.take(state.queueIndex + 1)
        val nextQueue = retained + reordered
        val nextIndex = if (current == null) -1 else retained.lastIndex
        mutableState.value = state.copy(queue = nextQueue, queueIndex = nextIndex, shuffle = enabled)
    }

    suspend fun removeFromQueue(index: Int) {
        val work = stateMutex.withLock {
            val current = mutableState.value
            if (index !in current.queue.indices) return@withLock null
            if (index == current.queueIndex) {
                val nextQueue = current.queue.toMutableList().also { it.removeAt(index) }
                playWhenLoaded.remove(current.sessionId)
                loadedPathKey = null
                if (nextQueue.isEmpty()) {
                    mutableState.value = current.copy(
                        queue = emptyList(), queueIndex = -1, currentTrack = null,
                        sessionId = null, isLoading = false, isPlaying = false,
                        positionMillis = 0, durationMillis = 0,
                    )
                    current.sessionId?.let(EngineWork::Stop)
                } else {
                    mutableState.value = current.copy(
                        queue = nextQueue,
                        queueIndex = index.coerceAtMost(nextQueue.lastIndex),
                        currentTrack = null,
                        sessionId = null,
                        isLoading = false,
                        isPlaying = false,
                    )
                    prepareStartLocked(mutableState.value.queueIndex)
                }
            } else {
                val nextQueue = current.queue.toMutableList().also { it.removeAt(index) }
                val nextIndex = if (index < current.queueIndex) current.queueIndex - 1 else current.queueIndex
                mutableState.value = current.copy(queue = nextQueue, queueIndex = nextIndex)
                null
            }
        }
        execute(work)
    }

    suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) = stateMutex.withLock {
        val current = mutableState.value
        if (fromIndex !in current.queue.indices || toIndex !in current.queue.indices || fromIndex == toIndex) return@withLock
        val reordered = current.queue.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        val currentIndex = current.currentTrack?.let { track -> reordered.indexOfFirst { it.pathKey == track.pathKey } } ?: -1
        mutableState.value = current.copy(queue = reordered, queueIndex = currentIndex)
    }

    suspend fun clearQueue() {
        val work = stateMutex.withLock {
            val current = mutableState.value
            playWhenLoaded.remove(current.sessionId)
            loadedPathKey = null
            mutableState.value = current.copy(
                queue = emptyList(), queueIndex = -1, currentTrack = null, sessionId = null,
                isLoading = false, isPlaying = false, positionMillis = 0, durationMillis = 0,
            )
            current.sessionId?.let(EngineWork::Stop)
        }
        execute(work)
    }

    suspend fun close() {
        val sessionId = stateMutex.withLock {
            val current = mutableState.value
            playWhenLoaded.clear()
            loadedPathKey = null
            mutableState.value = current.copy(sessionId = null, isLoading = false, isPlaying = false)
            current.sessionId
        }
        eventJob.cancel()
        scopeJob.cancel()
        engineMutex.withLock {
            if (sessionId != null) runCatching { engine.stop(sessionId) }
            engine.close()
        }
    }

    private fun prepareStartLocked(index: Int): EngineWork.Start? {
        val current = mutableState.value
        if (index !in current.queue.indices) return null
        return prepareCurrentLocked(current.queue[index], index, 0L, shouldPlay = true)
    }

    private fun prepareCurrentLocked(
        track: LocalTrack,
        index: Int,
        positionMillis: Long,
        shouldPlay: Boolean,
    ): EngineWork.Start {
        val sessionId = PlaybackSessionId(++nextSessionValue)
        playWhenLoaded[sessionId] = shouldPlay
        loadedPathKey = null
        val current = mutableState.value
        mutableState.value = current.copy(
            queueIndex = index,
            currentTrack = track,
            sessionId = sessionId,
            isLoading = true,
            isPlaying = false,
            positionMillis = positionMillis.coerceAtLeast(0),
            durationMillis = 0,
            error = null,
        )
        return EngineWork.Start(track, sessionId)
    }

    private fun seekLocked(positionMillis: Long): EngineWork? {
        val current = mutableState.value
        val track = current.currentTrack ?: return null
        val bounded = positionMillis.coerceAtLeast(0).let { value ->
            if (current.durationMillis > 0) value.coerceAtMost(current.durationMillis) else value
        }
        val sessionId = current.sessionId
        mutableState.value = current.copy(positionMillis = bounded)
        if (sessionId != null && current.isLoading) return null
        if (sessionId != null && loadedPathKey == track.pathKey) return EngineWork.Seek(sessionId, bounded)
        return prepareCurrentLocked(track, current.queueIndex, bounded, shouldPlay = current.isPlaying)
    }

    private fun advanceLocked(naturalEnd: Boolean): EngineWork? {
        val current = mutableState.value
        if (current.currentTrack == null || current.queue.isEmpty()) return null
        val sessionId = current.sessionId
        if (current.repeatMode == PlaybackRepeatMode.ONE && sessionId != null) {
            mutableState.value = current.copy(positionMillis = 0L, isLoading = false, isPlaying = true, error = null)
            playWhenLoaded[sessionId] = true
            return EngineWork.Replay(sessionId)
        }
        val nextIndex = when {
            current.queueIndex < current.queue.lastIndex -> current.queueIndex + 1
            current.repeatMode == PlaybackRepeatMode.ALL -> 0
            else -> -1
        }
        if (nextIndex < 0) {
            playWhenLoaded.remove(sessionId)
            loadedPathKey = null
            mutableState.value = current.copy(
                sessionId = null,
                isLoading = false,
                isPlaying = false,
                positionMillis = 0,
                error = null,
            )
            return if (naturalEnd) null else sessionId?.let(EngineWork::Stop)
        }
        return prepareStartLocked(nextIndex)
    }

    private suspend fun execute(work: EngineWork?) {
        when (work) {
            null -> Unit
            is EngineWork.Start -> {
                val job = scope.launch { loadAndStart(work) }
                job.join()
            }
            is EngineWork.Play -> runControl(work.sessionId, {
                if (work.positionMillis > 0) engine.seek(work.sessionId, work.positionMillis)
                engine.play(work.sessionId)
            }) { it.copy(isPlaying = true, isLoading = false, error = null) }
            is EngineWork.Pause -> runControl(work.sessionId, { engine.pause(work.sessionId) }) {
                it.copy(isPlaying = false, error = null)
            }
            is EngineWork.Seek -> runControl(work.sessionId, { engine.seek(work.sessionId, work.positionMillis) }) { it }
            is EngineWork.Volume -> runControl(work.sessionId, { engine.setVolume(work.sessionId, work.volume) }) { it }
            is EngineWork.Stop -> engineMutex.withLock { engine.stop(work.sessionId) }
            is EngineWork.Replay -> runControl(work.sessionId, {
                engine.seek(work.sessionId, 0L)
                engine.play(work.sessionId)
            }) { it.copy(positionMillis = 0L, isPlaying = true, error = null) }
        }
    }

    private suspend fun loadAndStart(work: EngineWork.Start) {
        try {
            engine.load(work.track, work.sessionId)
            engineMutex.withLock {
                val snapshot = stateMutex.withLock {
                    val current = mutableState.value
                    if (current.sessionId != work.sessionId) null
                    else FinalizeSnapshot(
                        volume = current.volume,
                        positionMillis = current.positionMillis,
                        shouldPlay = playWhenLoaded[work.sessionId] ?: false,
                    )
                } ?: return
                engine.setVolume(work.sessionId, snapshot.volume)
                if (snapshot.positionMillis > 0) engine.seek(work.sessionId, snapshot.positionMillis)
                if (snapshot.shouldPlay) engine.play(work.sessionId) else engine.pause(work.sessionId)
                stateMutex.withLock {
                    val current = mutableState.value
                    if (current.sessionId == work.sessionId) {
                        loadedPathKey = work.track.pathKey
                        playWhenLoaded.remove(work.sessionId)
                        mutableState.value = current.copy(
                            isLoading = false,
                            isPlaying = snapshot.shouldPlay,
                            error = null,
                        )
                    }
                }
            }
        } catch (failure: CancellationException) {
            stateMutex.withLock {
                if (mutableState.value.sessionId == work.sessionId) {
                    playWhenLoaded.remove(work.sessionId)
                    mutableState.value = mutableState.value.copy(isLoading = false, isPlaying = false)
                }
            }
        } catch (failure: Exception) {
            stateMutex.withLock {
                if (mutableState.value.sessionId == work.sessionId) {
                    loadedPathKey = null
                    playWhenLoaded.remove(work.sessionId)
                    mutableState.value = mutableState.value.copy(
                        isLoading = false,
                        isPlaying = false,
                        error = failure.message?.takeIf(String::isNotBlank) ?: "无法播放此本地文件。",
                    )
                }
            }
        }
    }

    private suspend fun runControl(
        sessionId: PlaybackSessionId,
        action: suspend () -> Unit,
        onSuccess: (PlaybackState) -> PlaybackState,
    ) = engineMutex.withLock {
        val active = stateMutex.withLock { mutableState.value.sessionId == sessionId }
        if (!active) return@withLock
        try {
            action()
            stateMutex.withLock {
                if (mutableState.value.sessionId == sessionId) mutableState.value = onSuccess(mutableState.value)
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            stateMutex.withLock {
                if (mutableState.value.sessionId == sessionId) {
                    mutableState.value = mutableState.value.copy(isPlaying = false, error = failure.message ?: "播放器操作失败。")
                }
            }
        }
    }

    private data class FinalizeSnapshot(val volume: Float, val positionMillis: Long, val shouldPlay: Boolean)

    private sealed interface EngineWork {
        data class Start(val track: LocalTrack, val sessionId: PlaybackSessionId) : EngineWork
        data class Play(val sessionId: PlaybackSessionId, val positionMillis: Long) : EngineWork
        data class Pause(val sessionId: PlaybackSessionId) : EngineWork
        data class Seek(val sessionId: PlaybackSessionId, val positionMillis: Long) : EngineWork
        data class Volume(val sessionId: PlaybackSessionId, val volume: Float) : EngineWork
        data class Stop(val sessionId: PlaybackSessionId) : EngineWork
        data class Replay(val sessionId: PlaybackSessionId) : EngineWork
    }

    companion object {
        private const val PREVIOUS_RESTART_THRESHOLD_MILLIS = 3_000L
    }
}
