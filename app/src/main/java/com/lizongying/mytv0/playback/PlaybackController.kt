package com.lizongying.mytv0.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import com.lizongying.mytv0.models.TVModel

/**
 * 统一播放控制器。
 *
 * 解决旧实现中的结构性问题：
 * - **重复创建播放器**：播放器只创建一次，仅在切换解码模式时重建；
 * - **并发切台**：所有操作在主线程串行执行，并通过 generation 令牌丢弃过期回调；
 * - **无限重试**：由 [PlaybackRecovery] 限制单地址 / 单类型 / 总尝试次数；
 * - **黑屏与卡死**：准备、首帧、缓冲三个阶段都有看门狗超时；
 * - **资源泄漏**：release() 幂等，View 销毁时必定释放 player 与监听器；
 * - **硬解失败不可恢复**：开启 decoder fallback，硬解初始化失败自动回退软解。
 */
@OptIn(UnstableApi::class)
class PlaybackController(
    private val context: Context,
    private val playerView: PlayerView,
    private val callback: Callback,
    private val recovery: PlaybackRecovery = PlaybackRecovery(),
) {

    interface Callback {
        fun onStateChanged(state: PlaybackState)
        fun onPlaying(tvModel: TVModel)
        fun onPlaybackFailed(tvModel: TVModel, message: String, kind: FailureKind)
        fun onVideoRatio(ratio: Float)
        fun onEnded()
    }

    private val handler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var softDecode = false

    /** 用户设置：失败后是否自动切备用源 */
    var autoSwitchSource = true

    var state: PlaybackState = PlaybackState.IDLE
        private set

    private var current: TVModel? = null
    private var generation = 0

    private var urlAttempts = 0
    private var typeAttempts = 0
    private var totalAttempts = 0
    private var urlSwitches = 0

    private var watchdog: Runnable? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (state == PlaybackState.RELEASED) return
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    setState(PlaybackState.BUFFERING)
                    scheduleWatchdog(BUFFER_STALL_TIMEOUT_MS, "buffering")
                }

                Player.STATE_READY -> {
                    setState(PlaybackState.READY)
                    scheduleWatchdog(FIRST_FRAME_TIMEOUT_MS, "first_frame")
                }

                Player.STATE_ENDED -> {
                    Log.i(TAG, "playback ended")
                    callback.onEnded()
                }

                Player.STATE_IDLE -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (state == PlaybackState.RELEASED) return
            if (isPlaying) {
                cancelWatchdog()
                setState(PlaybackState.PLAYING)
                current?.let {
                    it.onPlaySuccess()
                    callback.onPlaying(it)
                }
            } else if (state == PlaybackState.PLAYING) {
                setState(PlaybackState.PAUSED)
            }
        }

        override fun onRenderedFirstFrame() {
            if (state == PlaybackState.RELEASED) return
            cancelWatchdog()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            callback.onVideoRatio(videoSize.width.toFloat() / videoSize.height.toFloat())
        }

        override fun onPlayerError(error: PlaybackException) {
            if (state == PlaybackState.RELEASED) return
            // 丢弃上一个频道/上一个地址的迟到错误
            val expected = current?.getVideoUrl()
            val actual = player?.currentMediaItem?.localConfiguration?.uri?.toString()
            if (expected != null && actual != null && expected != actual) {
                Log.w(TAG, "ignore stale error: ${error.errorCodeName}")
                return
            }
            val kind = classify(error)
            Log.w(TAG, "player error ${error.errorCodeName} -> $kind")
            handleFailure(kind, error.errorCodeName)
        }
    }

    // ------------------------------------------------------------ 生命周期

    fun attach(softDecode: Boolean) {
        this.softDecode = softDecode
        if (player == null) {
            createPlayer(softDecode)
        }
    }

    private fun createPlayer(softDecode: Boolean) {
        releasePlayerOnly()
        val renderersFactory = DefaultRenderersFactory(context)
        renderersFactory.setMediaCodecSelector(H265SafeCodecSelector(preferSoftware = softDecode))
        renderersFactory.setExtensionRendererMode(
            if (softDecode) {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            } else {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            }
        )
        // 硬解初始化失败时自动回退到其它解码器（含软解），避免"有声音没画面"
        renderersFactory.setEnableDecoderFallback(true)

        val created = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .build()
        created.repeatMode = Player.REPEAT_MODE_ALL
        created.playWhenReady = true
        created.addListener(playerListener)
        created.addAnalyticsListener(analyticsListener)
        player = created
        playerView.player = created
        Log.i(TAG, "player created, softDecode=$softDecode")
    }

    /** 切换硬解 / 软解：重建播放器并重播当前频道 */
    fun setDecodeMode(softDecode: Boolean) {
        if (this.softDecode == softDecode && player != null) return
        this.softDecode = softDecode
        val model = current
        createPlayer(softDecode)
        if (model != null) {
            generation++
            prepareCurrent()
        }
    }

    fun play(tvModel: TVModel) {
        if (state == PlaybackState.RELEASED) return
        generation++
        cancelWatchdog()
        current = tvModel
        urlAttempts = 0
        typeAttempts = 0
        totalAttempts = 0
        urlSwitches = 0
        if (player == null) createPlayer(softDecode)
        setState(PlaybackState.PREPARING)
        prepareCurrent()
    }

    /**
     * 用户手动切换播放源。
     *
     * 旧实现只重置了 urlAttempts / typeAttempts，且没有取消看门狗：手动切源后
     * 上一个地址的 watchdog 仍会按时触发，把新地址判成超时。
     */
    fun switchSource(): Boolean {
        val model = current ?: return false
        if (model.sourceCount() < 2) return false
        generation++
        cancelWatchdog()
        urlAttempts = 0
        typeAttempts = 0
        totalAttempts = 0
        urlSwitches = 0
        model.nextVideo()
        Log.i(TAG, "manual switch source -> ${model.videoIndexValue + 1}/${model.sourceCount()}")
        setState(PlaybackState.PREPARING)
        prepareCurrent()
        return true
    }

    /**
     * 断网恢复：静默重来一轮当前频道。
     *
     * 与 [play] 的区别：不切频道、不动 UI 的 loading 页，只在失败态下把重试计数清零重新 prepare。
     * 出画后由 onIsPlayingChanged 正常回调，UI 自己从错误页切回播放页。
     */
    fun retryCurrent(): Boolean {
        val model = current ?: return false
        if (state == PlaybackState.RELEASED) return false
        if (state == PlaybackState.PLAYING || state == PlaybackState.READY ||
            state == PlaybackState.BUFFERING || state == PlaybackState.PREPARING
        ) {
            return false
        }
        generation++
        cancelWatchdog()
        urlAttempts = 0
        typeAttempts = 0
        totalAttempts = 0
        urlSwitches = 0
        Log.i(TAG, "retry current after ${model.lastFailureKind}")
        setState(PlaybackState.PREPARING)
        prepareCurrent()
        return true
    }

    fun pause() {
        if (state == PlaybackState.RELEASED) return
        player?.pause()
        if (state == PlaybackState.PLAYING) setState(PlaybackState.PAUSED)
    }

    fun resume() {
        if (state == PlaybackState.RELEASED) return
        val p = player ?: return
        if (current == null) return
        if (p.playbackState == Player.STATE_IDLE) {
            generation++
            prepareCurrent()
        } else {
            p.play()
        }
    }

    fun release() {
        generation++
        cancelWatchdog()
        handler.removeCallbacksAndMessages(null)
        current = null
        releasePlayerOnly()
        state = PlaybackState.RELEASED
        callback.onStateChanged(PlaybackState.RELEASED)
    }

    private fun releasePlayerOnly() {
        val p = player ?: return
        try {
            playerView.player = null
        } catch (_: Exception) {
        }
        try {
            p.removeListener(playerListener)
            p.release()
        } catch (e: Exception) {
            Log.e(TAG, "release player", e)
        }
        player = null
    }

    // ------------------------------------------------------------ 播放流程

    private fun prepareCurrent() {
        val p = player ?: return
        val model = current ?: return

        val mediaSource = try {
            model.buildMediaSource()
        } catch (e: Throwable) {
            Log.e(TAG, "build media source", e)
            null
        }

        if (mediaSource == null) {
            model.onPlayFailure()
            handleFailure(FailureKind.FORMAT, "no_media_source")
            return
        }

        try {
            p.stop()
            p.setMediaSource(mediaSource, true)
            p.prepare()
            p.playWhenReady = true
        } catch (e: Throwable) {
            Log.e(TAG, "prepare", e)
            handleFailure(FailureKind.UNKNOWN, "prepare_exception")
            return
        }

        setState(PlaybackState.PREPARING)
        scheduleWatchdog(PREPARE_TIMEOUT_MS, "prepare")
    }

    private fun handleFailure(kind: FailureKind, detail: String) {
        if (state == PlaybackState.RELEASED) return
        val model = current ?: return
        model.onPlayFailure()

        val snapshot = RecoveryState(
            urlAttempts = urlAttempts,
            typeAttempts = typeAttempts,
            totalAttempts = totalAttempts,
            urlSwitches = urlSwitches,
            urlCount = model.sourceCount().coerceAtLeast(1),
            hasMoreTypes = model.hasMoreSourceTypes(),
        )
        val action = recovery.decide(kind, snapshot, autoSwitchSource)
        Log.i(TAG, "recovery: $kind $snapshot -> $action")

        when (action) {
            RecoveryAction.RETRY_SAME -> {
                urlAttempts++
                totalAttempts++
                scheduleRetry(kind, urlAttempts)
            }

            RecoveryAction.NEXT_SOURCE_TYPE -> {
                typeAttempts++
                totalAttempts++
                model.nextSourceType()
                scheduleRetry(kind, 1)
            }

            RecoveryAction.NEXT_URL -> {
                urlSwitches++
                totalAttempts++
                urlAttempts = 0
                typeAttempts = 0
                model.nextVideo()
                scheduleRetry(kind, 1)
            }

            RecoveryAction.GIVE_UP -> {
                cancelWatchdog()
                setState(PlaybackState.ERROR)
                callback.onPlaybackFailed(model, detail, kind)
            }
        }
    }

    private fun scheduleRetry(kind: FailureKind, attempt: Int) {
        cancelWatchdog()
        val gen = generation
        val runnable = Runnable {
            if (gen != generation) return@Runnable
            if (state == PlaybackState.RELEASED) return@Runnable
            prepareCurrent()
        }
        watchdog = runnable
        handler.postDelayed(runnable, recovery.backoffMillis(kind, attempt))
    }

    private fun scheduleWatchdog(delayMs: Long, label: String) {
        cancelWatchdog()
        val gen = generation
        val runnable = Runnable {
            if (gen != generation) return@Runnable
            if (state == PlaybackState.RELEASED) return@Runnable
            Log.w(TAG, "watchdog timeout: $label")
            handleFailure(FailureKind.TIMEOUT, label)
        }
        watchdog = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun cancelWatchdog() {
        watchdog?.let { handler.removeCallbacks(it) }
        watchdog = null
    }

    private fun setState(next: PlaybackState) {
        if (state == next) return
        state = next
        callback.onStateChanged(next)
    }

    /**
     * 播放质量观测：把"输入帧率 / 丢帧数 / 首帧耗时 / 实际分辨率"打到日志。
     *
     * 4K@50 的 HLS 在低端盒子上是否真的流畅，无法靠截图判断，只能看丢帧计数。
     * 仅在 Log.i/w 级别输出，不影响播放路径。
     */
    private val analyticsListener = object : AnalyticsListener {
        private var dropped = 0L
        private var lastLogAt = 0L

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            Log.i(
                TAG,
                "video input ${format.sampleMimeType} ${format.width}x${format.height} " +
                        "fps=${format.frameRate} hdr=${format.colorInfo?.colorTransfer}"
            )
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long
        ) {
            Log.i(TAG, "first frame rendered in ${renderTimeMs}ms")
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) {
            dropped += droppedFrames
            val now = System.currentTimeMillis()
            if (now - lastLogAt >= 2_000L) {
                lastLogAt = now
                Log.w(TAG, "dropped video frames: +$droppedFrames, total=$dropped")
            }
        }
    }

    // ------------------------------------------------------------ 错误分类

    private fun classify(error: PlaybackException): FailureKind {
        val cause = error.cause
        if (cause is HttpDataSource.InvalidResponseCodeException) {
            return when (cause.responseCode) {
                401 -> FailureKind.AUTH
                403 -> FailureKind.AUTH
                404 -> FailureKind.NOT_FOUND
                429 -> FailureKind.RATE_LIMIT
                in 500..599 -> FailureKind.SERVER
                else -> FailureKind.UNKNOWN
            }
        }

        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT,
                -> FailureKind.TIMEOUT

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> FailureKind.DNS
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> FailureKind.UNKNOWN
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> FailureKind.NOT_FOUND
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> FailureKind.AUTH
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> FailureKind.TLS
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> FailureKind.FORMAT

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                -> FailureKind.FORMAT

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
                -> FailureKind.DECODE

            else -> {
                val net = com.lizongying.mytv0.net.NetErrorClassifier.fromThrowable(cause)
                when (net.kind) {
                    com.lizongying.mytv0.net.NetErrorKind.TIMEOUT -> FailureKind.TIMEOUT
                    com.lizongying.mytv0.net.NetErrorKind.DNS -> FailureKind.DNS
                    com.lizongying.mytv0.net.NetErrorKind.TLS -> FailureKind.TLS
                    com.lizongying.mytv0.net.NetErrorKind.HTTP_429 -> FailureKind.RATE_LIMIT
                    else -> FailureKind.UNKNOWN
                }
            }
        }
    }

    /**
     * H265 解码器排序：硬件优先、软件兜底。
     *
     * 旧实现把 H265 强制收敛到 c2.android.hevc.decoder 一个候选。这样在解码器实现不完整的
     * 廉价盒子上能规避绿屏/花屏，代价是把所有 H265 都压到 CPU：真机实测（骁龙 865，
     * 1080p H265）软件解码稳定占用 44% 单核，且丢掉了 ExoPlayer 的 fallback 能力
     * （候选列表只剩一个，硬件失败后无处可退）。
     *
     * 改为保留完整候选，硬件排在前：MediaCodecUtil 本身已按硬件优先排序，这里再显式分组一次，
     * 保证即便上层排序策略变化，H265 也是“硬件优先、软件兜底”；配合
     * DefaultRenderersFactory.setEnableDecoderFallback(true)，硬件解码器初始化失败时
     * ExoPlayer 会自动落到下一个（软件或 FFmpeg）候选。
     */
    class H265SafeCodecSelector(
        private val preferSoftware: Boolean = false,
    ) : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): MutableList<MediaCodecInfo> {
            val infos = MediaCodecUtil.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            if (infos.size < 2) return infos
            val hardware = infos.filter { it.hardwareAccelerated }
            val software = infos.filterNot { it.hardwareAccelerated }
            // 软解：优先平台软件解码器（c2.android.* / OMX.google.*）；
            // 硬解：优先厂商解码器。两种顺序都保留完整候选，交给
            // setEnableDecoderFallback 在初始化失败时自动回落。
            return if (preferSoftware) {
                if (software.isEmpty()) infos else (software + hardware).toMutableList()
            } else {
                if (hardware.isEmpty()) infos else (hardware + software).toMutableList()
            }
        }
    }

    companion object {
        private const val TAG = "PlaybackController"
        const val PREPARE_TIMEOUT_MS = 15_000L
        const val FIRST_FRAME_TIMEOUT_MS = 12_000L
        const val BUFFER_STALL_TIMEOUT_MS = 20_000L
    }
}
