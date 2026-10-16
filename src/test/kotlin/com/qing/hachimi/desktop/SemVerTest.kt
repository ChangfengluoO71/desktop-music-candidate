package com.qing.hachimi.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemVerTest {
    @Test
    fun `compares numeric version components without lexicographic ordering`() {
        assertTrue(parse("1.10.0") > parse("1.9.99"))
        assertTrue(parse("2.0.0") > parse("1.99.99"))
        assertTrue(parse("1.0.0") > parse("1.0.0-rc.1"))
    }

    @Test
    fun `orders prerelease identifiers according to semantic version rules`() {
        val ordered = listOf(
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
        ).map(::parse)

        ordered.zipWithNext().forEach { (older, newer) -> assertTrue(older < newer) }
    }

    @Test
    fun `ignores build metadata for precedence`() {
        assertEquals(0, parse("1.2.3+build.4").compareTo(parse("1.2.3+other")))
    }

    @Test
    fun `rejects malformed semantic versions and numeric prerelease leading zeros`() {
        listOf("", "1", "1.2", "01.2.3", "1.02.3", "1.2.03", "1.2.3-01", "v1.2.3", "1.2.3-")
            .forEach { assertNull(SemVer.parse(it), "Expected '$it' to be rejected") }
    }

    private fun parse(value: String): SemVer = requireNotNull(SemVer.parse(value))
}
