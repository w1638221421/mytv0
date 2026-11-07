package com.lizongying.mytv0.playback

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lizongying.mytv0.SP
import com.lizongying.mytv0.parser.ChannelIdentity

/**
 * 线路记忆：频道 → 上次播放成功的源。
 *
 * 解决的问题：自动切源（或手动换源）之后，一旦重启应用，频道又会从配置里的第一个源
 * 重新开始试；坏源在前时每次开机都要等一轮失败重试才看到画面。
 *
 * 设计要点：
 * - 记忆以「频道 key + 源 id」存储，不依赖配置里的顺序，直播源更新后依然能命中；
 * - 写入条件只有一个：该源**真的出画**（onPlaySuccess），失败源不会被记住；
 * - 只在内存里累积，按间隔落盘 + 退到后台/退出时强制刷盘，避免每次切台都写盘；
 * - 条目数有上限，长期使用不会让 SharedPreferences 无限膨胀；
 * - 关闭「线路记忆」开关时既不写入也不读取，已存数据保留（再次开启即恢复）。
 */
object SourceMemory {
    private const val TAG = "SourceMemory"

    /** 条目上限：按"最近一次使用"淘汰 */
    private const val MAX_ENTRIES = 600

    /** 两次落盘的最小间隔 */
    private const val FLUSH_INTERVAL_MS = 5_000L

    private val gson = Gson()
    private val map = LinkedHashMap<String, String>()
    private var loaded = false
    private var dirty = false
    private var lastFlushAt = 0L

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val json = try {
            if (SP.isInitialized()) SP.sourceMemoryMap else ""
        } catch (_: Exception) {
            ""
        }
        if (json.isEmpty()) return
        try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            val loadedMap: Map<String, String>? = gson.fromJson(json, type)
            loadedMap?.forEach { (k, v) ->
                if (k.isNotEmpty() && v.isNotEmpty()) map[k] = v
            }
        } catch (e: Exception) {
            Log.w(TAG, "load source memory failed")
            map.clear()
        }
    }

    /** 记住某频道当前成功的源地址；开关关闭或数据非法时直接忽略 */
    fun remember(channelKey: String, url: String?) {
        if (!SP.sourceMemory) return
        if (channelKey.isEmpty() || url.isNullOrEmpty()) return
        val sourceId = ChannelIdentity.sourceId(url)
        if (sourceId.isEmpty()) return

        val changed = synchronized(this) {
            ensureLoaded()
            if (map[channelKey] == sourceId) {
                // 命中同一个源：只更新顺序（最近使用），不写盘
                map.remove(channelKey)
                map[channelKey] = sourceId
                false
            } else {
                map.remove(channelKey)
                map[channelKey] = sourceId
                trimLocked()
                true
            }
        }
        if (changed) flushIfDue()
    }

    /**
     * 查询某频道上次成功的源在 [urls] 中的下标；未命中返回 -1。
     * 配置里已经没有这条地址时返回 -1，由调用方回退到默认行为。
     */
    fun indexOf(channelKey: String, urls: List<String>): Int {
        if (!SP.sourceMemory || urls.isEmpty()) return -1
        val sourceId = synchronized(this) {
            ensureLoaded()
            map[channelKey]
        } ?: return -1
        return matchIndex(urls, sourceId)
    }

    /**
     * 纯函数：按源 id 在地址列表里定位。
     * 抽出来是为了让"记忆是否命中"可以被单元测试直接验证，不依赖 SharedPreferences。
     */
    fun matchIndex(urls: List<String>, sourceId: String): Int {
        if (sourceId.isEmpty()) return -1
        return urls.indexOfFirst { ChannelIdentity.sourceId(it) == sourceId }
    }

    /** 该频道上次成功的源地址；未命中返回 null */
    fun urlOf(channelKey: String, urls: List<String>): String? {
        val index = indexOf(channelKey, urls)
        return if (index >= 0) urls[index] else null
    }

    /** 强制落盘（退出 / 切后台 / ViewModel 清理时调用） */
    @Synchronized
    fun flush() {
        if (!dirty) return
        val json = try {
            gson.toJson(map)
        } catch (e: Exception) {
            Log.w(TAG, "serialize source memory failed")
            return
        }
        try {
            if (SP.isInitialized()) {
                SP.sourceMemoryMap = json
                dirty = false
                lastFlushAt = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Log.w(TAG, "save source memory failed")
        }
    }

    private fun flushIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastFlushAt < FLUSH_INTERVAL_MS) return
        flush()
    }

    @Synchronized
    private fun trimLocked() {
        if (map.size <= MAX_ENTRIES) return
        val iterator = map.entries.iterator()
        while (map.size > MAX_ENTRIES && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    /** 测试与"恢复默认"使用 */
    @Synchronized
    fun clear() {
        map.clear()
        dirty = true
        flush()
    }

    @Synchronized
    fun size(): Int {
        ensureLoaded()
        return map.size
    }
}
