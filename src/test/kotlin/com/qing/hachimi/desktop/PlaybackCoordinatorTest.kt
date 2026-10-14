package com.qing.hachimi.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.coroutines.CoroutineContext

class PlaybackCoordinatorTest {
    @Test
    fun `enqueue starts first local track and avoids duplicate queue entries`() = runBlocking {
        val engine = FakePlaybackEngine()
        val player = PlaybackCoordinator(engine, Dispatchers.Default)
        val first = track("first")
        val second = track("second")
        try {
            player.enqueue(listOf(first, second, first))

            val state = player.state.value
            assertEquals(listOf("first", "second"), state.queue.map(LocalTrack::id))
            assertEquals("first", state.currentTrack?.id)
            assertTrue(state.isPlaying)
            assertEquals(listOf("first"), engine.loaded.map { it.track.id })
        } finally {
            player.close()
        }
    }

    @Test
    fun `play next inserts after current and preserves upcoming order`() = runBlocking {
        val engine = FakePlaybackEngine()
        val player = PlaybackCoordinator(engine, Dispatchers.Default)
        try {
            val tracks = listOf(track("one"), track("two"), track("three"))
            player.enqueue(tracks)
            player.enqueueNext(listOf(track("four"), track("five"), tracks[1]))

            assertEquals(listOf("one", "four", "five", "two", "three"), player.state.value.queue.map(LocalTrack::id))
            assertEquals("one", player.state.value.currentTrack?.id)
            assertEquals(0, player.state.value.queueIndex)
            assertEquals(listOf("one"), engine.loaded.map { it.track.id })
        } finally {
            player.close()
        }
    }

    @Test
    fun `play next starts immediately when the player is idle`() = runBlocking {
        val engine = FakePlaybackEngine()
        val player = PlaybackCoordinator(engine, Dispatchers.Default)
        try {
            player.enqueueNext(track("first"))
            assertEquals("first", player.state.value.currentTrack?.id)
            assertTrue(player.state.value.isPlaying)
        } finally {
            player.close()
        }
    }

    @Test
    fun `play tracks atomically replaces queue and begins with first unique local track`() = runBlocking {
        val engine = FakePlaybackEngine()
        val player = PlaybackCoordinator(engine, Dispatchers.Default)
        val first = track("first")
        val second = track("second")
        try {
            player.playTracks(listOf(first, second, first))

            assertEquals(listOf("first", "second"), player.state.value.queue.map(LocalTrack::id))
            assertEquals("first", player.state.value.currentTrack?.id)
            assertEquals(listOf("first"), engine.loaded.map { it.track.id })
            assertTrue(player.state.value.isPlaying)
        } finally {
            player.close()
        }
    }

    @Test
    fun `next and ended events advance queue while repeat one restarts current track`() = runBlocking {
        val engine = FakePlaybackEngine()
        val player = PlaybackCoordinator(engine, Dispatchers.Default)
        try {
            player.enqueue(listOf(track("one"), track("two")))
            player.next()
            assertEquals("two", player.state.value.currentTrack?.id)

            engine.emit(PlaybackEvent.Ended(engine.currentSessionId()))
            withTimeout(2_000) { player.state.first { !it.isPlaying && it.error == null } }
            assertEquals("two", player.state.value.currentTrack?.id)

            player.setRepeatMode(PlaybackRepeatMode.ONE)
            player.playCurrent()
            player.seek(4_000)
            engine.emit(PlaybackEvent.Ended(engine.currentSessionId()))
            withTimeout(2_000) { player.state.first { it.isPlaying && it.positionMillis == 0L } }
            assertEquals("two", player.state.value.currentTrack?.id)
        } finally {
            player.close()
        }
    }

    @Test
    fun `seek and volume are bounded and engine failure is visible`() = runBlocking {
        val engine = FakePlaybackEngine()
        val player = PlaybackCoordinator(engine, Dispatchers.Default)
        try {
            player.enqueue(listOf(track("one")))
            player.seek(-10)
            player.setVolume(2f)
            assertEquals(0L, engine.lastSeek)
            assertEquals(1f, engine.lastVolume)

            engine.emit(PlaybackEvent.Failed(engine.currentSessionId(), "unsupported codec"))
            val failed = withTimeout(2_000) { player.state.first { it.error != null } }
            assertEquals("unsupported codec", failed.error)
            assertFalse(failed.isPlaying)
        } finally {
            player.close()
        }
    }

    @Test
    fun `restored queue does not auto play and explicitly resumes from saved position`() = runBlocking {
        val engine = FakePlaybackEngine()
        val player = PlaybackCoordinator(engine, Dispatchers.Default)
        val restoredTrack = track("restored")
        try {
            player.restoreQueue(
                RestoredPlaybackQueue(
                    queue = listOf(restoredTrack),
                    queueIndex = 0,
                    positionMillis = 2_500,
                    volume = 0.4f,
                ),
            )

            assertFalse(player.state.value.isPlaying)
            assertTrue(engine.loaded.isEmpty())
            player.playCurrent()
            assertEquals(listOf("restored"), engine.loaded.map { it.track.id })
            assertEquals(2_500L, engine.lastSeek)
            assertEquals(0.4f, engine.lastVolume)
            assertTrue(player.state.value.isPlaying)
        } finally {
            player.close()
        }
    }

    @Test
    fun `manual queue reorder preserves the currently playing track and updates its index`() = runBlocking {
        val engine = FakePlaybackEngine()
        val player = PlaybackCoordinator(engine, Dispatchers.Default)
        val tracks = listOf(track("one"), track("two"), track("three"), track("four"))
        try {
            player.enqueue(tracks)
            player.next()

            player.moveQueueItem(fromIndex = 0, toIndex = 3)

            assertEquals(listOf("two", "three", "four", "one"), player.state.value.queue.map(LocalTrack::id))
            assertEquals("two", player.state.value.currentTrack?.id)
            assertEquals(0, player.state.value.queueIndex)
            assertEquals(listOf("one", "two"), engine.loaded.map { it.track.id })

            player.moveQueueItem(fromIndex = 0, toIndex = 2)
            assertEquals(listOf("three", "four", "two", "one"), player.state.value.queue.map(LocalTrack::id))
            assertEquals("two", player.state.value.currentTrack?.id)
            assertEquals(2, player.state.value.queueIndex)
            assertEquals(listOf("one", "two"), engine.loaded.map { it.track.id })
        } finally {
            player.close()
        }
    }

    @Test
    fun `relative seek moves in either direction and stays within duration`() = runBlocking {
        val engine = FakePlaybackEngine()
        val player = PlaybackCoordinator(engine, Dispatchers.Default)
        try {
            player.enqueue(listOf(track("one")))
            engine.emit(PlaybackEvent.Position(engine.currentSessionId(), positionMillis = 6_000, durationMillis = 10_000))
            withTimeout(2_000) { player.state.first { it.positionMillis == 6_000L } }

            player.seekBy(5_000)
            assertEquals(10_000L, player.state.value.positionMillis)
            assertEquals(10_000L, engine.lastSeek)

            player.seekBy(-50_000)
            assertEquals(0L, player.state.value.positionMillis)
            assertEquals(0L, engine.lastSeek)
        } finally {
            player.close()
        }
    }

    @Test
    fun `engine event collection is subscribed before coordinator construction returns`() = runBlocking {
        val engine = FakePlaybackEngine()
        val player = PlaybackCoordinator(engine, PausedDispatcher())
        try {
            assertEquals(1, engine.subscriberCount)
        } finally {
            player.close()
        }
    }

    private fun track(id: String) = LocalTrack(
        id = id,
        pathKey = "C:/music/$id.mp3",
        filePath = "C:/music/$id.mp3",
        fileSize = 1,
        lastModifiedMillis = 1,
        format = LocalAudioFormat.MP3,
        codec = "MP3",
        lossiness = AudioLossiness.LOSSY,
        durationSeconds = 10.0,
        bitrateKbps = 128,
        sampleRateHz = 44_100,
        bitDepth = null,
        title = id,
        titleSource = LocalTitleSource.TAG,
        artists = listOf("Artist"),
        album = "Album",
        albumArtist = "Artist",
        trackNumber = null,
        discNumber = null,
        metadataStatus = LocalMetadataStatus.READABLE,
    )

    private class FakePlaybackEngine : PlaybackEngine {
        private val mutableEvents = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
        override val events = mutableEvents
        val subscriberCount: Int get() = mutableEvents.subscriptionCount.value
        val loaded = mutableListOf<LoadedTrack>()
        private var activeSessionId: PlaybackSessionId? = null
        var lastSeek = -1L
        var lastVolume = -1f

        override suspend fun load(track: LocalTrack, sessionId: PlaybackSessionId) {
            activeSessionId = sessionId
            loaded += LoadedTrack(track, sessionId)
        }
        override suspend fun play(sessionId: PlaybackSessionId) = Unit
        override suspend fun pause(sessionId: PlaybackSessionId) = Unit
        override suspend fun stop(sessionId: PlaybackSessionId?) {
            if (sessionId == null || activeSessionId == sessionId) activeSessionId = null
        }
        override suspend fun seek(sessionId: PlaybackSessionId, positionMillis: Long) { lastSeek = positionMillis }
        override suspend fun setVolume(sessionId: PlaybackSessionId, volume: Float) { lastVolume = volume }
        override suspend fun close() = Unit
        fun emit(event: PlaybackEvent) { mutableEvents.tryEmit(event) }
        fun currentSessionId() = requireNotNull(activeSessionId)

        data class LoadedTrack(val track: LocalTrack, val sessionId: PlaybackSessionId)
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }
}
