package com.qing.hachimi.desktop.libmpv

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer

internal enum class NativeEndReason { EOF, STOP, QUIT, ERROR, REDIRECT, UNKNOWN }

internal data class NativeArgv(
    val pointers: Memory,
    @Suppress("unused") private val strings: List<Memory>,
)

internal fun decodeNativeEndReason(reason: Int): NativeEndReason = when (reason) {
    0 -> NativeEndReason.EOF
    2 -> NativeEndReason.STOP
    3 -> NativeEndReason.QUIT
    4 -> NativeEndReason.ERROR
    5 -> NativeEndReason.REDIRECT
    else -> NativeEndReason.UNKNOWN
}

internal fun encodeNullTerminatedUtf8Arguments(arguments: List<String>): NativeArgv {
    val strings = arguments.map { argument ->
        val bytes = argument.toByteArray(Charsets.UTF_8)
        Memory((bytes.size + 1).toLong()).apply {
            write(0, bytes, 0, bytes.size)
            setByte(bytes.size.toLong(), 0)
        }
    }
    val pointers = Memory((arguments.size + 1L) * Native.POINTER_SIZE)
    strings.forEachIndexed { index, memory ->
        pointers.setPointer(index * Native.POINTER_SIZE.toLong(), memory)
    }
    pointers.setPointer(arguments.size * Native.POINTER_SIZE.toLong(), Pointer.NULL)
    return NativeArgv(pointers, strings)
}
