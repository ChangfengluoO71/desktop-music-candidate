package com.qing.hachimi.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionManagerTest {
    @Test
    fun `credential storage supports save read exists and delete`() {
        val store = MemoryCredentialStore()
        val value = "test-session-${UUID.randomUUID()}"

        assertFalse(store.exists())
        store.save(value)
        assertTrue(store.exists())
        assertEquals(value, store.read())
        store.delete()
        assertFalse(store.exists())
        assertNull(store.read())
    }

    @Test
    fun `credential storage failure is sanitized`() {
        val store = object : CredentialStore {
            override fun save(value: String) = throw CredentialStoreException(CredentialStoreFailure.WRITE_FAILED)
            override fun read(): String? = throw CredentialStoreException(CredentialStoreFailure.CORRUPT_DATA)
            override fun delete() = Unit
            override fun exists(): Boolean = throw CredentialStoreException(CredentialStoreFailure.READ_FAILED)
        }
        val marker = "never-log-${UUID.randomUUID()}"

        val failure = runCatching { store.save(marker) }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse(failure.toString().contains(marker))
        assertFalse(runCatching { store.read() }.exceptionOrNull().toString().contains("secret"))
    }

    @Test
    fun `session restore validates credentials before use`() = runBlocking {
        val store = MemoryCredentialStore().apply { save("synthetic-valid-session") }
        val manager = SessionManager(store, SessionValidator { SessionValidation.VALID })

        manager.restore()

        assertEquals(SessionStatus.SIGNED_IN, manager.state.value.status)
        assertEquals("synthetic-valid-session", manager.credentialForRequest())
    }

    @Test
    fun `expired restored credential requires reauthentication and is not sent`() = runBlocking {
        val store = MemoryCredentialStore().apply { save("synthetic-expired-session") }
        val manager = SessionManager(store, SessionValidator { SessionValidation.EXPIRED })

        manager.restore()

        assertEquals(SessionStatus.SESSION_EXPIRED, manager.state.value.status)
        assertNull(manager.credentialForRequest())
    }

    @Test
    fun `re-authentication resumes a valid in-memory session`() = runBlocking {
        val store = MemoryCredentialStore()
        val manager = SessionManager(store, SessionValidator { token ->
            if (token == "synthetic-session") SessionValidation.VALID else SessionValidation.EXPIRED
        })

        val result = manager.login("synthetic-session", remember = false)

        assertTrue(result.accepted)
        assertEquals(SessionStatus.SIGNED_IN, manager.state.value.status)
        assertEquals("synthetic-session", manager.credentialForRequest())
        assertFalse(store.exists())
    }

    @Test
    fun `invalid session rejection does not ask the user for a raw cookie`() = runBlocking {
        val manager = SessionManager(MemoryCredentialStore(), SessionValidator { SessionValidation.EXPIRED })

        val result = manager.login("", remember = false)

        assertFalse(result.accepted)
        assertFalse(result.message.orEmpty().contains("MUSIC_U", ignoreCase = true))
    }

    @Test
    fun `logout clears process session and saved credential`() = runBlocking {
        val store = MemoryCredentialStore()
        val manager = SessionManager(store, SessionValidator { SessionValidation.VALID })
        manager.login("synthetic-session", remember = true)
        assertTrue(store.exists())

        manager.logout()

        assertEquals(SessionStatus.SIGNED_OUT, manager.state.value.status)
        assertNull(manager.credentialForRequest())
        assertFalse(store.exists())
    }

    @Test
    fun `damaged credential returns reauthentication state without exposing value`() = runBlocking {
        val store = object : CredentialStore {
            override fun save(value: String) = Unit
            override fun read(): String? = throw CredentialStoreException(CredentialStoreFailure.CORRUPT_DATA)
            override fun delete() = Unit
            override fun exists(): Boolean = true
        }
        val manager = SessionManager(store, SessionValidator { SessionValidation.VALID })

        manager.restore()

        assertEquals(SessionStatus.REAUTH_REQUIRED, manager.state.value.status)
        assertNull(manager.credentialForRequest())
        assertFalse(manager.state.value.message.orEmpty().contains("MUSIC_U"))
    }

    @Test
    fun `native credential adapter failure becomes a reauthentication state`() = runBlocking {
        val store = object : CredentialStore {
            override fun save(value: String) = Unit
            override fun read(): String? = throw IllegalStateException("native adapter unavailable")
            override fun delete() = Unit
            override fun exists(): Boolean = false
        }
        val manager = SessionManager(store, SessionValidator { SessionValidation.VALID })

        manager.restore()

        assertEquals(SessionStatus.REAUTH_REQUIRED, manager.state.value.status)
        assertNull(manager.credentialForRequest())
        assertFalse(manager.state.value.message.orEmpty().contains("native adapter"))
    }

    @Test
    fun `windows credential manager adapter round trips in the current user context`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val target = "DesktopMusicCandidate.Test.${UUID.randomUUID()}"
        val store = WindowsCredentialStore(target)
        val value = "synthetic-test-session-${UUID.randomUUID()}"
        try {
            store.save(value)
            assertTrue(store.exists())
            assertEquals(value, store.read())
        } finally {
            store.delete()
        }
        assertFalse(store.exists())
    }

    @Test
    fun `malformed credential bytes are rejected as sanitized corruption`() {
        val marker = "not-a-session-${UUID.randomUUID()}"
        val malformedUtf8 = marker.encodeToByteArray() + byteArrayOf(0xC3.toByte(), 0x28)
        val error = runCatching { WindowsCredentialStore.decodeCredential(malformedUtf8) }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is CredentialStoreException)
        assertEquals(CredentialStoreFailure.CORRUPT_DATA, error.failure)
        assertFalse(error.toString().contains(marker))
    }

    private class MemoryCredentialStore : CredentialStore {
        private var value: String? = null
        override fun save(value: String) { this.value = value }
        override fun read(): String? = value
        override fun delete() { value = null }
        override fun exists(): Boolean = value != null
    }
}
