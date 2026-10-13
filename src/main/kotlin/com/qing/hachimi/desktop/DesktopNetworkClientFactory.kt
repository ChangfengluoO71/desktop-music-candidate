package com.qing.hachimi.desktop

import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** Creates clients that use one explicit route and never silently fall back to direct networking. */
class DesktopNetworkClientFactory(
    private val settings: DesktopNetworkSettings = DesktopNetworkSettings(),
) {
    private val customProxy: Proxy? = if (settings.mode == DesktopNetworkMode.CUSTOM_PROXY) {
        val validationError = settings.validationError()
        require(validationError == null) { validationError ?: "代理设置无效" }
        val address = InetSocketAddress.createUnresolved(settings.proxyHost, settings.proxyPort)
        val type = when (settings.proxyType) {
            DesktopProxyType.HTTP -> Proxy.Type.HTTP
            DesktopProxyType.SOCKS5 -> Proxy.Type.SOCKS
        }
        Proxy(type, address)
    } else {
        null
    }

    private val systemProxySelector: ProxySelector? =
        if (settings.mode == DesktopNetworkMode.SYSTEM_DEFAULT) {
            ProxySelector.getDefault()?.let(::FirstRouteProxySelector)
        } else {
            null
        }

    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder = when (settings.mode) {
        DesktopNetworkMode.DIRECT -> builder.proxy(Proxy.NO_PROXY)
        DesktopNetworkMode.SYSTEM_DEFAULT -> {
            builder.proxy(null)
            systemProxySelector?.let(builder::proxySelector) ?: builder.proxy(Proxy.NO_PROXY)
        }
        DesktopNetworkMode.CUSTOM_PROXY -> builder.proxy(checkNotNull(customProxy))
    }

    /** Returns null only for the selected direct route. */
    fun routeFor(uri: URI): Proxy? = when (settings.mode) {
        DesktopNetworkMode.DIRECT -> null
        DesktopNetworkMode.CUSTOM_PROXY -> checkNotNull(customProxy)
        DesktopNetworkMode.SYSTEM_DEFAULT -> {
            val selected = systemProxySelector
                ?.select(uri)
                ?.firstOrNull()
                ?: Proxy.NO_PROXY
            selected.takeUnless { it.type() == Proxy.Type.DIRECT }
        }
    }

    /** Pins the selected per-URI route, avoiding selector/DNS disagreement. */
    fun clientFor(base: OkHttpClient, uri: URI): OkHttpClient =
        clientFor(base, uri, routeFor(uri))

    fun clientFor(base: OkHttpClient, uri: URI, selectedRoute: Proxy?): OkHttpClient {
        require(uri.isAbsolute) { "A network route requires an absolute URI" }
        return base.newBuilder().proxy(selectedRoute ?: Proxy.NO_PROXY).build()
    }

    private class FirstRouteProxySelector(
        private val delegate: ProxySelector,
    ) : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val selected = delegate.select(uri).firstOrNull()
                ?: throw IllegalStateException("System proxy selector returned no route")
            return listOf(selected)
        }

        // Do not let the system selector turn a failed proxy request into a different route.
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) = Unit
    }
}
