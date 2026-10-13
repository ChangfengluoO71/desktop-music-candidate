package com.qing.hachimi.desktop

import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlinx.coroutines.CompletableDeferred
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
    private val playerRef = AtomicReference<MediaPlayer?>(null)

    override val events: Flow<PlaybackEvent> = mutableEvents.asSharedFlow()

    override suspend fun load(track: LocalTrack) {
        require(supports(track.format)) { "当前播放器暂不支持 ${track.format} 编码。" }
        val path = Path.of(track.filePath).toAbsolutePath().normalize()
        require(Files.isRegularFile(path) && Files.isReadable(path)) { "本地音频文件不存在或无法读取。" }
        val ready = CompletableDeferred<Unit>()

        runOnFxThread {
            playerRef.getAndSet(null)?.dispose()
            val media = Media(path.toUri().toASCIIString())
            val next = MediaPlayer(media)
            playerRef.set(next)
            next.setOnReady {
                val duration = next.totalDuration.safeMillis()
                mutableEvents.tryEmit(PlaybackEvent.Position(0L, duration))
                ready.complete(Unit)
            }
            next.setOnError {
                val cause = next.error ?: media.error
                val message = cause?.message?.takeIf(String::isNotBlank)
                    ?: "音频解码失败，文件可能损坏或使用了未支持的编码。"
                val safeMessage = message.replace(path.toString(), path.fileName.toString())
                mutableEvents.tryEmit(PlaybackEvent.Failed(safeMessage))
                ready.completeExceptionally(IllegalArgumentException(safeMessage))
            }
            next.currentTimeProperty().addListener { _, currentTime, _ ->
                mutableEvents.tryEmit(
                    PlaybackEvent.Position(
                        positionMillis = currentTime.safeMillis(),
                        durationMillis = next.totalDuration.safeMillis(),
                    ),
                )
            }
            next.setOnEndOfMedia { mutableEvents.tryEmit(PlaybackEvent.Ended) }
            media.setOnError {
                val cause = media.error
                val message = cause?.message?.takeIf(String::isNotBlank)
                    ?: "音频文件无法解码。"
                val safeMessage = message.replace(path.toString(), path.fileName.toString())
                mutableEvents.tryEmit(PlaybackEvent.Failed(safeMessage))
                ready.completeExceptionally(IllegalArgumentException(safeMessage))
            }
        }

        try {
            withTimeout(LOAD_TIMEOUT_MILLIS) { ready.await() }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            throw IllegalStateException(
                failure.message?.takeIf(String::isNotBlank) ?: "等待音频解码器就绪超时。",
                failure,
            )
        }
    }

    override suspend fun play() = runOnFxThread {
        requireNotNull(playerRef.get()) { "尚未载入本地音频。" }.play()
    }

    override suspend fun pause() = runOnFxThread {
        requireNotNull(playerRef.get()) { "尚未载入本地音频。" }.pause()
    }

    override suspend fun stop() = runOnFxThread {
        playerRef.get()?.stop()
    }

    override suspend fun seek(positionMillis: Long) = runOnFxThread {
        requireNotNull(playerRef.get()) { "尚未载入本地音频。" }
            .seek(Duration.millis(positionMillis.coerceAtLeast(0).toDouble()))
    }

    override suspend fun setVolume(volume: Float) = runOnFxThread {
        requireNotNull(playerRef.get()) { "尚未载入本地音频。" }.volume = volume.coerceIn(0f, 1f).toDouble()
    }

    override suspend fun close() {
        if (!FxRuntime.started) return
        runOnFxThread { playerRef.getAndSet(null)?.dispose() }
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
