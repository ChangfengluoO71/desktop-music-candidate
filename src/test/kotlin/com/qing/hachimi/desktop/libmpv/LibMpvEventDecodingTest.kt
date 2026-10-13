package com.qing.hachimi.desktop.libmpv

import com.sun.jna.Native
import kotlin.test.Test
import kotlin.test.assertEquals

class LibMpvEventDecodingTest {
    @Test
    fun `decodes documented end file reasons and preserves unknown values`() {
        assertEquals(NativeEndReason.EOF, decodeNativeEndReason(0))
        assertEquals(NativeEndReason.STOP, decodeNativeEndReason(2))
        assertEquals(NativeEndReason.QUIT, decodeNativeEndReason(3))
        assertEquals(NativeEndReason.ERROR, decodeNativeEndReason(4))
        assertEquals(NativeEndReason.REDIRECT, decodeNativeEndReason(5))
        assertEquals(NativeEndReason.UNKNOWN, decodeNativeEndReason(-1))
        assertEquals(NativeEndReason.UNKNOWN, decodeNativeEndReason(99))
    }

    @Test
    fun `encodes command arguments as retained utf8 null terminated strings`() {
        val path = "C:\\测试 音频\\歌词.mp3"
        val argv = encodeNullTerminatedUtf8Arguments(listOf("loadfile", path, "replace"))

        assertEquals("loadfile", argv.pointers.getPointer(0).getString(0, "UTF-8"))
        assertEquals(path, argv.pointers.getPointer(Native.POINTER_SIZE.toLong()).getString(0, "UTF-8"))
        assertEquals("replace", argv.pointers.getPointer((Native.POINTER_SIZE * 2).toLong()).getString(0, "UTF-8"))
        assertEquals(null, argv.pointers.getPointer((Native.POINTER_SIZE * 3).toLong()))
    }
}
