package com.qing.hachimi.desktop

import java.math.BigInteger

/** A strict SemVer 2.0.0 value. Build metadata is preserved but does not affect precedence. */
class SemVer private constructor(
    val major: BigInteger,
    val minor: BigInteger,
    val patch: BigInteger,
    val prerelease: List<String>?,
    val buildMetadata: String?,
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }

        val left = prerelease
        val right = other.prerelease
        if (left == null && right == null) return 0
        if (left == null) return 1
        if (right == null) return -1
        for (index in 0 until minOf(left.size, right.size)) {
            val leftIdentifier = left[index]
            val rightIdentifier = right[index]
            val leftNumeric = leftIdentifier.isNumericIdentifier()
            val rightNumeric = rightIdentifier.isNumericIdentifier()
            val comparison = when {
                leftNumeric && rightNumeric -> leftIdentifier.toBigInteger().compareTo(rightIdentifier.toBigInteger())
                leftNumeric -> -1
                rightNumeric -> 1
                else -> leftIdentifier.compareTo(rightIdentifier)
            }
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        prerelease?.let { append('-').append(it.joinToString(".")) }
        buildMetadata?.let { append('+').append(it) }
    }

    override fun equals(other: Any?): Boolean = other is SemVer && compareTo(other) == 0
    override fun hashCode(): Int = listOf(major, minor, patch, prerelease).hashCode()

    companion object {
        private val pattern = Regex(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$",
        )

        fun parse(value: String): SemVer? {
            val match = pattern.matchEntire(value) ?: return null
            val prerelease = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.')
            if (prerelease.orEmpty().any { it.isNumericIdentifier() && it.length > 1 && it.startsWith('0') }) {
                return null
            }
            return SemVer(
                major = match.groupValues[1].toBigInteger(),
                minor = match.groupValues[2].toBigInteger(),
                patch = match.groupValues[3].toBigInteger(),
                prerelease = prerelease,
                buildMetadata = match.groupValues[5].takeIf(String::isNotEmpty),
            )
        }

        private fun String.isNumericIdentifier(): Boolean = isNotEmpty() && all(Char::isDigit)
    }
}
