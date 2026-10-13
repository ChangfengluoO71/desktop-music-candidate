package com.qing.hachimi.desktop.libmpv

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.nio.file.Files
import java.nio.file.Path

internal fun interface MpvClientFactory {
    fun create(libraryPath: Path): MpvClient
}

/** Small seam around libmpv. Production calls are made only from the engine's native worker. */
internal interface MpvClient : AutoCloseable {
    fun clientApiVersion(): Int
    fun setOption(name: String, value: String): Int
    fun requestEvent(eventId: Int, enable: Boolean): Int
    fun initialize(): Int
    fun observeProperty(userdata: Long, name: String, format: MpvPropertyFormat): Int
    fun unobserveProperty(userdata: Long): Int
    fun commandAsync(userdata: Long, arguments: List<String>): Int
    fun command(arguments: List<String>): Int
    fun setPropertyString(name: String, value: String): Int
    fun waitEvent(timeoutMillis: Long): MpvNativeEvent
    fun errorString(code: Int): String
    override fun close()
}

internal enum class MpvPropertyFormat(val nativeValue: Int) {
    STRING(1),
    DOUBLE(5),
}

internal sealed interface MpvPropertyValue {
    data class Text(val value: String?) : MpvPropertyValue
    data class Number(val value: Double?) : MpvPropertyValue
}

internal enum class MpvEndReason(val nativeValue: Int) {
    EOF(0),
    STOP(2),
    QUIT(3),
    ERROR(4),
    REDIRECT(5),
    UNKNOWN(Int.MIN_VALUE),
}

internal sealed interface MpvNativeEvent {
    data object None : MpvNativeEvent
    data object Shutdown : MpvNativeEvent
    data object FileLoaded : MpvNativeEvent
    data class StartFile(val playlistEntryId: Long) : MpvNativeEvent
    data class EndFile(val playlistEntryId: Long, val reason: MpvEndReason, val error: Int) : MpvNativeEvent
    data class CommandReply(val userdata: Long, val error: Int) : MpvNativeEvent
    data class PropertyChanged(val userdata: Long, val name: String, val value: MpvPropertyValue?) : MpvNativeEvent
    data class Other(val eventId: Int) : MpvNativeEvent
}

/** Loads only the explicitly configured absolute DLL; it never searches PATH. */
internal object JnaMpvClientFactory : MpvClientFactory {
    override fun create(libraryPath: Path): MpvClient {
        require(libraryPath.isAbsolute) { "libmpv library path must be absolute." }
        val canonical = libraryPath.toRealPath()
        require(Files.isRegularFile(canonical) && canonical.fileName.toString().equals("libmpv-2.dll", ignoreCase = true)) {
            "Configured libmpv library is missing or is not libmpv-2.dll."
        }
        return JnaMpvClient(canonical)
    }
}

internal interface MpvFunctions : Library {
    fun mpv_client_api_version(): Int
    fun mpv_create(): Pointer?
    fun mpv_set_option_string(ctx: Pointer, name: Pointer, value: Pointer): Int
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_request_event(ctx: Pointer, eventId: Int, enable: Int): Int
    fun mpv_observe_property(ctx: Pointer, userdata: Long, name: Pointer, format: Int): Int
    fun mpv_unobserve_property(ctx: Pointer, userdata: Long): Int
    fun mpv_command_async(ctx: Pointer, userdata: Long, args: Pointer): Int
    fun mpv_command(ctx: Pointer, args: Pointer): Int
    fun mpv_set_property_string(ctx: Pointer, name: Pointer, value: Pointer): Int
    fun mpv_wait_event(ctx: Pointer, timeoutSeconds: Double): Pointer?
    fun mpv_error_string(error: Int): Pointer?
    fun mpv_terminate_destroy(ctx: Pointer)
}

private class JnaMpvClient(libraryPath: Path) : MpvClient {
    private val api = Native.load(libraryPath.toString(), MpvFunctions::class.java)
    private val handle = api.mpv_create() ?: error("libmpv could not create a playback context.")
    @Volatile private var closed = false

    override fun clientApiVersion(): Int = api.mpv_client_api_version()

    override fun setOption(name: String, value: String): Int = withUtf8(name) { nativeName ->
        withUtf8(value) { nativeValue -> api.mpv_set_option_string(handle, nativeName, nativeValue) }
    }

    override fun requestEvent(eventId: Int, enable: Boolean): Int = api.mpv_request_event(handle, eventId, if (enable) 1 else 0)

    override fun initialize(): Int = api.mpv_initialize(handle)

    override fun observeProperty(userdata: Long, name: String, format: MpvPropertyFormat): Int = withUtf8(name) { nativeName ->
        api.mpv_observe_property(handle, userdata, nativeName, format.nativeValue)
    }

    override fun unobserveProperty(userdata: Long): Int = api.mpv_unobserve_property(handle, userdata)

    override fun commandAsync(userdata: Long, arguments: List<String>): Int = withMpvArgv(arguments) { argv ->
        api.mpv_command_async(handle, userdata, argv)
    }

    override fun command(arguments: List<String>): Int = withMpvArgv(arguments) { argv ->
        api.mpv_command(handle, argv)
    }

    override fun setPropertyString(name: String, value: String): Int = withUtf8(name) { nativeName ->
        withUtf8(value) { nativeValue -> api.mpv_set_property_string(handle, nativeName, nativeValue) }
    }

    override fun waitEvent(timeoutMillis: Long): MpvNativeEvent {
        if (closed) return MpvNativeEvent.None
        val pointer = api.mpv_wait_event(handle, timeoutMillis.coerceAtLeast(0).toDouble() / 1_000.0)
            ?: return MpvNativeEvent.None
        val event = NativeMpvEvent(pointer)
        return when (event.eventId) {
            MpvNativeEventId.SHUTDOWN -> MpvNativeEvent.Shutdown
            MpvNativeEventId.COMMAND_REPLY -> MpvNativeEvent.CommandReply(event.replyUserdata, event.error)
            MpvNativeEventId.START_FILE -> event.data?.let { NativeStartFileEvent(it).playlistEntryId }
                ?.let(MpvNativeEvent::StartFile) ?: MpvNativeEvent.Other(event.eventId)
            MpvNativeEventId.FILE_LOADED -> MpvNativeEvent.FileLoaded
            MpvNativeEventId.END_FILE -> event.data?.let { data ->
                NativeEndFileEvent(data).let { end ->
                    MpvNativeEvent.EndFile(end.playlistEntryId, MpvEventDecoder.endReason(end.reason), end.error)
                }
            } ?: MpvNativeEvent.Other(event.eventId)
            MpvNativeEventId.PROPERTY_CHANGE -> event.data?.let { data ->
                NativePropertyEvent(data).let { property ->
                    val name = property.name?.getString(0, Charsets.UTF_8.name()).orEmpty()
                    val value = property.data?.let { decodeProperty(property.format, it) }
                    MpvNativeEvent.PropertyChanged(event.replyUserdata, name, value)
                }
            } ?: MpvNativeEvent.Other(event.eventId)
            else -> MpvNativeEvent.Other(event.eventId)
        }
    }

    private fun decodeProperty(format: Int, pointer: Pointer): MpvPropertyValue? = when (format) {
        MpvPropertyFormat.STRING.nativeValue -> pointer.getPointer(0)
            ?.getString(0, Charsets.UTF_8.name())?.let(MpvPropertyValue::Text)
        MpvPropertyFormat.DOUBLE.nativeValue -> MpvPropertyValue.Number(pointer.getDouble(0))
        else -> null
    }

    override fun errorString(code: Int): String {
        val message = api.mpv_error_string(code)?.getString(0, Charsets.UTF_8.name())
        return message?.takeIf(String::isNotBlank) ?: "libmpv error $code"
    }

    override fun close() {
        if (!closed) {
            closed = true
            api.mpv_terminate_destroy(handle)
        }
    }

    private inline fun <T> withUtf8(value: String, action: (Pointer) -> T): T {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val memory = Memory(bytes.size + 1L)
        return try {
            memory.write(0, bytes, 0, bytes.size)
            memory.setByte(bytes.size.toLong(), 0)
            action(memory)
        } finally {
            memory.close()
        }
    }

    private inline fun <T> withMpvArgv(arguments: List<String>, action: (Pointer) -> T): T {
        val strings = arguments.map { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            Memory(bytes.size + 1L).apply {
                write(0, bytes, 0, bytes.size)
                setByte(bytes.size.toLong(), 0)
            }
        }
        val argv = Memory((strings.size + 1L) * Native.POINTER_SIZE)
        return try {
            strings.forEachIndexed { index, memory -> argv.setPointer(index * Native.POINTER_SIZE.toLong(), memory) }
            argv.setPointer(strings.size * Native.POINTER_SIZE.toLong(), Pointer.NULL)
            action(argv)
        } finally {
            argv.close()
            strings.forEach(Memory::close)
        }
    }

    private class NativeMpvEvent(pointer: Pointer) : Structure(pointer) {
        @JvmField var eventId: Int = 0
        @JvmField var error: Int = 0
        @JvmField var replyUserdata: Long = 0
        @JvmField var data: Pointer? = null

        init { read() }

        override fun getFieldOrder(): List<String> = listOf("eventId", "error", "replyUserdata", "data")
    }

    private class NativeStartFileEvent(pointer: Pointer) : Structure(pointer) {
        @JvmField var playlistEntryId: Long = 0

        init { read() }

        override fun getFieldOrder(): List<String> = listOf("playlistEntryId")
    }

    private class NativeEndFileEvent(pointer: Pointer) : Structure(pointer) {
        @JvmField var reason: Int = 0
        @JvmField var error: Int = 0
        @JvmField var playlistEntryId: Long = 0
        @JvmField var playlistInsertId: Long = 0
        @JvmField var playlistInsertNumEntries: Int = 0

        init { read() }

        override fun getFieldOrder(): List<String> = listOf(
            "reason", "error", "playlistEntryId", "playlistInsertId", "playlistInsertNumEntries",
        )
    }

    private class NativePropertyEvent(pointer: Pointer) : Structure(pointer) {
        @JvmField var name: Pointer? = null
        @JvmField var format: Int = 0
        @JvmField var data: Pointer? = null

        init { read() }

        override fun getFieldOrder(): List<String> = listOf("name", "format", "data")
    }
}

internal object MpvNativeEventId {
    const val SHUTDOWN = 1
    const val COMMAND_REPLY = 5
    const val START_FILE = 6
    const val END_FILE = 7
    const val FILE_LOADED = 8
    const val PROPERTY_CHANGE = 22
}
