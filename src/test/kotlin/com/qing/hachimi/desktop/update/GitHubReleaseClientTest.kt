package com.qing.hachimi.desktop.update

import com.qing.hachimi.desktop.SemVer
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubReleaseClientTest {
    @Test
    fun `chooses highest newer release for the preview channel and rejects equal versions`() = withServer { server ->
        server.respondWith(releases(
            release("1.2.3"),
            release("1.2.2"),
            release("1.3.0-rc.1", prerelease = true),
        ))
        val result = client(server, channel = UpdateChannel.PREVIEW, current = "1.2.3").check()

        val available = assertIs<ReleaseCheckResult.UpdateAvailable>(result)
        assertEquals("1.3.0-rc.1", available.release.version.toString())
        assertEquals("DesktopMusicCandidate-1.3.0-rc.1-Setup.exe", available.release.asset.name)
        assertEquals("a".repeat(64), available.release.asset.sha256)
    }

    @Test
    fun `stable channel ignores prereleases while preview can receive them`() = withServer { server ->
        server.respondWith(releases(release("1.4.0-rc.1", prerelease = true), release("1.3.0")))

        val preview = client(server, UpdateChannel.PREVIEW, "1.2.0").check()
        val stable = client(server, UpdateChannel.STABLE, "1.2.0").check()

        assertEquals("1.4.0-rc.1", assertIs<ReleaseCheckResult.UpdateAvailable>(preview).release.version.toString())
        assertEquals("1.3.0", assertIs<ReleaseCheckResult.UpdateAvailable>(stable).release.version.toString())
    }

    @Test
    fun `preview installation can update to a newer stable release`() = withServer { server ->
        server.respondWith(releases(release("0.0.2")))

        val previewInstall = client(server, UpdateChannel.PREVIEW, "0.0.1").check()

        val available = assertIs<ReleaseCheckResult.UpdateAvailable>(previewInstall)
        assertEquals("0.0.2", available.release.version.toString())
        assertEquals("DesktopMusicCandidate-0.0.2-Setup.exe", available.release.asset.name)
    }

    @Test
    fun `draft releases are ignored`() = withServer { server ->
        server.respondWith(releases(release("9.0.0", draft = true)))

        assertIs<ReleaseCheckResult.UpToDate>(client(server, UpdateChannel.PREVIEW, "1.2.0").check())
    }

    @Test
    fun `missing or duplicate exact installer asset is rejected`() = withServer { server ->
        server.respondWith(releases(release("1.3.0", assets = listOf(asset("other.exe")))))
        val missing = client(server, UpdateChannel.PREVIEW, "1.2.0").check()
        assertEquals(ReleaseCheckFailure.MISSING_INSTALLER_ASSET, assertIs<ReleaseCheckResult.Failed>(missing).category)

        val expected = asset("DesktopMusicCandidate-1.3.0-Setup.exe", tag = "v1.3.0")
        server.respondWith(releases(release("1.3.0", assets = listOf(expected, expected))))
        val duplicate = client(server, UpdateChannel.PREVIEW, "1.2.0").check()
        assertEquals(ReleaseCheckFailure.AMBIGUOUS_INSTALLER_ASSET, assertIs<ReleaseCheckResult.Failed>(duplicate).category)
    }

    @Test
    fun `asset size digest and download origin are validated`() = withServer { server ->
        val expectedName = "DesktopMusicCandidate-1.3.0-Setup.exe"
        val invalidAssets = listOf(
            asset(expectedName, size = 0),
            asset(expectedName, digest = "sha1:" + "b".repeat(40)),
            asset(expectedName, url = "http://github.com/ChangfengluoO71/desktop-music-candidate/releases/download/v1.3.0/$expectedName"),
            asset(expectedName, url = "https://github.com.evil.invalid/ChangfengluoO71/desktop-music-candidate/releases/download/v1.3.0/$expectedName"),
        )

        invalidAssets.forEach { invalid ->
            server.respondWith(releases(release("1.3.0", assets = listOf(invalid))))
            assertEquals(
                ReleaseCheckFailure.INVALID_METADATA,
                assertIs<ReleaseCheckResult.Failed>(client(server, UpdateChannel.PREVIEW, "1.2.0").check()).category,
            )
        }
    }

    @Test
    fun `malformed newer release version is reported as invalid metadata`() = withServer { server ->
        server.respondWith(JSONArray().put(JSONObject().put("tag_name", "v01.3.0").put("draft", false).put("prerelease", true).put("assets", JSONArray())))

        assertEquals(
            ReleaseCheckFailure.INVALID_METADATA,
            assertIs<ReleaseCheckResult.Failed>(client(server, UpdateChannel.PREVIEW, "1.2.0").check()).category,
        )
    }

    @Test
    fun `etag is sent and not modified is surfaced without parsing a body`() = withServer { server ->
        server.respondWith(JSONArray(), headers = mapOf("ETag" to "\"release-set-1\""))
        val first = assertIs<ReleaseCheckResult.UpToDate>(client(server, UpdateChannel.PREVIEW, "9.0.0").check())
        assertEquals("\"release-set-1\"", first.etag)
        server.respondWith("", status = 304, headers = mapOf("ETag" to "\"release-set-1\""))

        val second = client(server, UpdateChannel.PREVIEW, "9.0.0").check(first.etag)

        assertEquals("\"release-set-1\"", assertIs<ReleaseCheckResult.NotModified>(second).etag)
        assertEquals(first.etag, server.lastIfNoneMatch)
    }

    @Test
    fun `rate limits are categorized and retry time is retained`() = withServer { server ->
        listOf(403, 429).forEach { status ->
            server.respondWith("{}", status = status, headers = mapOf("Retry-After" to "120"))
            val failed = assertIs<ReleaseCheckResult.Failed>(client(server, UpdateChannel.PREVIEW, "1.2.0").check())
            assertEquals(ReleaseCheckFailure.RATE_LIMITED, failed.category)
            assertTrue(kotlin.math.abs(((failed.retryAfterEpochMillis ?: 0L) - System.currentTimeMillis()) / 1_000L - 120L) <= 2L)
        }
        server.respondWith("{}", status = 403)
        assertEquals(
            ReleaseCheckFailure.HTTP_ERROR,
            assertIs<ReleaseCheckResult.Failed>(client(server, UpdateChannel.PREVIEW, "1.2.0").check()).category,
        )
    }

    @Test
    fun `timeout does not expose response bodies`() = withServer { server ->
        server.delayMillis = 500
        val http = OkHttpClient.Builder().callTimeout(80, TimeUnit.MILLISECONDS).build()

        val failed = assertIs<ReleaseCheckResult.Failed>(client(server, UpdateChannel.PREVIEW, "1.2.0", http).check())

        assertEquals(ReleaseCheckFailure.TIMEOUT, failed.category)
        assertNull(failed.message)
    }

    private fun client(
        server: LocalReleaseServer,
        channel: UpdateChannel,
        current: String,
        httpClient: OkHttpClient = OkHttpClient(),
    ) = GitHubReleaseClient(
        client = httpClient,
        currentVersion = requireNotNull(SemVer.parse(current)),
        channel = channel,
        releasesEndpoint = server.endpoint,
    )

    private fun release(
        version: String,
        prerelease: Boolean = version.contains('-'),
        draft: Boolean = false,
        assets: List<JSONObject> = listOf(asset("DesktopMusicCandidate-$version-Setup.exe", tag = "v$version")),
    ) = JSONObject()
        .put("tag_name", "v$version")
        .put("draft", draft)
        .put("prerelease", prerelease)
        .put("body", "Release notes for $version")
        .put("assets", JSONArray().apply { assets.forEach(::put) })

    private fun asset(
        name: String,
        tag: String = "v1.3.0",
        size: Long = 42,
        digest: String = "sha256:" + "a".repeat(64),
        url: String = "https://github.com/ChangfengluoO71/desktop-music-candidate/releases/download/$tag/$name",
    ) = JSONObject().put("name", name).put("size", size).put("digest", digest).put("browser_download_url", url)

    private fun releases(vararg items: JSONObject) = JSONArray().apply { items.forEach(::put) }.toString()

    private fun withServer(block: suspend (LocalReleaseServer) -> Unit) = runBlocking {
        val server = LocalReleaseServer()
        try {
            block(server)
        } finally {
            server.close()
        }
    }

    private class LocalReleaseServer : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@LocalReleaseServer.executor
            createContext("/releases") { exchange -> handle(exchange) }
            start()
        }
        val endpoint: HttpUrl = "http://127.0.0.1:${server.address.port}/releases".toHttpUrl()
        var status: Int = 200
        var body: String = "[]"
        var headers: Map<String, String> = emptyMap()
        var delayMillis: Long = 0
        var lastIfNoneMatch: String? = null
            private set

        fun respondWith(body: String, status: Int = 200, headers: Map<String, String> = emptyMap()) {
            this.body = body
            this.status = status
            this.headers = headers
            this.delayMillis = 0
        }

        fun respondWith(body: JSONArray, status: Int = 200, headers: Map<String, String> = emptyMap()) =
            respondWith(body.toString(), status, headers)

        private fun handle(exchange: HttpExchange) {
            lastIfNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
            if (delayMillis > 0) Thread.sleep(delayMillis)
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            if (status == 304) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
