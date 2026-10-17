package com.qing.hachimi.desktop.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateMirrorTest {
    @Test
    fun `presets are divided by update API and installer support`() {
        val checkIds = UpdateMirrorCatalog.checkPresets.map { it.id }
        val downloadIds = UpdateMirrorCatalog.downloadPresets.map { it.id }

        assertTrue("direct" in checkIds)
        assertTrue("gh-proxy-com" in checkIds)
        assertTrue("gh-proxy-org" in checkIds)
        assertTrue("direct" in downloadIds)
        assertTrue("ghfast-top" in downloadIds)
        assertTrue("ghproxy-net" in downloadIds)
        assertTrue("gh-proxy-com" in downloadIds)
        assertTrue("ghfast-top" !in checkIds)
        assertTrue("ghproxy-net" !in checkIds)
    }

    @Test
    fun `HTTPS template routes only fixed public DMC release endpoints`() {
        val mirror = assertNotNull(UpdateMirrorTemplate.parse("https://mirror.example/proxy/{url}"))
        val api = "https://api.github.com/repos/ChangfengluoO71/desktop-music-candidate/releases?per_page=100".toHttpUrl()
        val installer = "https://github.com/ChangfengluoO71/desktop-music-candidate/releases/download/v0.0.2/DesktopMusicCandidate-0.0.2-Setup.exe".toHttpUrl()

        val routedApi = mirror.route(api, UpdateMirrorUse.RELEASE_CHECK)
        val routedInstaller = mirror.route(installer, UpdateMirrorUse.INSTALLER_DOWNLOAD)

        assertEquals("mirror.example", routedApi.host)
        assertEquals("/proxy/https://api.github.com/repos/ChangfengluoO71/desktop-music-candidate/releases", routedApi.encodedPath)
        assertEquals("100", routedApi.queryParameter("per_page"))
        assertEquals("mirror.example", routedInstaller.host)
        assertTrue(routedInstaller.encodedPath.endsWith("/v0.0.2/DesktopMusicCandidate-0.0.2-Setup.exe"))
    }

    @Test
    fun `blank template means direct and malformed or credentialed templates are rejected`() {
        assertNull(UpdateMirrorTemplate.parse(null))
        assertNull(UpdateMirrorTemplate.parse("  "))
        listOf(
            "http://mirror.example/{url}",
            "https://mirror.example/proxy",
            "https://{url}.mirror.example/proxy",
            "https://user:password@mirror.example/{url}",
            "https://mirror.example/{url}?token=secret",
            "https://mirror.example/{url}#fragment",
            "https://mirror.example/{url}/{url}",
        ).forEach { assertNull(UpdateMirrorTemplate.parse(it), it) }
    }

    @Test
    fun `mirror refuses URLs outside the fixed DMC GitHub API and installer assets`() {
        val mirror = assertNotNull(UpdateMirrorTemplate.parse("https://mirror.example/{url}"))

        assertFailsWith<IllegalArgumentException> {
            mirror.route("https://api.github.com/repos/another-owner/desktop-music-candidate/releases".toHttpUrl(), UpdateMirrorUse.RELEASE_CHECK)
        }
        assertFailsWith<IllegalArgumentException> {
            mirror.route("https://github.com/ChangfengluoO71/desktop-music-candidate/releases/download/v0.0.2/other.exe".toHttpUrl(), UpdateMirrorUse.INSTALLER_DOWNLOAD)
        }
        assertFailsWith<IllegalArgumentException> {
            mirror.route("https://evil.invalid/ChangfengluoO71/desktop-music-candidate/releases".toHttpUrl(), UpdateMirrorUse.RELEASE_CHECK)
        }
    }
}
