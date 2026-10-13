package com.qing.hachimi.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopQrLoginFlowTest {
    @Test
    fun `phone confirmation is validated and saved only when remember is selected`() = runBlocking {
        val gateway = ConfirmableQrGateway()
        val credentials = MemoryCredentialStore()
        val vm = viewModel(Files.createTempDirectory("qr-login-flow"), gateway, credentials)
        try {
            assertEquals(false, vm.ui.value.rememberLogin)
            vm.toggleRememberLogin()
            vm.startQrLogin()

            withTimeout(3_000) { gateway.pollStarted.receive() }
            val waiting = withTimeout(3_000) {
                vm.ui.first { it.qrLoginStatus == DesktopQrLoginStatus.WAITING }
            }
            val qrCode = requireNotNull(waiting.qrCode)
            assertTrue(qrCode.size > 0)
            assertEquals(qrCode.size * qrCode.size, qrCode.darkModules.size)
            assertEquals("https://music.163.com/login?codekey=synthetic-ticket", gateway.generatedUrl)
            assertNull(vm.sessionManager.credentialForRequest())

            gateway.confirm.complete(QrLoginPoll.Authenticated("synthetic-session"))
            withTimeout(3_000) { vm.ui.first { it.qrLoginStatus == DesktopQrLoginStatus.SIGNED_IN } }

            assertEquals(SessionStatus.SIGNED_IN, vm.sessionManager.state.value.status)
            assertEquals("synthetic-session", vm.sessionManager.credentialForRequest())
            assertEquals("synthetic-session", credentials.value)
            assertNull(vm.ui.value.qrCode)
        } finally {
            vm.shutdown()
        }
    }

    @Test
    fun `cancel clears QR from the screen and cancels the active poll`() = runBlocking {
        val gateway = BlockingQrGateway()
        val vm = viewModel(Files.createTempDirectory("qr-login-cancel"), gateway, MemoryCredentialStore())
        try {
            vm.startQrLogin()
            withTimeout(3_000) { gateway.pollStarted.receive() }
            withTimeout(3_000) { vm.ui.first { it.qrCode != null } }

            vm.cancelQrLogin()

            withTimeout(3_000) { gateway.pollCancelled.await() }
            assertNull(vm.ui.value.qrCode)
            assertEquals(DesktopQrLoginStatus.IDLE, vm.ui.value.qrLoginStatus)
        } finally {
            vm.shutdown()
        }
    }

    private fun viewModel(root: Path, qr: DesktopQrLoginGateway, credentials: MemoryCredentialStore): DesktopViewModel {
        val service = GatewayStub()
        val session = SessionManager(credentials, SessionValidator { SessionValidation.VALID })
        val manager = DownloadTaskManager(
            sourceResolver = service,
            transport = IdleDownloadTransport(),
            store = DownloadTaskStore(root.resolve("tasks.json")),
            dispatcher = Dispatchers.Default,
            sessionManager = session,
        )
        return DesktopViewModel(
            service = service,
            taskManager = manager,
            sessionManager = session,
            ioDispatcher = Dispatchers.Default,
            mainDispatcher = Dispatchers.Unconfined,
            qrLoginGateway = qr,
            qrPollIntervalMillis = 1,
        )
    }

    private class ConfirmableQrGateway : DesktopQrLoginGateway {
        val pollStarted = Channel<Unit>(Channel.UNLIMITED)
        val confirm = CompletableDeferred<QrLoginPoll>()
        var generatedUrl: String? = null

        override suspend fun createTicket() = QrLoginTicket("synthetic-ticket", emptyMap())
        override suspend fun poll(ticket: QrLoginTicket): QrLoginPoll {
            pollStarted.trySend(Unit)
            return confirm.await()
        }
        override fun loginUrl(ticket: QrLoginTicket): String =
            "https://music.163.com/login?codekey=${ticket.key}".also { generatedUrl = it }
    }

    private class BlockingQrGateway : DesktopQrLoginGateway {
        val pollStarted = Channel<Unit>(Channel.UNLIMITED)
        val pollCancelled = CompletableDeferred<Unit>()
        override suspend fun createTicket() = QrLoginTicket("cancel-ticket", emptyMap())
        override suspend fun poll(ticket: QrLoginTicket): QrLoginPoll {
            pollStarted.send(Unit)
            try {
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                pollCancelled.complete(Unit)
                throw cancelled
            }
        }
        override fun loginUrl(ticket: QrLoginTicket): String = "https://music.163.com/login?codekey=${ticket.key}"
    }

    private class MemoryCredentialStore : CredentialStore {
        var value: String? = null
        override fun save(value: String) { this.value = value }
        override fun read(): String? = value
        override fun delete() { value = null }
        override fun exists(): Boolean = value != null
    }

    private class GatewayStub : DesktopGateway {
        override suspend fun searchSongs(query: String, musicU: String): List<SongHit> = emptyList()
        override suspend fun validateSession(musicU: String): SessionValidation = SessionValidation.VALID
        override suspend fun resolve(task: DownloadTask, musicU: String?): ResolvedSongUrl =
            ResolvedSongUrl("https://example.invalid/${task.songId}", "mp3", "exhigh")
    }

    private class IdleDownloadTransport : DownloadTransport {
        override suspend fun download(
            taskId: String,
            target: Path,
            initialSource: ResolvedSongUrl,
            resolveAgain: suspend () -> ResolvedSongUrl,
            onProgress: suspend (DownloadProgressUpdate) -> Unit,
        ): DownloadTransferResult = error("no download expected")
        override fun cancel(taskId: String) = Unit
        override fun deletePartial(target: Path) = Unit
    }
}
