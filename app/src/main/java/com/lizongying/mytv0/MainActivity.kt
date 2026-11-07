package com.lizongying.mytv0

import MainViewModel
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.PopupWindow
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.lizongying.mytv0.databinding.SettingsWebBinding
import com.lizongying.mytv0.server.AccessToken
import java.util.Locale
import kotlin.math.abs


class MainActivity : AppCompatActivity() {

    private var ok = 0
    private var playerFragment = PlayerFragment()
    private val errorFragment = ErrorFragment()
    private val loadingFragment = LoadingFragment()
    private var infoFragment = InfoFragment()
    private var channelFragment = ChannelFragment()
    private var timeFragment = TimeFragment()
    private var menuFragment = MenuFragment()
    private var settingFragment = SettingFragment()
    private var programFragment = ProgramFragment()

    private val handler = Handler(Looper.myLooper()!!)
    private val delayHideMenu = 10 * 1000L
    private val delayHideSetting = 3 * 60 * 1000L

    /** 启动网络宽限：断网开 APP 时最长等 3 分钟，期间联网即自动出画 */
    private val startupNetWindow = 3 * 60 * 1000L
    private val startupRetryInterval = 6 * 1000L
    private var startupDeadline = 0L

    /** 前台标记：后台时不做播放重试，避免在桌面上突然出声 */
    private var activityResumed = false

    private var doubleBackToExitPressedOnce = false

    private lateinit var gestureDetector: GestureDetector

    private var server: SimpleServer? = null
    private val serverToken = AccessToken()
    private var readyCalled = false

    private lateinit var viewModel: MainViewModel

    private var isSafeToPerformFragmentTransactions = false

    /** 已经注册过监听器的频道模型，配置更新时先注销，避免观察者无限累积 */
    private var watchedTvModels: List<com.lizongying.mytv0.models.TVModel> = emptyList()

    /** 首次分组变化由 channelsOk 负责起播，避免启动时重复 prepare */
    private var groupChangeHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

//        requestWindowFeature(FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            windowInsetsController.let { controller ->
                controller.isAppearanceLightNavigationBars = true
                controller.isAppearanceLightStatusBars = true
                controller.hide(WindowInsetsCompat.Type.statusBars())
                controller.hide(WindowInsetsCompat.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = Color.TRANSPARENT
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.setAttributes(lp)
        }

        window.decorView.apply {
            systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        viewModel.init(this)

        // 仅 debug 构建：am start --es dbg <cmd> 驱动切台/刷新，供无输入注入权限时自动化测试
        handleDebugIntent(intent)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.main_browse_fragment, playerFragment)
                .add(R.id.main_browse_fragment, infoFragment)
                .add(R.id.main_browse_fragment, channelFragment)
                .commitNowAllowingStateLoss()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * 调试入口，仅 debuggable 构建生效（release 里是一次判断后立即返回）。
     * 用于无法注入触摸/按键事件的环境（如 MIUI 拦截 INJECT_EVENTS）：
     *   adb shell am start -n com.lizongying.mytv0/.MainActivity --es dbg "play:3"
     *   adb shell am start -n com.lizongying.mytv0/.MainActivity --es dbg "next"
     *   adb shell am start -n com.lizongying.mytv0/.MainActivity --es dbg "refresh"
     *   adb shell am start -n com.lizongying.mytv0/.MainActivity --es dbg "switch"
     *   adb shell am start -n com.lizongying.mytv0/.MainActivity --es dbg "pos"
     */
    private fun handleDebugIntent(intent: Intent?) {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val cmd = intent?.getStringExtra("dbg") ?: return
        Log.i(TAG, "dbg cmd: $cmd")
        try {
            when {
                cmd.startsWith("play:") -> play(cmd.substring("play:".length).toInt())
                cmd == "next" -> next()
                cmd == "prev" -> prev()
                cmd == "refresh" -> viewModel.refreshConfig()
                cmd == "switch" -> switchSource()
                cmd == "pos" -> Log.i(
                    TAG,
                    "dbg pos group=${viewModel.groupModel.positionValue} " +
                            "title=${viewModel.groupModel.getCurrent()?.tv?.title}"
                )

                else -> Log.w(TAG, "dbg unknown cmd: $cmd")
            }
        } catch (e: Exception) {
            Log.e(TAG, "dbg failed: $cmd", e)
        }
    }

    fun updateMenuSize() {
        menuFragment.updateSize()
    }

    fun ready(tag: String) {
        Log.i(TAG, "ready $tag")
        if (readyCalled) return
        ok++
        if (ok == 2) {
            readyCalled = true
            Log.i(TAG, "all ready")

            gestureDetector = GestureDetector(this, GestureListener(this))

            viewModel.groupModel.change.observe(this) { _ ->
                Log.i(TAG, "group changed")
                if (viewModel.groupModel.tvGroup.value != null) {
                    watch()
                    menuFragment.update()
                    if (groupChangeHandled) {
                        // 直播源更新后，让当前频道重新进入播放流程（旧频道可能已不存在）
                        viewModel.groupModel.getCurrent()?.setReady()
                    }
                    groupChangeHandled = true
                }
            }

//            if (SP.defaultLike) {
//                TVList.groupModel.setPosition(0)
//                val tvModel = TVList.listModel.find { it.like.value as Boolean }
//                TVList.setPosition(tvModel?.tv?.id ?: 0)
//                "播放收藏频道".showToast()
//            }

            viewModel.channelsOk.observe(this) { it ->
                if (it) {
                    val prevGroup = viewModel.groupModel.positionValue
                    val tvModel = if (SP.channel > 0) {
                        val position = if (SP.channel < viewModel.listModel.size) {
                            // R.string.play_default_channel.showToast()
                            SP.channel - 1
                        } else {
                            // R.string.default_channel_out_of_range.showToast()
                            SP.channel = 0
                            0
                        }
                        Log.i(TAG, "播放默认频道")
                        viewModel.groupModel.getPosition(position)
                    } else {
//                if (SP.position < 0 || SP.position >= TVList.groupModel.getAllList()!!
//                        .size()
//                ) {
//                    // R.string.last_channel_out_of_range.showToast()
//                    0
//                } else {
//                    // R.string.play_last_channel.showToast()
//                    SP.position
//                }
                        Log.i(TAG, "播放上次频道")
                        viewModel.groupModel.getCurrent()
                    }
                    viewModel.groupModel.setPositionPlaying()
                    viewModel.groupModel.getCurrentList()
                        ?.let {
                            Log.i(TAG, "当前组 ${it.getName()}")
                            it.setPositionPlaying()
                        }
                    tvModel?.setReady()

                    val currentGroup = viewModel.groupModel.positionValue
                    if (currentGroup != prevGroup) {
                        Log.i(TAG, "group change")
                        menuFragment.updateList(currentGroup)
                    }

                    viewModel.groupModel.isInLikeMode =
                        SP.defaultLike && viewModel.groupModel.positionValue == 0
                    if (viewModel.groupModel.isInLikeMode) {
//                R.string.favorite_mode.showToast()
                    } else {
//                R.string.standard_mode.showToast()
                    }

                    // TODO group position
                    viewModel.updateEPG()
                }
            }

            // 首次启动没有内置源：拉取失败且列表仍为空时，把错误显示出来
            viewModel.updateInfo.observe(this) { info ->
                if (viewModel.listModel.isEmpty() && info != null &&
                    (info.startsWith("auto:failed") || info.startsWith("exception") ||
                            info.startsWith("invalid") || info.startsWith("apply_failed"))
                ) {
                    Log.w(TAG, "channel fetch failed: $info")
                    errorFragment.setMsg(R.string.channel_fetch_failed.getString())
                    hideFragment(loadingFragment)
                    showFragment(errorFragment)
                }
            }

            Utils.isp.observe(this) {
                val id = when (it) {
//                    ISP.CHINA_MOBILE -> R.raw.mobile
//                    ISP.IPV6->R.raw.ipv6
                    else -> 0
                }

                if (id == 0) {
                    return@observe
                }

                resources.openRawResource(id).bufferedReader()
                    .use { i ->
                        val channels = i.readText()
                        if (channels.isNotEmpty()) {
                            viewModel.tryStr2Channels(channels, null, "")
                        } else {
                            Log.w(TAG, "$it is empty")
                        }
                    }
            }

            // 远程配置服务默认常驻：设置页"远程配置"点开即为二维码
            startServer()

            viewModel.updateConfig()
            startStartupNetWindow()
        }
    }

    private fun watch() {
        // 先注销上一轮频道的监听器（配置更新会重建频道模型）
        watchedTvModels.forEach { model ->
            model.errInfo.removeObservers(this)
            model.ready.removeObservers(this)
            model.like.removeObservers(this)
        }
        watchedTvModels = viewModel.listModel

        viewModel.listModel.forEach { tvModel ->
            tvModel.errInfo.observe(this) { _ ->

                if (tvModel.errInfo.value != null
//                    && tvModel.tv.id == TVList.positionValue
                ) {
                    hideFragment(loadingFragment)
                    if (tvModel.errInfo.value == "") {
                        Log.i(TAG, "${tvModel.tv.title} playing")
                        hideFragment(errorFragment)
                        showFragment(playerFragment)
                    } else {
                        Log.i(TAG, "${tvModel.tv.title} ${tvModel.errInfo.value.toString()}")
                        hideFragment(playerFragment)
                        errorFragment.setMsg(tvModel.errInfo.value.toString())
                        showFragment(errorFragment)
                    }
                }
            }

            tvModel.ready.observe(this) { _ ->

                // not first time && channel is not changed
                if (tvModel.ready.value != null
//                    && tvModel.tv.id == TVList.positionValue
                ) {
                    Log.i(TAG, "${tvModel.tv.title} 尝试播放")
                    hideFragment(errorFragment)
                    showFragment(loadingFragment)
                    playerFragment.play(tvModel)
                    infoFragment.show(tvModel)
                    if (SP.channelNum) {
                        channelFragment.show(tvModel)
                    }
                }
            }

            tvModel.like.observe(this) { _ ->
                if (tvModel.like.value != null && tvModel.tv.id != -1) {
                    val liked = tvModel.like.value as Boolean
                    if (liked) {
                        viewModel.groupModel.getFavoritesList()?.replaceTVModel(tvModel)
                    } else {
                        viewModel.groupModel.getFavoritesList()
                            ?.removeTVModel(tvModel.tv.id)
                    }
                    SP.setLike(tvModel.tv.key, liked)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null) {
            gestureDetector.onTouchEvent(event)
        }
        return super.onTouchEvent(event)
    }

    private inner class GestureListener(context: Context) :
        GestureDetector.SimpleOnGestureListener() {

        private var screenWidth = windowManager.defaultDisplay.width
        private var screenHeight = windowManager.defaultDisplay.height
        private val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager

        private var maxVolume = 0

        init {
            maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }

        override fun onDown(e: MotionEvent): Boolean {
            playerFragment.hideVolumeNow()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            showFragment(menuFragment)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            showSetting()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            showProgram()
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val oldX = e1?.rawX ?: 0f
            val oldY = e1?.rawY ?: 0f
            val newX = e2.rawX
            val newY = e2.rawY
            if (oldX > screenWidth / 3 && oldX < screenWidth * 2 / 3 && abs(newX - oldX) < abs(newY - oldY)) {
                if (velocityY > 0) {
                    if ((!menuFragment.isAdded || menuFragment.isHidden) && (!settingFragment.isAdded || settingFragment.isHidden)) {
                        prev()
                    }
                }
                if (velocityY < 0) {
                    if ((!menuFragment.isAdded || menuFragment.isHidden) && (!settingFragment.isAdded || settingFragment.isHidden)) {
                        next()
                    }
                }
            }

            return super.onFling(e1, e2, velocityX, velocityY)
        }

        private var lastScrollTime: Long = 0
        private var decayFactor: Float = 1.0f

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val oldX = e1?.rawX ?: 0f
            val oldY = e1?.rawY ?: 0f
            val newX = e2.rawX
            val newY = e2.rawY

            if (oldX < screenWidth / 3) {
                val currentTime = System.currentTimeMillis()
                val deltaTime = currentTime - lastScrollTime
                lastScrollTime = currentTime

                decayFactor =
                    0.01f.coerceAtLeast(decayFactor - 0.03f * deltaTime)
                val delta =
                    ((oldY - newY) * decayFactor * 0.2 / screenHeight).toFloat()
                adjustBrightness(delta)
                decayFactor = 1.0f
                return super.onScroll(e1, e2, distanceX, distanceY)
            }

            if (oldX > screenWidth * 2 / 3 && abs(distanceY) > abs(distanceX)) {
                val currentTime = System.currentTimeMillis()
                val deltaTime = currentTime - lastScrollTime
                lastScrollTime = currentTime

                decayFactor =
                    0.01f.coerceAtLeast(decayFactor - 0.03f * deltaTime)
                val delta =
                    ((oldY - newY) * maxVolume * decayFactor * 0.2 / screenHeight).toInt()
                adjustVolume(delta)
                decayFactor = 1.0f
                return super.onScroll(e1, e2, distanceX, distanceY)
            }

            return super.onScroll(e1, e2, distanceX, distanceY)
        }

        private fun adjustVolume(deltaVolume: Int) {
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            var newVolume = currentVolume + deltaVolume

            if (newVolume < 0) {
                newVolume = 0
            } else if (newVolume > maxVolume) {
                newVolume = maxVolume
            }

            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

            playerFragment.setVolumeMax(maxVolume * 100)
            playerFragment.setVolume(newVolume.toInt() * 100, true)
            playerFragment.showVolume(View.VISIBLE)
        }

        private fun adjustBrightness(deltaBrightness: Float) {
            var brightness = window.attributes.screenBrightness

            brightness += deltaBrightness
            brightness = 0.1f.coerceAtLeast(0.9f.coerceAtMost(brightness))

            val attributes = window.attributes.apply {
                screenBrightness = brightness
            }
            window.attributes = attributes

            playerFragment.setVolumeMax(100)
            playerFragment.setVolume((brightness * 100).toInt())
            playerFragment.showVolume(View.VISIBLE)
        }
    }

    fun onPlayEnd() {
        val tvModel = viewModel.groupModel.getCurrent() ?: return
        if (SP.repeatInfo) {
            infoFragment.show(tvModel)
            if (SP.channelNum) {
                channelFragment.show(tvModel)
            }
        }
    }

    /**
     * 按"全部频道"顺序播放指定频道。
     *
     * 旧实现用 getPosition() 在所有分组里累加位置，当收藏分组非空时
     * 数字键选台会偏移；这里改为直接索引"全部频道"列表，并同步分组与列表位置。
     */
    fun play(position: Int): Boolean {
        val all = viewModel.groupModel.getAllList()
        if (all == null || position < 0 || position >= all.size()) {
            R.string.channel_not_exist.showToast()
            return false
        }

        val prevGroup = viewModel.groupModel.positionValue
        val tvModel = all.getTVModel(position)
        if (tvModel == null) {
            R.string.channel_not_exist.showToast()
            return false
        }

        val group = tvModel.getGroupIndexInAll()
        viewModel.groupModel.setPosition(group)
        viewModel.groupModel.setPositionPlaying(group)

        val list = viewModel.groupModel.getCurrentList()
        val listPosition = tvModel.listIndex
        if (list != null && listPosition in 0 until list.size()) {
            list.setPosition(listPosition)
            list.setPositionPlaying(listPosition)
        }

        tvModel.setReady()

        val currentGroup = viewModel.groupModel.positionValue
        if (currentGroup != prevGroup) {
            menuFragment.updateList(currentGroup)
        }
        return true
    }

    fun prev() {
        val prevGroup = viewModel.groupModel.positionValue
        val tvModel =
            if (SP.defaultLike && viewModel.groupModel.isInLikeMode && viewModel.groupModel.getFavoritesList() != null
            ) {
                viewModel.groupModel.getPrev(true)
            } else {
                viewModel.groupModel.getPrev()
            }

        tvModel?.setReady()
        viewModel.groupModel.setPositionPlaying()
        viewModel.groupModel.getCurrentList()?.setPositionPlaying()

        val currentGroup = viewModel.groupModel.positionValue
        if (currentGroup != prevGroup) {
            menuFragment.updateList(currentGroup)
        }
    }

    fun next() {
        val prevGroup = viewModel.groupModel.positionValue
        val tvModel =
            if (SP.defaultLike && viewModel.groupModel.isInLikeMode && viewModel.groupModel.getFavoritesList() != null
            ) {
                viewModel.groupModel.getNext(true)
            } else {
                viewModel.groupModel.getNext()
            }

        tvModel?.setReady()
        viewModel.groupModel.setPositionPlaying()
        viewModel.groupModel.getCurrentList()?.setPositionPlaying()

        val currentGroup = viewModel.groupModel.positionValue
        if (currentGroup != prevGroup) {
            menuFragment.updateList(currentGroup)
        }
    }

    private fun showFragment(fragment: Fragment) {
        if (!isSafeToPerformFragmentTransactions) {
            return
        }

        if (!fragment.isAdded) {
            supportFragmentManager.beginTransaction()
                .add(R.id.main_browse_fragment, fragment)
                .commitAllowingStateLoss()
            return
        }

        if (!fragment.isHidden) {
            return
        }

        supportFragmentManager.beginTransaction()
            .show(fragment)
            .commitAllowingStateLoss()
    }

    private fun hideFragment(fragment: Fragment) {
        if (!isSafeToPerformFragmentTransactions) {
            return
        }

        if (!fragment.isAdded || fragment.isHidden) {
            return
        }

        supportFragmentManager.beginTransaction()
            .hide(fragment)
            .commitAllowingStateLoss()
    }

    fun menuActive() {
        handler.removeCallbacks(hideMenu)
        handler.postDelayed(hideMenu, delayHideMenu)
    }

    private val hideMenu = Runnable {
        if (!isFinishing && !supportFragmentManager.isStateSaved) {
            if (!menuFragment.isHidden) {
                supportFragmentManager.beginTransaction()
                    .hide(menuFragment)
                    .commitAllowingStateLoss()
            }
        }
    }

    fun switchSoftDecode() {
        if (!playerFragment.isAdded || playerFragment.isHidden) {
            return
        }

        playerFragment.updatePlayer()
    }

    /** 设置页切换"自动切源"开关 */
    fun setAutoSwitchSource(enabled: Boolean) {
        playerFragment.setAutoSwitchSource(enabled)
    }

    fun settingActive() {
        handler.removeCallbacks(hideSetting)
        handler.postDelayed(hideSetting, delayHideSetting)
    }

    private val hideSetting = Runnable {
        hideFragment(settingFragment)
        showTimeFragment()
    }

    fun showTimeFragment() {
        if (SP.time) {
            showFragment(timeFragment)
        } else {
            hideFragment(timeFragment)
        }
    }

    private fun showChannel(channel: Int) {
        if (!menuFragment.isHidden) {
            return
        }

        if (settingFragment.isVisible) {
            return
        }

//        if (SP.channelNum) {
//            channelFragment.show(channel)
//        }
        channelFragment.show(channel)
    }


    private fun channelUp() {
        if (programFragment.isAdded && !programFragment.isHidden) {
            return
        }

        if ((!menuFragment.isAdded || menuFragment.isHidden) && (!settingFragment.isAdded || settingFragment.isHidden)) {
            if (SP.channelReversal) {
                next()
                return
            }
            prev()
        }
    }

    private fun channelDown() {
        if (programFragment.isAdded && !programFragment.isHidden) {
            return
        }

        if ((!menuFragment.isAdded || menuFragment.isHidden) && (!settingFragment.isAdded || settingFragment.isHidden)) {
            if (SP.channelReversal) {
                prev()
                return
            }
            next()
        }
    }

    private fun back() {
        if (menuFragment.isAdded && !menuFragment.isHidden) {
            hideFragment(menuFragment)
            return
        }

        if (programFragment.isAdded && !programFragment.isHidden) {
            hideFragment(programFragment)
            return
        }

        if (settingFragment.isAdded && !settingFragment.isHidden) {
            hideFragment(settingFragment)
            showTimeFragment()
            return
        }

        if (channelFragment.isAdded && channelFragment.isVisible) {
            channelFragment.hideSelf()
            return
        }

        if (doubleBackToExitPressedOnce) {
            super.onBackPressed()
            return
        }

        doubleBackToExitPressedOnce = true
        R.string.press_again_to_exit.showToast()

        Handler(Looper.getMainLooper()).postDelayed({
            doubleBackToExitPressedOnce = false
        }, 2000)
    }

    private fun showSetting() {
        if (programFragment.isAdded && !programFragment.isHidden) {
            return
        }

        if (menuFragment.isAdded && !menuFragment.isHidden) {
            return
        }

        showFragment(settingFragment)

        settingActive()
    }

    private fun showProgram() {
        if (menuFragment.isAdded && !menuFragment.isHidden) {
            return
        }

        if (settingFragment.isAdded && !settingFragment.isHidden) {
            return
        }

        viewModel.groupModel.getCurrent()?.let {
            if (it.epgValue.isEmpty()) {
                R.string.epg_is_empty.showToast()
                return
            }
        }

        showFragment(programFragment)
    }

    private fun hideProgram(): Boolean {
        if (!programFragment.isAdded || programFragment.isHidden) {
            return false
        }

        hideFragment(programFragment)
        return true
    }

    fun showWebViewPopup(url: String) {
        val binding = SettingsWebBinding.inflate(layoutInflater)

        val webView = binding.web
        webView.settings.javaScriptEnabled = true
        webView.isFocusableInTouchMode = true
        webView.isFocusable = true
        webView.loadUrl(url)

        val popupWindow = PopupWindow(
            binding.root,
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )

        popupWindow.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        popupWindow.isFocusable = true
        popupWindow.isTouchable = true

        popupWindow.isClippingEnabled = false

        popupWindow.showAtLocation(window.decorView, Gravity.CENTER, 0, 0)

        webView.requestFocus()

        binding.close.setOnClickListener {
            popupWindow.dismiss()
        }
    }

    /**
     * 手动切换到下一个播放源。
     *
     * 只在用户按键时触发，自动切源仍由 PlaybackController 的恢复策略负责。
     * 切换成功后把错误页收回、显示加载页，避免"错误提示 + 新源正在加载"同时出现。
     */
    fun switchSource() {
        val switched = playerFragment.switchSource()
        if (switched) {
            hideFragment(errorFragment)
            showFragment(loadingFragment)
        }
    }

    fun onKey(keyCode: Int): Boolean {
        Log.d(TAG, "keyCode $keyCode")
        when (keyCode) {
            KeyEvent.KEYCODE_0,
            KeyEvent.KEYCODE_1,
            KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_6,
            KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_9,
                -> {
                showChannel(keyCode - 7)
                return true
            }

            KeyEvent.KEYCODE_ESCAPE -> {
                back()
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                back()
                return true
            }

            KeyEvent.KEYCODE_BOOKMARK -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_UNKNOWN -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_HELP -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_SETTINGS -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_MENU -> {
                showSetting()
                return true
            }

            // 手动换源：遥控器"信息"键或红色功能键
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_PROG_RED -> {
                switchSource()
                return true
            }

            KeyEvent.KEYCODE_ENTER -> {
                if (channelFragment.isAdded && channelFragment.isVisible) {
                    channelFragment.playNow()
                    return true
                }

                showFragment(menuFragment)
            }

            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (channelFragment.isAdded && channelFragment.isVisible) {
                    channelFragment.playNow()
                    return true
                }

                showFragment(menuFragment)
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                channelUp()
            }

            KeyEvent.KEYCODE_CHANNEL_UP -> {
                channelUp()
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                channelDown()
            }

            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                channelDown()
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                showProgram()
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showSetting()
            }
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (onKey(keyCode)) {
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()

        isSafeToPerformFragmentTransactions = true
        activityResumed = true

        showTimeFragment()
    }

    override fun onPause() {
        super.onPause()

        isSafeToPerformFragmentTransactions = false
        activityResumed = false
        try {
            viewModel.persistStats()
        } catch (e: Exception) {
            Log.w(TAG, "persist stats failed")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(startupRetryRunnable)
        stopServer()
    }

    // ------------------------------------------------------------ 启动网络宽限

    /**
     * 断网开机的恢复窗口。
     *
     * 旧逻辑：配置拉取 / 播放各失败一次就停手，之后网络恢复也不会重来，画面一直不出来。
     * 新逻辑：启动后开一个 3 分钟窗口，每 6 秒检查一次当前频道——
     * 只要失败类型是网络类（断网 / DNS / 服务端不可达 / 限流），就静默重试一轮；
     * 出画（lastFailureKind 被清空）立即停止；3 分钟到点彻底放手，不再打扰。
     */
    private val startupRetryRunnable = object : Runnable {
        override fun run() {
            if (startupDeadline <= 0L) return
            if (SystemClock.elapsedRealtime() >= startupDeadline) {
                Log.i(TAG, "startup network window closed")
                startupDeadline = 0L
                return
            }
            val kind = viewModel.groupModel.getCurrent()?.lastFailureKind
            if (activityResumed && kind != null && kind.isNetwork) {
                Log.i(TAG, "startup retry playback after $kind")
                playerFragment.retryCurrent()
            }
            handler.postDelayed(this, startupRetryInterval)
        }
    }

    private fun startStartupNetWindow() {
        startupDeadline = SystemClock.elapsedRealtime() + startupNetWindow
        handler.removeCallbacks(startupRetryRunnable)
        handler.postDelayed(startupRetryRunnable, startupRetryInterval)
    }

    // ------------------------------------------------------------ 远程配置服务

    fun startServer(): Boolean {
        if (server != null) return true
        // 每次开启服务都生成新令牌，旧二维码/旧链接立即失效
        serverToken.regenerate()
        val started = SimpleServer.start(this, viewModel, serverToken)
        server = started
        if (started == null) {
            Log.e(TAG, "start server failed")
            return false
        }
        Log.i(TAG, "server port ${started.listeningPort}")
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            // 仅调试构建：把带令牌的地址打出来，便于自动化测试访问
            Log.i(TAG, "dbg server address ${serverAddress()}")
        }
        return true
    }

    fun stopServer() {
        try {
            server?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "stop server", e)
        }
        server = null
    }

    fun isServerRunning(): Boolean = server != null

    /** 返回包含临时令牌的访问地址；未启动返回 null */
    fun serverAddress(): String? {
        val s = server ?: return null
        val ip = PortUtil.lan() ?: return null
        return "http://$ip:${s.listeningPort}/?t=${serverToken.current()}"
    }

    /**
     * 取二维码地址：服务未运行（端口被占用 / 上次启动失败）时先拉起，
     * 再换一个新令牌（旧二维码立即失效），避免点开却拿不到地址。
     */
    fun refreshServerToken(): String? {
        if (server == null && !startServer()) return null
        serverToken.regenerate()
        return serverAddress()
    }

    override fun attachBaseContext(base: Context) {
        try {
            val locale = Locale.SIMPLIFIED_CHINESE
            val config = Configuration()
            config.setLocale(locale)
            super.attachBaseContext(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                    base.createConfigurationContext(config)
                } else {
                    val resources = base.resources
                    resources.updateConfiguration(config, resources.displayMetrics)
                    base
                }
            )
        } catch (_: Exception) {
            super.attachBaseContext(base)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}