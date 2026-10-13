package com.qing.hachimi.desktop

import com.sun.jna.FunctionMapper
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class CredentialStoreFailure {
    NOT_SUPPORTED,
    READ_FAILED,
    WRITE_FAILED,
    DELETE_FAILED,
    TOO_LARGE,
    CORRUPT_DATA,
}

/** Contains only a safe category; native messages and credential data are never attached. */
class CredentialStoreException(val failure: CredentialStoreFailure) :
    IllegalStateException("Windows credential storage error: ${failure.name.lowercase()}")

interface CredentialStore {
    fun save(value: String)
    fun read(): String?
    fun delete()
    fun exists(): Boolean
}

class UnsupportedCredentialStore : CredentialStore {
    override fun save(value: String): Nothing = throw CredentialStoreException(CredentialStoreFailure.NOT_SUPPORTED)
    override fun read(): Nothing = throw CredentialStoreException(CredentialStoreFailure.NOT_SUPPORTED)
    override fun delete(): Nothing = throw CredentialStoreException(CredentialStoreFailure.NOT_SUPPORTED)
    override fun exists(): Nothing = throw CredentialStoreException(CredentialStoreFailure.NOT_SUPPORTED)
}

/** Uses the current user's Windows Credential Manager generic credential set. */
class WindowsCredentialStore internal constructor(
    private val targetName: String = DEFAULT_TARGET,
) : CredentialStore {
    override fun save(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (value.isBlank()) throw CredentialStoreException(CredentialStoreFailure.CORRUPT_DATA)
        if (bytes.size > MAX_CREDENTIAL_BLOB_SIZE) {
            bytes.fill(0)
            throw CredentialStoreException(CredentialStoreFailure.TOO_LARGE)
        }

        val target = wideMemory(targetName)
        val blob = Memory(bytes.size.toLong())
        val credential = NativeCredential().apply {
            type = CRED_TYPE_GENERIC
            targetName = target
            credentialBlobSize = bytes.size
            credentialBlob = blob
            persist = CRED_PERSIST_LOCAL_MACHINE
            write()
        }
        try {
            blob.write(0, bytes, 0, bytes.size)
            credential.write()
            if (!api.CredWriteW(credential, 0)) {
                throw CredentialStoreException(CredentialStoreFailure.WRITE_FAILED)
            }
        } finally {
            bytes.fill(0)
            blob.clear()
            blob.close()
            target.clear()
            target.close()
        }
    }

    override fun read(): String? {
        val result = PointerByReference()
        if (!api.CredReadW(WString(targetName), CRED_TYPE_GENERIC, 0, result)) {
            if (Native.getLastError() == ERROR_NOT_FOUND) return null
            throw CredentialStoreException(CredentialStoreFailure.READ_FAILED)
        }
        val nativeCredential = result.value
            ?: throw CredentialStoreException(CredentialStoreFailure.CORRUPT_DATA)
        try {
            val credential = NativeCredential(nativeCredential).apply { read() }
            val size = credential.credentialBlobSize
            val pointer = credential.credentialBlob
            if (size <= 0 || size > MAX_CREDENTIAL_BLOB_SIZE || pointer == null) {
                throw CredentialStoreException(CredentialStoreFailure.CORRUPT_DATA)
            }
            val bytes = pointer.getByteArray(0, size)
            return try {
                decodeCredential(bytes)
            } finally {
                bytes.fill(0)
            }
        } finally {
            api.CredFree(nativeCredential)
        }
    }

    override fun delete() {
        if (!api.CredDeleteW(WString(targetName), CRED_TYPE_GENERIC, 0) &&
            Native.getLastError() != ERROR_NOT_FOUND
        ) {
            throw CredentialStoreException(CredentialStoreFailure.DELETE_FAILED)
        }
    }

    override fun exists(): Boolean = read() != null

    companion object {
        private const val DEFAULT_TARGET = DesktopProductIdentity.CREDENTIAL_TARGET
        private const val CRED_TYPE_GENERIC = 1
        private const val CRED_PERSIST_LOCAL_MACHINE = 2
        private const val ERROR_NOT_FOUND = 1168
        private const val MAX_CREDENTIAL_BLOB_SIZE = 2560

        private val api: WinCredApi by lazy {
            if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                throw CredentialStoreException(CredentialStoreFailure.NOT_SUPPORTED)
            }
            Native.load("Advapi32", WinCredApi::class.java)
        }

        internal fun decodeCredential(bytes: ByteArray): String {
            if (bytes.isEmpty() || bytes.size > MAX_CREDENTIAL_BLOB_SIZE) {
                throw CredentialStoreException(CredentialStoreFailure.CORRUPT_DATA)
            }
            return try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                    .takeIf(String::isNotBlank)
                    ?: throw CredentialStoreException(CredentialStoreFailure.CORRUPT_DATA)
            } catch (_: CharacterCodingException) {
                throw CredentialStoreException(CredentialStoreFailure.CORRUPT_DATA)
            }
        }

        private fun wideMemory(value: String): Memory = Memory((value.length + 1L) * Native.WCHAR_SIZE).apply {
            setWideString(0, value)
        }
    }

    private interface WinCredApi : StdCallLibrary {
        fun CredWriteW(credential: NativeCredential, flags: Int): Boolean
        fun CredReadW(targetName: WString, type: Int, flags: Int, credential: PointerByReference): Boolean
        fun CredDeleteW(targetName: WString, type: Int, flags: Int): Boolean
        fun CredFree(buffer: Pointer)
    }

    @Structure.FieldOrder(
        "flags",
        "type",
        "targetName",
        "comment",
        "lastWritten",
        "credentialBlobSize",
        "credentialBlob",
        "persist",
        "attributeCount",
        "attributes",
        "targetAlias",
        "userName",
    )
    open class NativeCredential(pointer: Pointer? = null) : Structure(pointer) {
        @JvmField var flags: Int = 0
        @JvmField var type: Int = 0
        @JvmField var targetName: Pointer? = null
        @JvmField var comment: Pointer? = null
        @JvmField var lastWritten: NativeFileTime = NativeFileTime()
        @JvmField var credentialBlobSize: Int = 0
        @JvmField var credentialBlob: Pointer? = null
        @JvmField var persist: Int = 0
        @JvmField var attributeCount: Int = 0
        @JvmField var attributes: Pointer? = null
        @JvmField var targetAlias: Pointer? = null
        @JvmField var userName: Pointer? = null
    }

    @Structure.FieldOrder("lowDateTime", "highDateTime")
    class NativeFileTime : Structure() {
        @JvmField var lowDateTime: Int = 0
        @JvmField var highDateTime: Int = 0
    }
}
