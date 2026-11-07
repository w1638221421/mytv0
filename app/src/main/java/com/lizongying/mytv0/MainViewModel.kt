import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lizongying.mytv0.ImageHelper
import com.lizongying.mytv0.MyTVApplication
import com.lizongying.mytv0.R
import com.lizongying.mytv0.SP
import com.lizongying.mytv0.Utils.getDateFormat
import com.lizongying.mytv0.data.EPG
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.Global.typeEPGMap
import com.lizongying.mytv0.data.Source
import com.lizongying.mytv0.data.TV
import com.lizongying.mytv0.models.EPGXmlParser
import com.lizongying.mytv0.models.EpgIndex
import com.lizongying.mytv0.models.Sources
import com.lizongying.mytv0.models.TVGroupModel
import com.lizongying.mytv0.models.TVListModel
import com.lizongying.mytv0.models.TVModel
import com.lizongying.mytv0.parser.ChannelIdentity
import com.lizongying.mytv0.parser.ChannelParser
import com.lizongying.mytv0.parser.ParseResult
import com.lizongying.mytv0.requests.HttpClient
import com.lizongying.mytv0.showToast
import com.lizongying.mytv0.store.ChannelStore
import io.github.lizongying.Gua
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean


class MainViewModel : ViewModel() {

    private var timeFormat = if (SP.displaySeconds) "HH:mm:ss" else "HH:mm"

    private lateinit var appDirectory: File
    private lateinit var store: ChannelStore
    private val parser = ChannelParser()

    var listModel: List<TVModel> = emptyList()
        private set

    val groupModel = TVGroupModel()
    private var cacheChannels = ""
    private var initialized = false
    private var inited = false

    private lateinit var cacheEPG: File
    private var epgUrl: String? = SP.epg
    private val epgUpdating = AtomicBoolean(false)
    private val configUpdating = AtomicBoolean(false)

    private lateinit var imageHelper: ImageHelper

    val sources = Sources()

    private val _channelsOk = MutableLiveData<Boolean>()
    val channelsOk: LiveData<Boolean>
        get() = _channelsOk

    /** 最近一次配置更新的结果描述，供设置页展示 */
    private val _updateInfo = MutableLiveData<String>()
    val updateInfo: LiveData<String>
        get() = _updateInfo

    fun setDisplaySeconds(displaySeconds: Boolean) {
        timeFormat = if (displaySeconds) "HH:mm:ss" else "HH:mm"
        SP.displaySeconds = displaySeconds
    }

    fun getTime(): String {
        return getDateFormat(timeFormat)
    }

    // ------------------------------------------------------------ 初始化

    fun init(context: Context) {
        // ViewModel 可能在 Activity 重建时复用，init 只允许执行一次
        if (inited) {
            Log.i(TAG, "already initialized")
            return
        }
        inited = true

        val application = context.applicationContext as MyTVApplication
        imageHelper = application.imageHelper

        groupModel.addTVListModel(TVListModel("我的收藏", 0))
        groupModel.addTVListModel(TVListModel("全部频道", 1))

        appDirectory = context.filesDir
        store = ChannelStore(appDirectory)
        cacheEPG = File(appDirectory, CACHE_EPG)

        TVModel.stats.load(SP.sourceStats)

        // 不再内置直播源：首次启动没有缓存时，直接等 updateConfig() 从默认地址拉取
        val text = store.read().orEmpty()
        if (text.isEmpty()) {
            Log.i(TAG, "cacheChannels isEmpty, will fetch ${SP.configUrl}")
        } else {
            val parsed = safeParse(text)
            if (parsed.isValid()) {
                applyChannels(parsed, text, persist = false)
            } else {
                Log.e(TAG, "cached channels invalid: ${parsed.error}")
                R.string.channel_read_error.showToast()
            }
        }

        if (!cacheEPG.exists()) {
            try {
                cacheEPG.createNewFile()
            } catch (e: Exception) {
                Log.e(TAG, "create epg cache", e)
            }
        } else {
            viewModelScope.launch {
                val textEpg = withContext(Dispatchers.IO) {
                    try {
                        cacheEPG.readText()
                    } catch (e: Exception) {
                        ""
                    }
                }
                if (textEpg.isNotEmpty()) {
                    readEPG(textEpg)
                }
            }
        }

        initialized = true
    }


    private fun safeParse(text: String): ParseResult {
        val decoded = maybeDecode(text)
        return try {
            parser.parse(decoded)
        } catch (e: Exception) {
            Log.e(TAG, "parse", e)
            ParseResult(error = e.javaClass.simpleName)
        }
    }

    private fun maybeDecode(text: String): String {
        return try {
            val gua = Gua()
            if (gua.verify(text)) gua.decode(text) else text
        } catch (e: Exception) {
            text
        }
    }

    // ------------------------------------------------------------ EPG

    fun updateEPG() {
        // EPG 镜像经常整组失效（实测某源 3 个镜像全部超时，每个 20s）。
        // 失败后按 5min→10min→20min→30min 退避，避免每次启动都白跑一轮。
        val now = System.currentTimeMillis()
        val backoffUntil = SP.epgBackoffUntil
        if (now < backoffUntil) {
            Log.i(TAG, "EPG skipped, backoff ${(backoffUntil - now) / 1000}s left")
            return
        }
        if (!epgUpdating.compareAndSet(false, true)) {
            Log.i(TAG, "EPG update already running")
            return
        }
        viewModelScope.launch {
            try {
                var success = false
                val primary = epgUrl
                if (!primary.isNullOrEmpty()) {
                    success = updateEPG(primary)
                }
                if (!success) {
                    val configured = SP.epg
                    if (!configured.isNullOrEmpty() && configured != primary) {
                        success = updateEPG(configured)
                    }
                }
                if (success) {
                    SP.epgFailStreak = 0
                    SP.epgBackoffUntil = 0
                } else {
                    val streak = (SP.epgFailStreak + 1).coerceAtMost(8)
                    SP.epgFailStreak = streak
                    val backoff = com.lizongying.mytv0.net.epgBackoffMillis(streak)
                    SP.epgBackoffUntil = System.currentTimeMillis() + backoff
                    Log.w(TAG, "EPG all mirrors failed, backoff ${backoff / 1000}s (streak=$streak)")
                }
            } finally {
                epgUpdating.set(false)
            }
        }
    }

    private suspend fun updateEPG(url: String): Boolean {
        val urls = url.split(",").flatMap { u -> com.lizongying.mytv0.Utils.getUrls(u.trim()) }
            .filter { it.isNotEmpty() }
            .take(MAX_EPG_ATTEMPTS)

        for (a in urls) {
            val success = withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder().url(a).build()
                    HttpClient.apiClient.newCall(request).execute().use { response ->
                        val body = response.body ?: return@use false
                        if (!response.isSuccessful) {
                            Log.e(TAG, "EPG ${HttpClient.safeUrl(a)} ${response.code}")
                            return@use false
                        }
                        body.byteStream().use { input ->
                            readEPG(input)
                        }
                    }
                } catch (e: Exception) {
                    HttpClient.logFailure("EPG", a, e)
                    false
                }
            }
            if (success) {
                Log.i(TAG, "EPG success ${HttpClient.safeUrl(a)}")
                return true
            }
        }
        return false
    }

    suspend fun readEPG(input: InputStream): Boolean {
        return try {
            val result = EPGXmlParser().parse(input)
            // 源可能是"活着但没数据"（镜像过期 / 只给到前两天的节目）：
            // 解析结果为空时必须当失败处理，否则会挡住后面更新的镜像
            if (result.programmeCount == 0 || result.programmes.isEmpty()) {
                Log.w(TAG, "EPG parsed empty (stale mirror?), try next")
                return false
            }
            val index = EpgIndex.build(result.programmes)
            applyEpg(index)
            true
        } catch (e: Exception) {
            Log.e(TAG, "readEPG", e)
            false
        }
    }

    private suspend fun readEPG(json: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val cached: Map<String, List<EPG>> = gson.fromJson(json, typeEPGMap) ?: emptyMap()
            if (cached.isEmpty()) return@withContext false
            // 兼容旧缓存：旧版以频道名小写为 key，新版以稳定 key 为 key
            val merged = LinkedHashMap<String, List<EPG>>(cached)
            val index = EpgIndex.build(merged)
            withContext(Dispatchers.Main) {
                applyEpg(index, useKey = true)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "readEPG cache", e)
            false
        }
    }

    private suspend fun applyEpg(index: EpgIndex, useKey: Boolean = false) {
        withContext(Dispatchers.Main) {
            val cache = LinkedHashMap<String, List<EPG>>()
            var cachedEntries = 0
            for (m in listModel) {
                val epg = if (useKey) {
                    index.lookup(m.tv.key, m.tv.tvgId) ?: index.lookup(m.tv.matchName(), m.tv.tvgId)
                } else {
                    index.lookup(m.tv.matchName(), m.tv.tvgId)
                }
                if (epg != null) {
                    m.setEpg(epg)
                    if (cachedEntries < MAX_EPG_CACHE_ENTRIES) {
                        cache[m.tv.key] = epg
                        cachedEntries += epg.size
                    }
                }
            }
            Log.i(TAG, "EPG applied ${cache.size}")
            withContext(Dispatchers.IO) {
                try {
                    cacheEPG.writeText(gson.toJson(cache))
                } catch (e: Exception) {
                    Log.e(TAG, "write epg cache", e)
                }
            }
        }
    }

    // ------------------------------------------------------------ 配置更新

    /**
     * 启动时拉取直播源配置。
     *
     * 断网开机的旧行为：第一次请求失败就判死，之后网络恢复也不会再试，界面一直空着。
     * 新行为：在 [NETWORK_WAIT_WINDOW_MS]（3 分钟）内反复重试——
     * 单次尝试有独立的短超时，失败就等几秒再来；拉到了立刻停，超时到点才放弃。
     */
    fun updateConfig() {
        if (!SP.configAutoLoad) return
        val url = SP.configUrl
        if (url.isNullOrEmpty() || !url.startsWith("http")) return
        viewModelScope.launch {
            Log.i(TAG, "auto update config ${HttpClient.safeUrl(url)}")
            val deadline = SystemClock.elapsedRealtime() + NETWORK_WAIT_WINDOW_MS
            var attempt = 0
            while (true) {
                attempt++
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                val ok = importFromUrlInternal(
                    url,
                    id = "",
                    auto = true,
                    budgetMs = minOf(FETCH_ATTEMPT_TIMEOUT_MS, remaining),
                )
                if (ok) {
                    Log.i(TAG, "auto update config ok on attempt $attempt")
                    SP.lastUpdateTime = System.currentTimeMillis()
                    SP.lastUpdateResult = "auto:success:${listModel.size}"
                    updateEPG()
                    _updateInfo.value = SP.lastUpdateResult
                    return@launch
                }
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0L) break
                // 断网时别连珠炮式重连：歇几秒，等网络起来
                delay(minOf(RETRY_INTERVAL_MS, left))
            }
            Log.w(TAG, "auto update config gave up after ${NETWORK_WAIT_WINDOW_MS / 1000}s (attempts=$attempt)")
            SP.lastUpdateTime = System.currentTimeMillis()
            SP.lastUpdateResult = "auto:failed"
            _updateInfo.value = SP.lastUpdateResult
        }
    }

    /** 手动触发一次配置更新，返回是否成功 */
    fun refreshConfig() {
        val url = SP.configUrl
        if (url.isNullOrEmpty()) {
            R.string.channel_import_error.showToast()
            return
        }
        viewModelScope.launch {
            importFromUrlInternal(url, id = "", auto = false)
        }
    }

    fun importFromUri(uri: Uri, id: String = "") {
        if (uri.scheme == "file") {
            viewModelScope.launch {
                val file = withContext(Dispatchers.IO) {
                    try {
                        uri.toFile()
                    } catch (e: Exception) {
                        null
                    }
                }
                val text = withContext(Dispatchers.IO) {
                    try {
                        file?.takeIf { it.exists() }?.readText()
                    } catch (e: Exception) {
                        null
                    }
                }
                if (text.isNullOrEmpty()) {
                    R.string.file_not_exist.showToast()
                    return@launch
                }
                commitText(text, uri.toString(), id, auto = false)
            }
        } else {
            viewModelScope.launch {
                importFromUrlInternal(uri.toString(), id, auto = false)
            }
        }
    }

    private suspend fun importFromUrlInternal(
        url: String,
        id: String,
        auto: Boolean,
        budgetMs: Long = FETCH_TOTAL_TIMEOUT_MS,
    ): Boolean {
        return try {
            val text = fetchText(url, budgetMs = budgetMs)
            if (text.isNullOrEmpty()) {
                Log.w(TAG, "fetch config failed ${HttpClient.safeUrl(url)}")
                if (!auto) R.string.channel_request_error.showToast()
                return false
            }
            commitText(text, url, id, auto)
        } catch (e: Exception) {
            Log.e(TAG, "importFromUrlInternal", e)
            if (!auto) R.string.channel_request_error.showToast()
            false
        }
    }

    /**
     * 拉取文本（含 GitHub 镜像回退），限制单次尝试次数与总耗时，避免启动时卡住。
     */
    private suspend fun fetchText(
        url: String,
        maxBytes: Int = MAX_CONFIG_BYTES,
        budgetMs: Long = FETCH_TOTAL_TIMEOUT_MS,
    ): String? {
        val formatted = com.lizongying.mytv0.Utils.formatUrl(url)
        val urls = com.lizongying.mytv0.Utils.getUrls(formatted).take(MAX_FETCH_ATTEMPTS)
        val deadline = SystemClock.elapsedRealtime() + budgetMs.coerceAtLeast(1L)

        for (u in urls) {
            if (SystemClock.elapsedRealtime() > deadline) {
                Log.w(TAG, "fetch budget exhausted")
                break
            }
            val text = withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder().url(u).build()
                    HttpClient.apiClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.e(TAG, "fetch ${HttpClient.safeUrl(u)} ${response.code}")
                            return@use null
                        }
                        val body = response.body ?: return@use null
                        body.byteStream().use { readLimited(it, maxBytes) }
                    }
                } catch (e: Exception) {
                    HttpClient.logFailure("fetch", u, e)
                    null
                }
            }
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    private fun readLimited(input: InputStream, maxBytes: Int): String? {
        val out = ByteArrayOutputStream(64 * 1024)
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                Log.w(TAG, "content too large")
                return null
            }
            out.write(buffer, 0, read)
        }
        return out.toString("UTF-8")
    }

    /**
     * 解析 + 校验 + 原子提交 + 失败回滚。
     * 任何一步失败都不会覆盖当前配置。
     */
    private suspend fun commitText(text: String, url: String, id: String, auto: Boolean): Boolean {
        if (!configUpdating.compareAndSet(false, true)) {
            Log.i(TAG, "config update already running")
            return false
        }
        return try {
            val parsed = withContext(Dispatchers.IO) { safeParse(text) }

            if (!parsed.isValid()) {
                Log.w(TAG, "invalid config: ${parsed.error} ${parsed.stats}")
                if (!auto) R.string.channel_format_error.showToast()
                SP.lastUpdateResult = "invalid:${parsed.error ?: "empty"}"
                _updateInfo.value = SP.lastUpdateResult
                return false
            }

            // 自动更新时防止"坏源把上千频道替换成几条"
            if (auto && listModel.isNotEmpty() && parsed.validChannelCount < listModel.size / 5) {
                Log.w(
                    TAG,
                    "suspicious config: ${parsed.validChannelCount} vs ${listModel.size}"
                )
                SP.lastUpdateResult = "rejected:too_few_channels"
                _updateInfo.value = SP.lastUpdateResult
                return false
            }

            val previous = cacheChannels
            val applied = withContext(Dispatchers.Main) {
                applyChannels(parsed, maybeDecode(text), persist = true)
            }
            if (!applied) {
                // 应用失败：回滚文件与内存状态
                withContext(Dispatchers.IO) { store.rollback() }
                if (previous.isNotEmpty()) {
                    withContext(Dispatchers.Main) { restoreText(previous) }
                }
                SP.lastUpdateResult = "apply_failed"
                _updateInfo.value = SP.lastUpdateResult
                return false
            }

            if (url.isNotEmpty()) {
                SP.configUrl = url
                sources.addSource(Source(id = id, uri = url))
            }

            SP.lastUpdateTime = System.currentTimeMillis()
            SP.configVersion += 1
            SP.lastUpdateResult = "success:${listModel.size}"
            _updateInfo.value = SP.lastUpdateResult
            if (!auto) R.string.channel_import_success.showToast()
            true
        } catch (e: Exception) {
            Log.e(TAG, "commitText", e)
            if (!auto) R.string.channel_read_error.showToast()
            SP.lastUpdateResult = "exception:${e.javaClass.simpleName}"
            _updateInfo.value = SP.lastUpdateResult
            false
        } finally {
            configUpdating.set(false)
        }
    }

    /** 兼容旧调用方：来自 Web 配置页 / 设置页的文本导入 */
    fun tryStr2Channels(str: String, file: File?, url: String, id: String = "") {
        viewModelScope.launch {
            val ok = commitText(str, url, id, auto = false)
            if (!ok) {
                Log.w(TAG, "channel import failed")
            }
            file?.let {
                if (!ok) {
                    try {
                        it.delete()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    /** 恢复默认：回到内置默认直播源地址并立即重新拉取 */
    fun reset(context: Context) {
        SP.configUrl = SP.DEFAULT_CONFIG_URL
        SP.configAutoLoad = true
        viewModelScope.launch {
            importFromUrlInternal(SP.DEFAULT_CONFIG_URL, id = "", auto = false)
        }
    }

    /** 回滚到上一次成功的配置（备份文件） */
    fun restoreBackup(): Boolean {
        val text = store.readBackup() ?: return false
        val parsed = safeParse(text)
        if (!parsed.isValid()) return false
        return applyChannels(parsed, text, persist = true)
    }

    fun hasBackup(): Boolean = this::store.isInitialized && store.hasBackup()

    // ------------------------------------------------------------ 应用配置

    private fun restoreText(text: String) {
        val parsed = safeParse(text)
        if (parsed.isValid()) {
            applyChannels(parsed, text, persist = false)
        }
    }

    /**
     * 应用解析结果。必须在主线程调用。
     * 返回 false 表示列表构建失败（不会留下半成品状态）。
     */
    private fun applyChannels(parsed: ParseResult, rawText: String, persist: Boolean): Boolean {
        return try {
            // sortOrder 相同的保持配置原始顺序（稳定排序）
            val list = parsed.channels
                .filter { it.uris.isNotEmpty() && it.enabled }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            if (list.isEmpty()) return false

            // ---- 1. 先在内存中构建完整结构（失败时不影响现有状态） ----
            val grouped = LinkedHashMap<String, MutableList<TV>>()
            list.forEachIndexed { index, tv ->
                tv.id = index
                if (tv.key.isEmpty()) {
                    tv.key = ChannelIdentity.key(tv.group, tv.matchName())
                }
                if (tv.displayName.isEmpty()) tv.displayName = tv.showName()
                grouped.getOrPut(tv.group) { mutableListOf() }.add(tv)
            }

            // ---- 2. 持久化（原子写入 + 备份）；失败则中止，旧配置保持不变 ----
            if (persist) {
                val result = store.commit(rawText)
                if (!result.success) {
                    Log.e(TAG, "commit channel file failed: ${result.reason}")
                    return false
                }
            }

            // ---- 3. 提交内存状态 ----
            migrateLikesIfNeeded(list)

            groupModel.initTVGroup()

            val newListModel = ArrayList<TVModel>(list.size)
            var groupIndex = 2
            for ((group, tvs) in grouped) {
                val listTVModel = TVListModel(group.ifEmpty { "未知" }, groupIndex)
                tvs.forEachIndexed { listIndex, tv ->
                    val model = TVModel(tv)
                    model.setLike(SP.getLike(tv.key))
                    model.setGroupIndex(groupIndex)
                    model.listIndex = listIndex
                    listTVModel.addTVModel(model)
                    newListModel.add(model)
                }
                groupModel.addTVListModel(listTVModel)
                groupIndex++
            }

            listModel = newListModel

            // 全部频道
            groupModel.tvGroupValue.getOrNull(1)?.setTVListModel(listModel)

            val changed = initialized && rawText != cacheChannels
            cacheChannels = rawText
            if (changed) {
                groupModel.initPosition()
            }

            parsed.epgUrls.firstOrNull()?.let { epgUrl = it }

            groupModel.setChange()

            // 首次拿到数据（缓存或远程导入）时通知 UI 播放
            if (_channelsOk.value != true) {
                _channelsOk.value = true
            }

            viewModelScope.launch {
                preloadLogo()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "applyChannels", e)
            false
        }
    }

    private fun migrateLikesIfNeeded(list: List<TV>) {
        if (SP.likeVersion >= SP.LIKE_VERSION) return
        try {
            val idToKey = HashMap<Int, String>(list.size)
            list.forEach { tv -> if (tv.key.isNotEmpty()) idToKey[tv.id] = tv.key }
            val old = SP.getLikes()
            val migrated = ChannelIdentity.migrateLikes(old, idToKey)
            SP.setLikes(migrated)
            SP.likeVersion = SP.LIKE_VERSION
            Log.i(TAG, "likes migrated ${old.size} -> ${migrated.size}")
        } catch (e: Exception) {
            Log.e(TAG, "migrateLikes", e)
        }
    }

    // ------------------------------------------------------------ 台标预加载

    suspend fun preloadLogo() {
        if (!this::imageHelper.isInitialized) {
            Log.w(TAG, "imageHelper is not initialized")
            return
        }

        val targets = listModel.take(MAX_PRELOAD_LOGO)
        for (tvModel in targets) {
            val tv = tvModel.tv
            val name = tv.matchName()
            if (name.isEmpty()) continue
            // 源里给了台标就优先用源的；没给就查台标库（Gitee，国内可直连），
            // 候选顺序：精确名 → 4K 变体 → 去清晰度后缀。都取不到时由 UI 画文字台标。
            val logoUrls = if (tv.logo.isNotEmpty()) {
                com.lizongying.mytv0.Utils.getUrls(tv.logo).take(MAX_LOGO_MIRRORS)
            } else {
                com.lizongying.mytv0.requests.LogoUrls.candidates(name)
            }
            if (logoUrls.isEmpty()) continue
            imageHelper.preloadImage(name, logoUrls)
        }
    }

    fun persistStats() {
        try {
            SP.sourceStats = TVModel.stats.toJson()
        } catch (e: Exception) {
            Log.e(TAG, "persistStats", e)
        }
        // 线路记忆按间隔落盘，这里强制刷一次，保证"退出后再启动还是这个源"
        try {
            com.lizongying.mytv0.playback.SourceMemory.flush()
        } catch (e: Exception) {
            Log.e(TAG, "persist source memory", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        persistStats()
    }

    companion object {
        private const val TAG = "MainViewModel"
        const val CACHE_FILE_NAME = "channels.txt"
        const val CACHE_EPG = "epg.xml"

        private const val MAX_CONFIG_BYTES = 8 * 1024 * 1024
        private const val FETCH_TOTAL_TIMEOUT_MS = 60_000L

        /** 启动断网宽限：3 分钟内持续尝试联网拉配置，超过就放弃 */
        private const val NETWORK_WAIT_WINDOW_MS = 3 * 60 * 1000L

        /** 每次尝试的独立超时（不能一次把 3 分钟吃掉） */
        private const val FETCH_ATTEMPT_TIMEOUT_MS = 15_000L

        /** 两次尝试之间的间隔 */
        private const val RETRY_INTERVAL_MS = 6_000L
        private const val MAX_FETCH_ATTEMPTS = 3
        private const val MAX_EPG_ATTEMPTS = 3
        private const val MAX_PRELOAD_LOGO = 80
        private const val MAX_LOGO_MIRRORS = 2
        private const val MAX_EPG_CACHE_ENTRIES = 60_000
    }
}
