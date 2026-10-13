package com.qing.hachimi.desktop

import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Windows-local decoder. It accepts only file-backed tracks, never remote media URLs. */
class JavaFxPlaybackEngine : PlaybackEngine {
    private val mutableEvents = MutableSharedFlow<PlaybackEvent>(replay = 1, extraBufferCapacity = 32)
    private val playerRef = AtomicReference<LoadedPlayer?>(null)
    private val activeSessionId = AtomicReference<PlaybackSessionId?>(null)
    private val pendingLoad = AtomicReference<PendingLoad?>(null)

    override val events: Flow<PlaybackEvent> = mutableEvents.asSharedFlow()

    override suspend fun load(track: LocalTrack, sessionId: PlaybackSessionId) {
        require(supports(track.format)) { "当前播放器暂不支持 ${track.format} 编码。" }
        val path = Path.of(track.filePath).toAbsolutePath().normalize()
        require(Files.isRegularFile(path) && Files.isReadable(path)) { "本地音频文件不存在或无法读取。" }
        val previousSession = activeSessionId.get()
        if (previousSession != null && sessionId.value < previousSession.value) {
            throw CancellationException("播放请求已被更新的曲目取代。")
        }
        activeSessionId.set(sessionId)
        pendingLoad.getAndSet(null)?.ready?.completeExceptionally(CancellationException("播放请求已被更新的曲目取代。"))
        val ready = CompletableDeferred<Unit>()
        val requestedLoad = PendingLoad(sessionId, ready)
        pendingLoad.set(requestedLoad)

        runOnFxThread {
            if (activeSessionId.get() != sessionId) {
                ready.completeExceptionally(CancellationException("播放请求已被更新的曲目取代。"))
                return@runOnFxThread
            }
            playerRef.getAndSet(null)?.player?.dispose()
            val media = Media(path.toUri().toASCIIString())
            val next = MediaPlayer(media)
            playerRef.set(LoadedPlayer(sessionId, next))
            next.setOnReady {
                if (activeSessionId.get() != sessionId) return@setOnReady
                val duration = next.totalDuration.safeMillis()
                mutableEvents.tryEmit(PlaybackEvent.Position(sessionId, 0L, duration))
                ready.complete(Unit)
            }
            next.setOnError {
                if (activeSessionId.get() != sessionId) return@setOnError
                val cause = next.error ?: media.error
                val message = cause?.message?.takeIf(String::isNotBlank)
                    ?: "音频解码失败，文件可能损坏或使用了未支持的编码。"
                val safeMessage = message.replace(path.toString(), path.fileName.toString())
                mutableEvents.tryEmit(PlaybackEvent.Failed(sessionId, safeMessage))
                ready.completeExceptionally(IllegalArgumentException(safeMessage))
            }
            next.currentTimeProperty().addListener { _, currentTime, _ ->
                if (activeSessionId.get() == sessionId) mutableEvents.tryEmit(
                    PlaybackEvent.Position(
                        sessionId = sessionId,
                        positionMillis = currentTime.safeMillis(),
                        durationMillis = next.totalDuration.safeMillis(),
                    ),
                )
            }
            next.setOnEndOfMedia {
                if (activeSessionId.get() == sessionId) mutableEvents.tryEmit(PlaybackEvent.Ended(sessionId))
            }
            media.setOnError {
                if (activeSessionId.get() != sessionId) return@setOnError
                val cause = media.error
                val message = cause?.message?.takeIf(String::isNotBlank)
                    ?: "音频文件无法解码。"
                val safeMessage = message.replace(path.toString(), path.fileName.toString())
                mutableEvents.tryEmit(PlaybackEvent.Failed(sessionId, safeMessage))
                ready.completeExceptionally(IllegalArgumentException(safeMessage))
            }
        }

        try {
            withTimeout(LOAD_TIMEOUT_MILLIS) { ready.await() }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw IllegalStateException(
                failure.message?.takeIf(String::isNotBlank) ?: "等待音频解码器就绪超时。",
                failure,
            )
        } finally {
            pendingLoad.compareAndSet(requestedLoad, null)
        }
    }

    override suspend fun play(sessionId: PlaybackSessionId) = runOnFxThread {
        playerRef.get()?.takeIf { it.sessionId == sessionId }?.player?.play()
    }

    override suspend fun pause(sessionId: PlaybackSessionId) = runOnFxThread {
        playerRef.get()?.takeIf { it.sessionId == sessionId }?.player?.pause()
    }

    override suspend fun stop(sessionId: PlaybackSessionId?) {
        val active = activeSessionId.get()
        if (sessionId != null && active != sessionId) return
        if (sessionId == null) activeSessionId.set(null) else activeSessionId.compareAndSet(sessionId, null)
        pendingLoad.get()?.takeIf { sessionId == null || it.sessionId == sessionId }?.ready
            ?.completeExceptionally(CancellationException("播放已停止。"))
        runOnFxThread {
            val current = playerRef.get()
            if (current != null && (sessionId == null || current.sessionId == sessionId)) current.player.stop()
        }
    }

    override suspend fun seek(sessionId: PlaybackSessionId, positionMillis: Long) = runOnFxThread {
        playerRef.get()?.takeIf { it.sessionId == sessionId }?.player
            ?.seek(Duration.millis(positionMillis.coerceAtLeast(0).toDouble()))
    }

    override suspend fun setVolume(sessionId: PlaybackSessionId, volume: Float) = runOnFxThread {
        playerRef.get()?.takeIf { it.sessionId == sessionId }?.player?.volume = volume.coerceIn(0f, 1f).toDouble()
    }

    override suspend fun close() {
        activeSessionId.set(null)
        pendingLoad.getAndSet(null)?.ready?.completeExceptionally(CancellationException("播放器已关闭。"))
        if (!FxRuntime.started) return
        runOnFxThread { playerRef.getAndSet(null)?.player?.dispose() }
        Platform.exit()
        FxRuntime.markExited()
    }

    private suspend fun runOnFxThread(block: () -> Unit) {
        FxRuntime.startup().await()
        suspendCancellableCoroutine { continuation ->
            try {
                Platform.runLater {
                    if (!continuation.isActive) return@runLater
                    try {
                        block()
                        continuation.resume(Unit)
                    } catch (failure: Throwable) {
                        continuation.resumeWithException(failure)
                    }
                }
            } catch (failure: Throwable) {
                continuation.resumeWithException(failure)
            }
        }
    }

    companion object {
        private const val LOAD_TIMEOUT_MILLIS = 20_000L

        fun supports(format: LocalAudioFormat): Boolean = format in setOf(
            LocalAudioFormat.MP3,
            LocalAudioFormat.WAV,
            LocalAudioFormat.M4A,
            LocalAudioFormat.AAC,
        )
    }

    private data class LoadedPlayer(val sessionId: PlaybackSessionId, val player: MediaPlayer)
    private data class PendingLoad(val sessionId: PlaybackSessionId, val ready: CompletableDeferred<Unit>)
}

private fun Duration?.safeMillis(): Long {
    val millis = this?.toMillis() ?: return 0L
    return if (millis.isFinite() && millis > 0.0) millis.toLong() else 0L
}

private suspend fun CompletableFuture<Unit>.await() {
    suspendCancellableCoroutine { continuation ->
        whenComplete { _, failure ->
            if (failure == null) continuation.resume(Unit)
            else continuation.resumeWithException(failure.cause ?: failure)
        }
    }
}

private object FxRuntime {
    private val startupFuture = AtomicReference<CompletableFuture<Unit>?>(null)
    @Volatile var started: Boolean = false
        private set

    fun startup(): CompletableFuture<Unit> {
        startupFuture.get()?.let { return it }
        val candidate = CompletableFuture<Unit>()
        if (!startupFuture.compareAndSet(null, candidate)) return startupFuture.get()!!
        try {
            Platform.startup {
                Platform.setImplicitExit(false)
                started = true
                candidate.complete(Unit)
            }
        } catch (_: IllegalStateException) {
            // Another JavaFX user may have initialized the process toolkit first.
            try {
                Platform.runLater {
                    Platform.setImplicitExit(false)
                    started = true
                    candidate.complete(Unit)
                }
            } catch (failure: Throwable) {
                candidate.completeExceptionally(failure)
            }
        } catch (failure: Throwable) {
            candidate.completeExceptionally(failure)
        }
        return candidate
    }

    fun markExited() {
        started = false
    }
}
