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
    fun `retains untimed lyrics and recognizes explicit credits`() {
        val audio = temp.resolve("Song.mp3")
        Files.writeString(temp.resolve("Song.lrc"), "[ti:Song]\n作词： Someone\nfirst line\nsecond line")
        val bundle = LocalLyricsLoader { null }.load(audio)
        val view = bundle.presentation(LyricVariant.ORIGINAL)
        assertEquals(emptyList(), bundle.original)
        assertEquals(listOf("first line", "second line"), view.unsynchronizedLines)
        assertEquals(listOf("作词： Someone"), view.credits)
        assertEquals(-1, view.currentLineIndex(10000))
    }

    @Test
    fun `timed credits do not affect presentation current index`() {
        val audio = temp.resolve("Song.mp3")
        val bundle = LocalLyricsLoader { "[00:01]Composer: Name\n[00:02]verse\n[00:03]作曲：名字\n[00:04]composer of my dreams" }.load(audio)
        val view = bundle.presentation(LyricVariant.ORIGINAL)
        assertEquals(4, bundle.original.size)
        assertEquals(listOf("Composer: Name", "作曲：名字"), view.credits)
        assertEquals(listOf("verse", "composer of my dreams"), view.timedLines.map { it.text })
        assertEquals(-1, view.currentLineIndex(1500))
        assertEquals(0, view.currentLineIndex(3500))
        assertEquals(1, view.currentLineIndex(4500))
    }

    @Test
    fun `explicit artist album and production labels are not synchronized`() {
        val labels = listOf("Artist: Name", "Album：Title", "歌手: 名字", "艺术家：名字", "专辑：名称", "制作：名字", "作词人：名字")
        val timed = labels.mapIndexed { index, text -> TimedLyricLine(index.toLong(), text) } +
            TimedLyricLine(10, "artist of my life")
        val view = LocalLyricsBundle(original = timed).presentation(LyricVariant.ORIGINAL)
        assertEquals(labels, view.credits)
        assertEquals(listOf("artist of my life"), view.timedLines.map { it.text })
        assertEquals(-1, view.currentLineIndex(9))
        assertEquals(0, view.currentLineIndex(10))
    }

    @Test
    fun `copies derive presentation from updated timed rows`() {
        val original = LocalLyricsBundle(original = listOf(TimedLyricLine(1, "old")))
        val copied = original.copy(original = listOf(TimedLyricLine(2, "作词 / 作曲：名字"), TimedLyricLine(3, "new")))
        val view = copied.presentation(LyricVariant.ORIGINAL)
        assertEquals(listOf("new"), view.timedLines.map { it.text })
        assertEquals(listOf("作词 / 作曲：名字"), view.credits)
        assertEquals(-1, view.currentLineIndex(2))
        assertEquals(0, view.currentLineIndex(3))
    }

    @Test
    fun `timed sidecar retains precedence over untimed embedded lyrics`() {
        val audio = temp.resolve("Song.mp3")
        Files.writeString(temp.resolve("Song.lrc"), "[00:01]sidecar")
        val bundle = LocalLyricsLoader { "embedded plain" }.load(audio)
        assertEquals(listOf("sidecar"), bundle.presentation(LyricVariant.ORIGINAL).timedLines.map { it.text })
        assertEquals(emptyList(), bundle.presentation(LyricVariant.ORIGINAL).unsynchronizedLines)
    }

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
