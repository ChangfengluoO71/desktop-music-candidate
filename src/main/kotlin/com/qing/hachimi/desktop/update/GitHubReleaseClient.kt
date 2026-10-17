package com.qing.hachimi.desktop.update

import com.qing.hachimi.desktop.BuildInfo
import com.qing.hachimi.desktop.DesktopProductIdentity
import com.qing.hachimi.desktop.DesktopNetworkClientFactory
import com.qing.hachimi.desktop.DesktopNetworkSettings
import com.qing.hachimi.desktop.SemVer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Unauthenticated client for this product's public release collection only. */
internal class GitHubReleaseClient(
    private val client: OkHttpClient = defaultClient(),
    private val currentVersion: SemVer = requireNotNull(SemVer.parse(DesktopProductIdentity.VERSION)),
    private val channel: UpdateChannel = UpdateChannel.valueOf(BuildInfo.CHANNEL.uppercase(Locale.ROOT)),
    private val releasesEndpoint: HttpUrl = RELEASES_ENDPOINT,
    private val networkFactory: DesktopNetworkClientFactory = DesktopNetworkClientFactory(DesktopNetworkSettings()),
    private val checkMirrorTemplateProvider: () -> String? = { null },
) : ReleaseProvider {
    suspend fun check(): ReleaseCheckResult = check(null)

    override suspend fun check(etag: String?): ReleaseCheckResult = withContext(Dispatchers.IO) {
        val originalRequestUrl = releasesEndpoint.newBuilder().addQueryParameter("per_page", "100").build()
        val requestUrl = requestUrl(originalRequestUrl)
            ?: return@withContext ReleaseCheckResult.Failed(ReleaseCheckFailure.INVALID_METADATA)
        val request = Request.Builder()
            .url(requestUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .header("User-Agent", DesktopProductIdentity.USER_AGENT)
            .apply { etag?.takeIf(::isSafeEtag)?.let { header("If-None-Match", it) } }
            .get()
            .build()

        try {
            networkFactory.clientFor(client, requestUrl.toUri()).newCall(request).execute().use { response ->
                val responseEtag = response.header("ETag")?.takeIf(::isSafeEtag)
                when {
                    response.code == 304 -> ReleaseCheckResult.NotModified(responseEtag ?: etag)
                    response.code == 429 || (response.code == 403 && isRateLimited(response)) -> ReleaseCheckResult.Failed(
                        category = ReleaseCheckFailure.RATE_LIMITED,
                        retryAfterEpochMillis = retryAfterEpochMillis(response.header("Retry-After"), response.header("X-RateLimit-Reset")),
                        rateLimitKind = when {
                            response.header("X-RateLimit-Remaining") == "0" -> UpdateRateLimitKind.PRIMARY_API_QUOTA
                            response.code == 429 -> UpdateRateLimitKind.TOO_MANY_REQUESTS
                            response.header("Retry-After") != null -> UpdateRateLimitKind.RETRY_AFTER
                            else -> UpdateRateLimitKind.UNKNOWN
                        },
                    )
                    !response.isSuccessful -> ReleaseCheckResult.Failed(ReleaseCheckFailure.HTTP_ERROR)
                    else -> {
                        val body = response.body
                        val raw = readBounded(body.source())
                            ?: return@withContext ReleaseCheckResult.Failed(ReleaseCheckFailure.INVALID_RESPONSE)
                        parseReleases(raw, responseEtag)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            ReleaseCheckResult.Failed(ReleaseCheckFailure.TIMEOUT)
        } catch (failure: IOException) {
            val timeout = failure.message.orEmpty().contains("timeout", ignoreCase = true)
            ReleaseCheckResult.Failed(if (timeout) ReleaseCheckFailure.TIMEOUT else ReleaseCheckFailure.NETWORK)
        } catch (_: JSONException) {
            ReleaseCheckResult.Failed(ReleaseCheckFailure.INVALID_RESPONSE)
        } catch (_: IllegalArgumentException) {
            ReleaseCheckResult.Failed(ReleaseCheckFailure.INVALID_RESPONSE)
        }
    }

    private fun parseReleases(raw: String, etag: String?): ReleaseCheckResult {
        val rows = JSONArray(raw)
        val eligible = buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                if (row.optBoolean("draft", false)) continue
                val prerelease = row.optBoolean("prerelease", false)
                if (channel == UpdateChannel.STABLE && prerelease) continue
                add(row)
            }
        }
        val parsed = eligible.mapNotNull { row ->
            val tag = row.optString("tag_name").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val version = SemVer.parse(tag.removePrefix("v")) ?: return@mapNotNull null
            if (channel == UpdateChannel.STABLE && version.prerelease != null) return@mapNotNull null
            ParsedRelease(row, tag, version, row.optBoolean("prerelease", false))
        }
        if (parsed.isEmpty()) {
            return if (eligible.isNotEmpty()) ReleaseCheckResult.Failed(ReleaseCheckFailure.INVALID_METADATA)
            else ReleaseCheckResult.UpToDate(etag)
        }

        val latest = parsed.maxOf { it.version }
        if (latest <= currentVersion) return ReleaseCheckResult.UpToDate(etag)
        val candidates = parsed.filter { it.version.compareTo(latest) == 0 }
        if (candidates.size != 1) return ReleaseCheckResult.Failed(ReleaseCheckFailure.INVALID_METADATA)
        val release = candidates.single()
        val expectedName = "${DesktopProductIdentity.INSTALLER_NAME}-$latest-Setup.exe"
        val assets = release.json.optJSONArray("assets") ?: JSONArray()
        val matchingAssets = buildList {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                if (asset.optString("name") == expectedName) add(asset)
            }
        }
        if (matchingAssets.isEmpty()) return ReleaseCheckResult.Failed(ReleaseCheckFailure.MISSING_INSTALLER_ASSET)
        if (matchingAssets.size != 1) return ReleaseCheckResult.Failed(ReleaseCheckFailure.AMBIGUOUS_INSTALLER_ASSET)

        val assetJson = matchingAssets.single()
        if (!assetJson.has("size") || assetJson.isNull("size")) {
            return ReleaseCheckResult.Failed(ReleaseCheckFailure.INVALID_METADATA)
        }
        val size = assetJson.optLong("size", -1L)
        val digest = SHA256_DIGEST.matchEntire(assetJson.optString("digest"))?.groupValues?.get(1)
            ?: return ReleaseCheckResult.Failed(ReleaseCheckFailure.INVALID_METADATA)
        val url = assetJson.optString("browser_download_url").toHttpUrlOrNull()
            ?: return ReleaseCheckResult.Failed(ReleaseCheckFailure.INVALID_METADATA)
        if (size <= 0 || !isExpectedInstallerUrl(url, release.tag, expectedName)) {
            return ReleaseCheckResult.Failed(ReleaseCheckFailure.INVALID_METADATA)
        }

        val notes = release.json.optString("body").take(MAX_RELEASE_NOTES_CHARS)
        return ReleaseCheckResult.UpdateAvailable(
            UpdateRelease(
                version = release.version,
                tagName = release.tag,
                prerelease = release.prerelease,
                notes = notes,
                asset = InstallerAsset(expectedName, url, size, digest.lowercase(Locale.ROOT), release.tag),
            ),
            etag,
        )
    }

    private fun requestUrl(original: HttpUrl): HttpUrl? {
        val configuredTemplate = runCatching(checkMirrorTemplateProvider).getOrElse { return null }
            ?.takeIf(String::isNotBlank)
            ?: return original
        val mirror = UpdateMirrorTemplate.parse(configuredTemplate) ?: return null
        return runCatching { mirror.route(original, UpdateMirrorUse.RELEASE_CHECK) }.getOrNull()
    }

    private fun isExpectedInstallerUrl(url: HttpUrl, tag: String, assetName: String): Boolean {
        val expectedPath = "/$REPOSITORY_OWNER/$REPOSITORY_NAME/releases/download/$tag/$assetName"
        return url.scheme == "https" &&
            url.host == "github.com" &&
            url.port == 443 &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.querySize == 0 &&
            url.fragment == null &&
            url.encodedPath == expectedPath
    }

    private fun readBounded(source: okio.BufferedSource): String? {
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(buffer, 8_192L)
            if (read == -1L) break
            total += read
            if (total > MAX_RELEASE_RESPONSE_BYTES) return null
        }
        return buffer.readUtf8()
    }

    private fun retryAfterEpochMillis(retryAfter: String?, rateLimitReset: String?): Long? {
        val now = System.currentTimeMillis()
        val retryDelayMillis = retryAfter?.trim()?.let { value ->
            value.toLongOrNull()?.let { seconds ->
                seconds.coerceIn(1L, MAX_RETRY_AFTER_SECONDS) * 1_000L
            } ?: runCatching {
                val retryAt = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli()
                (retryAt - now).coerceIn(1_000L, MAX_RETRY_AFTER_SECONDS * 1_000L)
            }.getOrNull()
        }
        val resetDelayMillis = rateLimitReset?.trim()?.toLongOrNull()?.let { resetEpochSeconds ->
            (resetEpochSeconds - now / 1_000L).coerceIn(1L, MAX_RETRY_AFTER_SECONDS) * 1_000L
        }
        return now + (retryDelayMillis ?: resetDelayMillis ?: return null)
    }

    private fun isSafeEtag(value: String): Boolean =
        value.length in 1..MAX_ETAG_LENGTH && value.none { it == '\r' || it == '\n' || it.code < 0x20 }

    private fun isRateLimited(response: okhttp3.Response): Boolean =
        response.header("Retry-After") != null || response.header("X-RateLimit-Remaining") == "0"

    private data class ParsedRelease(
        val json: JSONObject,
        val tag: String,
        val version: SemVer,
        val prerelease: Boolean,
    )

    private companion object {
        const val REPOSITORY_OWNER = "ChangfengluoO71"
        const val REPOSITORY_NAME = "desktop-music-candidate"
        const val GITHUB_API_VERSION = "2022-11-28"
        const val MAX_RELEASE_RESPONSE_BYTES = 4L * 1024 * 1024
        const val MAX_RELEASE_NOTES_CHARS = 20_000
        const val MAX_ETAG_LENGTH = 512
        const val MAX_RETRY_AFTER_SECONDS = 24 * 60 * 60L
        val SHA256_DIGEST = Regex("^sha256:([0-9a-fA-F]{64})$")
        val RELEASES_ENDPOINT = "https://api.github.com/repos/$REPOSITORY_OWNER/$REPOSITORY_NAME/releases".toHttpUrl()

        fun defaultClient() = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
