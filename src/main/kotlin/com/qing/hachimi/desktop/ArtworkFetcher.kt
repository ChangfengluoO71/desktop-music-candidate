package com.qing.hachimi.desktop

import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.IDN
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

internal const val MAX_ARTWORK_BYTES = 5 * 1024 * 1024
private const val MAX_ARTWORK_REDIRECTS = 5
private const val DEFAULT_ARTWORK_SIZE = 640

internal enum class ArtworkFetchFailure {
    INVALID_URL,
    UNTRUSTED_HOST,
    DNS_RESOLUTION_FAILED,
    DNS_SYNTHETIC_ADDRESS_SUSPECTED,
    NON_PUBLIC_DNS_TARGET,
    NETWORK_ROUTE_UNAVAILABLE,
    TLS_VALIDATION_FAILED,
    REDIRECT_POLICY_REJECTED,
    HTTP_ERROR,
    TRANSFER_FAILED,
    IMAGE_INVALID,
    IMAGE_TOO_LARGE,
}

internal class ArtworkFetchException(
    val failure: ArtworkFetchFailure,
    val safeMessage: String,
) : IOException(safeMessage)

/** Fetches public NetEase artwork only, validating every redirect and pinning checked DNS answers. */
internal class ArtworkFetcher(
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .build(),
    trustedHosts: Set<String> = emptySet(),
    private val allowLegacyNetEaseHttpUrls: Boolean = false,
    private val dnsLookup: (String) -> List<InetAddress> = Dns.SYSTEM::lookup,
    private val allowLoopbackForTests: Boolean = false,
    private val networkFactory: DesktopNetworkClientFactory = DesktopNetworkClientFactory(),
) {
    private val baseClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .build()
    private val extraTrustedHosts = trustedHosts.mapNotNull(::normalizeHost).toSet()
    private val addressPolicy = AudioUrlPolicy(
        dnsLookup = dnsLookup,
        // Initial p1/p3/p4 artwork URLs normalize to p2. Keep fake-IP acceptance limited to
        // these exact public image CDN names, including same-CDN redirects.
        syntheticDnsAllowedHosts = NETEASE_ARTWORK_CDN_SYNTHETIC_DNS_HOSTS,
    )

    /** Compatibility wrapper used by non-interactive artwork consumers. */
    fun fetch(rawUrl: String): ArtworkPayload? = try {
        fetchOrThrow(rawUrl)
    } catch (_: Exception) {
        null
    }

    /** Interactive previews use this variant so a safe, actionable error can be shown to the user. */
    fun fetchOrThrow(rawUrl: String): ArtworkPayload {
        var current = normalizeInitialUrl(rawUrl)
        repeat(MAX_ARTWORK_REDIRECTS + 1) { redirectCount ->
            val uri = try {
                URI(current)
            } catch (_: Exception) {
                fail(ArtworkFetchFailure.INVALID_URL, "封面地址格式无效。")
            }
            val networkClient = try {
                val selectedRoute = networkFactory.routeFor(uri)
                addressPolicy.networkClientFor(
                    baseClient,
                    uri,
                    allowLoopbackForTests,
                    networkFactory,
                    selectedRoute,
                )
            } catch (error: AudioUrlPolicyException) {
                throw artworkPolicyFailure(error)
            } catch (_: Exception) {
                fail(
                    ArtworkFetchFailure.NETWORK_ROUTE_UNAVAILABLE,
                    "封面网络路由不可用，请检查网络设置。",
                )
            }
            val request = try {
                Request.Builder()
                    .url(current)
                    .header("User-Agent", "${DesktopProductIdentity.USER_AGENT} (+cover art fetch)")
                    .header("Accept", "image/jpeg,image/png,image/webp")
                    .get()
                    .build()
            } catch (_: Exception) {
                fail(ArtworkFetchFailure.INVALID_URL, "封面地址格式无效。")
            }

            val response = try {
                networkClient.newCall(request).execute()
            } catch (_: SSLException) {
                fail(
                    ArtworkFetchFailure.TLS_VALIDATION_FAILED,
                    "封面服务器 HTTPS 证书校验失败。",
                )
            } catch (_: UnknownHostException) {
                fail(
                    ArtworkFetchFailure.DNS_RESOLUTION_FAILED,
                    "系统 DNS 无法解析封面 CDN 主机。",
                )
            } catch (_: SocketTimeoutException) {
                fail(ArtworkFetchFailure.TRANSFER_FAILED, "封面请求超时，请检查网络或代理设置。")
            } catch (_: ConnectException) {
                fail(ArtworkFetchFailure.TRANSFER_FAILED, "无法连接封面服务器，请检查网络或代理设置。")
            } catch (_: IOException) {
                fail(ArtworkFetchFailure.TRANSFER_FAILED, "封面 HTTPS 请求失败，请检查网络或代理设置。")
            } catch (_: Exception) {
                fail(ArtworkFetchFailure.TRANSFER_FAILED, "封面请求失败，请检查网络设置。")
            }

            response.use {
                if (it.code in 300..399) {
                    if (redirectCount == MAX_ARTWORK_REDIRECTS) {
                        fail(ArtworkFetchFailure.REDIRECT_POLICY_REJECTED, "封面服务器重定向次数超出限制。")
                    }
                    val location = it.header("Location")
                        ?: fail(ArtworkFetchFailure.REDIRECT_POLICY_REJECTED, "封面服务器返回了无效重定向。")
                    val next = try {
                        uri.resolve(location).toASCIIString()
                    } catch (_: Exception) {
                        fail(ArtworkFetchFailure.REDIRECT_POLICY_REJECTED, "封面服务器返回了无效重定向。")
                    }
                    // Redirects must already be HTTPS. Legacy HTTP normalization applies only to
                    // the original API-provided cover URL and can never turn an HTTP redirect into
                    // an accepted target.
                    current = validateHttps(next)
                        ?: fail(ArtworkFetchFailure.REDIRECT_POLICY_REJECTED, "封面重定向未通过 HTTPS 主机校验。")
                    return@repeat
                }
                if (it.code != 200) {
                    fail(ArtworkFetchFailure.HTTP_ERROR, "封面服务器返回 HTTP " + it.code + "。")
                }
                val body = it.body
                val declaredLength = body.contentLength()
                if (declaredLength > MAX_ARTWORK_BYTES) {
                    fail(ArtworkFetchFailure.IMAGE_TOO_LARGE, "封面文件超过大小限制。")
                }
                val bytes = try {
                    body.byteStream().use { stream -> stream.readNBytes(MAX_ARTWORK_BYTES + 1) }
                } catch (_: IOException) {
                    fail(ArtworkFetchFailure.TRANSFER_FAILED, "读取封面数据时连接中断。")
                }
                if (bytes.isEmpty()) {
                    fail(ArtworkFetchFailure.IMAGE_INVALID, "封面服务器返回了空文件。")
                }
                if (bytes.size > MAX_ARTWORK_BYTES) {
                    fail(ArtworkFetchFailure.IMAGE_TOO_LARGE, "封面文件超过大小限制。")
                }
                // CDN Content-Type headers can be wrong (for example, image/jpg for PNG
                // bytes). Treat the bounded, recognized file signature as authoritative and
                // derive the stored extension from it; never trust the response header alone.
                val payload = artworkPayloadFromTrustedBytes(bytes)
                    ?: fail(ArtworkFetchFailure.IMAGE_INVALID, "封面响应内容不是支持的图片格式。")
                return payload
            }
        }
        fail(ArtworkFetchFailure.REDIRECT_POLICY_REJECTED, "封面服务器重定向未通过安全校验。")
    }

    /**
     * Mirrors the Android repository's narrowly scoped cover normalization:
     * upgrade trusted NetEase cover URLs to HTTPS, route p1/p3/p4 through p2,
     * and request the default display size when the API supplied no query.
     */
    private fun normalizeInitialUrl(raw: String): String {
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            fail(ArtworkFetchFailure.INVALID_URL, "封面地址格式无效。")
        }
        if (!uri.isAbsolute || uri.isOpaque || uri.rawFragment != null || uri.rawUserInfo != null) {
            fail(ArtworkFetchFailure.INVALID_URL, "封面地址格式无效。")
        }

        val scheme = uri.scheme?.lowercase()
            ?: fail(ArtworkFetchFailure.INVALID_URL, "封面地址格式无效。")
        if (scheme != "https" && !(scheme == "http" && allowLegacyNetEaseHttpUrls)) {
            fail(ArtworkFetchFailure.INVALID_URL, "封面地址必须使用 HTTPS。")
        }
        val rawHost = uri.host ?: fail(ArtworkFetchFailure.INVALID_URL, "封面地址格式无效。")
        if (rawHost.endsWith('.')) {
            fail(ArtworkFetchFailure.UNTRUSTED_HOST, "封面主机未通过安全校验。")
        }
        val host = normalizeHost(rawHost)
            ?: fail(ArtworkFetchFailure.INVALID_URL, "封面地址格式无效。")
        if (!isValidDnsName(host) || !isTrustedHost(host)) {
            fail(ArtworkFetchFailure.UNTRUSTED_HOST, "封面主机未通过安全校验。")
        }
        val validPort = uri.port == -1 ||
            (scheme == "http" && uri.port == 80) ||
            (scheme == "https" && uri.port == 443)
        if (!validPort) {
            fail(ArtworkFetchFailure.UNTRUSTED_HOST, "封面地址使用了不允许的端口。")
        }

        val targetHost = when (host) {
            "p1.music.126.net",
            "p3.music.126.net",
            "p4.music.126.net" -> "p2.music.126.net"
            else -> host
        }
        val targetPort = if (scheme == "https" && uri.port == 443) ":443" else ""
        val query = uri.rawQuery ?: ("param=" + DEFAULT_ARTWORK_SIZE + "y" + DEFAULT_ARTWORK_SIZE)
        val normalized = "https://" + targetHost + targetPort + uri.rawPath.orEmpty() + "?" + query
        return validateHttps(normalized)
            ?: fail(ArtworkFetchFailure.INVALID_URL, "封面地址未通过 HTTPS 校验。")
    }

    /** Strict validation used for every redirect; never upgrades or rewrites redirect targets. */
    private fun validateHttps(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!uri.isAbsolute || uri.isOpaque || uri.rawFragment != null || uri.rawUserInfo != null ||
            !uri.scheme.equals("https", ignoreCase = true) || uri.port !in setOf(-1, 443)
        ) return null
        val rawHost = uri.host ?: return null
        if (rawHost.endsWith('.')) return null
        val host = normalizeHost(rawHost) ?: return null
        if (!isValidDnsName(host) || !isTrustedHost(host)) return null
        return uri.toASCIIString()
    }

    private fun artworkPolicyFailure(error: AudioUrlPolicyException): ArtworkFetchException =
        when (error.failure) {
            AudioUrlFailure.DNS_RESOLUTION_FAILED -> ArtworkFetchException(
                ArtworkFetchFailure.DNS_RESOLUTION_FAILED,
                "系统 DNS 无法解析封面 CDN 主机。",
            )
            AudioUrlFailure.DNS_SYNTHETIC_ADDRESS_SUSPECTED -> ArtworkFetchException(
                ArtworkFetchFailure.DNS_SYNTHETIC_ADDRESS_SUSPECTED,
                "系统 DNS 返回了疑似合成地址 198.18.0.0/15，已按安全策略拦截。请在设置中选择系统默认或自定义代理后重启，或让 DNS 返回可公开路由的 CDN 地址。",
            )
            AudioUrlFailure.NON_PUBLIC_DNS_TARGET -> ArtworkFetchException(
                ArtworkFetchFailure.NON_PUBLIC_DNS_TARGET,
                "系统 DNS 将封面主机解析到非公网地址，已按安全策略拦截。",
            )
            AudioUrlFailure.TLS_VALIDATION_FAILED -> ArtworkFetchException(
                ArtworkFetchFailure.TLS_VALIDATION_FAILED,
                "封面服务器 HTTPS 证书校验失败。",
            )
            else -> ArtworkFetchException(
                ArtworkFetchFailure.NETWORK_ROUTE_UNAVAILABLE,
                "封面网络路由未通过安全校验，请检查网络设置。",
            )
        }

    private fun isTrustedHost(host: String): Boolean =
        host == "music.126.net" || host.endsWith(".music.126.net") || host in extraTrustedHosts

    private fun normalizeHost(host: String): String? = runCatching {
        IDN.toASCII(host.trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase()
    }.getOrNull()

    private fun isValidDnsName(host: String): Boolean = host.length <= 253 &&
        !host.endsWith('.') && host.contains('.') && host.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 && label.first() != '-' && label.last() != '-' &&
                label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        }

    private fun fail(failure: ArtworkFetchFailure, message: String): Nothing =
        throw ArtworkFetchException(failure, message)
}

private val NETEASE_ARTWORK_CDN_SYNTHETIC_DNS_HOSTS = setOf(
    "p1.music.126.net",
    "p2.music.126.net",
    "p3.music.126.net",
    "p4.music.126.net",
)
