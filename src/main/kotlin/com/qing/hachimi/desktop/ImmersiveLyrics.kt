package com.qing.hachimi.desktop

import java.lang.Math.subtractExact

data class ImmersiveLyricLine(
    val original: TimedLyricLine,
    val translation: TimedLyricLine?,
    val romanization: TimedLyricLine?,
)

/** Matches local lyric variants only when timestamp proximity is one-to-one and unambiguous. */
object ImmersiveLyrics {
    fun align(bundle: LocalLyricsBundle, toleranceMillis: Long = MAX_TOLERANCE_MILLIS): List<ImmersiveLyricLine> {
        require(toleranceMillis in 0..MAX_TOLERANCE_MILLIS) { "Lyric alignment tolerance must be between 0 and 500 ms." }

        val originals = bundle.original.sortedBy(TimedLyricLine::timeMillis)
        if (originals.isEmpty()) return emptyList()

        val translations = alignVariant(originals, bundle.translation, toleranceMillis)
        val romanizations = alignVariant(originals, bundle.romanization, toleranceMillis)
        return originals.indices.map { index ->
            ImmersiveLyricLine(
                original = originals[index],
                translation = translations[index],
                romanization = romanizations[index],
            )
        }
    }

    private fun alignVariant(
        originals: List<TimedLyricLine>,
        secondary: List<TimedLyricLine>,
        toleranceMillis: Long,
    ): Map<Int, TimedLyricLine?> {
        if (secondary.isEmpty()) return emptyMap()
        val secondaryByOriginal = Array(originals.size) { mutableListOf<Int>() }
        val originalsBySecondary = Array(secondary.size) { mutableListOf<Int>() }

        originals.forEachIndexed { originalIndex, original ->
            secondary.forEachIndexed { secondaryIndex, candidate ->
                if (withinTolerance(original.timeMillis, candidate.timeMillis, toleranceMillis)) {
                    secondaryByOriginal[originalIndex] += secondaryIndex
                    originalsBySecondary[secondaryIndex] += originalIndex
                }
            }
        }

        return originals.indices.associateWith { originalIndex ->
            val candidates = secondaryByOriginal[originalIndex]
            candidates.singleOrNull()
                ?.takeIf { secondaryIndex -> originalsBySecondary[secondaryIndex].size == 1 }
                ?.let(secondary::get)
        }
    }

    private fun withinTolerance(first: Long, second: Long, toleranceMillis: Long): Boolean {
        val distance = runCatching {
            if (first >= second) subtractExact(first, second) else subtractExact(second, first)
        }.getOrNull() ?: return false
        return distance <= toleranceMillis
    }

    private const val MAX_TOLERANCE_MILLIS = 500L
}
