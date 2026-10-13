package com.qing.hachimi.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.json.JSONObject
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDownloaderTest {
    @Test
    fun `ordinary 200 response streams to final file`() = runBlocking {
        val bytes = "complete-audio-payload".encodeToByteArray()
        LocalServer { it.reply(200, mapOf("Content-Type" to "audio/mpeg", "ETag" to "\"v1\""), bytes) }.use { server ->
            val root = Files.createTempDirectory("hachimi-download")
            val target = root.resolve("song.mp3")
            val downloader = DesktopDownloader(allowHttpForLoopbackTests = true)

            val result = downloader.download("task-1", target, source(server.url), { source(server.url) }) {}

            assertContentEquals(bytes, Files.readAllBytes(target))
            assertEquals(bytes.size.toLong(), result.bytesDownloaded)
            assertFalse(Files.exists(downloader.partialPath(target)))
        }
    }

    @Test
    fun `206 response resumes from the saved offset with If-Range`() = runBlocking {
        val bytes = "prefix-and-the-rest".encodeToByteArray()
        val prefix = "prefix-".encodeToByteArray()
        val seenRange = AtomicReference<String?>()
        val seenIfRange = AtomicReference<String?>()
        LocalServer { exchange ->
            seenRange.set(exchange.requestHeaders.getFirst("Range"))
            seenIfRange.set(exchange.requestHeaders.getFirst("If-Range"))
            val start = prefix.size
            exchange.reply(
                206,
                mapOf(
                    "Content-Type" to "audio/mpeg",
                    "Content-Range" to "bytes $start-${bytes.lastIndex}/${bytes.size}",
                    "ETag" to "\"v1\"",
                ),
                bytes.copyOfRange(start, bytes.size),
            )
        }.use { server ->
            val root = Files.createTempDirectory("hachimi-download")
            val target = root.resolve("song.mp3")
            val downloader = DesktopDownloader(allowHttpForLoopbackTests = true)
            seedPartial(downloader, target, prefix, bytes.size.toLong(), "\"v1\"")

            downloader.download("task-2", target, source(server.url), { source(server.url) }) {}

            assertEquals("bytes=${prefix.size}-", seenRange.get())
            assertEquals("\"v1\"", seenIfRange.get())
            assertContentEquals(bytes, Files.readAllBytes(target))
        }
    }

    @Test
    fun `Last-Modified is accepted as an If-Range validator`() = runBlocking {
        val bytes = "prefix-and-the-rest".encodeToByteArray()
        val prefix = "prefix-".encodeToByteArray()
        val modified = "Wed, 21 Oct 2015 07:28:00 GMT"
        val seenIfRange = AtomicReference<String?>()
        LocalServer { exchange ->
            seenIfRange.set(exchange.requestHeaders.getFirst("If-Range"))
            exchange.reply(
                206,
                mapOf(
                    "Content-Type" to "audio/mpeg",
                    "Content-Range" to "bytes ${prefix.size}-${bytes.lastIndex}/${bytes.size}",
                    "Last-Modified" to modified,
                ),
                bytes.copyOfRange(prefix.size, bytes.size),
            )
        }.use { server ->
            val root = Files.createTempDirectory("hachimi-download-last-modified")
            val target = root.resolve("song.mp3")
            val downloader = DesktopDownloader(allowHttpForLoopbackTests = true)
            Files.write(downloader.partialPath(target), prefix)
            Files.writeString(
                downloader.metadataPath(target),
                JSONObject()
                    .put("etag", JSONObject.NULL)
                    .put("lastModified", modified)
                    .put("totalBytes", bytes.size)
                    .put("contentType", "audio/mpeg")
                    .toString(),
            )

            downloader.download("task-lm", target, source(server.url), { source(server.url) }) {}

            assertEquals(modified, seenIfRange.get())
            assertContentEquals(bytes, Files.readAllBytes(target))
        }
    }

    @Test
    fun `server ignoring Range with 200 restarts instead of appending`() = runBlocking {
        val oldPrefix = "stale-prefix".encodeToByteArray()
        val bytes = "fresh-object".encodeToByteArray()
        val seenRange = AtomicReference<String?>()
        LocalServer { exchange ->
            seenRange.set(exchange.requestHeaders.getFirst("Range"))
            exchange.reply(200, mapOf("Content-Type" to "audio/mpeg", "ETag" to "\"v2\""), bytes)
        }.use { server ->
            val root = Files.createTempDirectory("hachimi-download")
            val target = root.resolve("song.mp3")
            val downloader = DesktopDownloader(allowHttpForLoopbackTests = true)
            seedPartial(downloader, target, oldPrefix, 99, "\"v1\"")

            downloader.download("task-3", target, source(server.url), { source(server.url) }) {}

            assertEquals("bytes=${oldPrefix.size}-", seenRange.get())
            assertContentEquals(bytes, Files.readAllBytes(target))
        }
    }

    @Test
    fun `416 completes an already complete part only when validators and length match`() = runBlocking {
        val bytes = "already-complete".encodeToByteArray()
        LocalServer { exchange ->
            exchange.reply(
                416,
                mapOf("Content-Range" to "bytes */${bytes.size}", "ETag" to "\"v1\""),
                byteArrayOf(),
            )
        }.use { server ->
            val root = Files.createTempDirectory("hachimi-download")
            val target = root.resolve("song.mp3")
            val downloader = DesktopDownloader(allowHttpForLoopbackTests = true)
            seedPartial(downloader, target, bytes, bytes.size.toLong(), "\"v1\"")

            downloader.download("task-4", target, source(server.url), { source(server.url) }) {}

            assertContentEquals(bytes, Files.readAllBytes(target))
        }
    }

    @Test
    fun `416 for a stale partial clears it and retries from zero`() = runBlocking {
        val oldPrefix = "stale".encodeToByteArray()
        val bytes = "replacement-audio".encodeToByteArray()
        val requests = AtomicInteger()
        LocalServer { exchange ->
            if (requests.incrementAndGet() == 1) {
                exchange.reply(
                    416,
                    mapOf("Content-Range" to "bytes */${bytes.size}", "ETag" to "\"v2\""),
                    byteArrayOf(),
                )
            } else {
                assertEquals(null, exchange.requestHeaders.getFirst("Range"))
                exchange.reply(200, mapOf("ETag" to "\"v2\"", "Content-Type" to "audio/mpeg"), bytes)
            }
        }.use { server ->
            val root = Files.createTempDirectory("hachimi-download")
            val target = root.resolve("song.mp3")
            val downloader = DesktopDownloader(allowHttpForLoopbackTests = true)
            seedPartial(downloader, target, oldPrefix, 100, "\"v1\"")

            downloader.download("task-4b", target, source(server.url), { source(server.url) }) {}

            assertEquals(2, requests.get())
            assertContentEquals(bytes, Files.readAllBytes(target))
        }
    }

    @Test
    fun `malformed Content-Range is rejected without appending`() = runBlocking {
        val prefix = "prefix".encodeToByteArray()
        LocalServer { exchange ->
            exchange.reply(
                206,
                mapOf(
                    "Content-Range" to "bytes 0-9/10",
                    "ETag" to "\"v1\"",
                    "Content-Type" to "audio/mpeg",
                ),
                ByteArray(10),
            )
        }.use { server ->
            val root = Files.createTempDirectory("hachimi-download")
            val target = root.resolve("song.mp3")
            val downloader = DesktopDownloader(allowHttpForLoopbackTests = true)
            seedPartial(downloader, target, prefix, 10, "\"v1\"")

            assertFailsWith<InvalidDownloadException> {
                downloader.download("task-4c", target, source(server.url), { source(server.url) }) {}
            }

            assertContentEquals(prefix, Files.readAllBytes(downloader.partialPath(target)))
        }
    }

    @Test
    fun `changed ETag discards old bytes and restarts from zero`() = runBlocking {
        val oldPrefix = "old".encodeToByteArray()
        val bytes = "new-complete-object".encodeToByteArray()
        val requests = AtomicInteger()
        LocalServer { exchange ->
            if (requests.incrementAndGet() == 1) {
                exchange.reply(
                    206,
                    mapOf(
                        "Content-Range" to "bytes ${oldPrefix.size}-${bytes.lastIndex}/${bytes.size}",
                        "ETag" to "\"v2\"",
                        "Content-Type" to "audio/mpeg",
                    ),
                    bytes.copyOfRange(oldPrefix.size, bytes.size),
                )
            } else {
                assertEquals(null, exchange.requestHeaders.getFirst("Range"))
                exchange.reply(200, mapOf("ETag" to "\"v2\"", "Content-Type" to "audio/mpeg"), bytes)
            }
        }.use { server ->
            val root = Files.createTempDirectory("hachimi-download")
            val target = root.resolve("song.mp3")
            val downloader = DesktopDownloader(allowHttpForLoopbackTests = true)
            seedPartial(downloader, target, oldPrefix, 100, "\"v1\"")

            downloader.download("task-5", target, source(server.url), { source(server.url) }) {}

            assertEquals(2, requests.get())
            assertContentEquals(bytes, Files.readAllBytes(target))
        }
    }

    @Test
    fun `expired temporary URL is refreshed once without persisting it`() = runBlocking {
        val refreshes = AtomicInteger()
        val bytes = "audio-after-refresh".encodeToByteArray()
        LocalServer { it.reply(403, mapOf("Content-Type" to "text/plain"), "expired".encodeToByteArray()) }.use { expired ->
            LocalServer { it.reply(200, mapOf("Content-Type" to "audio/mpeg"), bytes) }.use { fresh ->
                val root = Files.createTempDirectory("hachimi-download")
                val target = root.resolve("song.mp3")
                val downloader = DesktopDownloader(allowHttpForLoopbackTests = true)

                downloader.download("task-6", target, source(expired.url), {
                    refreshes.incrementAndGet()
                    source(fresh.url)
                }) {}

                assertEquals(1, refreshes.get())
                assertContentEquals(bytes, Files.readAllBytes(target))
                assertFalse(Files.exists(downloader.metadataPath(target)))
            }
        }
    }

    @Test
    fun `HTML error body is rejected and removed`() = runBlocking {
        LocalServer {
            it.reply(200, mapOf("Content-Type" to "application/octet-stream"), "<html>error</html>".encodeToByteArray())
        }.use { server ->
            val root = Files.createTempDirectory("hachimi-download")
            val target = root.resolve("song.mp3")
            val downloader = DesktopDownloader(allowHttpForLoopbackTests = true)

            assertFailsWith<InvalidDownloadException> {
                downloader.download("task-7", target, source(server.url), { source(server.url) }) {}
            }
            assertFalse(Files.exists(target))
            assertFalse(Files.exists(downloader.partialPath(target)))
        }
    }

    @Test
    fun `interrupted transfer retains resumable bytes and a later call completes`() = runBlocking {
        val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        val requestCount = AtomicInteger()
        val receivedRange = AtomicReference<String?>()
        LocalServer { exchange ->
            if (requestCount.incrementAndGet() == 1) {
                exchange.responseHeaders.add("Content-Type", "audio/mpeg")
                exchange.responseHeaders.add("Content-Length", bytes.size.toString())
                exchange.responseHeaders.add("ETag", "\"v1\"")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { output ->
                    output.write(bytes, 0, 48 * 1024)
                    output.flush()
                    Thread.sleep(250)
                }
            } else {
                val start = exchange.requestHeaders.getFirst("Range")
                    ?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                receivedRange.set(exchange.requestHeaders.getFirst("Range"))
                exchange.reply(
                    206,
                    mapOf(
                        "Content-Type" to "audio/mpeg",
                        "Content-Range" to "bytes $start-${bytes.lastIndex}/${bytes.size}",
                        "ETag" to "\"v1\"",
                    ),
                    bytes.copyOfRange(start, bytes.size),
                )
            }
        }.use { server ->
            val root = Files.createTempDirectory("hachimi-download")
            val target = root.resolve("song.mp3")
            val downloader = DesktopDownloader(
                allowHttpForLoopbackTests = true,
                client = testClient(readTimeoutMillis = 80),
            )
            assertFailsWith<Exception> {
                downloader.download("task-8", target, source(server.url), { source(server.url) }) {}
            }
            assertTrue(Files.size(downloader.partialPath(target)) > 0)

            downloader.download("task-8", target, source(server.url), { source(server.url) }) {}

            assertTrue(receivedRange.get()?.startsWith("bytes=") == true)
            assertContentEquals(bytes, Files.readAllBytes(target))
        }
    }

    @Test
    fun `cancel stops the active HTTP call and leaves data available for pause recovery`() = runBlocking {
        val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        val sentFirstChunk = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        LocalServer { exchange ->
            exchange.responseHeaders.add("Content-Type", "audio/mpeg")
            exchange.responseHeaders.add("Content-Length", bytes.size.toString())
            exchange.responseHeaders.add("ETag", "\"cancel-test\"")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            runCatching {
                exchange.responseBody.use { output ->
                    output.write(bytes, 0, 64 * 1024)
                    output.flush()
                    sentFirstChunk.countDown()
                    releaseServer.await(5, TimeUnit.SECONDS)
                    output.write(bytes, 64 * 1024, bytes.size - 64 * 1024)
                }
            }
        }.use { server ->
            val root = Files.createTempDirectory("hachimi-download-cancel")
            val target = root.resolve("song.mp3")
            val downloader = DesktopDownloader(
                allowHttpForLoopbackTests = true,
                client = testClient(readTimeoutMillis = 10_000),
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val request = scope.async {
                    downloader.download("task-cancel", target, source(server.url), { source(server.url) }) {}
                }
                assertTrue(sentFirstChunk.await(3, TimeUnit.SECONDS))
                delay(100)
                downloader.cancel("task-cancel")
                val failure = runCatching { withTimeout(3_000) { request.await() } }.exceptionOrNull()

                assertTrue(failure is IOException, "expected canceled HTTP read, got $failure")
                assertTrue(Files.size(downloader.partialPath(target)) > 0L)
                assertFalse(Files.exists(target))
            } finally {
                releaseServer.countDown()
                scope.cancel()
                downloader.deletePartial(target)
            }
        }
    }

    @Test
    fun `non-HTTPS non-loopback source is rejected`() = runBlocking {
        val root = Files.createTempDirectory("hachimi-download-url-policy")
        val target = root.resolve("song.mp3")
        val downloader = DesktopDownloader()
        val insecure = ResolvedSongUrl("http://example.invalid/audio", "mp3", "exhigh")

        val failure = assertFailsWith<AudioUrlPolicyException> {
            downloader.download("task-http", target, insecure, { insecure }) {}
        }
        assertEquals(AudioUrlFailure.UNTRUSTED_AUDIO_HOST, failure.failure)
        Unit
    }

    @Test
    fun `verified HTTP CDN probes HTTPS with Range and downloads without credentials`() = runBlocking {
        val payload = ByteArray(4096) { ((it % 251) + 1).toByte() }
        val seenRanges = CopyOnWriteArrayList<String?>()
        val seenCookies = CopyOnWriteArrayList<String?>()
        val seenRawQueries = CopyOnWriteArrayList<String?>()
        LocalHttpsServer { exchange ->
            seenRanges += exchange.requestHeaders.getFirst("Range")
            seenCookies += exchange.requestHeaders.getFirst("Cookie")
            seenRawQueries += exchange.requestURI.rawQuery
            if (exchange.requestHeaders.getFirst("Range") == "bytes=0-0") {
                exchange.responseHeaders.add("Content-Type", "audio/mpeg")
                exchange.responseHeaders.add("Content-Range", "bytes 0-0/${payload.size}")
                exchange.sendResponseHeaders(206, 1)
                exchange.responseBody.use { it.write(payload, 0, 1) }
            } else {
                exchange.responseHeaders.add("Content-Type", "audio/mpeg")
                exchange.responseHeaders.add("ETag", "\"v1\"")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
        }.use { server ->
            val root = Files.createTempDirectory("hachimi-https-upgrade")
            val diagnostics = DownloadDiagnosticsStore(root.resolve("diagnostics.json")).apply { setEnabled(true) }
            val taskId = "https-upgrade-task"
            diagnostics.registerActiveTask(DownloadDiagnosticTaskContext(taskId, 66282, "exhigh", true))
            val policy = testCdnPolicy()
            val downloader = DesktopDownloader(
                client = server.client(),
                allowHttpForLoopbackTests = true,
                diagnostics = diagnostics,
                audioUrlPolicy = policy,
            )
            val target = root.resolve("song.mp3")
            val source = ResolvedSongUrl(
                "http://m801.music.126.net/audio.mp3?signature=SIGNED_SOURCE_SECRET",
                "mp3",
                "exhigh",
                AudioUrlOrigin.NETEASE_SOURCE_API,
            )

            val result = downloader.download(taskId, target, source, { error("source remains available") }) {}

            assertContentEquals(payload, Files.readAllBytes(target))
            assertEquals(payload.size.toLong(), result.bytesDownloaded)
            assertEquals("bytes=0-0", seenRanges.first())
            assertTrue(seenRanges.size >= 2)
            assertTrue(seenCookies.all { it == null })
            assertTrue(seenRawQueries.all { it == "signature=SIGNED_SOURCE_SECRET" })
            val saved = Files.readString(diagnostics.filePath())
            assertFalse(saved.contains("SIGNED_SOURCE_SECRET"))
            assertFalse(saved.contains("http://"))
            diagnostics.clearActiveTask(taskId)
        }
    }

    @Test
    fun `Range ignored by HTTPS CDN probe reads only a bounded prefix`() = runBlocking {
        val payload = ByteArray(96 * 1024) { ((it % 251) + 1).toByte() }
        val seenRanges = CopyOnWriteArrayList<String?>()
        LocalHttpsServer { exchange ->
            seenRanges += exchange.requestHeaders.getFirst("Range")
            exchange.responseHeaders.add("Content-Type", "audio/mpeg")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            runCatching { exchange.responseBody.use { it.write(payload) } }
        }.use { server ->
            val target = Files.createTempDirectory("hachimi-range-ignored").resolve("song.mp3")
            val source = ResolvedSongUrl(
                "http://m801.music.126.net/audio.mp3?signature=RANGE_IGNORED_SECRET",
                "mp3",
                "exhigh",
                AudioUrlOrigin.NETEASE_SOURCE_API,
            )
            val downloader = DesktopDownloader(
                client = server.client(),
                allowHttpForLoopbackTests = true,
                audioUrlPolicy = testCdnPolicy(),
            )

            downloader.download("range-ignored", target, source, { error("source remains available") }) {}

            assertEquals("bytes=0-0", seenRanges.first())
            assertContentEquals(payload, Files.readAllBytes(target))
        }
    }

    @Test
    fun `expired signed source is reparsed once before HTTPS transfer`() = runBlocking {
        val payload = "fresh-authorized-audio".encodeToByteArray()
        val probeCount = AtomicInteger()
        val queriedSources = CopyOnWriteArrayList<String>()
        LocalHttpsServer { exchange ->
            if (exchange.requestHeaders.getFirst("Range") == "bytes=0-0") {
                if (probeCount.incrementAndGet() == 1) {
                    exchange.sendResponseHeaders(403, -1)
                    exchange.close()
                } else {
                    exchange.responseHeaders.add("Content-Type", "audio/mpeg")
                    exchange.responseHeaders.add("Content-Range", "bytes 0-0/${payload.size}")
                    exchange.sendResponseHeaders(206, 1)
                    exchange.responseBody.use { it.write(payload, 0, 1) }
                }
            } else {
                exchange.responseHeaders.add("Content-Type", "audio/mpeg")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
        }.use { server ->
            val root = Files.createTempDirectory("hachimi-expired-source")
            val target = root.resolve("song.mp3")
            val initial = ResolvedSongUrl(
                "http://m801.music.126.net/audio.mp3?signature=EXPIRED_SECRET",
                "mp3",
                "exhigh",
                AudioUrlOrigin.NETEASE_SOURCE_API,
            )
            val refreshed = initial.copy(url = "http://m801.music.126.net/audio.mp3?signature=FRESH_SECRET")
            val downloader = DesktopDownloader(
                client = server.client(),
                allowHttpForLoopbackTests = true,
                audioUrlPolicy = testCdnPolicy(),
            )

            val result = downloader.download("expired-source", target, initial, {
                queriedSources += refreshed.url
                refreshed
            }) {}

            assertEquals(1, queriedSources.size)
            assertEquals(payload.size.toLong(), result.bytesDownloaded)
            assertContentEquals(payload, Files.readAllBytes(target))
            assertEquals(2, probeCount.get())
        }
    }

    @Test
    fun `HTTPS probe rejects TLS certificates not trusted by the default client`() = runBlocking {
        LocalHttpsServer { exchange ->
            exchange.responseHeaders.add("Content-Type", "audio/mpeg")
            exchange.sendResponseHeaders(200, 4)
            exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3, 4)) }
        }.use { server ->
            val source = ResolvedSongUrl("https://m801.music.126.net/audio", "mp3", "exhigh")
            val downloader = DesktopDownloader(
                client = server.client(trustTestCertificate = false),
                allowHttpForLoopbackTests = true,
                audioUrlPolicy = AudioUrlPolicy(dnsLookup = { listOf(InetAddress.getByName("127.0.0.1")) }),
            )

            val failure = assertFailsWith<AudioUrlPolicyException> {
                downloader.download("tls-invalid", Files.createTempDirectory("hachimi-tls-invalid").resolve("a.mp3"), source, { source }) {}
            }

            assertEquals(AudioUrlFailure.TLS_VALIDATION_FAILED, failure.failure)
        }
    }

    @Test
    fun `HTTPS upgrade rejects redirects to HTTP and private destinations`() = runBlocking {
        val targets = listOf(
            "http://127.0.0.1:8080/audio",
            "https://127.0.0.1:8443/audio",
        )
        targets.forEachIndexed { index, location ->
            LocalHttpsServer { exchange ->
                exchange.responseHeaders.add("Location", location)
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }.use { server ->
                val source = ResolvedSongUrl(
                    "http://m801.music.126.net/redirect?signature=REDIRECT_SECRET_$index",
                    "mp3",
                    "exhigh",
                    AudioUrlOrigin.NETEASE_SOURCE_API,
                )
                val downloader = DesktopDownloader(
                    client = server.client(),
                    allowHttpForLoopbackTests = true,
                    audioUrlPolicy = testCdnPolicy(),
                )

                val failure = assertFailsWith<AudioUrlPolicyException> {
                    downloader.download(
                        "redirect-$index",
                        Files.createTempDirectory("hachimi-redirect-$index").resolve("a.mp3"),
                        source,
                        { source },
                    ) {}
                }

                assertEquals(AudioUrlFailure.REDIRECT_POLICY_REJECTED, failure.failure)
            }
        }
    }

    @Test
    fun `existing target is never overwritten`() = runBlocking {
        val bytes = "new-audio".encodeToByteArray()
        LocalServer { it.reply(200, mapOf("Content-Type" to "audio/mpeg"), bytes) }.use { server ->
            val root = Files.createTempDirectory("hachimi-download")
            val target = root.resolve("song.mp3")
            val existing = "keep-me".encodeToByteArray()
            Files.write(target, existing)
            val downloader = DesktopDownloader(allowHttpForLoopbackTests = true)

            assertFailsWith<java.nio.file.FileAlreadyExistsException> {
                downloader.download("task-9", target, source(server.url), { source(server.url) }) {}
            }

            assertContentEquals(existing, Files.readAllBytes(target))
        }
    }

    private fun source(url: String) = ResolvedSongUrl(url, "mp3", "exhigh")

    private fun testCdnPolicy() = AudioUrlPolicy(
        trustedCdnHosts = setOf("m801.music.126.net"),
        dnsLookup = { listOf(InetAddress.getByName("127.0.0.1")) },
    )

    private fun seedPartial(
        downloader: DesktopDownloader,
        target: Path,
        bytes: ByteArray,
        total: Long,
        etag: String,
    ) {
        Files.write(downloader.partialPath(target), bytes)
        Files.writeString(
            downloader.metadataPath(target),
            JSONObject()
                .put("etag", etag)
                .put("lastModified", JSONObject.NULL)
                .put("totalBytes", total)
                .put("contentType", "audio/mpeg")
                .toString(),
        )
    }

    private fun testClient(readTimeoutMillis: Long) = okhttp3.OkHttpClient.Builder()
        .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(readTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        .callTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private class LocalServer(handler: (HttpExchange) -> Unit) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@LocalServer.executor
            createContext("/") { exchange -> handler(exchange) }
            start()
        }
        val url: String = "http://127.0.0.1:${server.address.port}/audio"

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private class LocalHttpsServer(handler: (HttpExchange) -> Unit) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(TestTlsMaterial.serverContext)
            this.executor = this@LocalHttpsServer.executor
            createContext("/") { exchange -> handler(exchange) }
            start()
        }

        fun url(path: String) = "https://m801.music.126.net$path"

        fun client(trustTestCertificate: Boolean = true): OkHttpClient {
            val base = if (trustTestCertificate) TestTlsMaterial.trustingClient() else OkHttpClient()
            return base.newBuilder().addInterceptor { chain ->
                val request = chain.request()
                val localPortUrl = request.url.newBuilder().port(server.address.port).build()
                chain.proceed(request.newBuilder().url(localPortUrl).build())
            }.build()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun HttpExchange.reply(status: Int, headers: Map<String, String>, bytes: ByteArray) {
        headers.forEach { (name, value) -> responseHeaders.add(name, value) }
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}

internal object TestTlsMaterial {
    private const val PASSWORD = "test-only-pass"
    private val files by lazy { create() }
    val serverContext: SSLContext get() = files.first

    fun trustingClient(): OkHttpClient {
        val trustManager = files.second
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }
        return OkHttpClient.Builder().sslSocketFactory(context.socketFactory, trustManager).build()
    }

    private fun create(): Pair<SSLContext, X509TrustManager> {
        val directory = Files.createTempDirectory("hachimi-test-tls")
        val keyStoreFile = directory.resolve("server.p12")
        val certificateFile = directory.resolve("server.cer")
        val keytool = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) "keytool.exe" else "keytool",
        )
        runKeytool(
            keytool,
            "-genkeypair", "-alias", "hachimi-test", "-keyalg", "RSA", "-keysize", "2048",
            "-storetype", "PKCS12", "-keystore", keyStoreFile.toString(),
            "-storepass", PASSWORD, "-keypass", PASSWORD, "-dname", "CN=m801.music.126.net",
            "-ext", "SAN=dns:m801.music.126.net,ip:127.0.0.1", "-validity", "2", "-noprompt",
        )
        runKeytool(
            keytool,
            "-exportcert", "-alias", "hachimi-test", "-keystore", keyStoreFile.toString(),
            "-storepass", PASSWORD, "-file", certificateFile.toString(),
        )

        val keyStore = KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(keyStoreFile).use { load(it, PASSWORD.toCharArray()) }
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, PASSWORD.toCharArray())
        }
        val trustedCertificates = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            val certificate = Files.newInputStream(certificateFile).use {
                java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
            setCertificateEntry("hachimi-test", certificate)
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustedCertificates)
        }.trustManagers
        val trustManager = trustManagers.filterIsInstance<X509TrustManager>().single()
        val serverContext = SSLContext.getInstance("TLS").apply {
            init(keyManagers.keyManagers, trustManagers, null)
        }
        return serverContext to trustManager
    }

    private fun runKeytool(executable: Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf(executable.toString()) + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor() == 0) { "Unable to create local test TLS certificate: $output" }
    }
}
