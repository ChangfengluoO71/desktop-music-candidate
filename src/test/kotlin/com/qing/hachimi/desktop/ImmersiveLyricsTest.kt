package com.qing.hachimi.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImmersiveLyricsTest {
    @Test
    fun `pairs translation and romanization by timestamp including allowed skew`() {
        val result = ImmersiveLyrics.align(
            LocalLyricsBundle(
                original = listOf(TimedLyricLine(1_000, "one"), TimedLyricLine(3_000, "two")),
                translation = listOf(TimedLyricLine(1_400, "一"), TimedLyricLine(3_500, "二")),
                romanization = listOf(TimedLyricLine(900, "ichi"), TimedLyricLine(2_600, "ni")),
            ),
        )

        assertEquals(
            listOf(
                ImmersiveLyricLine(TimedLyricLine(1_000, "one"), TimedLyricLine(1_400, "一"), TimedLyricLine(900, "ichi")),
                ImmersiveLyricLine(TimedLyricLine(3_000, "two"), TimedLyricLine(3_500, "二"), TimedLyricLine(2_600, "ni")),
            ),
            result,
        )
    }

    @Test
    fun `does not attach secondary lyrics outside tolerance`() {
        val result = ImmersiveLyrics.align(
            LocalLyricsBundle(
                original = listOf(TimedLyricLine(1_000, "original")),
                translation = listOf(TimedLyricLine(1_501, "translation")),
            ),
        )

        assertEquals(listOf(ImmersiveLyricLine(TimedLyricLine(1_000, "original"), null, null)), result)
    }

    @Test
    fun `does not reuse one secondary line for multiple originals`() {
        val result = ImmersiveLyrics.align(
            LocalLyricsBundle(
                original = listOf(TimedLyricLine(1_000, "first"), TimedLyricLine(1_200, "second")),
                translation = listOf(TimedLyricLine(1_100, "translation")),
            ),
        )

        assertEquals(listOf(null, null), result.map { it.translation })
    }

    @Test
    fun `leaves equal distance candidates unpaired as ambiguous`() {
        val result = ImmersiveLyrics.align(
            LocalLyricsBundle(
                original = listOf(TimedLyricLine(1_000, "before"), TimedLyricLine(1_400, "after")),
                translation = listOf(TimedLyricLine(1_200, "ambiguous")),
            ),
        )

        assertEquals(listOf(null, null), result.map { it.translation })
    }

    @Test
    fun `matches repeated lyric text by unique time rather than text`() {
        val result = ImmersiveLyrics.align(
            LocalLyricsBundle(
                original = listOf(TimedLyricLine(1_000, "same"), TimedLyricLine(5_000, "same")),
                translation = listOf(TimedLyricLine(1_050, "同"), TimedLyricLine(5_100, "同")),
            ),
        )

        assertEquals(listOf("同", "同"), result.map { it.translation?.text })
    }

    @Test
    fun `preserves every original line in chronological order`() {
        val result = ImmersiveLyrics.align(
            LocalLyricsBundle(
                original = listOf(TimedLyricLine(2_000, "later"), TimedLyricLine(1_000, "earlier")),
            ),
        )

        assertEquals(listOf("earlier", "later"), result.map { it.original.text })
    }

    @Test
    fun `returns no groups without original lyrics`() {
        assertEquals(emptyList(), ImmersiveLyrics.align(LocalLyricsBundle(translation = listOf(TimedLyricLine(1, "translation")))))
    }

    @Test
    fun `rejects a tolerance above the design limit`() {
        assertFailsWith<IllegalArgumentException> {
            ImmersiveLyrics.align(LocalLyricsBundle(), toleranceMillis = 501)
        }
    }
}
