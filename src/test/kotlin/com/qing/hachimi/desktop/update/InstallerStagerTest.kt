package com.qing.hachimi.desktop.update

import com.qing.hachimi.desktop.TestTlsMaterial
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InstallerStagerTest {
    @Test
    fun `installer can be fetched through a selected HTTPS mirror`() = withServer { server, root ->
        val payload = "verified installer through mirror".encodeToByteArray()
        server.payload = payload

        val staged = stager(server, root, downloadMirrorTemplate = "https://ghfast.top/{url}").stage(asset(payload))

        assertEquals(root.resolve("updates/DesktopMusicCandidate-1.3.0-Setup.exe"), staged)
        assertEquals(
            "/https://github.com/ChangfengluoO71/desktop-music-candidate/releases/download/v1.3.0/DesktopMusicCandidate-1.3.0-Setup.exe",
            server.lastRequestPath,
        )
        assertEquals(sha256(payload), sha256(Files.readAllBytes(staged)))
    }

    @Test
    fun `mirror redirects remain limited to the exact mirror and official asset hosts`() = withServer { server, root ->
        val payload = "installer".encodeToByteArray()
        server.payload = payload
        server.redirectFirstTo = "https://evil.invalid/installer.exe"

        val failure = runCatching {
            stager(server, root, downloadMirrorTemplate = "https://ghfast.top/{url}").stage(asset(payload))
        }.exceptionOrNull()

        assertEquals(
            UpdateFailureCategory.REDIRECT_POLICY_REJECTED,
            assertIs<InstallerStagingException>(failure).category,
        )
    }

    @Test
    fun `verified HTTPS bytes become the fixed staged installer with progress`() = withServer { server, root ->
        val payload = "verified preview installer".encodeToByteArray()
        server.payload = payload
        val progress = mutableListOf<Pair<Long, Long>>()

        val staged = stager(server, root).stage(
            asset(payload),
            onProgress = { bytes: Long, total: Long -> progress.add(bytes to total) },
        )

        assertEquals(root.resolve("updates/DesktopMusicCandidate-1.3.0-Setup.exe"), staged)
        assertTrue(Files.isRegularFile(staged))
        assertEquals(payload.size.toLong(), Files.size(staged))
        assertEquals(sha256(payload), sha256(Files.readAllBytes(staged)))
        assertEquals(payload.size.toLong(), progress.last().first)
        assertEquals(payload.size.toLong(), progress.last().second)
        assertFalse(Files.exists(staged.resolveSibling("${staged.fileName}.part")))
        assertTrue(server.requestHeaders.all { it["Cookie"] == null && it["Authorization"] == null })
    }

    @Test
    fun `allowed GitHub asset redirect is checked and followed over HTTPS`() = withServer { server, root ->
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        server.payload = payload
        server.redirectFirstTo = "https://release-assets.githubusercontent.com/object/asset"

        val staged = stager(server, root).stage(asset(payload))

        assertEquals(payload.toList(), Files.readAllBytes(staged).toList())
        assertEquals(2, server.requestHeaders.size)
        assertTrue(server.requestHeaders.all { it["Cookie"] == null && it["Authorization"] == null })
    }

    @Test
    fun `HTTPS downgrade and untrusted redirect hosts are rejected before a second request`() = withServer { server, root ->
        val payload = byteArrayOf(7, 8, 9)
        server.payload = payload
        server.redirectFirstTo = "http://127.0.0.1:${server.port}/private"
        assertEquals(
            UpdateFailureCategory.REDIRECT_POLICY_REJECTED,
            assertIs<InstallerStagingException>(runCatching { stager(server, root).stage(asset(payload)) }.exceptionOrNull()).category,
        )
        assertEquals(1, server.requestHeaders.size)

        server.resetRequests()
        server.redirectFirstTo = "https://localhost:${server.port}/private"
        assertEquals(
            UpdateFailureCategory.REDIRECT_POLICY_REJECTED,
            assertIs<InstallerStagingException>(runCatching { stager(server, root).stage(asset(payload)) }.exceptionOrNull()).category,
        )
        assertEquals(1, server.requestHeaders.size)
    }

    @Test
    fun `declared size and sha256 must match before final file appears`() = withServer { server, root ->
        val payload = "bytes".encodeToByteArray()
        server.payload = payload
        val stager = stager(server, root)

        val sizeFailure = runCatching { stager.stage(asset(payload).copy(size = payload.size + 1L)) }.exceptionOrNull()
        assertEquals(UpdateFailureCategory.SIZE_MISMATCH, assertIs<InstallerStagingException>(sizeFailure).category)
        assertFalse(Files.exists(root.resolve("updates/DesktopMusicCandidate-1.3.0-Setup.exe")))

        val digestFailure = runCatching { stager.stage(asset(payload).copy(sha256 = "0".repeat(64))) }.exceptionOrNull()
        assertEquals(UpdateFailureCategory.DIGEST_MISMATCH, assertIs<InstallerStagingException>(digestFailure).category)
        assertFalse(Files.exists(root.resolve("updates/DesktopMusicCandidate-1.3.0-Setup.exe")))
        assertFalse(Files.exists(root.resolve("updates/DesktopMusicCandidate-1.3.0-Setup.exe.part")))
    }

    @Test
    fun `TLS verification failure is distinct from HTTP and file errors`() = withServer { server, root ->
        server.payload = byteArrayOf(1, 2, 3)
        val untrustedClient = server.client(trustTls = false)

        val failure = runCatching { stager(server, root, untrustedClient).stage(asset(server.payload)) }.exceptionOrNull()

        assertEquals(UpdateFailureCategory.TLS_FAILED, assertIs<InstallerStagingException>(failure).category)
    }

    @Test
    fun `CDN timeout and HTTP error have distinct safe categories`() = withServer { server, root ->
        val payload = byteArrayOf(1, 2, 3)
        server.payload = payload
        server.responseDelayMillis = 400
        val shortTimeoutClient = server.client().newBuilder().readTimeout(80, TimeUnit.MILLISECONDS).build()
        val timeout = runCatching { stager(server, root, shortTimeoutClient).stage(asset(payload)) }.exceptionOrNull()
        assertEquals(UpdateFailureCategory.TIMEOUT, assertIs<InstallerStagingException>(timeout).category)

        server.responseDelayMillis = 0
        server.responseStatus = 503
        val httpError = runCatching { stager(server, root).stage(asset(payload)) }.exceptionOrNull()
        assertEquals(UpdateFailureCategory.HTTP_ERROR, assertIs<InstallerStagingException>(httpError).category)
    }

    @Test
    fun `cancelling a stalled response closes the active HTTP call promptly`() = withServer { server, root ->
        val payload = ByteArray(1024) { 7 }
        server.payload = payload
        server.responseDelayMillis = 5_000
        val stager = stager(server, root)
        coroutineScope {
            val job = launch { runCatching { stager.stage(asset(payload)) } }
            val requestObserved = withContext(Dispatchers.IO) { server.requestEntered.await(2, TimeUnit.SECONDS) }
            assertTrue(requestObserved)

            withTimeout(1_000) { job.cancelAndJoin() }
        }
        assertFalse(Files.exists(root.resolve("updates/DesktopMusicCandidate-1.3.0-Setup.exe")))
        assertFalse(Files.exists(root.resolve("updates/DesktopMusicCandidate-1.3.0-Setup.exe.part")))
    }

    @Test
    fun `failed file creation leaves no installable package`() = withServer { server, root ->
        server.payload = byteArrayOf(1, 2, 3)
        val blocker = root.resolve("not-a-directory")
        Files.writeString(blocker, "occupied")
        val brokenStager = InstallerStager(blocker.resolve("updates"), server.client())

        val failure = runCatching { brokenStager.stage(asset(server.payload)) }.exceptionOrNull()

        assertEquals(UpdateFailureCategory.WRITE_FAILED, assertIs<InstallerStagingException>(failure).category)
        assertFalse(Files.exists(blocker.resolve("updates/DesktopMusicCandidate-1.3.0-Setup.exe")))
    }

    @Test
    fun `cancellation removes partial bytes and never exposes a final installer`() = withServer { server, root ->
        server.payload = ByteArray(512 * 1024) { (it % 251).toByte() }
        server.chunkDelayMillis = 20
        val stager = stager(server, root)
        var received = 0L
        coroutineScope {
            val job = launch {
                stager.stage(asset(server.payload), onProgress = { bytes, _ -> received = bytes })
            }
            withTimeout(5_000) {
                while (received == 0L) delay(10)
            }
            job.cancelAndJoin()
        }

        assertTrue(received > 0L)
        assertFalse(Files.exists(root.resolve("updates/DesktopMusicCandidate-1.3.0-Setup.exe")))
        assertFalse(Files.exists(root.resolve("updates/DesktopMusicCandidate-1.3.0-Setup.exe.part")))
    }

    private fun stager(
        server: LocalHttpsServer,
        root: Path,
        client: OkHttpClient = server.client(),
        downloadMirrorTemplate: String? = null,
    ) = InstallerStager(
        stagingDirectory = root.resolve("updates"),
        client = client,
        downloadMirrorTemplateProvider = { downloadMirrorTemplate },
    )

    private fun asset(bytes: ByteArray) = InstallerAsset(
        name = "DesktopMusicCandidate-1.3.0-Setup.exe",
        url = "https://github.com/ChangfengluoO71/desktop-music-candidate/releases/download/v1.3.0/DesktopMusicCandidate-1.3.0-Setup.exe".toHttpUrl(),
        size = bytes.size.toLong(),
        sha256 = sha256(bytes),
    )

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun withServer(block: suspend (LocalHttpsServer, Path) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("dmc-updater-stager-test")
        val server = LocalHttpsServer()
        try {
            block(server, root)
        } finally {
            server.close()
        }
    }

    private class LocalHttpsServer : AutoCloseable {
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(TestTlsMaterial.serverContext)
            this.executor = this@LocalHttpsServer.executor
            createContext("/") { exchange -> handle(exchange) }
            start()
        }
        val port: Int get() = server.address.port
        val requestHeaders = mutableListOf<Map<String, String?>>()
        var lastRequestPath: String? = null
        var payload: ByteArray = byteArrayOf()
        var redirectFirstTo: String? = null
        var chunkDelayMillis: Long = 0
        var responseDelayMillis: Long = 0
        var responseStatus: Int = 200
        val requestEntered = CountDownLatch(1)
        private var requests = 0
        fun client(trustTls: Boolean = true): OkHttpClient {
            val base = if (trustTls) TestTlsMaterial.trustingClient() else OkHttpClient()
            return base.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .dns { host ->
                    if (host.equals(TEST_TLS_HOST, ignoreCase = true)) {
                        listOf(java.net.InetAddress.getByName("127.0.0.1"))
                    } else {
                        Dns.SYSTEM.lookup(host)
                    }
                }
                .addInterceptor { chain ->
                    val request = chain.request()
                    val localUrl = request.url.newBuilder()
                        .host(TEST_TLS_HOST)
                        .port(port)
                        .build()
                    chain.proceed(request.newBuilder().url(localUrl).build())
                }
                .build()
        }

        fun resetRequests() {
            requestHeaders.clear()
            requests = 0
        }

        private fun handle(exchange: HttpExchange) {
            requestEntered.countDown()
            requestHeaders += listOf("Cookie", "Authorization").associateWith(exchange.requestHeaders::getFirst)
            lastRequestPath = exchange.requestURI.rawPath
            requests += 1
            val redirect = redirectFirstTo
            if (requests == 1 && redirect != null) {
                exchange.responseHeaders.add("Location", redirect)
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
                return
            }
            if (responseDelayMillis > 0) Thread.sleep(responseDelayMillis)
            if (responseStatus != 200) {
                exchange.sendResponseHeaders(responseStatus, -1)
                exchange.close()
                return
            }
            if (chunkDelayMillis > 0) {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { output ->
                    payload.asList().chunked(16 * 1024).forEach { chunk ->
                        output.write(chunk.toByteArray())
                        output.flush()
                        Thread.sleep(chunkDelayMillis)
                    }
                }
                return
            }
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }

        companion object { const val TEST_TLS_HOST = "m801.music.126.net" }
    }
}
