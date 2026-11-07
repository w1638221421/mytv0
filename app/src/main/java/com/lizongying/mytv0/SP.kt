package com.lizongying.mytv0


import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.Global.typeSourceList
import com.lizongying.mytv0.data.Source
import io.github.lizongying.Gua

/**
 * 配置存储。
 *
 * 本次改造点：
 * 1. 收藏（like）改为**线程安全**：所有读写都复制集合，避免 SharedPreferences
 *    返回同一个 StringSet 实例被并发修改导致数据丢失；
 * 2. 收藏改用稳定频道 key（见 ChannelIdentity），升级时从旧的序号收藏自动迁移；
 * 3. 收藏写入使用 commit()，保证进程被立即杀死时也不丢数据；
 * 4. 新增线路记忆（记住每个频道上次成功的播放源）、配置更新时间 / 结果 / 版本号，
 *    供直播源回滚与问题诊断使用；
 * 5. 保留全部旧 key 与默认值，不清理、不覆盖用户既有数据。
 */
object SP {
    private const val TAG = "SP"

    // If Change channel with up and down in reversed order or not
    private const val KEY_CHANNEL_REVERSAL = "channel_reversal"

    // If use channel num to select channel or not
    private const val KEY_CHANNEL_NUM = "channel_num"

    private const val KEY_TIME = "time"

    // If start app on device boot or not
    private const val KEY_BOOT_STARTUP = "boot_startup"

    // Position in list of the selected channel item
    private const val KEY_POSITION = "position"

    private const val KEY_POSITION_GROUP = "position_group"

    private const val KEY_POSITION_SUB = "position_sub"

    private const val KEY_REPEAT_INFO = "repeat_info"

    private const val KEY_CONFIG_URL = "config"

    private const val KEY_CONFIG_AUTO_LOAD = "config_auto_load"

    private const val KEY_CHANNEL = "channel"

    private const val KEY_DEFAULT_LIKE = "default_like"

    private const val KEY_DISPLAY_SECONDS = "display_seconds"

    private const val KEY_SHOW_ALL_CHANNELS = "show_all_channels"

    private const val KEY_COMPACT_MENU = "compact_menu"

    private const val KEY_LIKE = "like"

    private const val KEY_LIKE_VERSION = "like_version"

    private const val KEY_PROXY = "proxy"

    private const val KEY_EPG = "epg"

    private const val KEY_EPG_FAIL_STREAK = "epg_fail_streak"

    private const val KEY_EPG_BACKOFF_UNTIL = "epg_backoff_until"

    private const val KEY_VERSION = "version"

    private const val KEY_LOG_TIMES = "log_times"

    private const val KEY_SOURCES = "sources"

    private const val KEY_SOFT_DECODE = "soft_decode"

    // 播放失败时是否自动切换到备用源（默认开启；关闭后只在当前地址上有限重试）
    private const val KEY_AUTO_SWITCH_SOURCE = "auto_switch_source"

    // ---- 远程配置 / 线路记忆 / 配置更新状态 ----
    private const val KEY_SOURCE_MEMORY = "source_memory"

    private const val KEY_SOURCE_MEMORY_MAP = "source_memory_map"

    private const val KEY_LAST_UPDATE_TIME = "last_update_time"

    private const val KEY_LAST_UPDATE_RESULT = "last_update_result"

    private const val KEY_CONFIG_VERSION = "config_version"

    private const val KEY_SOURCE_STATS = "source_stats"

    const val DEFAULT_CHANNEL_REVERSAL = false
    const val DEFAULT_CHANNEL_NUM = false
    const val DEFAULT_TIME = true
    const val DEFAULT_BOOT_STARTUP = false
    const val DEFAULT_CONFIG_URL = "https://g.w163.de5.net/mytv.txt/wy163"
    const val DEFAULT_PROXY = ""
    /**
     * 内置节目单地址（逗号分隔，按顺序尝试，第一个拿到数据即停）：
     * 1. open-epg 中国区 XMLTV：国内可直连，含当天节目；
     * 2. 老张的 EPG（51zmt）：国内电信线路，纯 http:8000；
     * 3. fanmingming/live：走 GitHub 加速镜像（由 Utils.getUrls 展开）。
     *
     * 旧默认值 live.fanmingming.cn 域名已到期（解析到 2a03:2880:...，非自有主机），
     * 且 raw.githubusercontent.com 国内不可达，因此原来"节目单永远空"。
     */
    const val DEFAULT_EPG =
        "https://www.open-epg.com/files/china1.xml," +
            "http://epg.51zmt.top:8000/e.xml," +
            "https://raw.githubusercontent.com/fanmingming/live/main/e.xml"

    /** 旧内置节目单地址，仅用于升级迁移 */
    const val LEGACY_DEFAULT_EPG =
        "https://live.fanmingming.cn/e.xml,https://raw.githubusercontent.com/fanmingming/live/main/e.xml"
    const val DEFAULT_CHANNEL = 0
    const val DEFAULT_SHOW_ALL_CHANNELS = false
    const val DEFAULT_COMPACT_MENU = true
    const val DEFAULT_DISPLAY_SECONDS = true
    const val DEFAULT_LOG_TIMES = 10
    const val DEFAULT_SOFT_DECODE = false

    const val DEFAULT_AUTO_SWITCH_SOURCE = true

    /** 线路记忆默认开启：自动切源后，下次启动继续用上次播放成功的源 */
    const val DEFAULT_SOURCE_MEMORY = true

    // 0 favorite; 1 all
    const val DEFAULT_POSITION_GROUP = 1
    const val DEFAULT_POSITION = 0
    const val DEFAULT_REPEAT_INFO = true
    const val DEFAULT_CONFIG_AUTO_LOAD = true
    var DEFAULT_SOURCES = ""

    const val LIKE_VERSION = 2

    private lateinit var sp: SharedPreferences

    private val likeLock = Any()

    /**
     * The method must be invoked as early as possible(At least before using the keys)
     */
    fun init(context: Context) {
        sp = context.getSharedPreferences(
            context.getString(R.string.app_name),
            Context.MODE_PRIVATE
        )

        try {
            context.resources.openRawResource(R.raw.sources).bufferedReader()
                .use {
                    val str = it.readText()
                    if (str.isNotEmpty()) {
                        DEFAULT_SOURCES = gson.toJson(
                            Gua().decode(str).trim().split("\n").map { i ->
                                Source(
                                    uri = i
                                )
                            }, typeSourceList
                        ) ?: ""
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "init default sources", e)
        }

        migrateEpgIfNeeded()

        Log.i(TAG, "group position $positionGroup")
        Log.i(TAG, "list position $position")
        Log.i(TAG, "default channel $channel")
        Log.i(TAG, "proxy $proxy")
    }

    fun isInitialized(): Boolean = this::sp.isInitialized

    /** 旧内置节目单地址已失效：升级时自动换成新默认；用户自己填过的地址不动 */
    private fun migrateEpgIfNeeded() {
        try {
            if (epg == LEGACY_DEFAULT_EPG) {
                epg = DEFAULT_EPG
                Log.i(TAG, "default EPG migrated")
            }
        } catch (e: Exception) {
            Log.w(TAG, "migrate EPG", e)
        }
    }

    var channelReversal: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_REVERSAL, DEFAULT_CHANNEL_REVERSAL)
        set(value) = sp.edit().putBoolean(KEY_CHANNEL_REVERSAL, value).apply()

    var channelNum: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_NUM, DEFAULT_CHANNEL_NUM)
        set(value) = sp.edit().putBoolean(KEY_CHANNEL_NUM, value).apply()

    var time: Boolean
        get() = sp.getBoolean(KEY_TIME, DEFAULT_TIME)
        set(value) = sp.edit().putBoolean(KEY_TIME, value).apply()

    var bootStartup: Boolean
        get() = sp.getBoolean(KEY_BOOT_STARTUP, DEFAULT_BOOT_STARTUP)
        set(value) = sp.edit().putBoolean(KEY_BOOT_STARTUP, value).apply()

    var positionGroup: Int
        get() = sp.getInt(KEY_POSITION_GROUP, DEFAULT_POSITION_GROUP)
        set(value) = sp.edit().putInt(KEY_POSITION_GROUP, value).apply()

    var position: Int
        get() = sp.getInt(KEY_POSITION, DEFAULT_POSITION)
        set(value) = sp.edit().putInt(KEY_POSITION, value).apply()

    var positionSub: Int
        get() = sp.getInt(KEY_POSITION_SUB, 0)
        set(value) = sp.edit().putInt(KEY_POSITION_SUB, value).apply()

    var repeatInfo: Boolean
        get() = sp.getBoolean(KEY_REPEAT_INFO, DEFAULT_REPEAT_INFO)
        set(value) = sp.edit().putBoolean(KEY_REPEAT_INFO, value).apply()

    var configUrl: String?
        get() = sp.getString(KEY_CONFIG_URL, DEFAULT_CONFIG_URL)
        set(value) = sp.edit().putString(KEY_CONFIG_URL, value).apply()

    var configAutoLoad: Boolean
        get() = sp.getBoolean(KEY_CONFIG_AUTO_LOAD, DEFAULT_CONFIG_AUTO_LOAD)
        set(value) = sp.edit().putBoolean(KEY_CONFIG_AUTO_LOAD, value).apply()

    var channel: Int
        get() = sp.getInt(KEY_CHANNEL, DEFAULT_CHANNEL)
        set(value) = sp.edit().putInt(KEY_CHANNEL, value).apply()

    var compactMenu: Boolean
        get() = sp.getBoolean(KEY_COMPACT_MENU, DEFAULT_COMPACT_MENU)
        set(value) = sp.edit().putBoolean(KEY_COMPACT_MENU, value).apply()

    var showAllChannels: Boolean
        get() = sp.getBoolean(KEY_SHOW_ALL_CHANNELS, DEFAULT_SHOW_ALL_CHANNELS)
        set(value) = sp.edit().putBoolean(KEY_SHOW_ALL_CHANNELS, value).apply()

    var defaultLike: Boolean
        get() = sp.getBoolean(KEY_DEFAULT_LIKE, false)
        set(value) = sp.edit().putBoolean(KEY_DEFAULT_LIKE, value).apply()

    var displaySeconds: Boolean
        get() = sp.getBoolean(KEY_DISPLAY_SECONDS, DEFAULT_DISPLAY_SECONDS)
        set(value) = sp.edit().putBoolean(KEY_DISPLAY_SECONDS, value).apply()

    var softDecode: Boolean
        get() = sp.getBoolean(KEY_SOFT_DECODE, DEFAULT_SOFT_DECODE)
        set(value) = sp.edit().putBoolean(KEY_SOFT_DECODE, value).apply()

    /** 播放失败后是否自动尝试备用源 */
    var autoSwitchSource: Boolean
        get() = sp.getBoolean(KEY_AUTO_SWITCH_SOURCE, DEFAULT_AUTO_SWITCH_SOURCE)
        set(value) = sp.edit().putBoolean(KEY_AUTO_SWITCH_SOURCE, value).apply()

    /** 线路记忆：记住每个频道上次播放成功的源，下次启动沿用 */
    var sourceMemory: Boolean
        get() = sp.getBoolean(KEY_SOURCE_MEMORY, DEFAULT_SOURCE_MEMORY)
        set(value) = sp.edit().putBoolean(KEY_SOURCE_MEMORY, value).apply()

    /** 线路记忆数据：{"频道key":"源id"} 的 JSON，由 SourceMemory 维护 */
    var sourceMemoryMap: String
        get() = sp.getString(KEY_SOURCE_MEMORY_MAP, "") ?: ""
        set(value) {
            // commit：线路记忆直接影响下次开机的第一个画面，必须同步落盘
            sp.edit().putString(KEY_SOURCE_MEMORY_MAP, value).commit()
        }

    var lastUpdateTime: Long
        get() = sp.getLong(KEY_LAST_UPDATE_TIME, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_UPDATE_TIME, value).apply()

    var lastUpdateResult: String
        get() = sp.getString(KEY_LAST_UPDATE_RESULT, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_UPDATE_RESULT, value).apply()

    var configVersion: Int
        get() = sp.getInt(KEY_CONFIG_VERSION, 0)
        set(value) = sp.edit().putInt(KEY_CONFIG_VERSION, value).apply()

    // ------------------------------------------------------------ 收藏

    fun getLike(key: String): Boolean {
        if (key.isEmpty()) return false
        return synchronized(likeLock) {
            sp.getStringSet(KEY_LIKE, emptySet())?.contains(key) ?: false
        }
    }

    fun setLike(key: String, liked: Boolean) {
        if (key.isEmpty()) return
        synchronized(likeLock) {
            val current = sp.getStringSet(KEY_LIKE, emptySet())?.toMutableSet() ?: mutableSetOf()
            if (liked) current.add(key) else current.remove(key)
            // commit：收藏是用户数据，必须同步落盘
            sp.edit().putStringSet(KEY_LIKE, current).commit()
        }
    }

    fun getLikes(): Set<String> = synchronized(likeLock) {
        sp.getStringSet(KEY_LIKE, emptySet())?.toSet() ?: emptySet()
    }

    fun setLikes(keys: Set<String>) {
        synchronized(likeLock) {
            sp.edit().putStringSet(KEY_LIKE, keys.toSet()).commit()
        }
    }

    fun deleteLike() {
        synchronized(likeLock) {
            sp.edit().remove(KEY_LIKE).commit()
        }
    }

    var likeVersion: Int
        get() = sp.getInt(KEY_LIKE_VERSION, 1)
        set(value) = sp.edit().putInt(KEY_LIKE_VERSION, value).apply()

    // ------------------------------------------------------------ 播放源统计

    var sourceStats: String
        get() = sp.getString(KEY_SOURCE_STATS, "") ?: ""
        set(value) = sp.edit().putString(KEY_SOURCE_STATS, value).apply()

    var proxy: String?
        get() = sp.getString(KEY_PROXY, DEFAULT_PROXY)
        set(value) = sp.edit().putString(KEY_PROXY, value).apply()

    var epg: String?
        get() = sp.getString(KEY_EPG, DEFAULT_EPG)
        set(value) = sp.edit().putString(KEY_EPG, value).apply()

    /** EPG 连续拉取失败次数（用于退避，成功即清零） */
    var epgFailStreak: Int
        get() = sp.getInt(KEY_EPG_FAIL_STREAK, 0)
        set(value) = sp.edit().putInt(KEY_EPG_FAIL_STREAK, value).apply()

    /** 在该时间戳之前不再尝试自动拉取 EPG */
    var epgBackoffUntil: Long
        get() = sp.getLong(KEY_EPG_BACKOFF_UNTIL, 0L)
        set(value) = sp.edit().putLong(KEY_EPG_BACKOFF_UNTIL, value).apply()

    var version: String?
        get() = sp.getString(KEY_VERSION, "")
        set(value) = sp.edit().putString(KEY_VERSION, value).apply()

    var logTimes: Int
        get() = sp.getInt(KEY_LOG_TIMES, DEFAULT_LOG_TIMES)
        set(value) = sp.edit().putInt(KEY_LOG_TIMES, value).apply()

    var sources: String?
        get() = sp.getString(KEY_SOURCES, DEFAULT_SOURCES)
        set(value) = sp.edit().putString(KEY_SOURCES, value).apply()
}
