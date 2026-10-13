package com.qing.hachimi.desktop

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface PlaybackEvent {
    data class Position(val positionMillis: Long, val durationMillis: Long) : PlaybackEvent
    data object Ended : PlaybackEvent
    data class Failed(val message: String) : PlaybackEvent
}

interface PlaybackEngine {
    val events: Flow<PlaybackEvent>
    suspend fun load(track: LocalTrack)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seek(positionMillis: Long)
    suspend fun setVolume(volume: Float)
    suspend fun close()
}

enum class PlaybackRepeatMode { OFF, ALL, ONE }

data class PlaybackState(
    val queue: List<LocalTrack> = emptyList(),
    val queueIndex: Int = -1,
    val currentTrack: LocalTrack? = null,
    val isPlaying: Boolean = false,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val volume: Float = 1f,
    val shuffle: Boolean = false,
    val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
    val error: String? = null,
)

/** Serializes player commands and engine events. It only accepts local indexed tracks. */
class PlaybackCoordinator(
    private val engine: PlaybackEngine,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(PlaybackState())
    private var loadedPathKey: String? = null
    private val eventJob: Job = scope.launch {
        engine.events.collect { event ->
            mutex.withLock {
                when (event) {
                    PlaybackEvent.Ended -> advanceLocked()
                    is PlaybackEvent.Failed -> {
                        loadedPathKey = null
                        mutableState.update { it.copy(isPlaying = false, error = event.message) }
                    }
                    is PlaybackEvent.Position -> mutableState.update {
                        it.copy(
                            positionMillis = event.positionMillis.coerceAtLeast(0),
                            durationMillis = event.durationMillis.coerceAtLeast(0),
                        )
                    }
                }
            }
        }
    }

    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    suspend fun enqueue(tracks: List<LocalTrack>, startIfIdle: Boolean = true) = mutex.withLock {
        val current = mutableState.value
        val known = current.queue.mapTo(HashSet()) { it.pathKey }
        val additions = tracks.filter { it.pathKey.isNotBlank() && known.add(it.pathKey) }
        if (additions.isEmpty()) return@withLock
        val queue = current.queue + additions
        mutableState.update { it.copy(queue = queue, error = null) }
        if (current.currentTrack == null && startIfIdle) {
            startIndexLocked(0)
        }
    }

    suspend fun play(track: LocalTrack) = playTracks(listOf(track))

    suspend fun playTracks(tracks: List<LocalTrack>) = mutex.withLock {
        val queue = tracks.asSequence().filter { it.pathKey.isNotBlank() }.distinctBy { it.pathKey }.toList()
        if (queue.isEmpty()) return@withLock
        mutableState.value = mutableState.value.copy(
            queue = queue,
            queueIndex = -1,
            currentTrack = null,
            isPlaying = false,
            positionMillis = 0L,
            durationMillis = 0L,
            error = null,
        )
        loadedPathKey = null
        startIndexLocked(0)
    }

    suspend fun playIndex(index: Int) = mutex.withLock { startIndexLocked(index) }

    suspend fun playCurrent() = mutex.withLock {
        val current = mutableState.value
        if (current.currentTrack == null) {
            if (mutableState.value.queue.isNotEmpty()) startIndexLocked(mutableState.value.queueIndex.coerceAtLeast(0))
        } else {
            playCurrentLocked(current)
        }
    }

    suspend fun restoreQueue(snapshot: RestoredPlaybackQueue) = mutex.withLock {
        mutableState.value = PlaybackState(
            queue = snapshot.queue,
            queueIndex = snapshot.queueIndex,
            currentTrack = snapshot.queue.getOrNull(snapshot.queueIndex),
            positionMillis = snapshot.positionMillis,
            volume = snapshot.volume,
            shuffle = snapshot.shuffle,
            repeatMode = snapshot.repeatMode,
            isPlaying = false,
            error = snapshot.warning,
        )
        loadedPathKey = null
    }

    suspend fun pause() = mutex.withLock {
        runEngineAction(action = { engine.pause() }, onSuccess = { it.copy(isPlaying = false) })
    }

    suspend fun togglePlayback() = mutex.withLock {
        val current = mutableState.value
        if (current.isPlaying) runEngineAction(action = { engine.pause() }, onSuccess = { it.copy(isPlaying = false) })
        else if (current.currentTrack != null) playCurrentLocked(current)
        else if (current.queue.isNotEmpty()) startIndexLocked(current.queueIndex.coerceAtLeast(0))
    }

    suspend fun next() = mutex.withLock { advanceLocked() }

    suspend fun previous() = mutex.withLock {
        val current = mutableState.value
        if (current.currentTrack == null) return@withLock
        if (current.positionMillis > PREVIOUS_RESTART_THRESHOLD_MILLIS) {
            seekLocked(0)
        } else {
            val previousIndex = when {
                current.queueIndex > 0 -> current.queueIndex - 1
                current.repeatMode == PlaybackRepeatMode.ALL && current.queue.isNotEmpty() -> current.queue.lastIndex
                else -> current.queueIndex
            }
            if (previousIndex >= 0) startIndexLocked(previousIndex)
        }
    }

    suspend fun seek(positionMillis: Long) = mutex.withLock { seekLocked(positionMillis) }

    suspend fun setVolume(volume: Float) = mutex.withLock {
        val safeVolume = volume.coerceIn(0f, 1f)
        runEngineAction(action = { engine.setVolume(safeVolume) }, onSuccess = { it.copy(volume = safeVolume) })
    }

    suspend fun setRepeatMode(mode: PlaybackRepeatMode) = mutex.withLock {
        mutableState.update { it.copy(repeatMode = mode) }
    }

    suspend fun setShuffle(enabled: Boolean) = mutex.withLock {
        val state = mutableState.value
        if (state.shuffle == enabled) return@withLock
        val current = state.currentTrack
        val upcoming = state.queue.drop((state.queueIndex + 1).coerceAtLeast(0))
        val reordered = if (enabled) upcoming.shuffled() else upcoming.sortedBy { state.queue.indexOf(it) }
        val retained = if (current == null) state.queue.take(state.queueIndex.coerceAtLeast(0)) else state.queue.take(state.queueIndex + 1)
        val nextQueue = retained + reordered
        val nextIndex = if (current == null) -1 else retained.lastIndex
        mutableState.update { it.copy(queue = nextQueue, queueIndex = nextIndex, shuffle = enabled) }
    }

    suspend fun removeFromQueue(index: Int) = mutex.withLock {
        val current = mutableState.value
        if (index !in current.queue.indices) return@withLock
        if (index == current.queueIndex) {
            val nextQueue = current.queue.toMutableList().also { it.removeAt(index) }
            if (nextQueue.isEmpty()) {
                runEngineAction(
                    action = { engine.stop() },
                    onSuccess = { it.copy(queue = emptyList(), queueIndex = -1, currentTrack = null, isPlaying = false, positionMillis = 0, durationMillis = 0) },
                )
                loadedPathKey = null
            } else {
                mutableState.update { it.copy(queue = nextQueue, queueIndex = index.coerceAtMost(nextQueue.lastIndex), currentTrack = null, isPlaying = false) }
                startIndexLocked(mutableState.value.queueIndex)
            }
        } else {
            val nextQueue = current.queue.toMutableList().also { it.removeAt(index) }
            val nextIndex = if (index < current.queueIndex) current.queueIndex - 1 else current.queueIndex
            mutableState.update { it.copy(queue = nextQueue, queueIndex = nextIndex) }
        }
    }

    suspend fun clearQueue() = mutex.withLock {
        runEngineAction(
            action = { engine.stop() },
            onSuccess = { it.copy(queue = emptyList(), queueIndex = -1, currentTrack = null, isPlaying = false, positionMillis = 0, durationMillis = 0) },
        )
        loadedPathKey = null
    }

    suspend fun close() {
        eventJob.cancel()
        mutex.withLock {
            runCatching { engine.stop() }
            runCatching { engine.close() }
            loadedPathKey = null
        }
        scope.cancel()
    }

    private suspend fun startIndexLocked(index: Int) {
        val current = mutableState.value
        if (index !in current.queue.indices) return
        val track = current.queue[index]
        mutableState.update {
            it.copy(queueIndex = index, currentTrack = track, isPlaying = false, positionMillis = 0, durationMillis = 0, error = null)
        }
        try {
            engine.load(track)
            loadedPathKey = track.pathKey
            engine.setVolume(mutableState.value.volume)
            engine.play()
            mutableState.update { it.copy(isPlaying = true) }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            loadedPathKey = null
            mutableState.update { it.copy(isPlaying = false, error = failure.message ?: "无法播放此本地文件。") }
        }
    }

    private suspend fun advanceLocked() {
        val current = mutableState.value
        if (current.currentTrack == null || current.queue.isEmpty()) return
        if (current.repeatMode == PlaybackRepeatMode.ONE) {
            seekLocked(0)
            runEngineAction(action = { engine.play() }, onSuccess = { it.copy(isPlaying = true, error = null) })
            return
        }
        val nextIndex = when {
            current.queueIndex < current.queue.lastIndex -> current.queueIndex + 1
            current.repeatMode == PlaybackRepeatMode.ALL -> 0
            else -> -1
        }
        if (nextIndex < 0) {
            runEngineAction(action = { engine.stop() }, onSuccess = { it.copy(isPlaying = false, positionMillis = 0, error = null) })
            loadedPathKey = null
        } else startIndexLocked(nextIndex)
    }

    private suspend fun seekLocked(positionMillis: Long) {
        val bounded = positionMillis.coerceAtLeast(0).let { value ->
            val duration = mutableState.value.durationMillis
            if (duration > 0) value.coerceAtMost(duration) else value
        }
        val track = mutableState.value.currentTrack
        runEngineAction(
            action = {
                if (track != null) ensureLoadedLocked(track)
                engine.seek(bounded)
            },
            onSuccess = { it.copy(positionMillis = bounded) },
        )
    }

    private suspend fun ensureLoadedLocked(track: LocalTrack) {
        if (loadedPathKey == track.pathKey) return
        engine.load(track)
        loadedPathKey = track.pathKey
        engine.setVolume(mutableState.value.volume)
    }

    private suspend fun playCurrentLocked(current: PlaybackState) {
        val track = current.currentTrack ?: return
        runEngineAction(
            action = {
                ensureLoadedLocked(track)
                if (current.positionMillis > 0) engine.seek(current.positionMillis)
                engine.play()
            },
            onSuccess = { it.copy(isPlaying = true, error = null) },
        )
    }

    private suspend fun runEngineAction(
        action: suspend () -> Unit,
        onSuccess: (PlaybackState) -> PlaybackState,
    ) {
        try {
            action()
            mutableState.update(onSuccess)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            mutableState.update { it.copy(isPlaying = false, error = failure.message ?: "播放器操作失败。") }
        }
    }

    companion object {
        private const val PREVIOUS_RESTART_THRESHOLD_MILLIS = 3_000L
    }
}
