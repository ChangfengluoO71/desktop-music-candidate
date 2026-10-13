package com.qing.hachimi.desktop.libmpv

import com.sun.jna.Library
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.nio.file.Path
import java.time.Duration

/** Minimal test-only mapping of the libmpv C client API used by the R1 probe. */
internal interface LibMpvNativeApi : Library {
    fun mpv_create(): Pointer?
    fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_request_event(ctx: Pointer, eventId: Int, enable: Int): Int
    fun mpv_command(ctx: Pointer, args: Pointer): Int
    fun mpv_wait_event(ctx: Pointer, timeoutSeconds: Double): Pointer
    fun mpv_event_name(eventId: Int): Pointer?
    fun mpv_client_api_version(): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer)
    fun mpv_error_string(error: Int): Pointer?
    fun mpv_terminate_destroy(ctx: Pointer)
}

internal data class NativeDecodeResult(
    val loadCommandStatus: Int?,
    val stopCommandStatus: Int?,
    val fileLoaded: Boolean,
    val durationSeconds: Double?,
    val positionsSeconds: List<Double>,
    val endReason: NativeEndReason?,
    val endError: Int?,
    val timedOut: Boolean,
    val eventNames: List<String>,
) {
    val isCompletedDecode: Boolean
        get() = loadCommandStatus != null && loadCommandStatus >= 0 &&
            fileLoaded && durationSeconds != null && durationSeconds > 0.0 &&
            endReason == NativeEndReason.EOF && endError == 0 && !timedOut

    val isHealthyDecode: Boolean
        get() = isCompletedDecode && positionsSeconds.any { it > 0.001 }

    fun summary(): String =
        "loaded=$fileLoaded duration=$durationSeconds maxPosition=${positionsSeconds.maxOrNull()} " +
            "endReason=$endReason endError=$endError loadCommandStatus=$loadCommandStatus " +
            "stopCommandStatus=$stopCommandStatus timedOut=$timedOut events=${eventNames.joinToString(",")}"
}

/** Test-only client: uses null output and never enters the production playback path. */
internal class LibMpvNativeDecoder(private val api: LibMpvNativeApi) : AutoCloseable {
    private val handle: Pointer = api.mpv_create()
        ?: error("mpv_create returned null")
    private var closed = false

    init {
        try {
            setOption("config", "no")
            setOption("ao", "null")
            setOption("audio-fallback-to-null", "no")
            setOption("idle", "yes")
            setOption("load-scripts", "no")
            checkApi("request end-file events", api.mpv_request_event(handle, EVENT_END_FILE, 1))
            checkApi("request file-loaded events", api.mpv_request_event(handle, EVENT_FILE_LOADED, 1))
            checkApi("initialize", api.mpv_initialize(handle))
        } catch (failure: Throwable) {
            api.mpv_terminate_destroy(handle)
            closed = true
            throw failure
        }
    }

    fun decodeToEnd(file: Path, timeout: Duration = Duration.ofSeconds(30)): NativeDecodeResult {
        val state = DecodeState()
        val commandStatus = command("loadfile", file.toAbsolutePath().toString(), "replace")
        state.loadCommandStatus = commandStatus
        if (commandStatus >= 0) pump(state, timeout) { state.endReason != null }
        return state.snapshot()
    }

    fun loadThenStop(file: Path, timeout: Duration = Duration.ofSeconds(15)): NativeDecodeResult {
        val state = DecodeState()
        val commandStatus = command("loadfile", file.toAbsolutePath().toString(), "replace")
        state.loadCommandStatus = commandStatus
        if (commandStatus >= 0) {
            pump(state, timeout) { state.fileLoaded || state.endReason != null }
            if (state.fileLoaded && state.endReason == null) {
                val stopStatus = command("stop")
                state.stopCommandStatus = stopStatus
                if (stopStatus >= 0) pump(state, timeout) { state.endReason != null }
            }
        }
        return state.snapshot()
    }

    private fun pump(state: DecodeState, timeout: Duration, done: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!done() && System.nanoTime() < deadline) {
            val eventPointer = api.mpv_wait_event(handle, 0.1)
            val event = MpvEvent(eventPointer)
            if (event.eventId != EVENT_NONE) {
                val name = api.mpv_event_name(event.eventId)?.getString(0, "UTF-8")
                    ?: "UNKNOWN_${event.eventId}"
                state.eventNames += name
                when (event.eventId) {
                    EVENT_FILE_LOADED -> state.fileLoaded = true
                    EVENT_END_FILE -> event.data?.let { data ->
                        val end = MpvEndFileEvent(data)
                        state.endReason = decodeNativeEndReason(end.reason)
                        state.endError = end.error
                    }
                }
            }
            if (state.fileLoaded && state.endReason == null) {
                readDoubleProperty("duration")?.let { state.durationSeconds = it }
                readDoubleProperty("time-pos")?.let { position ->
                    if (state.positionsSeconds.isEmpty() ||
                        kotlin.math.abs(state.positionsSeconds.last() - position) > 0.001
                    ) {
                        state.positionsSeconds += position
                    }
                }
            }
        }
        if (!done()) state.timedOut = true
    }

    private fun command(vararg arguments: String): Int {
        check(!closed) { "libmpv decoder is closed" }
        val argv = encodeNullTerminatedUtf8Arguments(arguments.toList())
        return api.mpv_command(handle, argv.pointers)
    }

    private fun setOption(name: String, value: String) {
        checkApi("set option $name", api.mpv_set_option_string(handle, name, value))
    }

    private fun readDoubleProperty(name: String): Double? {
        val value = api.mpv_get_property_string(handle, name) ?: return null
        return try {
            value.getString(0, "UTF-8").toDoubleOrNull()
        } finally {
            api.mpv_free(value)
        }
    }

    private fun checkApi(operation: String, code: Int) {
        if (code < 0) {
            val detail = api.mpv_error_string(code)?.getString(0, "UTF-8") ?: "unknown native error"
            error("libmpv $operation failed: $detail ($code)")
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            api.mpv_terminate_destroy(handle)
        }
    }

    private class DecodeState {
        var loadCommandStatus: Int? = null
        var stopCommandStatus: Int? = null
        var fileLoaded = false
        var durationSeconds: Double? = null
        val positionsSeconds = mutableListOf<Double>()
        var endReason: NativeEndReason? = null
        var endError: Int? = null
        var timedOut = false
        val eventNames = mutableListOf<String>()

        fun snapshot() = NativeDecodeResult(
            loadCommandStatus = loadCommandStatus,
            stopCommandStatus = stopCommandStatus,
            fileLoaded = fileLoaded,
            durationSeconds = durationSeconds,
            positionsSeconds = positionsSeconds.toList(),
            endReason = endReason,
            endError = endError,
            timedOut = timedOut,
            eventNames = eventNames.toList(),
        )
    }

    @Structure.FieldOrder("eventId", "error", "replyUserdata", "data")
    class MpvEvent(pointer: Pointer) : Structure(pointer) {
        @JvmField var eventId: Int = 0
        @JvmField var error: Int = 0
        @JvmField var replyUserdata: Long = 0
        @JvmField var data: Pointer? = null

        init { read() }

    }

    @Structure.FieldOrder("reason", "error")
    class MpvEndFileEvent(pointer: Pointer) : Structure(pointer) {
        @JvmField var reason: Int = 0
        @JvmField var error: Int = 0

        init { read() }

    }

    private companion object {
        const val EVENT_NONE = 0
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8
    }
}
