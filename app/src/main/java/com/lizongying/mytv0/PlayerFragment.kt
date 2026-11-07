package com.lizongying.mytv0

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import com.lizongying.mytv0.databinding.PlayerBinding
import com.lizongying.mytv0.models.TVModel
import com.lizongying.mytv0.playback.FailureKind
import com.lizongying.mytv0.playback.PlaybackController
import com.lizongying.mytv0.playback.PlaybackState

/**
 * 播放器界面。
 *
 * 本类只负责"视图 + 遥控器音量条"，所有播放状态由 [PlaybackController] 统一管理：
 * - 播放器实例只创建一次（切换软/硬解时才重建）；
 * - 切台、重试、超时、换源全部在控制器内部串行执行；
 * - Fragment 销毁时必定释放播放器，避免电视盒子长时间运行后内存/解码器泄漏。
 */
class PlayerFragment : Fragment() {

    private var _binding: PlayerBinding? = null
    private val binding get() = _binding!!

    private var controller: PlaybackController? = null

    private var tvModel: TVModel? = null

    private val handler = Handler(Looper.getMainLooper())
    private val delayHideVolume = 2 * 1000L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = PlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 视频比例交给 PlayerView 处理：FIT 模式不会拉伸画面，也不会因为
        // 测量宽高为 0 触发除零崩溃（旧实现在 onVideoSizeChanged 中直接相除）。
        binding.playerView.resizeMode =
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT

        controller = PlaybackController(
            requireContext(),
            binding.playerView,
            object : PlaybackController.Callback {
                override fun onStateChanged(state: PlaybackState) {
                    Log.i(TAG, "state $state")
                }

                override fun onPlaying(tvModel: TVModel) {
                    Log.i(TAG, "${tvModel.tv.showName()} playing")
                    // 播放成功由 TVModel.errInfo 通知 UI 切换界面
                }

                override fun onPlaybackFailed(
                    tvModel: TVModel,
                    message: String,
                    kind: FailureKind
                ) {
                    Log.w(TAG, "${tvModel.tv.showName()} failed: $kind $message")
                    tvModel.lastFailureKind = kind
                    tvModel.setErrInfo(errorMessage(kind))
                }

                override fun onVideoRatio(ratio: Float) {
                    // 比例由 resizeMode 处理；此处仅记录，便于排查异常片源
                    Log.d(TAG, "video ratio $ratio")
                }

                override fun onEnded() {
                    (activity as? MainActivity)?.onPlayEnd()
                }
            },
        )
        controller?.attach(SP.softDecode)
        controller?.autoSwitchSource = SP.autoSwitchSource

        tvModel?.let { controller?.play(it) }

        (activity as MainActivity).ready(TAG)
    }

    /** 错误文案统一附带当前解码方式，方便用户判断要不要切软解 / 换源 */
    private fun errorMessage(kind: FailureKind): String {
        val base = when (kind) {
            FailureKind.TIMEOUT, FailureKind.DNS, FailureKind.SERVER, FailureKind.RATE_LIMIT ->
                R.string.play_error_network.getString()

            FailureKind.TLS, FailureKind.AUTH -> R.string.play_error_auth.getString()
            FailureKind.NOT_FOUND, FailureKind.EMPTY -> R.string.play_error_source.getString()
            FailureKind.DECODE, FailureKind.FORMAT -> R.string.play_error_decode.getString()
            FailureKind.UNKNOWN -> R.string.play_error.getString()
        }
        val mode = if (SP.softDecode) {
            R.string.decode_mode_soft.getString()
        } else {
            R.string.decode_mode_hard.getString()
        }
        return R.string.play_error_with_mode.getString().format(base, mode)
    }

    /**
     * 软硬解切换：由设置页调用。
     * 不再"重建 Fragment / 重建 Activity"，只重建播放器并续播当前频道。
     */
    fun updatePlayer() {
        controller?.setDecodeMode(SP.softDecode)
    }

    fun play(tvModel: TVModel) {
        this.tvModel = tvModel
        val c = controller ?: return
        c.play(tvModel)
    }

    /** 手动切换播放源（遥控器红键 / INFO 键 / 长按 OK） */
    fun switchSource(): Boolean {
        val c = controller ?: return false
        val model = tvModel ?: return false
        val ok = c.switchSource()
        if (ok) {
            R.string.play_source_switched.getString()
                .format(model.videoIndexValue + 1, model.sourceCount())
                .showToast()
        } else {
            R.string.source_switch_no_more.showToast()
        }
        return ok
    }

    /** 启动断网宽限期内由 MainActivity 调用：静默重试当前频道 */
    fun retryCurrent(): Boolean = controller?.retryCurrent() ?: false

    /** 设置页切换"自动切源"时调用 */
    fun setAutoSwitchSource(enabled: Boolean) {
        controller?.autoSwitchSource = enabled
    }

    fun currentState(): PlaybackState = controller?.state ?: PlaybackState.IDLE

    fun showVolume(visibility: Int) {
        if (_binding == null) return
        binding.icon.visibility = visibility
        binding.volume.visibility = visibility
        hideVolume()
    }

    fun setVolumeMax(volume: Int) {
        if (_binding == null) return
        binding.volume.max = volume
    }

    fun setVolume(progress: Int, volume: Boolean = false) {
        val context = context ?: return
        if (_binding == null) return
        binding.volume.progress = progress
        binding.icon.setImageDrawable(
            ContextCompat.getDrawable(
                context,
                if (volume) {
                    if (progress > 0) R.drawable.volume_up_24px else R.drawable.volume_off_24px
                } else {
                    R.drawable.light_mode_24px
                }
            )
        )
    }

    fun hideVolume() {
        handler.removeCallbacks(hideVolumeRunnable)
        handler.postDelayed(hideVolumeRunnable, delayHideVolume)
    }

    fun hideVolumeNow() {
        handler.removeCallbacks(hideVolumeRunnable)
        handler.postDelayed(hideVolumeRunnable, 0)
    }

    private val hideVolumeRunnable = Runnable {
        if (_binding == null) return@Runnable
        binding.icon.visibility = View.GONE
        binding.volume.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        controller?.resume()
    }

    override fun onPause() {
        super.onPause()
        controller?.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        controller?.release()
        controller = null
        _binding = null
    }

    companion object {
        private const val TAG = "PlayerFragment"
    }
}
