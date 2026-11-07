package com.lizongying.mytv0.playback

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lizongying.mytv0.parser.ChannelIdentity

/**
 * 播放源成功率记忆。
 *
 * - 记录每个地址最近成功 / 失败时间与失败次数；
 * - 只保存在内存 + 单个 SharedPreferences 字符串中（定时批量落盘），
 *   不写回频道配置文件，避免频繁 IO 与破坏用户配置；
 * - 条目数量有上限，长期使用不会无限增长。
 */
class SourceStats(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val gson: Gson = Gson(),
) {
    data class Stat(
        var lastSuccess: Long = 0L,
        var lastFailure: Long = 0L,
        var failCount: Int = 0,
        var successCount: Int = 0,
    )

    private val map = HashMap<String, Stat>()

    @Synchronized
    fun recordSuccess(url: String) {
        if (url.isEmpty()) return
        val key = ChannelIdentity.sourceId(url)
        if (key.isEmpty()) return
        val stat = map.getOrPut(key) { Stat() }
        stat.lastSuccess = clock()
        stat.failCount = 0
        stat.successCount++
    }

    @Synchronized
    fun recordFailure(url: String) {
        if (url.isEmpty()) return
        val key = ChannelIdentity.sourceId(url)
        if (key.isEmpty()) return
        val stat = map.getOrPut(key) { Stat() }
        stat.lastFailure = clock()
        stat.failCount++
    }

    @Synchronized
    fun lastSuccess(url: String): Long = map[ChannelIdentity.sourceId(url)]?.lastSuccess ?: 0L

    @Synchronized
    fun failCount(url: String): Int = map[ChannelIdentity.sourceId(url)]?.failCount ?: 0

    @Synchronized
    fun reset(url: String) {
        map.remove(ChannelIdentity.sourceId(url))
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun toJson(): String = try {
        trimLocked()
        gson.toJson(map)
    } catch (_: Exception) {
        ""
    }

    @Synchronized
    fun load(json: String?) {
        if (json.isNullOrEmpty()) return
        try {
            val type = object : TypeToken<Map<String, Stat>>() {}.type
            val loaded: Map<String, Stat>? = gson.fromJson(json, type)
            map.clear()
            loaded?.forEach { (k, v) -> if (k.isNotEmpty() && v != null) map[k] = v }
        } catch (_: Exception) {
            // 统计信息损坏直接丢弃，不影响播放
            map.clear()
        }
    }

    private fun trimLocked() {
        if (map.size <= maxEntries) return
        val sorted = map.entries.sortedByDescending { it.value.lastSuccess }
        map.clear()
        for (e in sorted.take(maxEntries)) {
            map[e.key] = e.value
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 3000
    }
}
