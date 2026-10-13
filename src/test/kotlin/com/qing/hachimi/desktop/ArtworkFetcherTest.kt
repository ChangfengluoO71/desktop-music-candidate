package com.qing.hachimi.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class ArtworkFetcherTest {
    @Test
    fun `jpeg png and webp signatures return verified extensions`() {
        val fixtures = listOf(
            Triple("image/jpeg", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1), ".jpg"),
            Triple("image/png", png(), ".png"),
            Triple("image/webp", webp(), ".webp"),
        )
        fixtures.forEach { (mime, bytes, extension) ->
            LocalArtworkServer { it.reply(200, mime, bytes) }.use { server ->
                val result = server.fetcher().fetch(server.url())
                assertNotNull(result)
                assertEquals(extension, result.extension)
                assertEquals(mime, result.mimeType)
                assertContentEquals(bytes, result.bytes)
            }
        }
    }

    @Test
    fun `recognized image signature wins over mismatched CDN content type`() {
        LocalArtworkServer { it.reply(200, "image/jpeg", png()) }.use { server ->
            val result = server.fetcher().fetch(server.url())
            assertNotNull(result)
            assertEquals("image/png", result.mimeType)
            assertEquals(".png", result.extension)
        }
    }

    @Test
    fun `oversized image body is rejected`() {
        LocalArtworkServer { it.reply(200, "image/png", ByteArray(MAX_ARTWORK_BYTES + 1)) }.use { server ->
            assertNull(server.fetcher().fetch(server.url()))
        }
    }

    @Test
    fun `CDN request has no Cookie or MUSIC_U header`() {
        var cookie: String? = null
        LocalArtworkServer {
            cookie = it.requestHeaders.getFirst("Cookie")
            it.reply(200, "image/png", png())
        }.use { server ->
            assertNotNull(server.fetcher().fetch(server.url()))
            assertNull(cookie)
        }
    }

    @Test
    fun `trusted HTTPS redirect is followed`() {
        LocalArtworkServer { exchange ->
            if (exchange.requestURI.path == "/start") {
                exchange.responseHeaders.add("Location", "/image")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            } else exchange.reply(200, "image/png", png())
        }.use { server ->
            assertContentEquals(png(), server.fetcher().fetch(server.url("/start"))?.bytes)
        }
    }

    @Test
    fun `HTTPS redirect to HTTP is rejected`() {
        LocalArtworkServer { exchange ->
            exchange.responseHeaders.add("Location", serverHttpLocation(exchange))
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }.use { server ->
            assertNull(server.fetcher().fetch(server.url()))
        }
    }

    @Test
    fun `redirect to untrusted host is rejected`() {
        LocalArtworkServer { exchange ->
            exchange.responseHeaders.add("Location", "https://attacker.example/image.png")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }.use { server ->
            assertNull(server.fetcher().fetch(server.url()))
        }
    }

    @Test
    fun `untrusted certificate fails with normal TLS validation`() {
        LocalArtworkServer { it.reply(200, "image/png", png()) }.use { server ->
            assertNull(server.fetcher(trustTestCertificate = false).fetch(server.url()))
        }
    }

    @Test
    fun `non-https untrusted host userinfo and unusual port are rejected`() {
        val fetcher = ArtworkFetcher()
        listOf(
            "http://m801.music.126.net/cover.png",
            "https://m801.music.126.net:444/cover.png",
            "https://user@p1.music.126.net/cover.png",
            "https://music.126.net.attacker.example/cover.png",
            "https://p1.music.126.net./cover.png",
        ).forEach { unsafe -> assertNull(fetcher.fetch(unsafe)) }
    }

    @Test
    fun `private and synthetic DNS targets are rejected before transfer`() {
        val requested = AtomicInteger()
        LocalArtworkServer { exchange ->
            requested.incrementAndGet()
            exchange.reply(200, "image/png", png())
        }.use { server ->
            listOf("192.168.1.10", "198.18.0.1").forEach { ip ->
                val fetcher = ArtworkFetcher(
                    client = server.client(trustTestCertificate = true),
                    trustedHosts = setOf("m801.music.126.net"),
                    dnsLookup = { listOf(InetAddress.getByName(ip)) },
                    allowLoopbackForTests = true,
                )
                assertNull(fetcher.fetch(server.url()))
            }
            assertEquals(0, requested.get())
        }
    }

    @Test
    fun `synthetic DNS is allowed for the normalized NetEase artwork CDN over HTTPS`() {
        val observedHosts = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                observedHosts += chain.request().url.host
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "image/png")
                    .body(png().toResponseBody("image/png".toMediaType()))
                    .build()
            }
            .build()
        val fetcher = ArtworkFetcher(
            client = client,
            allowLegacyNetEaseHttpUrls = true,
            dnsLookup = { host ->
                assertEquals("p2.music.126.net", host)
                listOf(InetAddress.getByName("198.18.0.77"))
            },
        )

        val payload = fetcher.fetch("http://p1.music.126.net/cover.png")

        assertNotNull(payload)
        assertEquals(listOf("p2.music.126.net"), observedHosts)
        assertEquals("image/png", payload.mimeType)
        assertContentEquals(png(), payload.bytes)
    }

    @Test
    fun `redirect to a private DNS answer is rejected before second request`() {
        val requested = AtomicInteger()
        LocalArtworkServer { exchange ->
            requested.incrementAndGet()
            if (exchange.requestURI.path == "/start") {
                exchange.responseHeaders.add("Location", "https://m802.music.126.net/private")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            } else {
                exchange.reply(200, "image/png", png())
            }
        }.use { server ->
            val fetcher = ArtworkFetcher(
                client = server.client(trustTestCertificate = true),
                trustedHosts = setOf("m801.music.126.net", "m802.music.126.net"),
                dnsLookup = { host ->
                    listOf(InetAddress.getByName(if (host == "m801.music.126.net") "127.0.0.1" else "10.0.0.4"))
                },
                allowLoopbackForTests = true,
            )

            assertNull(fetcher.fetch(server.url("/start")))
            assertEquals(1, requested.get())
        }
    }

    private fun LocalArtworkServer.fetcher(trustTestCertificate: Boolean = true) = ArtworkFetcher(
        client = client(trustTestCertificate),
        trustedHosts = setOf("m801.music.126.net"),
        dnsLookup = { listOf(InetAddress.getByName("127.0.0.1")) },
        allowLoopbackForTests = true,
    )

    private class LocalArtworkServer(handler: (HttpExchange) -> Unit) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(TestTlsMaterial.serverContext)
            this.executor = this@LocalArtworkServer.executor
            createContext("/") { exchange -> handler(exchange) }
            start()
        }

        fun url(path: String = "/cover") = "https://m801.music.126.net$path"

        fun client(trustTestCertificate: Boolean): OkHttpClient {
            val base = if (trustTestCertificate) TestTlsMaterial.trustingClient() else OkHttpClient()
            return base.newBuilder().addInterceptor { chain ->
                val local = chain.request().url.newBuilder().port(server.address.port).build()
                chain.proceed(chain.request().newBuilder().url(local).build())
            }.build()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun HttpExchange.reply(status: Int, mime: String, body: ByteArray) {
        responseHeaders.add("Content-Type", mime)
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    private fun serverHttpLocation(exchange: HttpExchange) = "http://m801.music.126.net/unsafe"

    private fun png() = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
    )

    private fun webp() = "RIFF".encodeToByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".encodeToByteArray()
}
