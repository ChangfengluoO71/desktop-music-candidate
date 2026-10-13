package com.qing.hachimi.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir

class LocalLyricsTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `parses multiple timestamps and sorts lyric lines`() {
        val lyrics = LocalLyricsParser.parse(
            """[ti:Example]
                |[00:02.500]second
                |[00:01.25][00:03.000]first and third
                |[ar:Artist]
            """.trimMargin(),
        )

        assertEquals(
            listOf(
                TimedLyricLine(1_250, "first and third"),
                TimedLyricLine(2_500, "second"),
                TimedLyricLine(3_000, "first and third"),
            ),
            lyrics,
        )
    }

    @Test
    fun `applies offset and ignores malformed and empty lyric rows`() {
        val lyrics = LocalLyricsParser.parse(
            """[offset:-250]
                |[00:01.00]line
                |[99:99.00]invalid
                |[00:02.00]
                |not a timestamp
            """.trimMargin(),
        )

        assertEquals(listOf(TimedLyricLine(750, "line")), lyrics)
    }

    @Test
    fun `negative offsets clamp timestamp at zero`() {
        assertEquals(
            listOf(TimedLyricLine(0, "early")),
            LocalLyricsParser.parse("[offset:-500]\n[00:00.20]early"),
        )
    }

    @Test
    fun `loads translation and romanization from downloader sibling sidecars`() {
        val audio = temp.resolve("Song.mp3")
        Files.write(audio, byteArrayOf(1, 2, 3))
        Files.writeString(temp.resolve("Song.lrc"), "[00:01.00]original")
        Files.writeString(temp.resolve("Song.tl.lrc"), "[00:01.00]translation")
        Files.writeString(temp.resolve("Song.roma.lrc"), "[00:01.00]romanization")

        val lyrics = LocalLyricsLoader { null }.load(audio)

        assertEquals("original", lyrics.original.single().text)
        assertEquals("translation", lyrics.translation.single().text)
        assertEquals("romanization", lyrics.romanization.single().text)
    }

    @Test
    fun `embedded original lyrics take precedence over sibling original file`() {
        val audio = temp.resolve("Song.mp3")
        Files.write(audio, byteArrayOf(1))
        Files.writeString(temp.resolve("Song.lrc"), "[00:01.00]sidecar")

        val lyrics = LocalLyricsLoader { "[00:02.00]embedded" }.load(audio)

        assertEquals(listOf(TimedLyricLine(2_000, "embedded")), lyrics.original)
    }
}
