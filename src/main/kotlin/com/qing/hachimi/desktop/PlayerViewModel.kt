package com.qing.hachimi.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

data class PlayerUiState(
    val playback: PlaybackState = PlaybackState(),
    val lyrics: LocalLyricsBundle = LocalLyricsBundle(),
    val lyricVariant: LyricVariant = LyricVariant.ORIGINAL,
    val isLoadingLyrics: Boolean = false,
    val lyricsUnavailable: Boolean = false,
    val queueRecoveryWarning: String? = null,
    val isNowPlayingOpen: Boolean = false,
) {
    val activeLyrics: List<TimedLyricLine> get() = lyrics.lines(lyricVariant)
    val activeLyricIndex: Int get() = lyrics.currentLineIndex(lyricVariant, playback.positionMillis)
}

@OptIn(FlowPreview::class)
class PlayerViewModel(
    private val coordinator: PlaybackCoordinator,
    private val localMusicViewModel: LocalMusicViewModel,
    private val queueStore: PlaybackQueueStore,
    private val lyricsLoader: LocalLyricsLoader = LocalLyricsLoader(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val mutableState = MutableStateFlow(PlayerUiState(playback = coordinator.state.value))
    private val restored = CompletableDeferred<Unit>()
    private var lyricsJob: Job? = null
    private var lastLyricPathKey: String? = null

    val state: StateFlow<PlayerUiState> = mutableState.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            val snapshot = queueStore.load(localMusicViewModel.state.value.tracks)
            coordinator.restoreQueue(snapshot)
            mutableState.update { it.copy(queueRecoveryWarning = snapshot.warning) }
            restored.complete(Unit)
        }
        scope.launch {
            coordinator.state.collect { playback ->
                mutableState.update { it.copy(playback = playback) }
                observeLyrics(playback.currentTrack)
            }
        }
        scope.launch {
            coordinator.state.sample(1_000).collect { playback ->
                restored.await()
                withContext(ioDispatcher) { runCatching { queueStore.save(playback) } }
            }
        }
    }

    fun play(track: LocalTrack) = scope.launch { restored.await(); coordinator.play(track) }
    fun playTracks(tracks: List<LocalTrack>) = scope.launch { restored.await(); coordinator.playTracks(tracks) }
    fun enqueue(tracks: List<LocalTrack>) = scope.launch { restored.await(); coordinator.enqueue(tracks) }
    fun playQueueIndex(index: Int) = scope.launch { restored.await(); coordinator.playIndex(index) }
    fun removeFromQueue(index: Int) = scope.launch { restored.await(); coordinator.removeFromQueue(index) }
    fun clearQueue() = scope.launch { restored.await(); coordinator.clearQueue() }
    fun togglePlayback() = scope.launch { restored.await(); coordinator.togglePlayback() }
    fun next() = scope.launch { restored.await(); coordinator.next() }
    fun previous() = scope.launch { restored.await(); coordinator.previous() }
    fun seek(positionMillis: Long) = scope.launch { restored.await(); coordinator.seek(positionMillis) }
    fun setVolume(volume: Float) = scope.launch { restored.await(); coordinator.setVolume(volume) }
    fun setShuffle(enabled: Boolean) = scope.launch { restored.await(); coordinator.setShuffle(enabled) }
    fun setRepeatMode(mode: PlaybackRepeatMode) = scope.launch { restored.await(); coordinator.setRepeatMode(mode) }
    fun selectLyricVariant(variant: LyricVariant) = mutableState.update { it.copy(lyricVariant = variant) }
    fun openNowPlaying() = mutableState.update { it.copy(isNowPlayingOpen = true) }
    fun closeNowPlaying() = mutableState.update { it.copy(isNowPlayingOpen = false) }

    suspend fun close() {
        restored.await()
        withContext(ioDispatcher) { runCatching { queueStore.save(coordinator.state.value) } }
        lyricsJob?.cancel()
        scope.cancel()
        coordinator.close()
    }

    private fun observeLyrics(track: LocalTrack?) {
        if (track?.pathKey == lastLyricPathKey) return
        lastLyricPathKey = track?.pathKey
        lyricsJob?.cancel()
        if (track == null) {
            mutableState.update { it.copy(lyrics = LocalLyricsBundle(), isLoadingLyrics = false, lyricsUnavailable = false) }
            return
        }
        mutableState.update { it.copy(lyrics = LocalLyricsBundle(), isLoadingLyrics = true, lyricsUnavailable = false) }
        lyricsJob = scope.launch(ioDispatcher) {
            val lyrics = runCatching { lyricsLoader.load(Path.of(track.filePath)) }.getOrDefault(LocalLyricsBundle())
            mutableState.update {
                it.copy(
                    lyrics = lyrics,
                    isLoadingLyrics = false,
                    lyricsUnavailable = lyrics.original.isEmpty() && lyrics.translation.isEmpty() && lyrics.romanization.isEmpty(),
                )
            }
        }
    }
}
