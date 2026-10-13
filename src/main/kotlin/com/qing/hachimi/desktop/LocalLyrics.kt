package com.qing.hachimi.desktop

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class TimedLyricLine(
    val timeMillis: Long,
    val text: String,
)

/** Parses local LRC text without fetching or changing any audio/tag files. */
object LocalLyricsParser {
    private val timestamp = Regex("^\\[(\\d{1,3}):([0-5]\\d)(?:[.:](\\d{1,3}))?]")
    private val offset = Regex("^\\[offset:([+-]?\\d+)]$", RegexOption.IGNORE_CASE)

    fun parse(content: String): List<TimedLyricLine> {
        var offsetMillis = 0L
        val parsed = mutableListOf<Pair<Int, TimedLyricLine>>()
        content.removePrefix("\uFEFF").lineSequence().forEachIndexed { lineIndex, rawLine ->
            val line = rawLine.trim()
            offset.matchEntire(line)?.let { match ->
                offsetMillis = match.groupValues[1].toLongOrNull() ?: 0L
                return@forEachIndexed
            }

            var remaining = line
            val times = mutableListOf<Long>()
            while (true) {
                val match = timestamp.find(remaining) ?: break
                val minutes = match.groupValues[1].toLongOrNull() ?: break
                val seconds = match.groupValues[2].toLongOrNull() ?: break
                val fraction = match.groupValues[3]
                val fractionMillis = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLongOrNull()?.times(100L) ?: 0L
                    2 -> fraction.toLongOrNull()?.times(10L) ?: 0L
                    else -> fraction.toLongOrNull() ?: 0L
                }
                val rawTime = runCatching { Math.addExact(Math.addExact(Math.multiplyExact(minutes, 60_000L), seconds * 1_000L), fractionMillis) }
                    .getOrNull() ?: break
                times += (rawTime + offsetMillis).coerceAtLeast(0L)
                remaining = remaining.substring(match.range.last + 1)
            }

            val text = remaining.trim()
            if (text.isNotEmpty()) {
                times.forEach { time -> parsed += lineIndex to TimedLyricLine(time, text) }
            }
        }
        return parsed.sortedWith(compareBy<Pair<Int, TimedLyricLine>> { it.second.timeMillis }.thenBy { it.first })
            .map { it.second }
    }
}

enum class LyricVariant { ORIGINAL, TRANSLATION, ROMANIZATION }

data class LocalLyricsBundle(
    val original: List<TimedLyricLine> = emptyList(),
    val translation: List<TimedLyricLine> = emptyList(),
    val romanization: List<TimedLyricLine> = emptyList(),
) {
    fun lines(variant: LyricVariant): List<TimedLyricLine> = when (variant) {
        LyricVariant.ORIGINAL -> original
        LyricVariant.TRANSLATION -> translation
        LyricVariant.ROMANIZATION -> romanization
    }

    fun currentLineIndex(variant: LyricVariant, positionMillis: Long): Int {
        val lines = lines(variant)
        var low = 0
        var high = lines.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMillis <= positionMillis) {
                found = mid
                low = mid + 1
            } else high = mid - 1
        }
        return found
    }
}

class LocalLyricsLoader(
    private val embeddedLyricsReader: (Path) -> String? = ::readEmbeddedLyrics,
) {
    fun load(audioPath: Path): LocalLyricsBundle {
        val stem = audioPath.fileName?.toString()?.substringBeforeLast('.', audioPath.fileName.toString()).orEmpty()
        val parent = audioPath.parent ?: return LocalLyricsBundle()
        val base = parent.resolve(stem)
        val embedded = runCatching { embeddedLyricsReader(audioPath) }.getOrNull().orEmpty()
        val original = parseIfPresent(embedded).ifEmpty { parseSidecar(base.resolveSibling("$stem.lrc")) }
        return LocalLyricsBundle(
            original = original,
            translation = parseSidecar(base.resolveSibling("$stem.tl.lrc")),
            romanization = parseSidecar(base.resolveSibling("$stem.roma.lrc")),
        )
    }

    private fun parseSidecar(path: Path): List<TimedLyricLine> {
        if (!Files.isRegularFile(path)) return emptyList()
        val content = runCatching {
            if (Files.size(path) > MAX_LYRICS_BYTES) return emptyList()
            Files.readString(path, StandardCharsets.UTF_8)
        }.getOrNull() ?: return emptyList()
        return parseIfPresent(content)
    }

    private fun parseIfPresent(content: String): List<TimedLyricLine> =
        if (content.isBlank() || content.length > MAX_LYRICS_BYTES) emptyList() else LocalLyricsParser.parse(content)

    private companion object {
        const val MAX_LYRICS_BYTES = 2 * 1024 * 1024L

        fun readEmbeddedLyrics(path: Path): String? = runCatching {
            AudioFileIO.read(path.toFile()).tag?.getFirst(FieldKey.LYRICS)
        }.getOrNull()?.takeIf(String::isNotBlank)
    }
}
