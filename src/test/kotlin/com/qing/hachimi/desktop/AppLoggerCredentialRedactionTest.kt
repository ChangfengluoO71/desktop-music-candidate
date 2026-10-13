package com.qing.hachimi.desktop

import com.qing.hachimi.util.AppLogger
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLoggerCredentialRedactionTest {
    @Test
    fun `logger redacts synthetic session values before writing`() {
        val marker = "synthetic-${UUID.randomUUID()}"
        val output = ByteArrayOutputStream()
        val originalError = System.err
        try {
            System.setErr(PrintStream(output, true, Charsets.UTF_8))
            AppLogger.warn("rejected MUSIC_U=$marker")
        } finally {
            System.setErr(originalError)
        }

        val logged = output.toString(Charsets.UTF_8)
        assertFalse(logged.contains(marker))
        assertTrue(logged.contains("MUSIC_U=[REDACTED]"))
    }
}
