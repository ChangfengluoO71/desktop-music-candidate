package com.qing.hachimi.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SessionStatus {
    CHECKING,
    SIGNED_OUT,
    SIGNING_IN,
    SIGNED_IN,
    SESSION_EXPIRED,
    REAUTH_REQUIRED,
}

data class SessionState(
    val status: SessionStatus = SessionStatus.CHECKING,
    val message: String? = null,
)

enum class SessionValidation { VALID, EXPIRED, TEMPORARY_FAILURE }

fun interface SessionValidator {
    suspend fun validate(musicU: String): SessionValidation
}

data class SessionLoginResult(val accepted: Boolean, val message: String? = null)

/** Owns the only in-memory credential and validates it before authenticated requests use it. */
class SessionManager(
    private val credentialStore: CredentialStore,
    private val validator: SessionValidator,
) {
    private val stateMutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(SessionState())

    @Volatile private var musicU: String? = null

    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    suspend fun awaitReady() {
        ready.await()
    }

    suspend fun restore() {
        stateMutex.withLock {
            if (ready.isCompleted) return
            try {
                val saved = try {
                    credentialCall(CredentialStoreFailure.READ_FAILED) { credentialStore.read() }
                } catch (_: CredentialStoreException) {
                    mutableState.value = SessionState(
                        SessionStatus.REAUTH_REQUIRED,
                        "无法读取 Windows 保存的登录凭据，请重新登录。",
                    )
                    return
                }
                if (saved.isNullOrBlank()) {
                    mutableState.value = SessionState(SessionStatus.SIGNED_OUT)
                    return
                }
                when (validateSafely(saved)) {
                    SessionValidation.VALID -> {
                        musicU = saved
                        mutableState.value = SessionState(SessionStatus.SIGNED_IN)
                    }
                    SessionValidation.EXPIRED -> {
                        musicU = null
                        mutableState.value = SessionState(
                            SessionStatus.SESSION_EXPIRED,
                            "登录状态已过期，请重新登录。",
                        )
                    }
                    SessionValidation.TEMPORARY_FAILURE -> {
                        musicU = null
                        mutableState.value = SessionState(
                            SessionStatus.REAUTH_REQUIRED,
                            "暂时无法验证登录状态，请检查网络后重试。",
                        )
                    }
                }
            } finally {
                ready.complete(Unit)
            }
        }
    }

    suspend fun login(rawMusicU: String, remember: Boolean): SessionLoginResult = stateMutex.withLock {
        val candidate = rawMusicU.trim()
        if (candidate.isEmpty() || candidate.any(Char::isWhitespace) || candidate.contains(';')) {
            ready.complete(Unit)
            musicU = null
            mutableState.value = SessionState(SessionStatus.SIGNED_OUT, "登录会话无效，请重新扫码登录。")
            return@withLock SessionLoginResult(false, mutableState.value.message)
        }

        mutableState.value = SessionState(SessionStatus.SIGNING_IN)
        when (validateSafely(candidate)) {
            SessionValidation.EXPIRED -> {
                musicU = null
                mutableState.value = SessionState(SessionStatus.SIGNED_OUT, "凭据无效或已过期，请检查后重试。")
                ready.complete(Unit)
                SessionLoginResult(false, mutableState.value.message)
            }
            SessionValidation.TEMPORARY_FAILURE -> {
                musicU = null
                mutableState.value = SessionState(SessionStatus.REAUTH_REQUIRED, "暂时无法验证登录，请检查网络后重试。")
                ready.complete(Unit)
                SessionLoginResult(false, mutableState.value.message)
            }
            SessionValidation.VALID -> {
                val persistenceMessage = try {
                    if (remember) {
                        credentialCall(CredentialStoreFailure.WRITE_FAILED) { credentialStore.save(candidate) }
                    } else {
                        credentialCall(CredentialStoreFailure.DELETE_FAILED) { credentialStore.delete() }
                    }
                    null
                } catch (_: CredentialStoreException) {
                    if (remember) "登录成功，但 Windows 凭据无法保存；关闭应用后需要重新登录。"
                    else "登录成功，但无法清除旧凭据；请在 Windows 凭据管理器中检查 ${DesktopProductIdentity.DISPLAY_NAME} 项。"
                }
                musicU = candidate
                mutableState.value = SessionState(SessionStatus.SIGNED_IN, persistenceMessage)
                ready.complete(Unit)
                SessionLoginResult(true, persistenceMessage)
            }
        }
    }

    fun credentialForRequest(): String? =
        musicU?.takeIf { mutableState.value.status == SessionStatus.SIGNED_IN }

    suspend fun markExpired() = stateMutex.withLock {
        musicU = null
        mutableState.value = SessionState(SessionStatus.SESSION_EXPIRED, "登录状态已过期，请重新登录。")
        ready.complete(Unit)
    }

    suspend fun logout(): Boolean = stateMutex.withLock {
        musicU = null
        val removed = try {
            credentialCall(CredentialStoreFailure.DELETE_FAILED) { credentialStore.delete() }
            true
        } catch (_: CredentialStoreException) {
            false
        }
        mutableState.value = if (removed) {
            SessionState(SessionStatus.SIGNED_OUT, "已退出登录。")
        } else {
            SessionState(
                SessionStatus.REAUTH_REQUIRED,
                "进程中的登录状态已清除，但 Windows 未能删除保存的凭据。",
            )
        }
        ready.complete(Unit)
        removed
    }

    private suspend fun validateSafely(value: String): SessionValidation = try {
        validator.validate(value)
    } catch (_: Exception) {
        SessionValidation.TEMPORARY_FAILURE
    }

    private inline fun <T> credentialCall(
        failure: CredentialStoreFailure,
        operation: () -> T,
    ): T = try {
        operation()
    } catch (error: CredentialStoreException) {
        throw error
    } catch (_: Exception) {
        throw CredentialStoreException(failure)
    } catch (_: LinkageError) {
        throw CredentialStoreException(failure)
    }
}
