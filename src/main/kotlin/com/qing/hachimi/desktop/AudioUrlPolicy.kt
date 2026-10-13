package com.qing.hachimi.desktop

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.net.IDN
import java.net.Proxy

enum class AudioUrlFailure {
    INVALID_URL_FORMAT,
    UNSUPPORTED_URL_SCHEME,
    UNTRUSTED_AUDIO_HOST,
    HTTPS_UPGRADE_UNAVAILABLE,
    TLS_VALIDATION_FAILED,
    REDIRECT_POLICY_REJECTED,
    AUDIO_SOURCE_EXPIRED,
    HTTP_TRANSFER_FAILED,
    DNS_RESOLUTION_FAILED,
    DNS_SYNTHETIC_ADDRESS_SUSPECTED,
    NON_PUBLIC_DNS_TARGET,
}

enum class AudioUrlScheme { HTTP, HTTPS, OTHER, INVALID }

enum class AudioUrlValidationRule {
    HTTPS_ALLOWED,
    HTTP_HOST_REQUIRES_VERIFICATION,
    HTTP_HTTPS_UPGRADE_ALLOWED,
    INVALID_URL_FORMAT,
    UNSUPPORTED_URL_SCHEME,
    USERINFO_PRESENT,
    IP_LITERAL,
    NON_DEFAULT_PORT,
    INVALID_HOST,
}

internal enum class AudioUrlOrigin { NETEASE_SOURCE_API, UNTRUSTED }

data class AudioUrlMetadata(
    val parseable: Boolean,
    val scheme: AudioUrlScheme,
    val host: String?,
    val explicitPort: Int?,
    val effectivePort: Int?,
    val userInfoPresent: Boolean,
    val ipLiteral: Boolean,
    val validationRule: AudioUrlValidationRule,
)

internal data class PreparedAudioUrl(
    val url: String,
    val upgradedFromHttp: Boolean,
    val originalMetadata: AudioUrlMetadata,
    val loopbackTestOnly: Boolean = false,
)

/**
 * Applies a deliberately narrow URL policy to audio sources returned in memory by the NetEase
 * resolver. HTTP upgrades are limited to exact hosts observed in trusted resolver responses. The
 * downloader must still complete a normal-TLS, bounded Range probe before any audio transfer.
 * Tests may inject a finite exact host set.
 */
internal class AudioUrlPolicy(
    trustedCdnHosts: Set<String> = NETEASE_AUDIO_CDN_HOSTS_REQUIRING_HTTPS_PROBE,
    private val dnsLookup: (String) -> List<InetAddress> = Dns.SYSTEM::lookup,
    syntheticDnsAllowedHosts: Set<String> = trustedCdnHosts,
) {
    private val trustedCdnHosts = trustedCdnHosts.map(::normalizeHost).toSet()
    private val syntheticDnsAllowedHosts = syntheticDnsAllowedHosts.map(::normalizeHost).toSet()

    fun inspect(rawUrl: String, origin: AudioUrlOrigin = AudioUrlOrigin.UNTRUSTED): AudioUrlMetadata {
        val uri = try {
            URI(rawUrl)
        } catch (_: Exception) {
            return invalid(AudioUrlValidationRule.INVALID_URL_FORMAT)
        }
        if (!uri.isAbsolute || uri.isOpaque || uri.rawFragment != null) {
            return invalid(AudioUrlValidationRule.INVALID_URL_FORMAT, uri)
        }

        val scheme = when (uri.scheme?.lowercase()) {
            "http" -> AudioUrlScheme.HTTP
            "https" -> AudioUrlScheme.HTTPS
            null -> AudioUrlScheme.INVALID
            else -> AudioUrlScheme.OTHER
        }
        val rawHost = uri.host?.trim('[', ']')
        val host = rawHost?.let(::safeNormalizedHost)
        val ipLiteral = rawHost?.let(::isIpLiteral) ?: false
        val userInfo = uri.rawUserInfo != null
        val explicitPort = uri.port.takeIf { it >= 0 }
        val effectivePort = when (scheme) {
            AudioUrlScheme.HTTP -> explicitPort ?: 80
            AudioUrlScheme.HTTPS -> explicitPort ?: 443
            else -> explicitPort
        }

        val rule = when {
            scheme == AudioUrlScheme.INVALID -> AudioUrlValidationRule.INVALID_URL_FORMAT
            scheme == AudioUrlScheme.OTHER -> AudioUrlValidationRule.UNSUPPORTED_URL_SCHEME
            userInfo -> AudioUrlValidationRule.USERINFO_PRESENT
            ipLiteral -> AudioUrlValidationRule.IP_LITERAL
            rawHost == null || host == null || !isValidDnsName(host) -> AudioUrlValidationRule.INVALID_HOST
            scheme == AudioUrlScheme.HTTPS && explicitPort != null && explicitPort != 443 ->
                AudioUrlValidationRule.NON_DEFAULT_PORT
            scheme == AudioUrlScheme.HTTP && explicitPort != null && explicitPort != 80 ->
                AudioUrlValidationRule.NON_DEFAULT_PORT
            scheme == AudioUrlScheme.HTTPS -> AudioUrlValidationRule.HTTPS_ALLOWED
            scheme == AudioUrlScheme.HTTP && origin == AudioUrlOrigin.NETEASE_SOURCE_API && host in trustedCdnHosts ->
                AudioUrlValidationRule.HTTP_HTTPS_UPGRADE_ALLOWED
            else -> AudioUrlValidationRule.HTTP_HOST_REQUIRES_VERIFICATION
        }
        return AudioUrlMetadata(
            parseable = true,
            scheme = scheme,
            host = host,
            explicitPort = explicitPort,
            effectivePort = effectivePort,
            userInfoPresent = userInfo,
            ipLiteral = ipLiteral,
            validationRule = rule,
        )
    }

    fun prepare(
        rawUrl: String,
        origin: AudioUrlOrigin,
        allowLoopbackForTests: Boolean = false,
    ): PreparedAudioUrl {
        if (allowLoopbackForTests && isLoopbackUrl(rawUrl)) {
            return PreparedAudioUrl(rawUrl, false, inspect(rawUrl, origin), loopbackTestOnly = true)
        }
        val metadata = inspect(rawUrl, origin)
        when (metadata.validationRule) {
            AudioUrlValidationRule.HTTPS_ALLOWED -> return PreparedAudioUrl(rawUrl, false, metadata)
            AudioUrlValidationRule.HTTP_HTTPS_UPGRADE_ALLOWED -> {
                val colon = rawUrl.indexOf(':')
                if (colon <= 0) throw AudioUrlPolicyException(AudioUrlFailure.INVALID_URL_FORMAT)
                val candidate = if (metadata.explicitPort == 80) {
                    upgradeAndDropDefaultHttpPort(rawUrl, colon)
                } else {
                    "https" + rawUrl.substring(colon)
                }
                val candidateMetadata = inspect(candidate, AudioUrlOrigin.UNTRUSTED)
                if (candidateMetadata.validationRule != AudioUrlValidationRule.HTTPS_ALLOWED ||
                    candidateMetadata.host != metadata.host
                ) {
                    throw AudioUrlPolicyException(AudioUrlFailure.HTTPS_UPGRADE_UNAVAILABLE)
                }
                return PreparedAudioUrl(candidate, true, metadata)
            }
            AudioUrlValidationRule.INVALID_URL_FORMAT ->
                throw AudioUrlPolicyException(AudioUrlFailure.INVALID_URL_FORMAT)
            AudioUrlValidationRule.UNSUPPORTED_URL_SCHEME ->
                throw AudioUrlPolicyException(AudioUrlFailure.UNSUPPORTED_URL_SCHEME)
            AudioUrlValidationRule.HTTP_HOST_REQUIRES_VERIFICATION,
            AudioUrlValidationRule.INVALID_HOST,
            AudioUrlValidationRule.USERINFO_PRESENT,
            AudioUrlValidationRule.IP_LITERAL,
            AudioUrlValidationRule.NON_DEFAULT_PORT,
            -> throw AudioUrlPolicyException(AudioUrlFailure.UNTRUSTED_AUDIO_HOST)
        }
    }

    /** Resolve once, reject any non-public answer, then pin this exact answer into OkHttp. */
    fun pinnedClient(base: OkHttpClient, uri: URI, allowLoopbackForTests: Boolean): OkHttpClient {
        val host = safeNormalizedHost(uri.host?.trim('[', ']'))
            ?: throw AudioUrlPolicyException(AudioUrlFailure.INVALID_URL_FORMAT)
        val addresses = try {
            dnsLookup(host).distinctBy { it.hostAddress }
        } catch (_: Exception) {
            throw AudioUrlPolicyException(AudioUrlFailure.DNS_RESOLUTION_FAILED)
        }
        if (addresses.isEmpty()) {
            throw AudioUrlPolicyException(AudioUrlFailure.DNS_RESOLUTION_FAILED)
        }
        // Some TUN proxies return RFC 2544 198.18/15 fake-IP mappings. Permit those only for
        // exact CDN hosts explicitly trusted by this caller; the HTTPS URL still uses the
        // original hostname, so normal SNI and certificate verification remain in force.
        val syntheticAddressAllowed = host in syntheticDnsAllowedHosts
        if (addresses.any(::isSyntheticAddress) && !syntheticAddressAllowed) {
            throw AudioUrlPolicyException(AudioUrlFailure.DNS_SYNTHETIC_ADDRESS_SUSPECTED)
        }
        if (addresses.any { address ->
                when {
                    isSyntheticAddress(address) && syntheticAddressAllowed -> false
                    allowLoopbackForTests && address.isLoopbackAddress -> false
                    else -> !isPublicUnicast(address)
                }
            }
        ) {
            throw AudioUrlPolicyException(AudioUrlFailure.NON_PUBLIC_DNS_TARGET)
        }

        val pinned = addresses.toList()
        return base.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .dns(Dns { requestedHost ->
                if (!requestedHost.equals(host, ignoreCase = true)) {
                    throw UnknownHostException("Host is not part of the validated request")
                }
                pinned
            })
            .build()
    }

    fun isTrustedAudioProxyHost(host: String?): Boolean =
        safeNormalizedHost(host)?.let(trustedCdnHosts::contains) == true

    fun networkClientFor(
        base: OkHttpClient,
        uri: URI,
        allowLoopbackForTests: Boolean,
        networkFactory: DesktopNetworkClientFactory,
        selectedRoute: Proxy? = networkFactory.routeFor(uri),
    ): OkHttpClient = if (selectedRoute == null) {
        pinnedClient(
            networkFactory.clientFor(base, uri, selectedRoute),
            uri,
            allowLoopbackForTests,
        )
    } else {
        networkFactory.clientFor(base, uri, selectedRoute)
    }

    fun prepareRedirect(
        rawUrl: String,
        origin: AudioUrlOrigin,
        allowLoopbackForTests: Boolean,
        requiredHost: String?,
    ): PreparedAudioUrl {
        val prepared = try {
            prepare(rawUrl, origin, allowLoopbackForTests)
        } catch (_: AudioUrlPolicyException) {
            throw AudioUrlPolicyException(AudioUrlFailure.REDIRECT_POLICY_REJECTED)
        }
        val metadata = inspect(rawUrl, origin)
        if (!prepared.loopbackTestOnly && metadata.scheme != AudioUrlScheme.HTTPS) {
            throw AudioUrlPolicyException(AudioUrlFailure.REDIRECT_POLICY_REJECTED)
        }
        if (requiredHost != null && metadata.host != requiredHost) {
            throw AudioUrlPolicyException(AudioUrlFailure.REDIRECT_POLICY_REJECTED)
        }
        return prepared
    }

    private fun invalid(rule: AudioUrlValidationRule, uri: URI? = null) = AudioUrlMetadata(
        parseable = uri != null,
        scheme = AudioUrlScheme.INVALID,
        host = null,
        explicitPort = null,
        effectivePort = null,
        userInfoPresent = uri?.rawUserInfo != null,
        ipLiteral = false,
        validationRule = rule,
    )

    private fun safeNormalizedHost(value: String?): String? = value?.let {
        runCatching { normalizeHost(it) }.getOrNull()
    }

    private fun isLoopbackUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (uri.userInfo != null || uri.rawFragment != null) return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        val host = uri.host?.trim('[', ']') ?: return false
        if (host.equals("localhost", ignoreCase = true)) return true
        if (!isIpLiteral(host)) return false
        return runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
    }

    private fun upgradeAndDropDefaultHttpPort(rawUrl: String, schemeColon: Int): String {
        val authorityStart = schemeColon + 3
        if (authorityStart > rawUrl.length || !rawUrl.regionMatches(schemeColon, "://", 0, 3)) {
            throw AudioUrlPolicyException(AudioUrlFailure.INVALID_URL_FORMAT)
        }
        val authorityEnd = rawUrl.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
            .let { if (it < 0) rawUrl.length else it }
        val authority = rawUrl.substring(authorityStart, authorityEnd)
        if (!authority.endsWith(":80")) {
            throw AudioUrlPolicyException(AudioUrlFailure.HTTPS_UPGRADE_UNAVAILABLE)
        }
        return "https://" + authority.dropLast(3) + rawUrl.substring(authorityEnd)
    }

    private fun normalizeHost(value: String): String = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase()

    private fun isValidDnsName(host: String): Boolean = host.length <= 253 &&
        !host.endsWith('.') && host.contains('.') && host.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.first() != '-' && label.last() != '-' &&
                label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        }

    private fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true
        val labels = host.split('.')
        return labels.size == 4 && labels.all { it.isNotEmpty() && it.all(Char::isDigit) } ||
            host.isNotEmpty() && host.all { it.isDigit() || it == '.' }
    }

    private fun isPublicUnicast(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val bytes = address.address.map { it.toInt() and 0xff }
        return when (address) {
            is Inet4Address -> isPublicIpv4(bytes)
            is Inet6Address -> isGlobalIpv6(bytes)
            else -> false
        }
    }

    /** RFC 2544's benchmarking range is used by some fake-IP resolvers; treat it as a clue only. */
    private fun isSyntheticAddress(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val bytes = address.address.map { it.toInt() and 0xff }
        return bytes.size == 4 && bytes[0] == 198 && bytes[1] in 18..19
    }

    private fun isPublicIpv4(b: List<Int>): Boolean {
        if (b.size != 4) return false
        val a = b[0]
        val second = b[1]
        return when {
            a == 0 || a == 10 || a == 127 || a >= 224 -> false
            a == 100 && second in 64..127 -> false
            a == 169 && second == 254 -> false
            a == 172 && second in 16..31 -> false
            a == 192 && second == 168 -> false
            a == 192 && second == 0 && b[2] == 0 -> false
            a == 192 && second == 0 && b[2] == 2 -> false
            a == 192 && second == 88 && b[2] == 99 -> false
            a == 198 && second in 18..19 -> false
            a == 198 && second == 51 && b[2] == 100 -> false
            a == 203 && second == 0 && b[2] == 113 -> false
            else -> true
        }
    }

    private fun isGlobalIpv6(b: List<Int>): Boolean {
        if (b.size != 16) return false
        // Accept only global-unicast 2000::/3; reject documentation, transition and special ranges.
        if ((b[0] and 0xe0) != 0x20) return false
        if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8) return false
        if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x00 && b[3] == 0x00) return false
        if (b[0] == 0x20 && b[1] == 0x02) return false
        if (b[0] == 0x3f && b[1] == 0xff) return false
        return true
    }

    companion object {
        // Exact hosts observed in authenticated NetEase source API responses. These only enable
        // HTTPS candidate construction; DesktopDownloader probes TLS and Range before transferring.
        private val NETEASE_AUDIO_CDN_HOSTS_REQUIRING_HTTPS_PROBE: Set<String> =
            setOf("m7.music.126.net", "m701.music.126.net", "m801.music.126.net")
    }
}

internal class AudioUrlPolicyException(val failure: AudioUrlFailure) : java.io.IOException(
    when (failure) {
        AudioUrlFailure.INVALID_URL_FORMAT -> "音源地址格式无效。"
        AudioUrlFailure.UNSUPPORTED_URL_SCHEME -> "音源地址使用了不支持的协议。"
        AudioUrlFailure.UNTRUSTED_AUDIO_HOST -> "音源主机未通过安全校验。"
        AudioUrlFailure.HTTPS_UPGRADE_UNAVAILABLE -> "该音源无法安全升级到 HTTPS。"
        AudioUrlFailure.TLS_VALIDATION_FAILED -> "音源 HTTPS 证书校验失败。"
        AudioUrlFailure.REDIRECT_POLICY_REJECTED -> "音源重定向未通过安全校验。"
        AudioUrlFailure.AUDIO_SOURCE_EXPIRED -> "音源地址已过期，请重新解析。"
        AudioUrlFailure.HTTP_TRANSFER_FAILED -> "音源 HTTPS 传输失败。"
        AudioUrlFailure.DNS_RESOLUTION_FAILED -> "系统 DNS 无法解析音源主机。"
        AudioUrlFailure.DNS_SYNTHETIC_ADDRESS_SUSPECTED ->
            "系统 DNS 返回了疑似合成地址 198.18.0.0/15，已拦截；这只是诊断线索，请检查 DNS 和代理路由。"
        AudioUrlFailure.NON_PUBLIC_DNS_TARGET -> "系统 DNS 将音源主机解析到非公网地址，已拦截。"
    },
)
