package com.qing.hachimi.desktop.libmpv

import com.qing.hachimi.desktop.AudioLossiness
import com.qing.hachimi.desktop.LocalAudioFormat
import com.qing.hachimi.desktop.LocalMetadataStatus
import com.qing.hachimi.desktop.LocalTitleSource
import com.qing.hachimi.desktop.LocalTrack
import com.qing.hachimi.desktop.PlaybackEngine
import com.qing.hachimi.desktop.PlaybackEvent
import com.qing.hachimi.desktop.PlaybackSessionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibMpvPlaybackEngineTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `initializes with wasapi only and explicitly disables null fallback`() = runBlocking {
        val client = FakeMpvClient()
        val engine = engine(client)
        try {
            client.awaitInitialized()
            assertEquals("no", client.options["config"])
            assertEquals("no", client.options["load-scripts"])
            assertEquals("yes", client.options["idle"])
            assertEquals("wasapi", client.options["ao"])
            assertEquals("no", client.options["audio-display"])
            assertEquals("no", client.options["audio-exclusive"])
            assertEquals("no", client.options["audio-fallback-to-null"])
            assertFalse(client.options.values.any { it == "null" })
        } finally {
            engine.close()
        }
        assertEquals(setOf("dmc-libmpv-native"), client.nativeCallThreads.toSet())
    }

    @Test
    fun `reports the native initialization failure instead of masking it as a closed engine`() = runBlocking {
        val engine = LibMpvPlaybackEngine(
            libraryPath = tempDir.resolve("libmpv-2.dll"),
            clientFactory = MpvClientFactory { throw IllegalStateException("synthetic WASAPI initialization failure") },
            loadTimeoutMillis = 2_000,
            outputTimeoutMillis = 100,
        )
        try {
            val failure = runCatching { engine.load(track(), PlaybackSessionId(1)) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("synthetic WASAPI initialization failure"))
            assertFalse(failure?.message.orEmpty().contains("engine is closed"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `reports native worker failure after initialization`() = runBlocking {
        val client = FakeMpvClient().apply { waitEventFailure = IllegalStateException("synthetic event-loop failure") }
        val engine = engine(client)
        try {
            client.awaitInitialized()
            delay(100)
            val failure = runCatching { engine.load(track(), PlaybackSessionId(1)) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("synthetic event-loop failure"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `load waits for matching file loaded event instead of command submission`() = runBlocking {
        val client = FakeMpvClient()
        val engine = engine(client)
        val session = PlaybackSessionId(10)
        val load = async { engine.load(track(), session) }
        try {
            client.awaitLoadCommand()
            client.replyLoadSuccess()
            delay(100)
            assertFalse(load.isCompleted, "load must not finish when loadfile is merely accepted")

            client.emit(MpvNativeEvent.StartFile(71))
            client.emit(MpvNativeEvent.FileLoaded)
            withTimeout(2_000) { load.await() }
        } finally {
            load.cancelAndJoin()
            engine.close()
        }
    }

    @Test
    fun `maps eof to matching session and never reports stop as natural end`() = runBlocking {
        val client = FakeMpvClient()
        val engine = engine(client)
        val session = PlaybackSessionId(11)
        try {
            loadFile(client, engine, session, 81)
            val ended = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000) { engine.events.first { it is PlaybackEvent.Ended } }
            }
            client.emit(MpvNativeEvent.EndFile(81, MpvEndReason.STOP, 0))
            delay(100)
            assertFalse(ended.isCompleted, "STOP must not be surfaced as natural end")

            loadFile(client, engine, PlaybackSessionId(12), 82)
            client.emit(MpvNativeEvent.EndFile(82, MpvEndReason.EOF, 0))
            assertEquals(PlaybackSessionId(12), ended.await().sessionId)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `superseded loading session cannot complete the replacement session`() = runBlocking {
        val client = FakeMpvClient()
        val engine = engine(client)
        val firstSession = PlaybackSessionId(20)
        val secondSession = PlaybackSessionId(21)
        val first = async { runCatching { engine.load(track(), firstSession) } }
        try {
            client.awaitLoadCommand(0)
            // Simulate mpv reporting the superseded entry while the synchronous stop is drained.
            client.queueStopEvents(MpvNativeEvent.StartFile(90), MpvNativeEvent.FileLoaded)
            val second = async { engine.load(track(), secondSession) }
            client.awaitLoadCommand(1)
            assertTrue(first.await().isFailure, "superseding a load should cancel its waiter")
            delay(100)
            assertFalse(second.isCompleted, "the obsolete file-loaded event must not complete session B")

            client.replyLoadSuccess(0)
            client.replyLoadSuccess(1)
            client.emit(MpvNativeEvent.StartFile(91))
            client.emit(MpvNativeEvent.FileLoaded)
            withTimeout(2_000) { second.await() }

            val ended = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000) { engine.events.first { it is PlaybackEvent.Ended } }
            }
            client.emit(MpvNativeEvent.EndFile(90, MpvEndReason.EOF, 0))
            assertEquals(firstSession, ended.await().sessionId)
            val currentEnded = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000) { engine.events.first { it is PlaybackEvent.Ended } }
            }
            client.emit(MpvNativeEvent.EndFile(91, MpvEndReason.EOF, 0))
            assertEquals(secondSession, currentEnded.await().sessionId)
        } finally {
            first.cancelAndJoin()
            engine.close()
        }
    }

    @Test
    fun `play waits until wasapi is the active output`() = runBlocking {
        val client = FakeMpvClient()
        val engine = engine(client)
        val session = PlaybackSessionId(31)
        try {
            loadFile(client, engine, session, 102)
            val play = async { engine.play(session) }
            delay(50)
            assertFalse(play.isCompleted, "play must wait for current-ao rather than assume WASAPI")
            client.emit(MpvNativeEvent.PropertyChanged(1L, "current-ao", MpvPropertyValue.Text("wasapi")))
            withTimeout(2_000) { play.await() }
            assertEquals("wasapi", engine.currentAudioOutput.value)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `native error end reports failed and never advances as eof`() = runBlocking {
        val client = FakeMpvClient()
        val engine = engine(client)
        val session = PlaybackSessionId(32)
        try {
            loadFile(client, engine, session, 103)
            val failed = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000) { engine.events.first { it is PlaybackEvent.Failed } }
            }
            client.emit(MpvNativeEvent.EndFile(103, MpvEndReason.ERROR, -1))
            val failure = failed.await() as PlaybackEvent.Failed
            assertEquals(session, failure.sessionId)
            assertTrue(failure.message.contains("could not play"))
            assertFalse(failure.message.contains("sample.mp3"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `redirect and unknown end reasons do not advance the queue`() = runBlocking {
        val client = FakeMpvClient()
        val engine = engine(client)
        try {
            loadFile(client, engine, PlaybackSessionId(33), 104)
            val ended = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000) { engine.events.first { it is PlaybackEvent.Ended } }
            }
            client.emit(MpvNativeEvent.EndFile(104, MpvEndReason.REDIRECT, 0))
            delay(100)
            assertFalse(ended.isCompleted)

            loadFile(client, engine, PlaybackSessionId(34), 105)
            client.emit(MpvNativeEvent.EndFile(105, MpvEndReason.UNKNOWN, 0))
            delay(100)
            assertFalse(ended.isCompleted)
            ended.cancelAndJoin()
        } finally {
            engine.close()
        }
    }

    @Test
    fun `async command failure completes load with a path-free error`() = runBlocking {
        val client = FakeMpvClient()
        val engine = engine(client)
        val load = async { runCatching { engine.load(track(), PlaybackSessionId(35)) } }
        try {
            val userdata = client.awaitLoadCommand()
            client.emit(MpvNativeEvent.CommandReply(userdata, -13))
            val failure = load.await().exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertFalse(failure.message.orEmpty().contains("sample.mp3"))
        } finally {
            load.cancelAndJoin()
            engine.close()
        }
    }

    @Test
    fun `repeated seeks stay serialized and target active session`() = runBlocking {
        val client = FakeMpvClient()
        val engine = engine(client)
        val session = PlaybackSessionId(30)
        try {
            loadFile(client, engine, session, 101)
            engine.seek(session, 1_250)
            engine.seek(session, 2_500)
            engine.seek(session, 3_750)
            assertEquals(listOf("1.25", "2.5", "3.75"), client.timeSeeks.takeLast(3))
            engine.seek(PlaybackSessionId(29), 999)
            assertEquals(3, client.timeSeeks.size)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `cancelling load stops only its current pending native item`() = runBlocking {
        val client = FakeMpvClient()
        val engine = engine(client)
        val session = PlaybackSessionId(40)
        val load = async { engine.load(track(), session) }
        try {
            client.awaitLoadCommand()
            load.cancelAndJoin()
            withTimeout(2_000) { client.stopCount.awaitAtLeast(1) }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `stop during load completes pending request and leaves engine responsive`() = runBlocking {
        val client = FakeMpvClient()
        val engine = engine(client)
        val session = PlaybackSessionId(41)
        val load = async { runCatching { engine.load(track(), session) } }
        try {
            client.awaitLoadCommand()
            engine.stop(session)
            assertTrue(load.await().isFailure)
            assertTrue(client.stopCount.count >= 1)
            assertTrue(runCatching { engine.load(track(), session) }.isFailure, "a stopped session ID cannot be reused")
            assertEquals(1, client.loadCommandCount())

            val next = async { engine.load(track(), PlaybackSessionId(42)) }
            client.awaitLoadCommand(1)
            client.replyLoadSuccess(1)
            client.emit(MpvNativeEvent.StartFile(111))
            client.emit(MpvNativeEvent.FileLoaded)
            withTimeout(2_000) { next.await() }
        } finally {
            load.cancelAndJoin()
            engine.close()
        }
    }

    @Test
    fun `close waits for native worker to leave event polling`() = runBlocking {
        val client = FakeMpvClient()
        val engine = engine(client)
        client.awaitInitialized()
        client.blockEventPolling()
        client.awaitEventPolling()
        val close = async { engine.close() }
        try {
            assertFalse(close.isCompleted, "close must join the native event worker")
            client.releaseEventPolling()
            withTimeout(2_000) { close.await() }
            assertFalse(engine.isWorkerAlive())
        } finally {
            client.releaseEventPolling()
            close.cancelAndJoin()
        }
    }

    private fun engine(client: FakeMpvClient): LibMpvPlaybackEngine = LibMpvPlaybackEngine(
        libraryPath = tempDir.resolve("libmpv-2.dll"),
        clientFactory = MpvClientFactory { client },
        loadTimeoutMillis = 2_000,
        outputTimeoutMillis = 100,
    )

    private suspend fun loadFile(client: FakeMpvClient, engine: PlaybackEngine, session: PlaybackSessionId, entry: Long) = coroutineScope {
        val commandIndex = client.loadCommandCount()
        val loaded = async { engine.load(track(), session) }
        client.awaitLoadCommand(commandIndex)
        client.replyLoadSuccess(commandIndex)
        client.emit(MpvNativeEvent.StartFile(entry))
        client.emit(MpvNativeEvent.FileLoaded)
        withTimeout(2_000) { loaded.await() }
    }

    private fun track(): LocalTrack {
        val file = tempDir.resolve("sample.mp3")
        Files.write(file, byteArrayOf(1, 2, 3))
        return LocalTrack(
            id = "sample",
            pathKey = file.toString().lowercase(),
            filePath = file.toString(),
            fileSize = Files.size(file),
            lastModifiedMillis = Files.getLastModifiedTime(file).toMillis(),
            format = LocalAudioFormat.MP3,
            codec = "MP3",
            lossiness = AudioLossiness.LOSSY,
            durationSeconds = null,
            bitrateKbps = null,
            sampleRateHz = null,
            bitDepth = null,
            title = "sample",
            titleSource = LocalTitleSource.FILENAME,
            artists = emptyList(),
            album = "",
            albumArtist = "",
            trackNumber = null,
            discNumber = null,
            metadataStatus = LocalMetadataStatus.READABLE,
        )
    }

    private class FakeMpvClient : MpvClient {
        val options = mutableMapOf<String, String>()
        val timeSeeks = mutableListOf<String>()
        val nativeCallThreads = java.util.concurrent.CopyOnWriteArrayList<String>()
        val stopCount = StopCounter()
        private val events = java.util.concurrent.LinkedBlockingQueue<MpvNativeEvent>()
        private val initialized = CompletableDeferred<Unit>()
        private val loadCommands = java.util.concurrent.CopyOnWriteArrayList<Long>()
        private val queuedStopEvents = java.util.concurrent.CopyOnWriteArrayList<MpvNativeEvent>()
        @Volatile var waitEventFailure: Throwable? = null
        @Volatile private var pollEntered = CountDownLatch(1)
        @Volatile private var pollRelease: CountDownLatch? = null
        @Volatile private var closed = false

        override fun clientApiVersion(): Int { recordCall(); return 2 shl 16 }
        override fun setOption(name: String, value: String): Int { recordCall(); options[name] = value; return 0 }
        override fun requestEvent(eventId: Int, enable: Boolean): Int { recordCall(); return 0 }
        override fun initialize(): Int { recordCall(); initialized.complete(Unit); return 0 }
        override fun observeProperty(userdata: Long, name: String, format: MpvPropertyFormat): Int { recordCall(); return 0 }
        override fun unobserveProperty(userdata: Long): Int { recordCall(); return 0 }
        override fun commandAsync(userdata: Long, arguments: List<String>): Int {
            recordCall()
            when (arguments.firstOrNull()) {
                "loadfile" -> loadCommands += userdata
                "stop" -> stopCount.increment()
            }
            return 0
        }
        override fun command(arguments: List<String>): Int {
            recordCall()
            if (arguments.firstOrNull() == "stop") {
                stopCount.increment()
                events.addAll(queuedStopEvents.toList())
                queuedStopEvents.clear()
            }
            return 0
        }
        override fun setPropertyString(name: String, value: String): Int {
            recordCall()
            if (name == "time-pos") timeSeeks += value
            if (name == "pause" && value == "yes") Unit
            return 0
        }
        override fun waitEvent(timeoutMillis: Long): MpvNativeEvent {
            recordCall()
            waitEventFailure?.let { throw it }
            pollRelease?.let { release ->
                pollEntered.countDown()
                release.await()
            }
            return events.poll(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS) ?: MpvNativeEvent.None
        }
        override fun errorString(code: Int): String { recordCall(); return "fake error $code" }
        override fun close() { recordCall(); closed = true }

        suspend fun awaitInitialized() = withTimeout(2_000) { initialized.await() }
        suspend fun awaitLoadCommand(index: Int = 0): Long = withTimeout(2_000) {
            while (loadCommands.size <= index) delay(5)
            loadCommands[index]
        }
        fun loadCommandCount(): Int = loadCommands.size
        fun replyLoadSuccess(index: Int = loadCommands.lastIndex) {
            events += MpvNativeEvent.CommandReply(loadCommands[index], 0)
        }
        fun emit(event: MpvNativeEvent) { events += event }
        fun queueStopEvents(vararg events: MpvNativeEvent) { queuedStopEvents.addAll(events) }
        fun blockEventPolling() { pollEntered = CountDownLatch(1); pollRelease = CountDownLatch(1) }
        suspend fun awaitEventPolling() = withTimeout(2_000) { check(pollEntered.await(2, TimeUnit.SECONDS)) }
        fun releaseEventPolling() { pollRelease?.countDown(); pollRelease = null }
        fun isClosed() = closed
        private fun recordCall() { nativeCallThreads += Thread.currentThread().name }
    }

    private class StopCounter {
        @Volatile var count = 0
            private set
        fun increment() { count++ }
        suspend fun awaitAtLeast(expected: Int) = withTimeout(2_000) {
            while (count < expected) delay(5)
        }
    }
}
