package com.qing.hachimi.desktop.update

import com.qing.hachimi.desktop.DesktopProductIdentity
import com.qing.hachimi.desktop.SemVer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class UpdateMirrorUse {
    RELEASE_CHECK,
    INSTALLER_DOWNLOAD,
}

internal data class UpdateMirrorPreset(
    val id: String,
    val label: String,
    val template: String?,
)

/** Mirrors are separated by capability: several public proxies serve files but reject GitHub API calls. */
internal object UpdateMirrorCatalog {
    private val direct = UpdateMirrorPreset("direct", "官方直连", null)
    private val ghProxyCom = UpdateMirrorPreset("gh-proxy-com", "GH-Proxy (.com)", "https://gh-proxy.com/{url}")
    private val ghProxyOrg = UpdateMirrorPreset("gh-proxy-org", "GH-Proxy 备用域名 (.org)", "https://gh-proxy.org/{url}")
    private val ghFast = UpdateMirrorPreset("ghfast-top", "GHFast (ghfast.top)", "https://ghfast.top/{url}")
    private val ghProxyNet = UpdateMirrorPreset("ghproxy-net", "GHProxy.net (ghproxy.net)", "https://ghproxy.net/{url}")

    val checkPresets: List<UpdateMirrorPreset> = listOf(direct, ghProxyCom, ghProxyOrg)
    val downloadPresets: List<UpdateMirrorPreset> = listOf(direct, ghFast, ghProxyNet, ghProxyCom, ghProxyOrg)
}

/** A strict HTTPS path-prefix template. The mirror only receives fixed public DMC GitHub release URLs. */
internal class UpdateMirrorTemplate private constructor(
    val value: String,
    val host: String,
    val port: Int,
) {
    fun route(original: HttpUrl, use: UpdateMirrorUse): HttpUrl {
        require(isAllowedTarget(original, use)) { "Only the public DMC GitHub release endpoint can use an update mirror" }
        val routed = requireNotNull(value.replace(URL_TOKEN, original.toString()).toHttpUrlOrNull())
        require(
            routed.scheme == "https" && routed.host == host && routed.port == port &&
                routed.username.isEmpty() && routed.password.isEmpty() && routed.fragment == null,
        ) { "The update mirror template produced an unsafe URL" }
        return routed
    }

    companion object {
        private const val URL_TOKEN = "{url}"
        private const val MAX_TEMPLATE_CHARS = 2_048
        private const val REPOSITORY_OWNER = "ChangfengluoO71"
        private const val REPOSITORY_NAME = "desktop-music-candidate"
        private val RELEASES_PATH = "/repos/$REPOSITORY_OWNER/$REPOSITORY_NAME/releases"

        fun parse(raw: String?): UpdateMirrorTemplate? {
            val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
            if (value.length > MAX_TEMPLATE_CHARS || value.any { it.isWhitespace() || it.code < 0x20 || it == '\\' }) return null
            if (value.countOccurrences(URL_TOKEN) != 1 || value.contains('?') || value.contains('#')) return null
            if (!value.startsWith("https://", ignoreCase = true)) return null
            val authorityStart = value.indexOf("://") + 3
            val authorityEnd = value.indexOf('/', authorityStart).takeIf { it >= 0 } ?: value.length
            val tokenIndex = value.indexOf(URL_TOKEN)
            if (tokenIndex < authorityEnd) return null

            val probe = value.replace(URL_TOKEN, "https://api.github.com$RELEASES_PATH")
            val url = probe.toHttpUrlOrNull() ?: return null
            if (url.scheme != "https" || url.host.isBlank() || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.querySize != 0 || url.fragment != null
            ) return null
            return UpdateMirrorTemplate(value, url.host, url.port)
        }

        fun isValid(raw: String?): Boolean = raw.isNullOrBlank() || parse(raw) != null

        private fun isAllowedTarget(url: HttpUrl, use: UpdateMirrorUse): Boolean {
            val common = url.scheme == "https" && url.port == 443 && url.username.isEmpty() &&
                url.password.isEmpty() && url.fragment == null
            if (!common) return false
            return when (use) {
                UpdateMirrorUse.RELEASE_CHECK ->
                    url.host == "api.github.com" && url.encodedPath == RELEASES_PATH &&
                        url.querySize == 1 && url.queryParameter("per_page") == "100"

                UpdateMirrorUse.INSTALLER_DOWNLOAD -> {
                    if (url.host != "github.com" || url.querySize != 0) return false
                    val segments = url.encodedPath.split('/').filter(String::isNotEmpty)
                    if (segments.size != 6 || segments[0] != REPOSITORY_OWNER || segments[1] != REPOSITORY_NAME ||
                        segments[2] != "releases" || segments[3] != "download"
                    ) return false
                    val tag = segments[4]
                    val assetName = segments[5]
                    val prefix = "${DesktopProductIdentity.INSTALLER_NAME}-"
                    val versionText = assetName.removePrefix(prefix).removeSuffix("-Setup.exe")
                    val version = SemVer.parse(versionText) ?: return false
                    assetName == "$prefix$versionText-Setup.exe" && tag in setOf(versionText, "v$version")
                }
            }
        }

        private fun String.countOccurrences(needle: String): Int = windowed(needle.length).count { it == needle }
    }
}
