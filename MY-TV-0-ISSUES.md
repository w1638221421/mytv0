# MY-TV-0 问题清单（含修复方案与验收标准）

> 严重程度：**P0** 安全/核心播放/崩溃；**P1** 重要功能/兼容/稳定性；**P2** 体验与维护性；**P3** 长期规划。
> 每条问题均给出：文件 · 类 · 方法 · 现状 · 原因 · 方案 · 代码 · 风险 · 测试 · 验收。

---

## P0-1 HTTPS 证书校验被完全关闭

| 项 | 内容 |
|---|---|
| 严重程度 | **P0（安全）** |
| 文件 | `app/src/main/java/com/lizongying/mytv0/requests/HttpClient.kt` |
| 类 / 方法 | `HttpClient` / `createBuilder()` |

**当前实现**
```kotlin
val trustManager = object : X509TrustManager {
    override fun checkServerTrusted(chain: ..., authType: String?) { }  // 空实现
    override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
}
...
.sslSocketFactory(sslContext.socketFactory, trustManager)
.hostnameVerifier { _, _ -> true }
```

**存在的问题**：任何证书都被接受、任何域名都通过校验。中间人可替换直播源、EPG、版本信息，进而下发任意 APK。

**产生原因**：为兼容自签名/过期证书的 IPTV 源，直接关闭了校验，而不是按主机粒度做可控配置。

**修改方案**：改用系统 CA（`TrustManagerFactory`）+ OkHttp 默认严格域名校验；保留 `COMPATIBLE_TLS` 与 `CLEARTEXT` 以兼容老服务器和明文 IPTV；不提供任何"信任全部"开关。

**修改后代码**
```kotlin
// requests/TlsSecurity.kt（新增）
fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder {
    val tm = systemTrustManager()          // TrustManagerFactory.getInstance("X509").init(null)
    builder.sslSocketFactory(sslContext(tm).socketFactory, tm)
    builder.connectionSpecs(connectionSpecs())   // MODERN_TLS(1.3/1.2) + COMPATIBLE_TLS + CLEARTEXT
    return builder                        // 不设置 hostnameVerifier → 使用 OkHttp 默认严格校验
}
```
`HttpClient.createBuilder()` 被删除，统一由 `TlsSecurity.apply()` 接管。

**潜在风险**：使用自签名证书的私有源将无法播放（这是安全修复的必然代价）；已在设置页保留代理配置，用户可通过受信任的反代访问。

**测试方法**：`TlsSecurityTest.selfSignedCertificateIsRejected`（本地自签名 HTTPS 服务，断言握手失败）；`HostnameVerificationTest.clientRejectsWrongHostname`（证书 SAN=example.org，断言 `example.com`/IP 均被拒绝）；`trustManagerHasRealAnchors`（`acceptedIssuers` 非空）。

**验收标准**：自签名/错误域名 HTTPS 请求必须失败；系统 CA 签名站点正常；HTTP 明文源不受影响。

---

## P0-2 远程配置服务无鉴权且默认开启

| 项 | 内容 |
|---|---|
| 严重程度 | **P0（安全）** |
| 文件 | `SimpleServer.kt`、`MainActivity.kt`、`SettingFragment.kt` |
| 类 / 方法 | `SimpleServer.init/serve/handleImportText/handleProxy`、`MainActivity.ready()` |

**当前实现**：`init { start() }` 无条件监听 `0.0.0.0:34567`；`serve()` 直接按 URI 分发，无 token、无限流、无 body 上限；`MainActivity.ready()` 中 `server = SimpleServer(this, viewModel)`。

**存在的问题**：同网段任意设备可读写全部配置；`GET /api/settings` 会返回完整频道文本；`POST /api/import-text` 无大小限制；`handleSources` 用 `runBlocking` 在请求线程拉取外网。

**产生原因**：功能优先，未考虑局域网威胁模型；把"扫码配置"等同于"无需认证"。

**修改方案**：默认关闭 + 128bit 随机令牌（30 分钟有效、可重新生成）+ 常量时间比较 + 按 IP 限流 + body 上限 + 端口自动回退 + `stop()` 释放端口。令牌通过二维码 URL `?t=` 携带。

**修改后代码**
```kotlin
// server/AccessToken.kt
fun isValid(candidate: String?): Boolean {
    if (candidate.isNullOrEmpty() || isExpired()) return false
    return MessageDigest.isEqual(candidate.toByteArray(), value.toByteArray())
}

// SimpleServer.serve()
if (!generalLimiter.allow(ip)) return json(TOO_MANY_REQUESTS, """{"ok":false,"error":"rate_limited"}""")
if (!isAuthorized(session)) return json(Response.Status.UNAUTHORIZED, """{"ok":false,"error":"unauthorized"}""")
if (session.method == Method.POST && !writeLimiter.allow(ip)) return json(TOO_MANY_REQUESTS, ...)
```
```kotlin
// MainActivity
private fun startServerIfEnabled() { if (!SP.remoteServer) return; startServer() }
fun startServer(): Boolean { serverToken.regenerate(); ... }   // 每次开启换新令牌
```

**潜在风险**：用户升级后需要重新在设置页打开服务；旧书签链接失效（安全预期行为）。

**测试方法**：`AccessTokenTest`（有效期/重新生成/主动失效）、`RateLimiterTest`（窗口限流、key 数量上限）。

**验收标准**：默认不监听端口；无令牌请求返回 401；令牌过期后写接口全部拒绝；超过限流返回 429；关闭开关后端口释放。

---

## P0-3 崩溃日志默认外传，且崩溃时可能死锁

| 项 | 内容 |
|---|---|
| 严重程度 | **P0（隐私 + 稳定性）** |
| 文件 | `MyTVExceptionHandler.kt` |
| 类 / 方法 | `MyTVExceptionHandler.uncaughtException/saveLog` |

**当前实现**：无条件 `runBlocking { launch { saveLog(crashInfo) } }` POST 到 `https://lyrics.run/my-tv-0/v1/log`，内容含设备型号与完整堆栈（可能含频道 URL 与 token）。

**存在的问题**：① 未经授权上传；② 主线程崩溃时 `withContext(Dispatchers.Main)` 永远无法调度，进程卡死；③ 日志无上限、无清理。

**产生原因**：把"收集崩溃"当成默认行为，且用协程包装同步网络请求。

**修改方案**：默认只写本地；上传需用户在设置页显式开启；落盘前脱敏（去 query、去凭据、token/password 掩码）；单文件 256KB、最多 5 个、保留 14 天；提供"导出诊断/清除日志"入口。

**修改后代码**
```kotlin
override fun uncaughtException(t: Thread, e: Throwable) {
    val info = redact(buildReport(t, e))
    val writer = Thread({ persist(info) }, "mytv-crash-writer").apply { isDaemon = true }
    writer.start(); writer.join(1500)          // 有超时，不再阻塞主线程
    Process.killProcess(Process.myPid()); exitProcess(10)
}
```
```kotlin
private fun uploadIfConsented(info: String) {
    if (!SP.crashReport) return                  // 默认 false
    ...
}
```

**潜在风险**：作者侧崩溃数据减少（隐私优先，符合要求）。

**测试方法**：代码审查 + `redact` 规则人工核对；`export()`/`clear()` 在设置页可手动验证。

**验收标准**：默认配置下抓包无任何外发请求；崩溃 1.5 秒内进程退出；日志目录不超过 5 个文件。

---

## P0-4 `onVideoSizeChanged` 整数除零崩溃

| 项 | 内容 |
|---|---|
| 严重程度 | **P0（崩溃）** |
| 文件 | `PlayerFragment.kt` |
| 类 / 方法 | `PlayerFragment.updatePlayer()` 内部匿名 `Player.Listener.onVideoSizeChanged` |

**当前实现**
```kotlin
val ratio = playerView.measuredWidth.div(playerView.measuredHeight)
```
**存在的问题**：`measuredHeight == 0` 时抛 `ArithmeticException`，冷启动/布局未完成时必现概率不低。

**产生原因**：用 View 测量值代替视频宽高，且没有防御。

**修改方案**：改用 `PlayerView` 内建 `AspectRatioFrameLayout`，`resizeMode = FIT` 处理比例；回调只接收 `VideoSize` 比例用于日志。

**修改后代码**
```kotlin
binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
...
override fun onVideoRatio(ratio: Float) { Log.d(TAG, "video ratio $ratio") }
```

**潜在风险**：4:3 频道改为等比留黑边（原 `fill` 会拉伸，属于修正）。

**测试方法**：`PlayerFragment` 冷启动 + 4:3 / 16:9 / 21:9 片源切换。

**验收标准**：任意比例片源不崩溃、不拉伸、无黑屏。

---

## P0-5 自更新安装必然崩溃且无签名校验

| 项 | 内容 |
|---|---|
| 严重程度 | **P0（崩溃 + 供应链）** |
| 文件 | `UpdateManager.kt` |
| 类 / 方法 | `UpdateManager.DownloadReceiver.installNewVersion()` |

**当前实现**
```kotlin
val apkUri = Uri.parse("file://$apkFile")
installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive")
```
**存在的问题**：Android 7.0+ 抛 `FileUriExposedException`；未校验 APK 包名/签名；下载文件不存在也会尝试安装。

**修改方案**：`FileProvider` + `Content Uri`；安装前校验包名、签名（与当前应用一致）、文件大小 > 1MB；失败给出提示并删除坏包。

**修改后代码**
```kotlin
val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apkFile)
setDataAndType(uri, APK_MIME).addFlags(FLAG_GRANT_READ_URI_PERMISSION or FLAG_ACTIVITY_NEW_TASK)
...
if (!verifyApk(apkFile)) { "安装包校验失败".showToast(); apkFile.delete(); return }
```
配套新增 `res/xml/file_paths.xml` 与 manifest `FileProvider` 声明。

**潜在风险**：自编译 APK 与官方签名不一致时无法覆盖安装（预期行为，提示用户卸载后安装）。

**测试方法**：构造签名不一致的 APK 走下载流程，断言拒绝安装。

**验收标准**：Android 7~14 均能拉起安装器；篡改包被拒绝。

---

## P0-6 播放重试无上限、无超时

| 项 | 内容 |
|---|---|
| 严重程度 | **P0（播放稳定性）** |
| 文件 | `PlayerFragment.kt`、`models/TVModel.kt` |
| 类 / 方法 | `PlayerFragment.play()`、`onPlayerError()`、`TVModel.nextVideo()/nextSourceType()` |

**当前实现**：`retryTimes` 上限 10，但地址与封装类型同时轮转（`nextVideo()` 取模、`nextSourceType()` 取模），组合后可持续循环；无准备/首帧超时；`while (true)` 在 `play()` 中遍历地址。

**存在的问题**：坏源会长时间占用播放器；连续切台时旧回调仍作用于新频道；`play()` 的 `while(true)` 在极端输入下可能长时间占用主线程。

**修改方案**：新增 `PlaybackController` + `PlaybackRecovery`：单地址重试 ≤2、单类型 ≤2、总尝试 ≤6、地址轮换 ≤n-1；准备 15s、首帧 12s、缓冲 20s 三个看门狗；generation 令牌丢弃过期回调。

**修改后代码**
```kotlin
// playback/PlaybackRecovery.kt
FailureKind.TIMEOUT, DNS, SERVER, RATE_LIMIT -> when {
    state.urlAttempts < maxUrlAttempts -> RETRY_SAME
    state.hasMoreTypes && state.typeAttempts < maxTypeAttempts -> NEXT_SOURCE_TYPE
    state.hasMoreUrls -> NEXT_URL
    else -> GIVE_UP
}
// playback/PlaybackController.kt
scheduleWatchdog(PREPARE_TIMEOUT_MS, "prepare")   // 15s
```

**潜在风险**：重试更保守，极差网络下更快给出错误提示（优于无限转圈）。

**测试方法**：`PlaybackRecoveryTest`（含"总预算必然收敛"用例，1000 个地址也只尝试 ≤6 次）。

**验收标准**：任何失败场景在 ≤6 次尝试内结束并显示错误；连续快速切台无错乱。

---

## P0-7 直播源更新失败会覆盖旧配置

| 项 | 内容 |
|---|---|
| 严重程度 | **P0（数据丢失）** |
| 文件 | `MainViewModel.kt` |
| 类 / 方法 | `MainViewModel.tryStr2Channels()/str2Channels()` |

**当前实现**
```kotlin
if (str2Channels(str)) {
    cacheFile!!.writeText(str)      // 解析后立刻覆盖
```
**存在的问题**：空列表/全无效条目也返回 `true`；写入非原子；无备份；解析成功但应用失败时无回滚。

**产生原因**：把"解析没抛异常"当作"配置有效"。

**修改方案**：`ChannelParser` 返回结构化 `ParseResult`（含 `validChannelCount`）+ `ChannelStore` 原子写（tmp → fsync → 备份 → rename）+ 自动更新时"频道数骤降 80%"保护 + 失败回滚。

**修改后代码**
```kotlin
// store/ChannelStore.kt
fun commit(text: String): CommitResult   // 空内容/超 8MB 拒绝；旧文件先复制为 .bak
// MainViewModel.commitText()
if (!parsed.isValid()) return false
if (auto && listModel.isNotEmpty() && parsed.validChannelCount < listModel.size / 5) return false
val applied = applyChannels(parsed, text, persist = true)   // 先构建，再提交文件，再换内存
if (!applied) { store.rollback(); restoreText(previous); return false }
```

**潜在风险**：极少数"合法但频道数骤减"的源会被自动更新拒绝，用户可手动在设置页导入。

**测试方法**：`ChannelStoreTest`（提交/备份/回滚/超大内容拒绝）、`ChannelParserTest.parse_emptyOrGarbageIsInvalid`。

**验收标准**：任何非法/空/超大配置都不会改变现有 `channels.txt`；失败后仍可正常播放原频道。

---

## P0-8 JSON 直播源缺字段导致 NPE

| 项 | 内容 |
|---|---|
| 严重程度 | **P0（崩溃）** |
| 文件 | `MainViewModel.kt` |
| 类 / 方法 | `MainViewModel.str2Channels()`（Gson 反序列化 `List<TV>`） |

**当前实现**：`gson.fromJson(string, typeTvList)` 直接映射到非空 `String` 字段；Gson 用 Unsafe 实例化，缺失字段被赋 `null`。后续 `k.ifEmpty()`（`TVListModel(k.ifEmpty { "未知" })`）对 `null` 接收者抛 NPE。

**存在的问题**：用户写一个 `[{"title":"CCTV1","uris":["..."]}]` 就足以让应用在启动/更新时崩溃。

**修改方案**：不再用 Gson 直接映射 `TV`，改为逐字段手工解析（`JsonObject` + 默认值 + 类型校验），并对 URL/头部名称做合法性过滤。

**修改后代码**
```kotlin
// parser/ChannelParser.kt
private fun tvFromJson(o: JsonObject): TV? {
    fun str(vararg names: String): String { ... }   // 缺失/JsonNull → ""
    ...
    return TV(name = name.ifEmpty { title }, title = title.ifEmpty { name }, ...)
}
```

**潜在风险**：自定义字段（非标准 JSON）不再被 Gson 自动映射；已覆盖原有全部字段。

**测试方法**：`ChannelParserTest.parseJson_minimalFieldsDoNotCrash`、`parseJson_nullFieldsAreTolerated`。

**验收标准**：任意缺字段 JSON 不崩溃，且缺 `uris` 的条目被安全跳过。

---

## P0-9 多处 `!!` 导致的 NPE

| 项 | 内容 |
|---|---|
| 严重程度 | **P0（崩溃）** |
| 文件 | `MainActivity.kt`、`ChannelFragment.kt`、`TVGroupModel.kt` |
| 类 / 方法 | `MainActivity.onPlayEnd()/play()`、`ChannelFragment.show(Int)`、`TVGroupModel.getPosition()` |

**当前实现**
```kotlin
val tvModel = viewModel.groupModel.getCurrent()!!        // onPlayEnd
position < viewModel.groupModel.getAllList()!!.size()     // play
val tv = viewModel.groupModel.getCurrent()!!.tv           // ChannelFragment.show
if (tvGroupValue[1].size() == 0)                          // 分组未初始化
```
**存在的问题**：频道列表为空、分组被重建、配置更新瞬间都会触发崩溃。

**修改方案**：全部改为可空链路 + 明确错误提示；`getTVListModel` 增加边界检查；`defaultPosition()` 增加分组数不足时的降级。

**修改后代码**
```kotlin
fun onPlayEnd() { val tvModel = viewModel.groupModel.getCurrent() ?: return; ... }
val all = viewModel.groupModel.getAllList() ?: return false
val tv = viewModel.groupModel.getCurrent()?.tv ?: run { hideSelf(); return }
```

**潜在风险**：无。

**测试方法**：清空 `channels.txt` 后启动；配置更新瞬间连按返回/数字键。

**验收标准**：列表为空时不崩溃、给出"频道不存在"提示。

---

## P1-1 修改代理后仍复用旧客户端

| 项 | 内容 |
|---|---|
| 严重程度 | **P1（功能）** |
| 文件 | `requests/HttpClient.kt` |
| 类 / 方法 | `HttpClient.getClientWithProxy()/builder` |

**当前实现**：`val builder by lazy { createBuilder() }`，`getClientWithProxy()` 反复在**同一个** Builder 上 `builder.proxy(...)` 再 `build()`。

**存在的问题**：清除代理后 `builder` 仍保留旧 `Proxy` 对象，于是"取消代理"无效；`clientCache` 以 `SP.proxy` 为 key，但 client 实际由"历史 builder 状态"决定，键值不一致。

**修改方案**：客户端按 `(media|api, proxySpec)` 独立构建并缓存（LRU 4），代理变化天然产生新键；不再共享可变 Builder。

**修改后代码**
```kotlin
fun client(media: Boolean, proxy: String? = SP.proxy): OkHttpClient {
    val spec = ProxyConfig.parse(proxy)
    val key = (if (media) "m:" else "a:") + (spec?.toString() ?: "direct")
    synchronized(clients) { clients[key]?.let { return it }; return build(spec, media).also { clients[key] = it } }
}
```

**测试方法**：`NetworkConfigTest.proxyParsing`；手动切换代理后访问 `api.ipify.org` 验证出口 IP。

**验收标准**：设置/清除代理立即生效，无需重启。

---

## P1-2 媒体与接口共用同一超时策略

**严重程度**：P1｜文件：`requests/HttpClient.kt`

**当前实现**：单例 `okHttpClient`，全部使用 OkHttp 默认 10s 读超时。

**存在的问题**：直播流缓冲超过 10s 会被判定 `SocketTimeoutException`，表现为"看几秒就断"；而接口请求又没有总时长上限。

**修改方案**：`okHttpClient`（媒体，`readTimeout=0`）+ `apiClient`（接口，connect 8s / read 15s / call 35s）。

**修改后代码**：见 `HttpClient.build()` 中 `if (media) { readTimeout(0) } else { ... }`；`MainViewModel`/`UpdateManager`/`ImageHelper`/`Utils` 全部改用 `apiClient`。

**测试方法**：播放 30s 以上直播流；弱网下加载配置页。

**验收标准**：长连接不断流；接口卡死 35s 内返回失败。

---

## P1-3 DNS 永久缓存

**严重程度**：P1｜文件：`requests/DnsCache.kt`

**当前实现**：`dnsCache[hostname]` 命中即返回，永不过期；无上限；IPv4 在 IPv6 之前（这一点是好的）。

**存在的问题**：CDN 换 IP 后无法恢复；大量域名导致 Map 无限增长；解析失败无负缓存，切台时反复同步解析。

**修改方案**：正缓存 5 分钟 / 负缓存 30 秒 / 最多 256 条 / 提供 `clear()`。

**修改后代码**：见 `DnsCache` 的 `Entry(addresses, expireAt)` 与 `put()` 清理逻辑。

**测试方法**：连续解析同一域名并观察 TTL 行为（可在 Logcat 中观察 OkHttp 事件）。

**验收标准**：域名变更后 5 分钟内生效；内存不随域名数量增长。

---

## P1-4 收藏按序号存储，换源后错位；StringSet 并发

**严重程度**：P1｜文件：`SP.kt`、`MainViewModel.kt`、`MainActivity.kt`

**当前实现**：`SP.getLike(id: Int)` 以解析序号（`0,1,2...`）为 key；`str2Channels` 每次重新从 0 编号。

**存在的问题**：更换直播源后，原收藏会落到完全不同的频道上；`getStringSet` 返回内部实例，多处并发 `toMutableSet` 再 `putStringSet` 会丢更新。

**修改方案**：新增稳定 `TV.key = md5(归一化分组 + 归一化名称)`；收藏改存 key；一次性迁移旧序号（`like_version`），迁移失败不清空；所有读写加锁并 `commit()`。

**修改后代码**
```kotlin
// parser/ChannelIdentity.kt
fun key(group: String?, name: String?) = md5("${normalizeName(group)}\u0001${normalizeName(name)}")
// SP.kt
fun setLike(key: String, liked: Boolean) = synchronized(likeLock) {
    val current = sp.getStringSet(KEY_LIKE, emptySet())?.toMutableSet() ?: mutableSetOf()
    if (liked) current.add(key) else current.remove(key)
    sp.edit().putStringSet(KEY_LIKE, current).commit()
}
```

**测试方法**：`ChannelIdentityTest`（key 稳定性/分组区分/迁移）；手动：收藏 3 个频道 → 更换直播源 → 收藏仍指向同名频道。

**验收标准**：换源后收藏不丢失、不错配。

---

## P1-5 EPG 解析与时区/溢出/匹配问题

**严重程度**：P1｜文件：`models/EPGXmlParser.kt`、`MainViewModel.readEPG`

**当前实现**
```kotlin
private val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault())
...
if (formatFTime(stop) > now) epg[channel]?.add(...)
```
**存在的问题**：
1. `SimpleDateFormat` 非线程安全（当前每次 new parser 尚可，但属于隐性炸弹）；
2. 时间用 `Int` 秒，2038 溢出；
3. `programme` 出现在 `channel` 之前会被丢弃（`epg[channel]?` 为 null）；
4. 空 `<title/>` 时 `nextText()` 抛异常，整个 EPG 失败；
5. 频道匹配用 `name.contains(n)`，`CCTV1` 会命中 `CCTV10`；
6. 缓存在 `Dispatchers.Main` 内写盘。

**修改方案**：`XmlPullParserFactory` + 手工时间解析（支持 `±HHMM`）+ `Long` + 两阶段映射 + 容错读取 + `EpgIndex`（id 精确 → 名称精确 → 边界感知包含）+ IO 线程写缓存。

**修改后代码**：见 `EPGXmlParser.parse()` 与 `EpgIndex.lookup()`。

**测试方法**：`EpgXmlParserTest`（7 例：时区、非法时间、programme 前置、空标题、畸形 XML、CCTV1/CCTV10 不串台）。

**验收标准**：上述 6 类输入均正确处理，播放不受影响。

---

## P1-6 播放器监听器重复注册与资源释放

**严重程度**：P1｜文件：`PlayerFragment.kt`

**当前实现**：每次 `updatePlayer()`（软硬解切换、`onViewCreated`）都 `player.release()` 后新建并 `addListener`；`onDestroy` 只 `release()`，未移除监听；`onDestroyView` 不释放。

**存在的问题**：Fragment 视图重建后旧 player 仍持有 View 引用；切换软解后旧实例的监听仍可能触发。

**修改方案**：`PlaybackController` 持有唯一 player，`release()` 幂等（removeListener → playerView.player = null → release）；`onDestroyView` 调用。

**修改后代码**：`PlayerFragment.onDestroyView { controller?.release(); controller = null; _binding = null }`。

**测试方法**：反复进入/退出设置页、切换软解 20 次，观察 `dumpsys meminfo` 与 Logcat 中 player 创建次数。

**验收标准**：播放器实例数稳定，内存不持续增长。

---

## P1-7 `onPause` 调用 `stop()` 导致恢复黑屏

**严重程度**：P1｜文件：`PlayerFragment.kt`

**当前实现**：`onPause { if (player?.isPlaying == true) player?.stop() }`，`onResume { prepare(); play() }`。

**存在的问题**：每次回到前台都重新起播，直播流需要重新建连（2~5s 黑屏），且 `stop()` 会清空缓冲。

**修改方案**：改为 `pause()` / `resume()`；仅在 `STATE_IDLE` 时才重新 prepare。

**修改后代码**：`PlaybackController.pause()/resume()`。

**测试方法**：HOME 键往返 10 次，计时恢复首帧。

**验收标准**：恢复播放 < 1s，无黑屏。

---

## P1-8 台标预加载请求风暴

**严重程度**：P1｜文件：`MainViewModel.preloadLogo()`、`ImageHelper`

**当前实现**：对**全部**频道逐个尝试 14 个 GitHub 镜像（`getUrls`），串行执行；下载直接写目标文件，无大小限制。

**存在的问题**：2000 个频道 → 最多 28000 次请求；启动即占满带宽，影响首帧；半截文件被永久缓存。

**修改方案**：最多 80 个频道、每频道最多 3 个候选地址、下载走 `apiClient`、2MB 上限、临时文件 + rename。

**修改后代码**：`preloadLogo()` 的 `take(MAX_PRELOAD_LOGO)` 与 `ImageHelper.downloadImage` 的 tmp 文件逻辑。

**测试方法**：抓包统计启动 30s 内请求数。

**验收标准**：启动请求数 < 100；坏图不落盘。

---

## P1-9 更新模块 Cursor 泄漏 / 进度失效 / 接收器泄漏

**严重程度**：P1｜文件：`UpdateManager.kt`

**当前实现**：`if (statusIndex < 0) return` 前未 `cursor.close()`；`getDownloadProgress` 中 `postDelayed` 被注释；`destroy()` 未在 `SettingFragment.onDestroyView` 调用；进度用 `getInt` 读字节数（>2GB 溢出）。

**修改方案**：全部 `Cursor.use {}`；轮询 Runnable 自我重排并在完成/失败/销毁时移除；`onDestroyView` 调用 `destroy()`；字节数用 `Long`；支持 `cancelDownload()`。

**测试方法**：`UpdateManagerTest` 相关逻辑（版本比较）；手动下载中断/重试。

**验收标准**：连续检查更新 20 次无 FD 增长；下载失败有提示。

---

## P1-10 `watch()` 观察者无限累积

**严重程度**：P1｜文件：`MainActivity.kt`

**当前实现**：`groupModel.change` 每次触发都 `listModel.forEach { errInfo.observe(); ready.observe(); like.observe() }`，旧模型观察者从不注销。

**存在的问题**：每次更新直播源，观察者数量翻倍，最终每个频道回调执行多次（重复切台、卡顿）。

**修改方案**：记录 `watchedTvModels`，重新注册前 `removeObservers(this)`。

**修改后代码**：见 `watch()` 开头。

**测试方法**：连续导入 5 次直播源后切台，观察 `play()` 调用次数。

**验收标准**：切台只触发一次播放。

---

## P1-11 数字键选台在收藏非空时错位

**严重程度**：P1｜文件：`MainActivity.play(position)`

**当前实现**：`groupModel.getPosition(position)` 在"收藏 + 全部 + 分组"上累加位置，而 `position` 来自"全部频道"的索引。

**存在的问题**：收藏非空时，输入频道号会跳到错误频道。

**修改方案**：直接索引"全部频道"列表，并同步分组位置与列表位置。

**修改后代码**：见 `MainActivity.play()` 新实现。

**测试方法**：收藏 2 个频道后输入 `12`，核对播放频道。

**验收标准**：数字键与频道号严格对应。

---

## P2 级问题（摘要）

| 编号 | 问题 | 位置 | 修复 |
|---|---|---|---|
| P2-1 | `sortOrder` 字段未被使用 | `MainViewModel.applyChannels` | 按 `sortOrder` 稳定排序后再编号 |
| P2-2 | 一个频道多地址无法分别配置请求头/优先级/成功记忆 | `TV`/`TVModel` | 新增 `TVSource` + `SourceStats`，按 优先级→最近成功→原始顺序 排序 |
| P2-3 | `MenuFragment` 对 adapter 强转可能 NPE | `MenuFragment.update/updateList` | 改为 `as?` + 空判断 |
| P2-4 | 配置页 `innerHTML` 拼接直播源，存在注入风险 | `res/raw/index.html` | 全部改为 `document.createElement` + `.value` |
| P2-5 | 二维码 10 秒自动消失，来不及扫码 | `ModalFragment` | 延长至 60 秒 |
| P2-6 | 版本比较用 `>=`，同版本也提示更新 | `UpdateManager.checkAndUpdate` | 抽出 `VersionPolicy.isNewer()` 严格比较 |
| P2-7 | 无更新时间/结果/版本号，出问题无法诊断 | `SP` | 新增 `lastUpdateTime/lastUpdateResult/configVersion` |
| P2-8 | 发布版 `network.xml` 信任用户 CA | `res/xml/network.xml` | 仅系统 CA，`debug-overrides` 放开 |
| P2-9 | `index.html` 无令牌，接口返回格式变更后无法解析 | `index.html` + `/api/sources` | 令牌注入 + JSON 解析 + 401 提示 |

## P3 级（长期，未实施，理由见重构方案）

| 编号 | 内容 | 决策 |
|---|---|---|
| P3-1 | 迁移 DataStore/Room | 不做：低内存盒子收益低、迁移风险高；现有 SharedPreferences 已加锁 |
| P3-2 | 双播放器预加载无缝切台 | 不做：与"低内存设备"约束冲突，明确禁止 |
| P3-3 | 定时自动更新直播源 | 未做：需 WorkManager（新依赖），当前保留启动更新 + 手动更新 |
| P3-4 | 频道图标 CDN 自建 | 未做：涉及服务端，超出本次范围 |
