package com.qing.hachimi.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.nio.file.Files
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

class DownloadDiagnosticsTest {
    @Test
    fun `failed URL validation preserves quality reported by the source`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-unsafe-source-quality")
        val server = localApiServer(
            status = 200,
            body = """{"code":200,"data":[{"code":200,"level":"lossless","size":12,"type":"flac","url":"http://cdn.invalid/signed"}]}""",
        )
        val redirectToLocalServer = okhttp3.Interceptor { chain ->
            val original = chain.request()
            val localUrl = "${server.localUrl.substringBefore("/eapi/")}${original.url.encodedPath}"
            chain.proceed(original.newBuilder().url(localUrl).build())
        }
        val service = DesktopService(
            httpClientOverride = OkHttpClient.Builder().addInterceptor(redirectToLocalServer).build(),
            sessionValidatorOverride = { SessionValidation.VALID },
        )
        val transport = object : DownloadTransport {
            override suspend fun download(
                taskId: String,
                target: java.nio.file.Path,
                initialSource: ResolvedSongUrl,
                resolveAgain: suspend () -> ResolvedSongUrl,
                onProgress: suspend (DownloadProgressUpdate) -> Unit,
            ): DownloadTransferResult = error("Unsafe source must be rejected before transfer")

            override fun cancel(taskId: String) = Unit
            override fun deletePartial(target: java.nio.file.Path) = Unit
        }
        val manager = DownloadTaskManager(
            sourceResolver = service,
            transport = transport,
            store = DownloadTaskStore(root.resolve("tasks.json")),
            dispatcher = Dispatchers.Default,
        )
        try {
            manager.addSongs(listOf(SongHit(9001, "Test song", "Artist", "Album")), "hires", root.toString())
            // A failed transfer may use the configured 1s + 2s + 4s retry backoff before
            // the terminal state is persisted. Keep the assertion strict and allow that path.
            val failed = withTimeout(15_000) {
                manager.tasks.first { tasks -> tasks.singleOrNull()?.status == DownloadTaskStatus.FAILED }
            }.single()

            assertEquals("lossless", failed.actualQuality)
            assertEquals(DownloadErrorType.SOURCE_UNAVAILABLE, failed.errorType)
            assertEquals(3, failed.automaticRetriesUsed)
        } finally {
            manager.shutdown()
            server.shutdown()
        }
    }

    @Test
    fun `source response distinguishes empty URL from API and HTTP failures`() {
        val emptyUrl = SourceResponseInspector.inspect(
            200,
            """{"code":200,"data":[{"code":200,"level":"exhigh","size":0,"type":"mp3","url":null}]}""",
        )
        assertEquals("200", emptyUrl.apiBusinessCode)
        assertEquals("200", emptyUrl.dataItemCode)
        assertEquals("exhigh", emptyUrl.level)
        assertEquals(0L, emptyUrl.size)
        assertEquals("mp3", emptyUrl.type)
        assertFalse(emptyUrl.urlPresent)
        assertIs<SourceResolutionFailure.UrlMissing>(emptyUrl.failure)

        val apiFailure = SourceResponseInspector.inspect(200, """{"code":500,"data":[]}""")
        assertIs<SourceResolutionFailure.ApiBusinessCode>(apiFailure.failure)

        val httpFailure = SourceResponseInspector.inspect(503, """{"code":200,"data":[]}""")
        val httpStatusFailure = assertIs<SourceResolutionFailure.HttpStatus>(httpFailure.failure)
        assertEquals(503, httpStatusFailure.statusCode)

        val songItemFailure = SourceResponseInspector.inspect(
            200,
            """{"code":200,"data":[{"code":404,"level":"standard","size":0,"type":"mp3","url":null}]}""",
        )
        assertIs<SourceResolutionFailure.DataItemCode>(songItemFailure.failure)
        assertEquals("404", songItemFailure.dataItemCode)
    }

    @Test
    fun `source response distinguishes malformed JSON and missing song data`() {
        assertIs<SourceResolutionFailure.InvalidJson>(
            SourceResponseInspector.inspect(200, "not-json").failure,
        )
        assertIs<SourceResolutionFailure.DataMissing>(
            SourceResponseInspector.inspect(200, """{"code":200}""").failure,
        )
    }

    @Test
    fun `diagnostics are opt-in bounded and never persist response secrets`() {
        val root = Files.createTempDirectory("hachimi-diagnostics")
        val file = root.resolve("diagnostics.json")
        val store = DownloadDiagnosticsStore(file, maxEvents = 2)
        val response = SourceResponseInspector.inspect(
            200,
            """{"code":200,"data":[{"level":"exhigh","size":12,"type":"mp3","url":"https://cdn.invalid/a?token=SIGNED_SOURCE_SECRET","MUSIC_U":"COOKIE_SECRET"}]}""",
        )
        val event = DownloadDiagnosticEvent(
            taskId = "task-123",
            songId = 66282,
            requestedQuality = "exhigh",
            stage = DownloadDiagnosticStage.SOURCE_RESPONSE,
            elapsedMillis = 123,
            outcome = DownloadDiagnosticOutcome.RESPONSE,
            attemptQuality = "exhigh",
            httpStatus = response.httpStatus,
            apiBusinessCode = response.apiBusinessCode,
            level = response.level,
            size = response.size,
            type = response.type,
            urlPresent = response.urlPresent,
            sessionPresent = true,
        )

        store.record(event)
        assertFalse(Files.exists(file))

        store.setEnabled(true)
        repeat(3) { store.record(event) }

        val saved = Files.readString(file)
        assertEquals(2, JSONArray(saved).length())
        assertFalse(saved.contains("SIGNED_SOURCE_SECRET"))
        assertFalse(saved.contains("COOKIE_SECRET"))
        assertFalse(saved.contains("cdn.invalid"))
        assertFalse(saved.contains("MUSIC_U"))
        assertFalse(saved.contains("\"url\":"))
        assertEquals(2, store.snapshot().size)
    }

    @Test
    fun `source interceptor records allowlisted response fields and omits cookies and signed URL`() {
        val root = Files.createTempDirectory("hachimi-source-observation")
        val store = DownloadDiagnosticsStore(root.resolve("diagnostics.json")).apply { setEnabled(true) }
        val observation = AtomicReference<SourceApiObservation?>()
        val context = SourceRequestTraceContext(
            taskId = "task-123",
            songId = 66282,
            requestedQuality = "hires",
            attemptQuality = "exhigh",
        )
        val current = ThreadLocal<SourceRequestTraceContext>().apply { set(context) }
        val server = localApiServer(
            200,
            """{"code":200,"data":[{"code":200,"level":"exhigh","size":1234,"type":"mp3","url":"https://cdn.invalid/a?token=SIGNED_SOURCE_SECRET","MUSIC_U":"COOKIE_SECRET"}]}""",
        )
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(SourceResponseDiagnosticsInterceptor(store, { current.get() }) { _, result, _ ->
                    observation.set(result)
                })
                .build()
            val request = Request.Builder()
                .url(server.localUrl)
                .header("Cookie", "MUSIC_U=COOKIE_SECRET; appver=3.1.17.204416")
                .get()
                .build()

            client.newCall(request).execute().use { assertEquals(200, it.code) }

            assertEquals("200", observation.get()?.apiBusinessCode)
            assertEquals("exhigh", observation.get()?.level)
            assertEquals(1234L, observation.get()?.size)
            assertEquals("mp3", observation.get()?.type)
            assertEquals(true, observation.get()?.urlPresent)
            val saved = Files.readString(store.filePath())
            assertFalse(saved.contains("SIGNED_SOURCE_SECRET"))
            assertFalse(saved.contains("COOKIE_SECRET"))
            assertTrue(saved.contains("cdn.invalid"))
            assertFalse(saved.contains("MUSIC_U"))
            assertFalse(saved.contains("\"url\":"))
        } finally {
            current.remove()
            server.shutdown()
        }
    }

    @Test
    fun `source diagnostics record parsed URL metadata without signed path or query`() {
        val root = Files.createTempDirectory("hachimi-source-url-metadata")
        val store = DownloadDiagnosticsStore(root.resolve("diagnostics.json")).apply { setEnabled(true) }
        val server = localApiServer(
            200,
            """{"code":200,"data":[{"code":200,"level":"exhigh","size":12,"type":"mp3","url":"http://m801.music.126.net/signed/path.mp3?token=SIGNED_SOURCE_SECRET"}]}""",
        )
        val context = ThreadLocal.withInitial {
            SourceRequestTraceContext("task-url-metadata", 66282, "exhigh", "exhigh")
        }
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(SourceResponseDiagnosticsInterceptor(store, { context.get() }))
                .build()
            client.newCall(Request.Builder().url(server.localUrl).get().build()).execute().use {
                assertEquals(200, it.code)
            }

            val saved = Files.readString(store.filePath())
            val event = JSONArray(saved).getJSONObject(0)
            assertEquals(true, event.optBoolean("urlParseable"))
            assertEquals("HTTP", event.optString("urlScheme"))
            assertEquals("m801.music.126.net", event.optString("urlHost"))
            assertEquals(80, event.optInt("urlEffectivePort"))
            assertEquals(false, event.optBoolean("urlUserInfoPresent"))
            assertEquals(false, event.optBoolean("urlIpLiteral"))
            assertEquals("HTTP_HTTPS_UPGRADE_ALLOWED", event.optString("urlValidationRule"))
            assertFalse(saved.contains("SIGNED_SOURCE_SECRET"))
            assertFalse(saved.contains("signed/path"))
            assertFalse(saved.contains("/audio"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `source interceptor classifies timeout without persisting exception message`() {
        val root = Files.createTempDirectory("hachimi-source-timeout")
        val store = DownloadDiagnosticsStore(root.resolve("diagnostics.json")).apply { setEnabled(true) }
        val failure = AtomicReference<SourceResolutionFailure?>()
        val context = SourceRequestTraceContext("task-456", 123, "standard", "standard")
        val current = ThreadLocal<SourceRequestTraceContext>().apply { set(context) }
        val server = localApiServer(200, "{}", delayMillis = 300L)
        try {
            val client = OkHttpClient.Builder()
                .readTimeout(40, TimeUnit.MILLISECONDS)
                .addInterceptor(SourceResponseDiagnosticsInterceptor(store, { current.get() }, onRequestFailure = { _, kind, _ ->
                    failure.set(kind)
                }))
                .build()
            val request = Request.Builder().url(server.localUrl).get().build()

            assertFailsWith<IOException> { client.newCall(request).execute() }

            assertIs<SourceResolutionFailure.RequestTimeout>(failure.get())
            val saved = Files.readString(store.filePath())
            assertFalse(saved.contains("timeout after"))
            assertFalse(saved.contains("request URL"))
        } finally {
            current.remove()
            server.shutdown()
        }
    }

    @Test
    fun `desktop source request sends app version and reports typed empty URL`() {
        val requestHeader = AtomicReference<String?>()
        val requestPayload = AtomicReference<String?>()
        val server = localApiServer(
            200,
            """{"code":200,"data":[{"code":200,"level":"standard","size":0,"type":"mp3","url":null}]}""",
            onRequest = { exchange ->
            requestHeader.set(exchange.requestHeaders.getFirst("Cookie"))
            requestPayload.set(exchange.requestBody.readAllBytes().decodeToString())
            },
        )
        val redirectToLocalServer = okhttp3.Interceptor { chain ->
            val original = chain.request()
            val localUrl = "${server.localUrl.substringBefore("/eapi/")}${original.url.encodedPath}"
            chain.proceed(original.newBuilder().url(localUrl).build())
        }
        val client = OkHttpClient.Builder().addInterceptor(redirectToLocalServer).build()
        val service = DesktopService(
            httpClientOverride = client,
            sessionValidatorOverride = { SessionValidation.VALID },
        )
        try {
            val task = DownloadTask(
                id = "diagnostic-task",
                songId = 66282,
                songName = "test song",
                artists = "test artist",
                album = "test album",
                requestedQuality = "standard",
                filePath = Files.createTempDirectory("hachimi-source-task").resolve("song.mp3").toString(),
            )

            val failure = assertFailsWith<SourceResolutionException> {
                kotlinx.coroutines.runBlocking { service.resolve(task, "SYNTHETIC_SESSION") }
            }

            assertIs<SourceResolutionFailure.UrlMissing>(failure.failure)
            assertEquals("SYNTHETIC_SESSION", requestHeader.get()?.substringAfter("MUSIC_U=")?.substringBefore(';'))
            assertTrue(requestHeader.get().orEmpty().contains("appver=3.1.17.204416"))
            assertEquals("3.1.17.204416", decryptedSourceHeader(requestPayload.get().orEmpty()).optString("appVer"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `desktop logger omits request messages and exception contents`() {
        val previous = System.err
        val captured = java.io.ByteArrayOutputStream()
        try {
            System.setErr(java.io.PrintStream(captured, true, Charsets.UTF_8))
            com.qing.hachimi.util.AppLogger.warn(
                "EAPI error body MUSIC_U=COOKIE_SECRET https://cdn.invalid/audio?token=SIGNED_SOURCE_SECRET",
                IOException("COOKIE_SECRET SIGNED_SOURCE_SECRET"),
            )
        } finally {
            System.setErr(previous)
        }
        assertFalse(captured.toString(Charsets.UTF_8).contains("COOKIE_SECRET"))
        assertFalse(captured.toString(Charsets.UTF_8).contains("SIGNED_SOURCE_SECRET"))
        assertFalse(captured.toString(Charsets.UTF_8).contains("cdn.invalid"))
    }

    @Test
    fun `source fallback records requested and actual quality without changing fallback order`() {
        val requestedLevels = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = localApiServer(
            status = 200,
            body = "{}",
            responseBody = { exchange ->
                val formBody = exchange.requestBody.readAllBytes().decodeToString()
                val level = decryptedSourcePayload(formBody).optString("level")
                requestedLevels += level
                val url = if (level == "exhigh") "https://cdn.invalid/audio.mp3" else ""
                val itemCode = if (url.isNotEmpty()) 200 else 404
                """{"code":200,"data":[{"code":$itemCode,"level":"$level","size":12,"type":"mp3","url":${if (url.isEmpty()) "null" else "\"$url\""}}]}"""
            },
        )
        val redirectToLocalServer = okhttp3.Interceptor { chain ->
            val original = chain.request()
            val localUrl = "${server.localUrl.substringBefore("/eapi/")}${original.url.encodedPath}"
            chain.proceed(original.newBuilder().url(localUrl).build())
        }
        val service = DesktopService(
            httpClientOverride = OkHttpClient.Builder().addInterceptor(redirectToLocalServer).build(),
            sessionValidatorOverride = { SessionValidation.VALID },
        )
        try {
            val task = DownloadTask(
                id = "quality-fallback-task",
                songId = 456,
                songName = "test song",
                artists = "test artist",
                album = "test album",
                requestedQuality = "hires",
                filePath = Files.createTempDirectory("hachimi-quality").resolve("song.mp3").toString(),
            )

            val resolved = kotlinx.coroutines.runBlocking { service.resolve(task, "SYNTHETIC_SESSION") }

            assertEquals(listOf("hires", "lossless", "exhigh"), requestedLevels)
            assertEquals("exhigh", resolved.actualQuality)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `local transfer records HTTP and atomic file commit stages`() {
        val root = Files.createTempDirectory("hachimi-diagnostic-transfer")
        val payload = ByteArray(2048) { (it % 251).toByte() }
        val store = DownloadDiagnosticsStore(root.resolve("diagnostics.json")).apply { setEnabled(true) }
        val taskId = "transfer-diagnostic-task"
        store.registerActiveTask(DownloadDiagnosticTaskContext(taskId, 789, "standard", true))
        val server = localAudioServer(payload)
        val downloader = DesktopDownloader(
            client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
            allowHttpForLoopbackTests = true,
            diagnostics = store,
        )
        try {
            val target = root.resolve("audio.mp3")
            val result = kotlinx.coroutines.runBlocking {
                downloader.download(
                    taskId,
                    target,
                    ResolvedSongUrl(server.audioUrl, "mp3", "standard"),
                    resolveAgain = { error("the local source remains available") },
                    onProgress = {},
                )
            }

            assertEquals(payload.size.toLong(), result.bytesDownloaded)
            assertEquals(payload.toList(), Files.readAllBytes(target).toList())
            assertTrue(
                store.snapshot().any {
                    it.stage == DownloadDiagnosticStage.HTTP_TRANSFER &&
                        it.outcome == DownloadDiagnosticOutcome.RESPONSE && it.httpStatus == 200
                },
            )
            assertTrue(
                store.snapshot().any {
                    it.stage == DownloadDiagnosticStage.FILE_COMMIT &&
                        it.outcome == DownloadDiagnosticOutcome.SUCCEEDED
                },
            )
        } finally {
            store.clearActiveTask(taskId)
            server.shutdown()
        }
    }

    @Test
    fun `trusted synthetic DNS mapping passes validation and reaches HTTPS transfer`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-diagnostic-synthetic-dns")
        val store = DownloadDiagnosticsStore(root.resolve("diagnostics.json")).apply { setEnabled(true) }
        val taskId = "synthetic-dns-task"
        store.registerActiveTask(DownloadDiagnosticTaskContext(taskId, 66282, "hires", true))
        val requestReachedInterceptor = java.util.concurrent.atomic.AtomicBoolean(false)
        val audio = ByteArray(2048) { (it % 251).toByte() }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestReachedInterceptor.set(true)
                val isProbe = chain.request().header("Range") == "bytes=0-0"
                val body = if (isProbe) audio.copyOfRange(0, 1) else audio
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (isProbe) 206 else 200)
                    .message("OK")
                    .header("Content-Type", "audio/mpeg")
                    .apply {
                        if (isProbe) header("Content-Range", "bytes 0-0/${audio.size}")
                    }
                    .body(body.toResponseBody("audio/mpeg".toMediaType()))
                    .build()
            }
            .build()
        val policy = AudioUrlPolicy(
            trustedCdnHosts = setOf("m801.music.126.net"),
            dnsLookup = { listOf(java.net.InetAddress.getByName("198.18.0.10")) },
        )
        val downloader = DesktopDownloader(client = client, diagnostics = store, audioUrlPolicy = policy)
        val target = root.resolve("audio.flac")

        val result = downloader.download(
            taskId,
            target,
            ResolvedSongUrl(
                "http://m801.music.126.net/audio.flac",
                "flac",
                "lossless",
                AudioUrlOrigin.NETEASE_SOURCE_API,
            ),
            resolveAgain = { error("the local source should not need refreshing") },
            onProgress = {},
        )

        assertTrue(requestReachedInterceptor.get())
        assertEquals(audio.size.toLong(), result.bytesDownloaded)
        assertEquals(audio.toList(), Files.readAllBytes(target).toList())
        assertTrue(
            store.snapshot().any {
                it.stage == DownloadDiagnosticStage.URL_VALIDATION &&
                    it.outcome == DownloadDiagnosticOutcome.SUCCEEDED &&
                    it.urlMetadata?.scheme == AudioUrlScheme.HTTPS
            },
        )
        assertTrue(
            store.snapshot().any {
                it.stage == DownloadDiagnosticStage.HTTP_TRANSFER &&
                    it.outcome == DownloadDiagnosticOutcome.RESPONSE && it.httpStatus == 206
            },
        )
        assertTrue(
            store.snapshot().any {
                it.stage == DownloadDiagnosticStage.FILE_COMMIT &&
                    it.outcome == DownloadDiagnosticOutcome.SUCCEEDED
            },
        )
        store.clearActiveTask(taskId)
    }

    @Test
    fun `untrusted synthetic DNS mapping is still rejected before HTTP transfer`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-diagnostic-untrusted-synthetic-dns")
        val store = DownloadDiagnosticsStore(root.resolve("diagnostics.json")).apply { setEnabled(true) }
        val taskId = "untrusted-synthetic-dns-task"
        store.registerActiveTask(DownloadDiagnosticTaskContext(taskId, 66282, "hires", true))
        val requestReachedInterceptor = java.util.concurrent.atomic.AtomicBoolean(false)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestReachedInterceptor.set(true)
                chain.proceed(chain.request())
            }
            .build()
        val policy = AudioUrlPolicy(
            trustedCdnHosts = setOf("m801.music.126.net"),
            dnsLookup = { listOf(java.net.InetAddress.getByName("198.18.0.10")) },
        )
        val downloader = DesktopDownloader(client = client, diagnostics = store, audioUrlPolicy = policy)
        val target = root.resolve("audio.flac")

        val error = assertFailsWith<AudioUrlPolicyException> {
            downloader.download(
                taskId,
                target,
                ResolvedSongUrl("https://m999.music.126.net/audio.flac", "flac", "lossless"),
                resolveAgain = { error("untrusted synthetic DNS must not refresh the source") },
                onProgress = {},
            )
        }

        assertEquals(AudioUrlFailure.DNS_SYNTHETIC_ADDRESS_SUSPECTED, error.failure)
        assertFalse(requestReachedInterceptor.get())
        assertFalse(Files.exists(target))
        val failedValidation = store.snapshot().first {
            it.stage == DownloadDiagnosticStage.URL_VALIDATION && it.outcome == DownloadDiagnosticOutcome.FAILED
        }
        assertEquals(
            "DNS_SYNTHETIC_ADDRESS_SUSPECTED",
            NetworkFailureClassifier.category(failedValidation.error!!),
        )
        assertEquals("HTTPS", failedValidation.urlMetadata?.scheme?.name)
        store.clearActiveTask(taskId)
    }

    private fun localApiServer(
        status: Int,
        body: String,
        delayMillis: Long = 0L,
        onRequest: (com.sun.net.httpserver.HttpExchange) -> Unit = {},
        responseBody: (com.sun.net.httpserver.HttpExchange) -> String = { body },
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newSingleThreadExecutor()
        server.createContext("/eapi/song/enhance/player/url/v1") { exchange ->
            if (delayMillis > 0L) Thread.sleep(delayMillis)
            onRequest(exchange)
            val bytes = responseBody(exchange).encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    private fun localAudioServer(payload: ByteArray): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newSingleThreadExecutor()
        server.createContext("/audio.mp3") { exchange ->
            exchange.responseHeaders.add("Content-Type", "audio/mpeg")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        return server
    }

    private val HttpServer.localUrl: String
        get() = "http://127.0.0.1:${address.port}/eapi/song/enhance/player/url/v1"

    private val HttpServer.audioUrl: String
        get() = "http://127.0.0.1:${address.port}/audio.mp3"

    private fun HttpServer.shutdown() {
        stop(0)
        (executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    private fun decryptedSourceHeader(formBody: String): org.json.JSONObject {
        val payload = decryptedSourcePayload(formBody)
        return org.json.JSONObject(payload.getString("header"))
    }

    private fun decryptedSourcePayload(formBody: String): org.json.JSONObject {
        val encoded = formBody.substringAfter("params=").substringBefore('&')
        val bytes = encoded.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec("e82ckenh8dichen8".encodeToByteArray(), "AES"),
        )
        val raw = cipher.doFinal(bytes).decodeToString()
        val prefix = "/api/song/enhance/player/url/v1-36cd479b6b5-"
        val payload = raw.substringAfter(prefix).substringBeforeLast("-36cd479b6b5-")
        return org.json.JSONObject(payload)
    }
}
