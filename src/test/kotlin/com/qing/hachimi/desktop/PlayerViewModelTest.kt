package com.qing.hachimi.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerViewModelTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `loads local lyrics for current track and switches variant`() = runBlocking {
        val audio = temp.resolve("track.mp3")
        Files.write(audio, byteArrayOf(1, 2, 3))
        Files.writeString(temp.resolve("track.lrc"), "[00:01.00]original")
        Files.writeString(temp.resolve("track.tl.lrc"), "[00:01.00]translated")
        val track = track(audio)
        val engine = TestEngine()
        val local = LocalMusicViewModel(LocalMusicStore(temp.resolve("library.db")), mainDispatcher = Dispatchers.Default)
        val viewModel = PlayerViewModel(
            PlaybackCoordinator(engine, Dispatchers.Default),
            local,
            PlaybackQueueStore(temp.resolve("player.json")),
            ioDispatcher = Dispatchers.IO,
            mainDispatcher = Dispatchers.Default,
        )
        try {
            viewModel.play(track)
            withTimeout(3_000) { viewModel.state.first { it.playback.isPlaying && it.lyrics.original.isNotEmpty() } }
            viewModel.selectLyricVariant(LyricVariant.TRANSLATION)
            assertEquals(LyricVariant.TRANSLATION, viewModel.state.value.lyricVariant)
            assertEquals("translated", viewModel.state.value.activeLyrics.single().text)
        } finally {
            viewModel.close()
            local.shutdown()
        }
    }

    @Test
    fun `late lyrics from a previous track do not overwrite the current track`() = runBlocking {
        val firstAudio = temp.resolve("first.mp3")
        val secondAudio = temp.resolve("second.mp3")
        Files.write(firstAudio, byteArrayOf(1))
        Files.write(secondAudio, byteArrayOf(2))
        Files.writeString(temp.resolve("first.lrc"), "[00:01.00]first")
        Files.writeString(temp.resolve("second.lrc"), "[00:01.00]second")
        val first = track(firstAudio)
        val second = track(secondAudio)
        val firstLoadStarted = java.util.concurrent.CountDownLatch(1)
        val releaseFirstLoad = java.util.concurrent.CountDownLatch(1)
        val firstLoadFinished = java.util.concurrent.CountDownLatch(1)
        val runningFirstLoad = ThreadLocal<Boolean>()
        val ioDispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                Dispatchers.IO.dispatch(context) {
                    try {
                        block.run()
                    } finally {
                        if (runningFirstLoad.get() == true) firstLoadFinished.countDown()
                        runningFirstLoad.remove()
                    }
                }
            }
        }
        val loader = LocalLyricsLoader { path ->
            if (path == firstAudio) {
                runningFirstLoad.set(true)
                firstLoadStarted.countDown()
                releaseFirstLoad.await()
            }
            null
        }
        val engine = TestEngine()
        val local = LocalMusicViewModel(LocalMusicStore(temp.resolve("library.db")), mainDispatcher = Dispatchers.Default)
        val viewModel = PlayerViewModel(
            PlaybackCoordinator(engine, Dispatchers.Default),
            local,
            PlaybackQueueStore(temp.resolve("player.json")),
            lyricsLoader = loader,
            ioDispatcher = ioDispatcher,
            mainDispatcher = Dispatchers.Default,
        )
        try {
            viewModel.play(first)
            assertTrue(firstLoadStarted.await(3, java.util.concurrent.TimeUnit.SECONDS))

            viewModel.play(second)
            withTimeout(3_000) {
                viewModel.state.first { state ->
                    state.playback.currentTrack?.pathKey == second.pathKey &&
                        state.lyrics.original.singleOrNull()?.text == "second"
                }
            }

            releaseFirstLoad.countDown()
            assertTrue(firstLoadFinished.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(second.pathKey, viewModel.state.value.playback.currentTrack?.pathKey)
            assertEquals("second", viewModel.state.value.lyrics.original.single().text)
        } finally {
            releaseFirstLoad.countDown()
            viewModel.close()
            local.shutdown()
        }
    }

    @Test
    fun `persisted playback restores queue without starting audio`() = runBlocking {
        val audio = temp.resolve("saved.mp3")
        Files.write(audio, byteArrayOf(1))
        val track = track(audio)
        val store = PlaybackQueueStore(temp.resolve("player.json"))
        val database = temp.resolve("library.db")
        val local = localMusicViewModel(database, track)
        val firstEngine = TestEngine()
        val first = PlayerViewModel(
            PlaybackCoordinator(firstEngine, Dispatchers.Default), local, store,
            ioDispatcher = Dispatchers.IO, mainDispatcher = Dispatchers.Default,
        )
        try {
            first.play(track)
            withTimeout(3_000) { first.state.first { it.playback.isPlaying } }
        } finally {
            first.close()
            local.shutdown()
        }

        val nextEngine = TestEngine()
        val nextLocal = localMusicViewModel(database, track)
        val next = PlayerViewModel(
            PlaybackCoordinator(nextEngine, Dispatchers.Default), nextLocal, store,
            ioDispatcher = Dispatchers.IO, mainDispatcher = Dispatchers.Default,
        )
        try {
            val restored = withTimeout(3_000) { next.state.first { it.playback.queue.isNotEmpty() } }
            assertFalse(restored.playback.isPlaying)
            assertTrue(nextEngine.loaded.isEmpty())
        } finally {
            next.close()
            nextLocal.shutdown()
        }
    }

    private fun localMusicViewModel(database: Path, track: LocalTrack): LocalMusicViewModel {
        val store = LocalMusicStore(database)
        val root = store.registerRoot(temp)
        store.saveTrack(root.path, "test-scan", track)
        return LocalMusicViewModel(store, mainDispatcher = Dispatchers.Default)
    }

    private fun track(path: Path) = LocalTrack(
        id = path.fileName.toString(), pathKey = path.toString().lowercase(), filePath = path.toString(), fileSize = 1,
        lastModifiedMillis = 1, format = LocalAudioFormat.MP3, codec = "MP3", lossiness = AudioLossiness.LOSSY,
        durationSeconds = 10.0, bitrateKbps = 128, sampleRateHz = 44_100, bitDepth = null,
        title = "Track", titleSource = LocalTitleSource.TAG, artists = listOf("Artist"), album = "Album",
        albumArtist = "Artist", trackNumber = null, discNumber = null, metadataStatus = LocalMetadataStatus.READABLE,
    )

    private class TestEngine : PlaybackEngine {
        override val events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
        val loaded = mutableListOf<LocalTrack>()
        override suspend fun load(track: LocalTrack, sessionId: PlaybackSessionId) { loaded += track }
        override suspend fun play(sessionId: PlaybackSessionId) = Unit
        override suspend fun pause(sessionId: PlaybackSessionId) = Unit
        override suspend fun stop(sessionId: PlaybackSessionId?) = Unit
        override suspend fun seek(sessionId: PlaybackSessionId, positionMillis: Long) = Unit
        override suspend fun setVolume(sessionId: PlaybackSessionId, volume: Float) = Unit
        override suspend fun close() = Unit
    }
}
