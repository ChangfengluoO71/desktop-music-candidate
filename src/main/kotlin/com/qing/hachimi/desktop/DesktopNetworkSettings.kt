package com.qing.hachimi.desktop

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress

enum class DesktopNetworkMode {
    DIRECT,
    SYSTEM_DEFAULT,
    CUSTOM_PROXY,
}

enum class DesktopProxyType {
    HTTP,
    SOCKS5,
}

/** Persistable network preferences. Proxy credentials are intentionally unsupported. */
data class DesktopNetworkSettings(
    val mode: DesktopNetworkMode = DesktopNetworkMode.DIRECT,
    val proxyType: DesktopProxyType = DesktopProxyType.HTTP,
    val proxyHost: String = "",
    val proxyPort: Int = 7890,
) {
    /** Returns a user-readable validation error only when a custom proxy is selected. */
    fun validationError(): String? {
        if (mode != DesktopNetworkMode.CUSTOM_PROXY) return null
        if (!isValidProxyHost(proxyHost)) return "请输入不含协议、路径或认证信息的代理主机名或 IP 地址。"
        if (proxyPort !in 1..65_535) return "代理端口必须在 1 到 65535 之间。"
        return null
    }

    private fun isValidProxyHost(value: String): Boolean {
        if (value.isBlank() || value != value.trim() || value.any { it.isWhitespace() }) return false
        if (value.any { it in "/\\?#@%" }) return false

        if (':' in value) {
            if (!value.matches(Regex("(?i)^[0-9a-f:.]+$"))) return false
            return runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)
        }

        val ascii = runCatching {
            IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase()
        }.getOrNull() ?: return false
        if (ascii.length > 253) return false
        return ascii.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 && label.first() != '-' && label.last() != '-' &&
                label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        }
    }
}
