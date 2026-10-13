package com.qing.hachimi.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackCoordinatorSessionTest {
    @Test
    fun `late ended event from old session does not advance current queue item`() = runBlocking {
        val engine = SessionAwareFakeEngine()
        val player = PlaybackCoordinator(engine, Dispatchers.Default)
        try {
            player.playTracks(listOf(track("one"), track("two")))
            val oldSession = requireNotNull(player.state.value.sessionId)
            player.playIndex(1)
            val currentSession = requireNotNull(player.state.value.sessionId)
            assertNotEquals(oldSession, currentSession)

            engine.emit(PlaybackEvent.Ended(oldSession))
            engine.emit(PlaybackEvent.Position(currentSession, 123L, 10_000L))
            withTimeout(2_000) { player.state.first { it.positionMillis == 123L } }

            assertEquals("two", player.state.value.currentTrack?.id)
            assertEquals(1, player.state.value.queueIndex)
            assertTrue(player.state.value.isPlaying)
        } finally {
            player.close()
        }
    }

    @Test
    fun `late position event from old session does not overwrite current progress`() = runBlocking {
        val engine = SessionAwareFakeEngine()
        val player = PlaybackCoordinator(engine, Dispatchers.Default)
        try {
            player.playTracks(listOf(track("one"), track("two")))
            val oldSession = requireNotNull(player.state.value.sessionId)
            player.playIndex(1)
            val currentSession = requireNotNull(player.state.value.sessionId)

            engine.emit(PlaybackEvent.Position(oldSession, 800L, 1_000L))
            engine.emit(PlaybackEvent.Position(currentSession, 4_201L, 10_000L))
            withTimeout(2_000) { player.state.first { it.positionMillis == 4_201L } }

            assertEquals(10_000L, player.state.value.durationMillis)
            assertEquals("two", player.state.value.currentTrack?.id)
        } finally {
            player.close()
        }
    }

    @Test
    fun `late failure event from old session does not fail current track`() = runBlocking {
        val engine = SessionAwareFakeEngine()
        val player = PlaybackCoordinator(engine, Dispatchers.Default)
        try {
            player.playTracks(listOf(track("one"), track("two")))
            val oldSession = requireNotNull(player.state.value.sessionId)
            player.playIndex(1)
            val currentSession = requireNotNull(player.state.value.sessionId)

            engine.emit(PlaybackEvent.Failed(oldSession, "old decoder error"))
            engine.emit(PlaybackEvent.Position(currentSession, 987L, 10_000L))
            withTimeout(2_000) { player.state.first { it.positionMillis == 987L } }

            assertNull(player.state.value.error)
            assertTrue(player.state.value.isPlaying)
            assertEquals("two", player.state.value.currentTrack?.id)
        } finally {
            player.close()
        }
    }

    @Test
    fun `playing another track and clearing queue remain responsive during a pending load`() = runBlocking {
        val engine = SessionAwareFakeEngine()
        val player = PlaybackCoordinator(engine, Dispatchers.Default)
        try {
            val gateA = CompletableDeferred<Unit>()
            engine.blockNextLoad(gateA)
            val loadingA = async { player.playTracks(listOf(track("one"), track("two"))) }
            val aStarted = withTimeout(2_000) { engine.loadStarted.receive() }
            assertEquals("one", aStarted.track.id)

            val gateB = CompletableDeferred<Unit>()
            engine.blockNextLoad(gateB)
            val loadingB = async { player.playIndex(1) }
            val bStarted = withTimeout(2_000) { engine.loadStarted.receive() }
            assertEquals("two", bStarted.track.id)
            assertEquals("two", player.state.value.currentTrack?.id)
            assertTrue(player.state.value.isLoading)
            assertFalse(player.state.value.isPlaying)

            gateB.complete(Unit)
            loadingB.await()
            loadingA.await()
            assertTrue(player.state.value.isPlaying)

            val gateC = CompletableDeferred<Unit>()
            engine.blockNextLoad(gateC)
            val loadingCurrent = async { player.playIndex(0) }
            val cStarted = withTimeout(2_000) { engine.loadStarted.receive() }
            assertEquals("one", cStarted.track.id)

            player.clearQueue()
            loadingCurrent.await()
            assertTrue(player.state.value.queue.isEmpty())
            assertNull(player.state.value.currentTrack)
            assertNull(player.state.value.sessionId)
            assertFalse(player.state.value.isLoading)
        } finally {
            player.close()
        }
    }

    private fun track(id: String) = LocalTrack(
        id = id,
        pathKey = "C:/music/$id.flac",
        filePath = "C:/music/$id.flac",
        fileSize = 1,
        lastModifiedMillis = 1,
        format = LocalAudioFormat.FLAC,
        codec = "FLAC",
        lossiness = AudioLossiness.LOSSLESS,
        durationSeconds = 10.0,
        bitrateKbps = 900,
        sampleRateHz = 44_100,
        bitDepth = 16,
        title = id,
        titleSource = LocalTitleSource.TAG,
        artists = listOf("Artist"),
        album = "Album",
        albumArtist = "Artist",
        trackNumber = null,
        discNumber = null,
        metadataStatus = LocalMetadataStatus.READABLE,
    )

    private class SessionAwareFakeEngine : PlaybackEngine {
        private val mutableEvents = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 16)
        override val events = mutableEvents
        val loadStarted = Channel<StartedLoad>(Channel.UNLIMITED)
        private val nextLoadGates = ConcurrentLinkedQueue<CompletableDeferred<Unit>>()
        private val pendingLoadCancellations = ConcurrentHashMap<PlaybackSessionId, CompletableDeferred<Unit>>()
        private val loadedTracks = ConcurrentHashMap<PlaybackSessionId, LocalTrack>()
        @Volatile private var activeSessionId: PlaybackSessionId? = null

        fun blockNextLoad(gate: CompletableDeferred<Unit>) {
            nextLoadGates.add(gate)
        }

        override suspend fun load(track: LocalTrack, sessionId: PlaybackSessionId) {
            val current = activeSessionId
            if (current != null && sessionId.value < current.value) return
            activeSessionId = sessionId
            pendingLoadCancellations.entries
                .filter { it.key != sessionId && it.key.value < sessionId.value }
                .forEach { (oldId, cancelled) ->
                    cancelled.complete(Unit)
                    pendingLoadCancellations.remove(oldId)
                }
            val cancellation = CompletableDeferred<Unit>()
            pendingLoadCancellations[sessionId] = cancellation
            val gate = nextLoadGates.poll()
            loadStarted.send(StartedLoad(track, sessionId))
            try {
                if (gate != null) {
                    select<Unit> {
                        gate.onAwait { }
                        cancellation.onAwait { throw kotlinx.coroutines.CancellationException("load superseded") }
                    }
                }
                if (activeSessionId != sessionId) throw kotlinx.coroutines.CancellationException("load superseded")
                loadedTracks[sessionId] = track
            } finally {
                pendingLoadCancellations.remove(sessionId)
            }
        }

        override suspend fun play(sessionId: PlaybackSessionId) = Unit
        override suspend fun pause(sessionId: PlaybackSessionId) = Unit
        override suspend fun stop(sessionId: PlaybackSessionId?) {
            if (sessionId == null || activeSessionId == sessionId) {
                val stopped = activeSessionId
                activeSessionId = null
                if (stopped != null) pendingLoadCancellations.remove(stopped)?.complete(Unit)
            }
        }
        override suspend fun seek(sessionId: PlaybackSessionId, positionMillis: Long) = Unit
        override suspend fun setVolume(sessionId: PlaybackSessionId, volume: Float) = Unit
        override suspend fun close() = Unit
        suspend fun emit(event: PlaybackEvent) = mutableEvents.emit(event)
    }

    private data class StartedLoad(val track: LocalTrack, val sessionId: PlaybackSessionId)
}
