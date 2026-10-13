package com.qing.hachimi.util

import com.qing.hachimi.desktop.DesktopProductIdentity

/** JVM replacement for the Android Log wrapper. Never prints request payloads or cookies. */
object AppLogger {
    fun debug(message: String) = Unit
    fun info(message: String) = Unit
    fun warn(message: String, error: Throwable? = null) {
        System.err.println("[${DesktopProductIdentity.DISPLAY_NAME}] ${redactSensitive(message)}")
        if (error != null) System.err.println(error.javaClass.simpleName)
    }
    fun error(message: String, error: Throwable? = null) = warn(message, error)

    internal fun redactSensitive(value: String): String {
        val redactions = listOf("MUSIC_U", "MUSIC_A", "__csrf")
            .filter { key -> Regex("(?i)(?:^|[;\\s])${Regex.escape(key)}\\s*=").containsMatchIn(value) }
            .joinToString(" ") { "$it=[REDACTED]" }
        return redactions.ifEmpty { "upstream diagnostic details omitted" }
    }
}
