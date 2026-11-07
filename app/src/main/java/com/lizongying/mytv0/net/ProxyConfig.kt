package com.lizongying.mytv0.net

import java.net.InetSocketAddress
import java.net.Proxy

/**
 * 代理配置解析（纯 Kotlin/JVM，便于单元测试）。
 *
 * 支持格式：
 *   host:port
 *   http://host:port
 *   https://host:port
 *   socks5://host:port
 *   socks://user:pass@host:port   （凭据会被忽略，不进入日志）
 */
data class ProxySpec(
    val type: Proxy.Type,
    val host: String,
    val port: Int,
) {
    fun toProxy(): Proxy = Proxy(type, InetSocketAddress.createUnresolved(host, port))

    /** 不含任何凭据，可安全写入日志 */
    override fun toString(): String =
        "${if (type == Proxy.Type.SOCKS) "socks5" else "http"}://$host:$port"
}

object ProxyConfig {

    fun parse(raw: String?): ProxySpec? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        if (s.isEmpty()) return null

        var scheme = ""
        val schemeIdx = s.indexOf("://")
        if (schemeIdx > 0) {
            scheme = s.substring(0, schemeIdx).lowercase()
            s = s.substring(schemeIdx + 3)
        }

        // 去掉路径与查询
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        // 去掉 user:password@
        val at = s.lastIndexOf('@')
        if (at >= 0) s = s.substring(at + 1)
        if (s.isEmpty()) return null

        val host: String
        val portText: String?
        if (s.startsWith("[")) {
            // IPv6 字面量 [::1]:8080
            val end = s.indexOf(']')
            if (end <= 1) return null
            host = s.substring(1, end)
            portText = s.substring(end + 1).removePrefix(":").ifEmpty { null }
        } else {
            val colon = s.lastIndexOf(':')
            if (colon > 0 && s.indexOf(':') == colon) {
                host = s.substring(0, colon)
                portText = s.substring(colon + 1)
            } else {
                host = s
                portText = null
            }
        }

        if (host.isEmpty() || host.any { it.isWhitespace() }) return null

        val type = when (scheme) {
            "socks", "socks4", "socks4a", "socks5", "socks5h" -> Proxy.Type.SOCKS
            "http", "https", "" -> Proxy.Type.HTTP
            else -> return null
        }

        val defaultPort = if (type == Proxy.Type.SOCKS) 1080 else 8080
        val port = portText?.toIntOrNull() ?: defaultPort
        if (port !in 1..65535) return null

        return ProxySpec(type, host, port)
    }
}
