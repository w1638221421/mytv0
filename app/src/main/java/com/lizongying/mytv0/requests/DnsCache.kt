package com.lizongying.mytv0.requests

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 带 TTL 的 DNS 缓存。
 *
 * 旧实现永久缓存、无上限、无失败缓存：
 * - 直播源域名切换 IP 后永远解析到旧地址；
 * - 大量不同域名（EPG/台标/直播源）会让 Map 无限增长；
 * - DNS 失败时会反复同步解析，拖慢切台。
 *
 * 新实现：
 * - 正缓存 5 分钟，负缓存 30 秒；
 * - 最多缓存 256 个域名，超出后清理过期项；
 * - IPv4 优先（部分电视盒子 IPv6 路由不通，导致首帧超时）；
 * - 空 hostname 直接交给系统解析，不缓存。
 */
class DnsCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val negativeTtlMillis: Long = DEFAULT_NEGATIVE_TTL_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : Dns {

    private class Entry(val addresses: List<InetAddress>, val expireAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isEmpty()) {
            return Dns.SYSTEM.lookup(hostname)
        }

        cache[hostname]?.let {
            if (it.expireAt > clock()) return it.addresses
            cache.remove(hostname)
        }

        val addresses = try {
            val all = InetAddress.getAllByName(hostname)
            val ipv4 = ArrayList<InetAddress>(all.size)
            val ipv6 = ArrayList<InetAddress>(all.size)
            for (a in all) {
                when (a) {
                    is Inet4Address -> ipv4.add(a)
                    is Inet6Address -> ipv6.add(a)
                    else -> ipv4.add(a)
                }
            }
            ipv4 + ipv6
        } catch (e: Exception) {
            // 负缓存：避免同一域名的失败解析在短时间内反复触发
            put(hostname, emptyList(), negativeTtlMillis)
            throw e
        }

        if (addresses.isNotEmpty()) {
            put(hostname, addresses, ttlMillis)
        }
        return addresses
    }

    private fun put(hostname: String, addresses: List<InetAddress>, ttl: Long) {
        if (cache.size >= maxEntries) {
            val now = clock()
            val iterator = cache.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value.expireAt <= now) iterator.remove()
            }
            if (cache.size >= maxEntries) cache.clear()
        }
        cache[hostname] = Entry(addresses, clock() + ttl)
    }

    fun clear() {
        cache.clear()
    }

    fun size(): Int = cache.size

    companion object {
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L
        const val DEFAULT_NEGATIVE_TTL_MILLIS = 30 * 1000L
        const val DEFAULT_MAX_ENTRIES = 256
    }
}
