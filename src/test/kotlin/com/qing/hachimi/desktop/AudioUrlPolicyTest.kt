package com.qing.hachimi.desktop

import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioUrlPolicyTest {
    @Test
    fun `HTTPS source remains unchanged`() {
        val url = "https://audio.example.net/path/file.mp3?signature=a%2Fb%3Dc"

        val prepared = AudioUrlPolicy().prepare(url, AudioUrlOrigin.NETEASE_SOURCE_API)

        assertEquals(url, prepared.url)
        assertFalse(prepared.upgradedFromHttp)
    }

    @Test
    fun `exact verified CDN host upgrades scheme and preserves signed path and query`() {
        val policy = AudioUrlPolicy(setOf("m801.music.126.net"))
        val original = "http://m801.music.126.net/a%2Fb/song.mp3?auth=one%2Ftwo%3Dthree&br=320000"

        val prepared = policy.prepare(original, AudioUrlOrigin.NETEASE_SOURCE_API)

        assertEquals("https://m801.music.126.net/a%2Fb/song.mp3?auth=one%2Ftwo%3Dthree&br=320000", prepared.url)
        assertTrue(prepared.upgradedFromHttp)
    }

    @Test
    fun `production policy permits only the live observed NetEase CDN host`() {
        val policy = AudioUrlPolicy()

        val prepared = policy.prepare(
            "http://m801.music.126.net/live/song.flac?signature=REDACTED",
            AudioUrlOrigin.NETEASE_SOURCE_API,
        )
        val suffixSpoof = assertFailsWith<AudioUrlPolicyException> {
            policy.prepare(
                "http://m801.music.126.net.evil.example/live/song.flac",
                AudioUrlOrigin.NETEASE_SOURCE_API,
            )
        }

        assertEquals("https://m801.music.126.net/live/song.flac?signature=REDACTED", prepared.url)
        assertTrue(prepared.upgradedFromHttp)
        assertEquals(AudioUrlFailure.UNTRUSTED_AUDIO_HOST, suffixSpoof.failure)
    }

    @Test
    fun `production policy upgrades exact m701 host returned by the source API`() {
        val policy = AudioUrlPolicy()
        val original = "http://m701.music.126.net/a%2Fb/track.flac?signature=test-value&br=999000"

        val prepared = policy.prepare(original, AudioUrlOrigin.NETEASE_SOURCE_API)

        assertEquals("https://m701.music.126.net/a%2Fb/track.flac?signature=test-value&br=999000", prepared.url)
        assertTrue(prepared.upgradedFromHttp)
    }

    @Test
    fun `production policy upgrades exact m7 host observed in source response`() {
        val original = "http://m7.music.126.net/path%2Ftrack.flac?signature=redacted&br=999000"

        val prepared = AudioUrlPolicy().prepare(original, AudioUrlOrigin.NETEASE_SOURCE_API)

        assertEquals("https://m7.music.126.net/path%2Ftrack.flac?signature=redacted&br=999000", prepared.url)
        assertTrue(prepared.upgradedFromHttp)
    }

    @Test
    fun `explicit default HTTP port is removed from HTTPS candidate`() {
        val policy = AudioUrlPolicy(setOf("m801.music.126.net"))

        val prepared = policy.prepare(
            "http://m801.music.126.net:80/path%2Fpart/audio.mp3?sig=a%2Fb",
            AudioUrlOrigin.NETEASE_SOURCE_API,
        )

        assertEquals("https://m801.music.126.net/path%2Fpart/audio.mp3?sig=a%2Fb", prepared.url)
        assertEquals("/path%2Fpart/audio.mp3", URI(prepared.url).rawPath)
        assertEquals("sig=a%2Fb", URI(prepared.url).rawQuery)
    }

    @Test
    fun `HTTP source requires resolver provenance and exact host match`() {
        val policy = AudioUrlPolicy(setOf("m801.music.126.net"))

        val untrustedOrigin = assertFailsWith<AudioUrlPolicyException> {
            policy.prepare("http://m801.music.126.net/audio", AudioUrlOrigin.UNTRUSTED)
        }
        val suffixSpoof = assertFailsWith<AudioUrlPolicyException> {
            policy.prepare("http://m801.music.126.net.evil.example/audio", AudioUrlOrigin.NETEASE_SOURCE_API)
        }

        assertEquals(AudioUrlFailure.UNTRUSTED_AUDIO_HOST, untrustedOrigin.failure)
        assertEquals(AudioUrlFailure.UNTRUSTED_AUDIO_HOST, suffixSpoof.failure)
    }

    @Test
    fun `userinfo IP literals and nondefault ports are rejected`() {
        val policy = AudioUrlPolicy(setOf("m801.music.126.net"))
        val userInfo = assertFailsWith<AudioUrlPolicyException> {
            policy.prepare("http://user@m801.music.126.net/audio", AudioUrlOrigin.NETEASE_SOURCE_API)
        }
        val ipLiteral = assertFailsWith<AudioUrlPolicyException> {
            policy.prepare("http://127.0.0.1/audio", AudioUrlOrigin.NETEASE_SOURCE_API)
        }
        val customPort = assertFailsWith<AudioUrlPolicyException> {
            policy.prepare("http://m801.music.126.net:8080/audio", AudioUrlOrigin.NETEASE_SOURCE_API)
        }

        assertEquals(AudioUrlFailure.UNTRUSTED_AUDIO_HOST, userInfo.failure)
        assertEquals(AudioUrlFailure.UNTRUSTED_AUDIO_HOST, ipLiteral.failure)
        assertEquals(AudioUrlFailure.UNTRUSTED_AUDIO_HOST, customPort.failure)
        assertTrue(policy.inspect("http://127.0.0.1/audio").ipLiteral)
    }

    @Test
    fun `private DNS answers are rejected before connecting`() {
        val privateAddress = InetAddress.getByName("10.1.2.3")
        val policy = AudioUrlPolicy(
            trustedCdnHosts = setOf("m801.music.126.net"),
            dnsLookup = { listOf(privateAddress) },
        )

        val failure = assertFailsWith<AudioUrlPolicyException> {
            policy.networkClientFor(
                OkHttpClient(),
                URI("https://m801.music.126.net/audio"),
                allowLoopbackForTests = false,
                networkFactory = DesktopNetworkClientFactory(),
            )
        }

        assertEquals("NON_PUBLIC_DNS_TARGET", failure.failure.name)
    }

    @Test
    fun `public DNS answers are accepted and pinned`() {
        val publicAddress = InetAddress.getByName("8.8.8.8")
        val policy = AudioUrlPolicy(
            trustedCdnHosts = setOf("m801.music.126.net"),
            dnsLookup = { listOf(publicAddress) },
        )

        val client = policy.networkClientFor(
            OkHttpClient(),
            URI("https://m801.music.126.net/audio"),
            allowLoopbackForTests = false,
            networkFactory = DesktopNetworkClientFactory(),
        )

        assertEquals(listOf(publicAddress), client.dns.lookup("m801.music.126.net"))
    }

    @Test
    fun `198 point 18 DNS mapping is pinned for an exact trusted audio CDN host`() {
        val benchmarkRangeAddress = InetAddress.getByName("198.18.0.10")
        val policy = AudioUrlPolicy(
            trustedCdnHosts = setOf("m801.music.126.net"),
            dnsLookup = { listOf(benchmarkRangeAddress) },
        )

        val client = policy.networkClientFor(
            OkHttpClient(),
            URI("https://m801.music.126.net/audio"),
            allowLoopbackForTests = false,
            networkFactory = DesktopNetworkClientFactory(),
        )

        assertEquals(listOf(benchmarkRangeAddress), client.dns.lookup("m801.music.126.net"))
    }

    @Test
    fun `198 point 18 DNS mapping remains rejected for an unlisted CDN host`() {
        val benchmarkRangeAddress = InetAddress.getByName("198.18.0.10")
        val policy = AudioUrlPolicy(
            trustedCdnHosts = setOf("m801.music.126.net"),
            dnsLookup = { listOf(benchmarkRangeAddress) },
        )

        val failure = assertFailsWith<AudioUrlPolicyException> {
            policy.networkClientFor(
                OkHttpClient(),
                URI("https://m999.music.126.net/audio"),
                allowLoopbackForTests = false,
                networkFactory = DesktopNetworkClientFactory(),
            )
        }

        assertEquals("DNS_SYNTHETIC_ADDRESS_SUSPECTED", failure.failure.name)
    }

    @Test
    fun `DNS resolution failure has a distinct policy error`() {
        val policy = AudioUrlPolicy(
            trustedCdnHosts = setOf("m801.music.126.net"),
            dnsLookup = { throw java.net.UnknownHostException("not exposed") },
        )

        val failure = assertFailsWith<AudioUrlPolicyException> {
            policy.networkClientFor(
                OkHttpClient(),
                URI("https://m801.music.126.net/audio"),
                allowLoopbackForTests = false,
                networkFactory = DesktopNetworkClientFactory(),
            )
        }

        assertEquals("DNS_RESOLUTION_FAILED", failure.failure.name)
    }

    @Test
    fun `malformed and unsupported URL schemes have specific failures`() {
        val policy = AudioUrlPolicy()

        val malformed = assertFailsWith<AudioUrlPolicyException> {
            policy.prepare("https://[broken/audio", AudioUrlOrigin.NETEASE_SOURCE_API)
        }
        val unsupported = assertFailsWith<AudioUrlPolicyException> {
            policy.prepare("ftp://audio.example.net/audio", AudioUrlOrigin.NETEASE_SOURCE_API)
        }

        assertEquals(AudioUrlFailure.INVALID_URL_FORMAT, malformed.failure)
        assertEquals(AudioUrlFailure.UNSUPPORTED_URL_SCHEME, unsupported.failure)
    }
}
