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

data class LyricPresentation(
    val timedLines: List<TimedLyricLine> = emptyList(),
    val unsynchronizedLines: List<String> = emptyList(),
    val credits: List<String> = emptyList(),
) {
    fun currentLineIndex(positionMillis: Long): Int = timedLines.indexOfLast { it.timeMillis <= positionMillis }
    val isEmpty: Boolean get() = timedLines.isEmpty() && unsynchronizedLines.isEmpty() && credits.isEmpty()
}

private const val creditRole = "作词人|作詞人|作词|作詞|作曲人|作曲|编曲|編曲|制作人|製作人|制作|製作|歌手|艺术家|藝術家|专辑|專輯|lyricist|lyrics by|composer|composed by|arranger|arranged by|producer|produced by|artist|album"
private val creditLabel = Regex(
    """^(?:$creditRole)(?:\s*[/／]\s*(?:$creditRole))*\s*[:：]\s*\S.*$""",
    RegexOption.IGNORE_CASE,
)
private val lrcTimestamp = Regex("""^(?:\[\d{1,3}:[0-5]\d(?:[.:]\d{1,3})?])+""")
private val lrcMetadata = Regex("""^\[[A-Za-z]+:[^]]*]$""")

data class UntimedLyricContent(
    val lines: List<String> = emptyList(),
    val credits: List<String> = emptyList(),
)

private fun untimedContent(content: String): UntimedLyricContent {
    val plain = content.removePrefix("\uFEFF").lineSequence().map(String::trim)
        .filter { it.isNotEmpty() && !lrcTimestamp.containsMatchIn(it) && !lrcMetadata.matches(it) }
        .toList()
    return UntimedLyricContent(plain.filterNot { creditLabel.matches(it) }, plain.filter { creditLabel.matches(it) })
}

private fun presentation(timed: List<TimedLyricLine>, untimed: UntimedLyricContent): LyricPresentation =
    LyricPresentation(
        timedLines = timed.filterNot { creditLabel.matches(it.text) },
        unsynchronizedLines = untimed.lines,
        credits = timed.map { it.text }.filter { creditLabel.matches(it) }.distinct() + untimed.credits,
    )

data class LocalLyricsBundle(
    val original: List<TimedLyricLine> = emptyList(),
    val translation: List<TimedLyricLine> = emptyList(),
    val romanization: List<TimedLyricLine> = emptyList(),
    val originalUntimed: UntimedLyricContent = UntimedLyricContent(),
    val translationUntimed: UntimedLyricContent = UntimedLyricContent(),
    val romanizationUntimed: UntimedLyricContent = UntimedLyricContent(),
) {
    fun presentation(variant: LyricVariant): LyricPresentation = when (variant) {
        LyricVariant.ORIGINAL -> presentation(original, originalUntimed)
        LyricVariant.TRANSLATION -> presentation(translation, translationUntimed)
        LyricVariant.ROMANIZATION -> presentation(romanization, romanizationUntimed)
    }

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
            .takeIf { it.length <= MAX_LYRICS_BYTES }.orEmpty()
        val embeddedTimed = parseIfPresent(embedded)
        val sidecar = if (embeddedTimed.isEmpty()) readSidecar(base.resolveSibling("$stem.lrc")) else ""
        val sidecarTimed = parseIfPresent(sidecar)
        val originalContent = if (embeddedTimed.isNotEmpty() || sidecarTimed.isEmpty() && embedded.isNotBlank()) embedded else sidecar
        val original = if (embeddedTimed.isNotEmpty()) embeddedTimed else sidecarTimed
        val translationContent = readSidecar(base.resolveSibling("$stem.tl.lrc"))
        val romanizationContent = readSidecar(base.resolveSibling("$stem.roma.lrc"))
        val translation = parseIfPresent(translationContent)
        val romanization = parseIfPresent(romanizationContent)
        return LocalLyricsBundle(
            original = original,
            translation = translation,
            romanization = romanization,
            originalUntimed = untimedContent(originalContent),
            translationUntimed = untimedContent(translationContent),
            romanizationUntimed = untimedContent(romanizationContent),
        )
    }

    private fun readSidecar(path: Path): String {
        if (!Files.isRegularFile(path)) return ""
        return runCatching {
            if (Files.size(path) > MAX_LYRICS_BYTES) return ""
            Files.readString(path, StandardCharsets.UTF_8)
        }.getOrNull() ?: ""
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
