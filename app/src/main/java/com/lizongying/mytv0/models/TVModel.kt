package com.lizongying.mytv0.models

import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.lizongying.mytv0.SP
import com.lizongying.mytv0.data.EPG
import com.lizongying.mytv0.data.SourceType
import com.lizongying.mytv0.data.TV
import com.lizongying.mytv0.data.TVSource
import com.lizongying.mytv0.playback.FailureKind
import com.lizongying.mytv0.playback.SourceMemory
import com.lizongying.mytv0.playback.SourceStats
import com.lizongying.mytv0.requests.HttpClient
import kotlin.math.max
import kotlin.math.min

/**
 * 单个频道的播放模型。
 *
 * 与旧实现相比：
 * - 每次构建 MediaSource 都重新生成 DataSource，避免"换了地址却仍在用旧请求头"；
 * - 播放源顺序按 优先级 → 最近成功时间 → 原始顺序 排序；
 * - 每个频道独立的"线路记忆"（SourceMemory）：重启后仍从上次出画的源开始播；
 * - 明确区分"地址"（uris）与"地址元数据"（sources），支持每地址独立请求头；
 * - 重试计数与当前地址 / 类型索引解耦，由播放控制器统一驱动。
 */
class TVModel(var tv: TV) : ViewModel() {
    var retryTimes = 0

    /** 最近一次播放失败的类型；出画后清空。启动断网宽限期据此判断要不要继续重试 */
    var lastFailureKind: FailureKind? = null
    var retryMaxTimes = 10
    var programUpdateTime = 0L

    private var _groupIndex = 0
    val groupIndex: Int
        get() = if (SP.showAllChannels || _groupIndex == 0) _groupIndex else _groupIndex - 1

    fun setGroupIndex(index: Int) {
        _groupIndex = index
    }

    fun getGroupIndexInAll(): Int {
        return _groupIndex
    }

    var listIndex = 0

    private var sourceTypeList: List<SourceType> = listOf(SourceType.UNKNOWN)
    private var sourceTypeIndex = 0

    private val _errInfo = MutableLiveData<String>()
    val errInfo: LiveData<String>
        get() = _errInfo

    fun setErrInfo(info: String) {
        // MutableLiveData.setValue 必须在主线程；此处做保护，避免后台线程调用崩溃
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            _errInfo.value = info
        } else {
            _errInfo.postValue(info)
        }
    }

    private var _epg = MutableLiveData<List<EPG>>()
    val epg: LiveData<List<EPG>>
        get() = _epg
    val epgValue: List<EPG>
        get() = _epg.value ?: emptyList()

    fun setEpg(epg: List<EPG>) {
        _epg.value = epg
    }

    private val _videoIndex = MutableLiveData<Int>()
    val videoIndex: LiveData<Int>
        get() = _videoIndex
    val videoIndexValue: Int
        get() = _videoIndex.value ?: 0

    fun getVideoUrl(): String? {
        if (tv.uris.isEmpty()) return null
        val index = videoIndexValue
        if (index < 0 || index >= tv.uris.size) return null
        return tv.uris[index].takeIf { it.isNotEmpty() }
    }

    private val _like = MutableLiveData<Boolean>()
    val like: LiveData<Boolean>
        get() = _like

    fun setLike(liked: Boolean) {
        _like.value = liked
    }

    private val _ready = MutableLiveData<Boolean>()
    val ready: LiveData<Boolean>
        get() = _ready

    fun setReady(retry: Boolean = false) {
        if (!retry) {
            setErrInfo("")
            retryTimes = 0
            restoreRememberedSource()
            reorderSources()
            // reorder 会按"最近成功"重排并保持当前地址，这里以 URL 为准回写索引，
            // 避免下次 setReady 用过期下标把频道切到别的源
            tv.videoIndex = max(0, min(tv.uris.size - 1, videoIndexValue))
            _videoIndex.value = tv.videoIndex
            sourceTypeIndex =
                max(0, min(sourceTypeList.size - 1, sourceTypeList.indexOf(tv.sourceType)))
        }
        _ready.value = true
    }

    /**
     * 线路记忆：把播放地址切回该频道上次播放成功的源。
     *
     * 匹配依据是 URL 的稳定摘要，配置里源的顺序变了也能命中；
     * 记忆的地址已经不在配置中时保持原有行为（默认第一个源）。
     */
    private fun restoreRememberedSource() {
        if (!SP.sourceMemory) return
        val index = SourceMemory.indexOf(tv.key, tv.uris)
        if (index < 0 || index == videoIndexValue) return
        tv.videoIndex = index
        _videoIndex.value = index
        // 地址变了，封装类型与请求头需要重新推断
        sourceTypeList = listOf(SourceType.UNKNOWN)
        sourceTypeIndex = 0
        preparedUrl = null
    }

    private var userAgent = ""
    private var preparedUrl: String? = null

    // 兼容旧接口：保留最近一次构建结果
    private var _httpDataSource: OkHttpDataSource.Factory? = null
    private var _mediaItem: MediaItem? = null

    private fun headersFor(url: String): Map<String, String>? {
        val index = tv.uris.indexOf(url)
        if (index >= 0) {
            tv.sources?.getOrNull(index)?.headers?.let { if (it.isNotEmpty()) return it }
        }
        return tv.headers?.takeIf { it.isNotEmpty() }
    }

    @OptIn(UnstableApi::class)
    fun buildMediaSource(): MediaSource? {
        val url = getVideoUrl() ?: return null
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        val path = uri.path?.lowercase() ?: ""

        // 根据地址推断候选类型；显式声明的类型优先。
        // 同一地址在"重试 / 切换封装类型"期间必须保留当前类型索引，否则会永远回到第 0 项。
        if (preparedUrl != url) {
            val candidates = when {
                path.endsWith(".m3u8") || url.contains(".m3u8", true) -> listOf(SourceType.HLS)
                path.endsWith(".mpd") || url.contains(".mpd", true) -> listOf(SourceType.DASH)
                scheme == "rtsp" -> listOf(SourceType.RTSP)
                scheme == "rtmp" || scheme == "rtmps" -> listOf(SourceType.RTMP)
                scheme == "rtp" || scheme == "udp" -> listOf(SourceType.RTP)
                path.endsWith(".mp4") || path.endsWith(".flv") || path.endsWith(".ts") ||
                        path.endsWith(".mkv") || path.endsWith(".m4v") ->
                    listOf(SourceType.PROGRESSIVE)

                else -> listOf(SourceType.HLS, SourceType.PROGRESSIVE)
            }
            sourceTypeList = if (tv.sourceType != SourceType.UNKNOWN) {
                (listOf(tv.sourceType) + candidates).distinct()
            } else {
                candidates
            }
            sourceTypeIndex = 0
            preparedUrl = url
        }

        val headers = headersFor(url)
        userAgent = ""
        headers?.forEach { (key, value) ->
            if (key.equals("user-agent", ignoreCase = true)) userAgent = value
        }

        val mediaItem = MediaItem.fromUri(uri)
        _mediaItem = mediaItem

        return when (getSourceTypeCurrent()) {
            SourceType.HLS -> {
                val factory = httpDataSource(headers)
                HlsMediaSource.Factory(factory).createMediaSource(mediaItem)
            }

            SourceType.DASH -> {
                val factory = httpDataSource(headers)
                DashMediaSource.Factory(factory).createMediaSource(mediaItem)
            }

            SourceType.PROGRESSIVE -> {
                val factory = httpDataSource(headers)
                ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem)
            }

            SourceType.RTSP -> {
                if (userAgent.isEmpty()) {
                    RtspMediaSource.Factory().createMediaSource(mediaItem)
                } else {
                    RtspMediaSource.Factory().setUserAgent(userAgent).createMediaSource(mediaItem)
                }
            }

            SourceType.RTMP -> ProgressiveMediaSource.Factory(RtmpDataSource.Factory())
                .createMediaSource(mediaItem)

            SourceType.RTP -> null

            SourceType.UNKNOWN -> null
        }
    }

    @OptIn(UnstableApi::class)
    private fun httpDataSource(headers: Map<String, String>?): OkHttpDataSource.Factory {
        val factory = OkHttpDataSource.Factory(HttpClient.okHttpClient)
        if (!headers.isNullOrEmpty()) {
            try {
                factory.setDefaultRequestProperties(headers)
            } catch (_: Exception) {
                // 非法头部不应导致整条频道不可播
            }
        }
        _httpDataSource = factory
        return factory
    }

    /** 兼容旧调用方 */
    @OptIn(UnstableApi::class)
    fun getMediaItem(): MediaItem? {
        return getVideoUrl()?.let {
            try {
                MediaItem.fromUri(it)
            } catch (_: Exception) {
                null
            }
        }
    }

    /** 兼容旧调用方 */
    @OptIn(UnstableApi::class)
    fun getMediaSource(): MediaSource? = buildMediaSource()

    fun getSourceTypeDefault(): SourceType = tv.sourceType

    fun getSourceTypeCurrent(): SourceType {
        if (sourceTypeList.isEmpty()) sourceTypeList = listOf(SourceType.UNKNOWN)
        sourceTypeIndex = max(0, min(sourceTypeList.size - 1, sourceTypeIndex))
        return sourceTypeList[sourceTypeIndex]
    }

    /** 切换解码容器类型；返回 true 表示已经循环到最后一个 */
    fun nextSourceType(): Boolean {
        if (sourceTypeList.isEmpty()) return true
        sourceTypeIndex = (sourceTypeIndex + 1) % sourceTypeList.size
        return sourceTypeIndex == sourceTypeList.size - 1
    }

    fun confirmSourceType() {
        tv.sourceType = getSourceTypeCurrent()
    }

    fun confirmVideoIndex() {
        tv.videoIndex = videoIndexValue
    }

    fun isLastVideo(): Boolean {
        return videoIndexValue >= tv.uris.size - 1
    }

    fun sourceCount(): Int = tv.uris.size

    fun nextVideo(): Boolean {
        if (tv.uris.isEmpty()) return false
        _videoIndex.value = (videoIndexValue + 1) % tv.uris.size
        sourceTypeList = listOf(SourceType.UNKNOWN)
        sourceTypeIndex = 0
        preparedUrl = null
        return isLastVideo()
    }

    fun hasMoreSourceTypes(): Boolean = sourceTypeList.size > 1

    fun currentSourceTypeIndex(): Int = sourceTypeIndex

    /** 手动切换到下一个可用源（用户操作），成功返回 true */
    fun switchSource(): Boolean {
        if (tv.uris.size < 2) return false
        return nextVideo()
    }

    /**
     * 依据 优先级 / 最近成功时间 / 原始顺序 重排播放地址。
     * 保持当前正在播放的地址不变，避免重排导致"跳到别的源"。
     */
    private fun reorderSources() {
        val uris = tv.uris
        if (uris.size < 2) return
        val meta = tv.sources
        val indices = uris.indices.toList()
        val sorted = indices.sortedWith(
            compareBy(
                { meta?.getOrNull(it)?.priority ?: 0 },
                { -stats.lastSuccess(uris[it]) },
                { it },
            )
        )
        if (sorted == indices) return

        val current = uris.getOrNull(videoIndexValue)
        val newUris = sorted.map { uris[it] }
        val newSources = sorted.map { i -> meta?.getOrNull(i) ?: TVSource(url = uris[i]).normalized() }
        tv.uris = newUris
        tv.sources = newSources
        if (current != null) {
            _videoIndex.value = newUris.indexOf(current).coerceAtLeast(0)
        }
    }

    fun update(t: TV) {
        tv = t
    }

    /** 播放成功：记录成功源，并写入线路记忆（下次启动沿用） */
    fun onPlaySuccess() {
        val url = getVideoUrl() ?: return
        stats.recordSuccess(url)
        confirmSourceType()
        confirmVideoIndex()
        SourceMemory.remember(tv.key, url)
        lastFailureKind = null
        setErrInfo("")
        retryTimes = 0
    }

    /** 播放失败：记录失败源 */
    fun onPlayFailure(url: String? = getVideoUrl()) {
        url?.let { stats.recordFailure(it) }
    }

    init {
        if (tv.key.isEmpty()) {
            tv.key = com.lizongying.mytv0.parser.ChannelIdentity.key(tv.group, tv.matchName())
        }
        _videoIndex.value = max(0, min(tv.uris.size - 1, tv.videoIndex))
        _like.value = SP.getLike(tv.key)
    }

    companion object {
        private const val TAG = "TVModel"

        /** 全局播放源统计（内存 + 定时落盘） */
        val stats = SourceStats()
    }
}
