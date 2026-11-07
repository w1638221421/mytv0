package com.lizongying.mytv0.server

/**
 * 固定窗口限流器（按 key，一般为客户端 IP）。
 *
 * 目的：防止局域网内其他设备对本机配置接口进行暴力试探或高频写操作。
 * 复杂度 O(1)，key 数量有上限，避免被大量伪造来源撑爆内存。
 */
class RateLimiter(
    private val maxRequests: Int,
    private val windowMillis: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxKeys: Int = 512,
) {
    private class Window(var start: Long, var count: Int)

    private val windows = HashMap<String, Window>()

    @Synchronized
    fun allow(key: String): Boolean {
        val now = clock()
        if (windows.size > maxKeys) cleanup(now)
        if (windows.size > maxKeys) windows.clear()

        val w = windows.getOrPut(key) { Window(now, 0) }
        if (now - w.start >= windowMillis) {
            w.start = now
            w.count = 0
        }
        if (w.count >= maxRequests) return false
        w.count++
        return true
    }

    @Synchronized
    fun reset(key: String) {
        windows.remove(key)
    }

    @Synchronized
    fun size(): Int = windows.size

    private fun cleanup(now: Long) {
        val iterator = windows.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.start >= windowMillis) iterator.remove()
        }
    }

    companion object {
        const val DEFAULT_MAX_REQUESTS = 120
        const val DEFAULT_WINDOW_MILLIS = 60_000L
        const val WRITE_MAX_REQUESTS = 20
    }
}
