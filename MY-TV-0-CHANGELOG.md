# MY-TV-0 修改记录（Changelog）

> 基线：`my-tv-0-main.zip`（lizongying/my-tv-0, 2025-05-12 快照）
> 版本：正式版 `1.0.0`（前一版：审计改造版 `0.0.0-audit.1`）
> 规模：新增 12 个源文件、重写 9 个、修改 14 个；Kotlin 净增约 1,050 行（+2,421 / −1,376），新增测试 64 例。

---

---

## v1.0.1（2026-11-07，正式版补丁）

两个用户可感知的问题：断网开机后"判死"不再重试；节目单一直空。

### 一、断网启动宽限 3 分钟

旧行为：启动时配置拉取失败一次就停手；播放失败一次就停在错误页。之后网络恢复也不会重来，
必须杀进程重开。用户实测"断网开 APP，20 秒后判没网，联网了也不动"。

| 文件 | 改动 |
|---|---|
| `MainViewModel.updateConfig()` | 改成 **3 分钟窗口内的重试循环**：每轮 15 秒独立超时，失败后隔 6 秒再试；拿到配置立即停止；`NETWORK_WAIT_WINDOW_MS=180_000` / `FETCH_ATTEMPT_TIMEOUT_MS=15_000` / `RETRY_INTERVAL_MS=6_000` |
| `MainViewModel.fetchText()` / `importFromUrlInternal()` | 增加 `budgetMs` 参数，单轮不会吃掉整个窗口（原来是 60 秒总预算，一次就能耗掉） |
| `MainActivity` | 新增 `startupRetryRunnable` + `startStartupNetWindow()`：启动后 3 分钟内每 6 秒检查当前频道，网络类失败就静默重试；出画即停；后台（`onPause` 之后）不重试，避免桌面突然出声；`onDestroy` 反注册 |
| `PlaybackController.retryCurrent()`（新） | 失败态下重置重试计数并重新 `prepare`，不切频道、不走 loading 页，避免每 6 秒闪一次界面 |
| `PlayerFragment.retryCurrent()`（新） | 暴露给 Activity |
| `TVModel.lastFailureKind` | 记录最近失败类型，出画清空；`PlayerFragment.onPlaybackFailed` 写入 |
| `FailureKind.isNetwork` | 只对 TIMEOUT / DNS / TLS / SERVER / RATE_LIMIT 重试；404、解码错误重试无意义，不重试 |

### 二、节目单换源 + 匹配增强

- 旧默认 `https://live.fanmingming.cn/e.xml` 与 `raw.githubusercontent.com` 双双失效：
  前者域名解析到 `2a03:2880:f127:83:face:b00c:0:25de`（非自有主机），后者国内不可达；
- 新默认（`SP.DEFAULT_EPG`，逗号分隔，第一个成功即停）：
  `open-epg 中国区 china1.xml` → `http://epg.51zmt.top:8000/e.xml`（国内电信线，实测 1MB / 7367 条） →
  `raw.githubusercontent.com/fanmingming/live/main/e.xml`（由 `Utils.getUrls` 展开成 GitHub 加速镜像）；
- 升级迁移：`SP.LEGACY_DEFAULT_EPG` 命中时自动替换为新默认，用户自己填过的地址不动；
- `EpgIndex`：新增 `matchKey()`（`+`→`plus`、去 `.cn/.com` 等地域后缀）与 `boundaryOk()`
  （后一位是数字或拉丁字母即拒绝），并补"EPG 名包含频道名"的前缀匹配；
  实测 `CCTV1→16 条`、`CCTV5→11 条`、`CCTV5+` 不再串到 CCTV5；
- `MainViewModel.readEPG()`：解析结果为空视为失败，继续试下一个镜像
  （旧逻辑会把"活着但只给前两天的"镜像判成功，直接挡住后面更新的源）。

实测（本机沙箱，2026-11-07）：open-epg 151 频道 / 2268 条当天及未来节目，解析无异常；
51zmt 当前只更新到 11-06，属于镜像自身滞后，会被判空后自动跳过。

### 三、其他

- 版本号 `1.0.1`（`versionCode 2`），可覆盖安装 `1.0.0`；
- 单元测试 74 例全绿（新增 `EpgIndexMatchTest` 5 例：描述性名称、数字边界、精确命中、CCTV5/CCTV5+ 区分）；
- 两套 APK（media3 1.8.0 / 1.11.1）同步重建，签名不变。

## 正式版 v1.0.0（2026-11-07）

定位：**砍掉运营向功能，只保留"看电视"这件事**，可对外分发。

### 一、设置页删除（界面 + 代码 + 文案 + 权限）

| 删除项 | 改动位置 | 说明 |
|---|---|---|
| 更新应用 | `setting.xml` / `SettingFragment` / `UpdateManager.kt` / `ConfirmationFragment.kt` / `VersionPolicy.kt` / `data/ReleaseResponse.kt` | 自更新链路整体下线，含 `requestInstallPermissions()` 与 `PERMISSIONS_REQUEST_CODE` 分支 |
| 赞赏作者 | `setting.xml` / `SettingFragment` / `res/drawable/appreciate.png` | 按钮与图片资源一并删除 |
| 汇出诊断 | `setting.xml` / `SettingFragment` / `MyTVExceptionHandler.export()` | 导出功能与文案删除 |
| 清除日志 | `setting.xml` / `SettingFragment` / `MyTVExceptionHandler.clear()` | 同上 |
| 远程配置服务开关 | `setting.xml` / `SettingFragment` / `SP.remoteServer` | 改为常驻（见第二节） |
| 上传崩溃日志开关 | `setting.xml` / `SettingFragment` / `SP.crashReport` / `MyTVExceptionHandler` | 上传路径与端点常量整体删除，崩溃日志只落本地 |
| 权限清理 | `AndroidManifest.xml` | 删除 `REQUEST_INSTALL_PACKAGES`、`WRITE_EXTERNAL_STORAGE`、`FileProvider`（及其 `xml/file_paths.xml`） |

文案同步：`values`、`values-zh-rCN`、`values-zh-rTW` 三套删除 11 条不再引用的字符串
（`check_version`/`appreciate`/`remote_server`/`crash_report`/`remote_server_started`/
`remote_server_need_enable`/`crash_logs_cleared`/`export_diagnostics`/`clear_logs`/
`export_failed`/`diagnostics_exported`）。

### 二、远程配置恢复"默认开启 + 点开即二维码"

- `MainActivity.ready()` 直接 `startServer()`（原 `startServerIfEnabled()` 及其开关判断删除）；
- `refreshServerToken()`：服务未运行先拉起再换令牌，失败才提示 `远程配置服务启动失败`；
- 端口被占用时 `SimpleServer.start()` 自动尝试 34567..34576（原有能力，现在成为常态路径）；
- 设置页 `清除/恢复默认` 不再关停服务、不再重置已删除的开关。

### 三、新增「线路记忆」开关（默认开启）

目标：**自动切源之后，下次开机还是这条源**，不用再等一轮失败重试。

| 位置 | 改动 |
|---|---|
| `playback/SourceMemory.kt`（新） | 频道 key → 源 id 的记忆表；只记"真的出画"的源；内存累积 + 5s 节流落盘 + 切后台/退出强刷；条目上限 600（按最近使用淘汰） |
| `SP.kt` | 新增 `sourceMemory`（默认 true）与 `sourceMemoryMap`（JSON，`commit()` 同步落盘），删除 `remoteServer` / `crashReport` |
| `models/TVModel.kt` | `onPlaySuccess()` 写入记忆；`setReady()` 里 `restoreRememberedSource()` 按 **URL 摘要**（不是下标）回切，配置顺序变化也能命中；顺手修掉 `setReady` 用过期 `tv.videoIndex` 覆盖 reorder 结果的隐患 |
| `MainActivity` / `MainViewModel` | `persistStats()` 一并 `SourceMemory.flush()`（`onPause`、`onCleared` 都会走到） |
| `setting.xml` / `SettingFragment` | 新增开关「线路记忆」，位置在「自动切换播放源」之后；关闭后既不读也不写，已存数据保留 |
| 单测 | `SourceMemoryMatchTest`：顺序变化仍按身份命中、地址消失返回 -1 |

### 四、其他

- 应用语言固定 **简体中文**：`MyTVApplication` / `MainActivity.attachBaseContext()` 由
  `Locale.TRADITIONAL_CHINESE` 改为 `Locale.SIMPLIFIED_CHINESE`（此前强制繁体，缺词条时会掉到英文）；
- `file://` 配置导入的权限回调对齐：请求码原先用 `1`、回调判断 `2`，回调永远不会触发，已统一为 `2`；
- 设置页开关样式循环补上漏掉的「自动切换播放源」（字号/焦点色与其它开关一致）；
- `versionName` 无 git 环境下由 `0.0.0-1` 改为 `1.0.0`；
- 新增 `SwitchCompat`（线路记忆）后，设置页仍为单列滚动，1280×720 / 1920×1080 均无需改尺寸。

### 五、产物

```
my-tv-0-release-media3-1.8.0.apk     5,474,319 B   minSdk 21 / compileSdk 35 / Kotlin 2.0.0
my-tv-0-release-media3-1.11.1.apk    8,367,055 B   minSdk 23 / compileSdk 36 / Kotlin 2.2.10
```

签名：`CN=my-tv-0 Release`（`keystore/my-tv-0.jks`，长期复用，见 `MY-TV-0-BUILD.md`）。
单元测试：`testDebugUnitTest` 全绿（含新增 `SourceMemoryMatchTest`）。

## 零、真机回归修复（2026-11-07，Android 13 / 14 双机实测）

真机跑通后在三个点上有实质性回归，均已修复并附单测（详见 `MY-TV-0-TEST-REPORT.md` 第 6 节）。

### `playback/PlaybackRecovery.kt`（改）

- 旧策略：`TIMEOUT` 先在当前地址上重试 2 次，再换源 → 地址被黑洞吞掉时要黑屏 30s 才换下一个；
- 新策略：`TIMEOUT` 且存在备用地址时**直接 `NEXT_URL`**；没有备用地址才重试；仍失败再看封装类型；
- 单测：`timeoutSwitchesUrlImmediatelyWhenAlternativeExists`、`timeoutRetriesSameUrlWithoutAlternative`、
  `timeoutGivesUpAfterUrlsAndRetriesExhausted`、`dnsErrorSwitchesTypeAfterUrlBudget`。

### `MainViewModel` EPG 退避 + `net/NetError.kt`（改）

- 旧实现：EPG 镜像整组失效时，每次启动都要 3 个镜像 × 20s 超时（实测每次 60s 后台空转）；
- 新增 `epgFailStreak` / `epgBackoffUntil`（SP 持久化），连续失败按 5→10→20→30 分钟退避，成功清零；
- 顺带修掉 `updateEPG()` 里 fallback 结果未回传 `success` 的漏洞（备用镜像成功也会被判失败）；
- 纯函数 `epgBackoffMillis(streak)` 抽到 `net` 包，附单测 `EpgBackoffTest`。

### `requests/LogoCircuit.kt`（新）+ `ImageHelper.kt`（改）

- 台标镜像不可达时，旧实现会对 80 个频道 × 2 个镜像逐个超时（实测每 2.5s 一条失败日志，持续数分钟）；
- 新增负缓存（同频道 6h 内不重试）+ 熔断（连续 8 次失败暂停预加载 10 分钟，成功即复位）；
- 真机效果：失败请求由约 160 次降到 18 次，并输出 `logo mirrors unreachable, pause preload for 600s`。

### `MainActivity.kt`（改，仅调试构建生效）

- 新增 `handleDebugIntent()`：`am start --es dbg "play:N|next|prev|refresh|pos"`；
- 触发条件为 `ApplicationInfo.FLAG_DEBUGGABLE`，release 包内是一条判断后立即返回的路径；
- 用途：MIUI/HyperOS 禁用输入事件注入（`INJECT_EVENTS`）且无模拟器时，仍可自动驱动切台与配置刷新。

### `PlaybackController.H265SafeCodecSelector`（改）

- 旧实现：H.265 只返回 `c2.android.hevc.decoder` 一个候选，等于强制软解并废掉 decoder fallback；
- 新实现：硬件优先、软件兜底、保留完整候选，配合 `setEnableDecoderFallback(true)` 自动回落；
- 真机数据：骁龙 865 上硬解 60% vs 软解 44%（单核），骁龙 835 上硬解 38.8%，见测试报告 6.4。

### 补齐方案缺口（#1 手动切源 / #2 服务绑定范围）

| 项 | 方案条目 | 实现 |
|---|---|---|
| 手动切源 | 第八章 §8.8 | `PlaybackController.switchSource()` 原本是**没有调用者的死代码**，且不取消看门狗、不重置 `totalAttempts/urlSwitches`。现修正计数并在应用层接线：遥控器 `KEYCODE_INFO` / `KEYCODE_PROG_RED` → `MainActivity.switchSource()` → 收起错误页 + 显示加载页 + Toast `已切换到播放源 2/3`；`am start --es dbg "switch"` 可在禁输入注入的环境验证 |
| 自动切源开关 | 第八章 §8.9 | 新增 `SP.autoSwitchSource`（默认开）与设置页开关；`PlaybackRecovery.decide(kind, state, autoSwitch)` 在关闭时把 `NEXT_URL` / `NEXT_SOURCE_TYPE` 一律降级为 `GIVE_UP`，只保留同地址有限重试（新增 2 条单测） |
| 错误提示带解码方式 | 第五章 §5 | `errorMessage()` 统一追加"当前解码：硬解/软解"，中英繁三语新增 `play_error_with_mode` / `decode_mode_hard` / `decode_mode_soft` |
| ~~服务绑定范围~~ | 第十章 §10.20 | **已按用户要求撤销（2026-11-07）**：`SimpleServer` 恢复为 `NanoHTTPD(port)` 全接口绑定，`PortUtil.lan()` 恢复原始实现（取第一个非 127.0.0.1 的 IPv4）。安全模型仍由「默认关闭 + 令牌 + 限流 + 体积上限」承担 |

### 4K 专项：内核升级评估与软解修复（2026-11-07）

| 项 | 内容 |
|---|---|
| Media3 升级 | `1.5.1 → 1.8.0`。1.9.0 起最低 `minSdk 23`、1.10.0 起最低 `compileSdk 36`，均违反本方案约束，故 1.8.0 是当前工程配置下的上限 |
| 4K 实测结论 | 用户源的 3 个 4K 频道中，浙江卫视4k 为 3840×2160@50fps（HLG），在骁龙 835 上硬解每 2 秒丢 50 帧；1.5.1 与 1.8.0 表现完全一致 → 属解码器吞吐上限，非内核版本问题 |
| 软解开关修复 | AAR 内视频渲染器类名为 `ExperimentalFfmpegVideoRenderer`，ExoPlayer 只反射 `FfmpegVideoRenderer`，导致"软解"从未生效。改为 `H265SafeCodecSelector(preferSoftware)`：软解优先 `c2.android.*`，硬解优先厂商解码器，两模式均保留完整候选 |
| 播放质量观测 | `PlaybackController` 增加 `AnalyticsListener`：记录输入分辨率/帧率/HDR、首帧耗时、累计丢帧，用于区分"网络/解码/渲染"三类问题 |

### 默认源 / 台标 / 多线路（2026-11-07）

| 项 | 方案对应 | 改动 |
|---|---|---|
| 删除内置源 | 第六章 | 删除 `res/raw/channels.txt`，`MainViewModel` 不再从 assets 读取频道；`SP.DEFAULT_CONFIG_URL` 改为 `https://g.w163.de5.net/mytv.txt/wy163`，`DEFAULT_CONFIG_AUTO_LOAD` 默认 `true`。首次启动直接拉远程源；拉取失败且列表为空时显示错误页（新增 `channel_fetch_failed` 文案） |
| 首次启动补播 | 第四章 | `channelsOk` 从 `init` 移到 `applyChannels`（仅首次置位）。原来空列表启动会先触发 `channelsOk`，导入完成后不补播，导致黑屏 |
| 台标库 | 第五章 | 接入 `https://gitee.com/mytv-android/myTVlogo`：精确名 → 4K 大小写变体 → 去清晰度后缀 → 去 `CCTV` 前缀，最多 4 个候选；源自带 `logo` 时优先用源。实测直播源 86 个频道覆盖 81 个（94%） |
| 台标兜底 | 第五章 | 新增 `LogoFallback`：没有台标或下载失败时画频道名文字台标（自适应字号），不再出现空白方块 |
| 404 与故障区分 | 第五章 | `ImageHelper` 引入 `LogoResult`：404 只做负缓存（不触发熔断），只有网络/图床故障才累加熔断计数，避免个别频道缺图导致整批台标停摆 |
| 多线路显示 | 第八章 | 信息条在频道有多个地址时显示「线路 2/3」，随手动切源/自动切源实时变化 |

### 全量简体化 + 双内核版本（2026-11-07）

| 项 | 内容 |
|---|---|
| 简体化 | 用 OpenCC `t2s` 全量转换 10 个文件：`values-zh-rTW/strings.xml`（71 行）、`res/raw/index.html`（32 行）、`InfoFragment`（硬编码“精彩節目”）、`MainActivity`、`MainViewModel`、`TVGroupModel`、`MenuFragment`、`GroupAdapter`、`program_item.xml`、`values/strings.xml`。转换后复查：源码中繁体字符 0 处 |
| 双版本 | 同时产出 `media3-1.11.1`（minSdk 23 / compileSdk 36 / Kotlin 2.2.10）与 `media3-1.8.0`（minSdk 21 / compileSdk 35 / Kotlin 2.0.0），行为一致，仅内核与兼容范围不同 |
| 编译器适配 | Kotlin 2.2 要求显式导入默认包符号：`ProgramFragment` 补 `import MainViewModel`（2.0 下可隐式通过，2.2 报 Unresolved reference） |
| 4K 结论 | 同一台骁龙 835 上，1.5.1 / 1.8.0 / 1.11.1 三个内核播放 4K50 丢帧完全一致（每 2 秒 50 帧）；同机另外两个非 50fps 的 4K 频道零丢帧 → 卡顿由设备解码上限决定，非内核版本 |

---

## 一、新增文件

### `requests/TlsSecurity.kt`（新）

- 系统 CA `TrustManagerFactory`、`SSLContext`、`ConnectionSpec` 统一出口；
- 不设置 `hostnameVerifier`，保持严格域名校验；
- 提供 `supportedProtocols()` 便于诊断。

### `requests/HttpClient.kt`（重写）

| 项 | 改造前 | 改造后 |
|---|---|---|
| 证书 | 全部信任 | 系统 CA |
| 域名 | 全部通过 | OkHttp 严格校验 |
| 客户端 | 单例 + 可变 Builder | 按 `(media/api, proxy)` 缓存，LRU 4 |
| 超时 | 默认 10s | 媒体 `readTimeout=0`；接口 8/15/35s |
| 代理 | 复用 Builder，清除无效 | 每次按配置独立构建 |
| 日志 | 打印完整 URL | `safeUrl()` 去掉 query 与凭据 |

### `net/ProxyConfig.kt`（新）

`host:port` / `http://` / `https://` / `socks[45]://` / IPv6 字面量 / 带凭据（凭据被丢弃且不落日志）；端口范围校验。

### `net/NetError.kt`（新）

HTTP 码与异常 → `NetErrorKind` + 是否可重试 + 退避时间；403/404 不重试、429 长退避、DNS/超时可重试。

### `parser/ChannelParser.kt`（新）

- 三种格式统一入口，输出 `ParseResult(channels, stats, epgUrls, error)`；
- JSON 手工映射，缺字段/`null`/类型错误全部安全降级；
- M3U 新增 `tvg-id`、`#EXTGRP`、`#EXTHTTP`、`#KODIPROP`、`x-tvg-url`；
- TXT 支持多地址合并；
- 输入 8MB / 频道 20000 / 单频道地址 32 的上限。

### `parser/ChannelIdentity.kt`（新）

稳定频道 key、播放源 id、旧收藏序号迁移。

### `store/ChannelStore.kt`（新）

原子写（tmp + fsync + 备份 + rename）、`rollback()`、大小上限、失败不破坏原文件。

### `server/AccessToken.kt` / `server/RateLimiter.kt`（新）

128bit 令牌 + TTL + 常量时间比较；固定窗口限流 + key 上限。

### `playback/PlaybackRecovery.kt`（新）

`PlaybackState` 8 态、`FailureKind`、`RecoveryAction`、`RecoveryState`、重试预算与退避（纯函数，可测试）。

### `playback/PlaybackController.kt`（新）

- 唯一 ExoPlayer 实例 + 唯一监听器；
- generation 令牌丢弃过期回调；
- 准备 15s / 首帧 12s / 缓冲 20s 看门狗；
- 单地址 ≤2、单类型 ≤2、总尝试 ≤6、地址轮换 ≤n−1；
- `setEnableDecoderFallback(true)`：硬解失败自动回退；
- `release()` 幂等。

### `playback/SourceStats.kt`（新）

播放源成功/失败记忆，JSON 持久化，上限 3000 条。

### `models/EpgIndex.kt`（新）

频道 id 精确 → 名称精确 → 边界感知包含；避免 `CCTV1` 命中 `CCTV10`。

### `VersionPolicy.kt`（新）

严格版本比较（versionCode 优先，versionName 回退）。

### `res/xml/file_paths.xml`（新）

FileProvider 仅暴露 `Download/`。

### 测试（新，`app/src/test/java/com/lizongying/mytv0/`）

`ChannelParserTest`、`EpgXmlParserTest`、`ChannelIdentityTest`（含 `HostnameVerificationTest`、`TvModelKeyTest`）、`CoreLogicTest`（含 `NetworkConfigTest`、`PlaybackRecoveryTest`、`AccessTokenTest`、`RateLimiterTest`、`ChannelStoreTest`、`VersionPolicyTest`）、`TlsSecurityTest`。

---

## 二、重写文件

### `PlayerFragment.kt`（290 → 约 200 行）

| 改造前 | 改造后 |
|---|---|
| `updatePlayer()` 每次新建 ExoPlayer，监听器匿名且无法移除 | 委托 `PlaybackController`，播放器只创建一次 |
| `onVideoSizeChanged` 整数除零 | `AspectRatioFrameLayout.FIT` |
| `onPlayerError` 无限循环重试 | 控制器预算内重试，超限报错 |
| `play()` 内 `while(true)` | 单次构建 + 看门狗 |
| `onPause` → `stop()` | `pause()`，恢复不断流 |
| `onDestroy` 才释放 | `onDestroyView` 即释放 |

### `MainViewModel.kt`（601 → 约 640 行）

| 改造前 | 改造后 |
|---|---|
| `str2Channels` 内联三种解析 | 使用 `ChannelParser` |
| 解析后立刻覆盖 `channels.txt` | `ChannelStore` 原子提交 + 备份 + 回滚 |
| 无有效性校验 | `validChannelCount` + 自动更新"骤降 80%"保护 |
| 收藏按序号 | 稳定 key + 一次性迁移 |
| EPG `contains` 匹配 | `EpgIndex` |
| EPG 缓存在主线程写 | IO 线程写，上限 6 万条 |
| 台标预加载全部频道 × 14 镜像 | 80 频道 × ≤3 地址 |
| 拉取无超时/无预算 | 3 次尝试 + 60s 总预算 + 8MB 上限 |
| 无更新时间/结果 | `SP.lastUpdateTime/lastUpdateResult/configVersion` |

### `SimpleServer.kt`（313 → 约 380 行）

默认关闭、令牌鉴权、双限流、body 上限、端口回退、错误不外泄、`handleSources` 去掉 `runBlocking` 并加 10 分钟缓存、`/api/sources` 返回 JSON。

### `MyTVExceptionHandler.kt`（82 → 约 230 行）

本地写盘 + 脱敏 + 轮转 + 可选上传 + 导出/清理；移除主线程死锁写法。

### `UpdateManager.kt`（274 → 约 330 行）

FileProvider 安装、签名/包名校验、Cursor `use`、真实进度轮询、可取消、接收器生命周期、`VersionPolicy`。

### `SP.kt`（218 → 约 290 行）

收藏线程安全 + `commit()` + 稳定 key + 迁移版本；新增远程服务、崩溃上报、更新时间/结果/版本、播放源统计键；`init` 增加异常保护。

### `models/EPGXmlParser.kt`（60 → 约 230 行）

手工时间解析（±HHMM）、`Long`、programme 前置、容量上限、容错读取、`XmlPullParserFactory`（可单测）。

### `models/TVModel.kt`（243 → 约 350 行）

每地址请求头、优先级、成功记忆排序、`buildMediaSource()` 统一构建、类型索引保持、`setErrInfo` 线程安全、`hasMoreSourceTypes()`。

### `res/raw/index.html`

令牌注入、401 提示、`createElement` 替代 `innerHTML`、适配 `/api/sources` 新格式、错误提示。

---

## 三、修改文件

| 文件 | 修改内容 |
|---|---|
| `data/TV.kt` | 新增 `key/displayName/enabled/sortOrder/sources/tvgId`，新增 `showName()/matchName()/firstUri()` |
| `data/TVSource.kt`（新） | 播放源元数据模型 |
| `data/EPG.kt` | `Int` 秒 → `Long` 秒；新增 `channelId` |
| `models/TVGroupModel.kt` | `getPosition` 越界保护、`getTVListModel` 边界、`defaultPosition` 降级 |
| `models/TVListModel.kt` | 未改动（保持接口） |
| `MainActivity.kt` | 服务生命周期 + 令牌、`ready` 幂等、观察者注销、`play()` 重写、NPE 修复、退出前持久化统计 |
| `SettingFragment.kt` | 远程服务开关、崩溃上报开关、导出诊断、清除日志、二维码地址改为动态生成、`UpdateManager.destroy()` |
| `MenuFragment.kt` | adapter 强转 → 安全转换 |
| `ChannelFragment.kt` | `getCurrent()!!` → 可空处理 |
| `ModalFragment.kt` | 二维码展示 10s → 60s |
| `Utils.kt` | 新增 `getDateFormat(format, Long)`；接口请求改用 `apiClient` |
| `ImageHelper.kt` | 下载大小上限、临时文件 + rename、`apiClient` |
| `res/layout/setting.xml` | 新增 2 个 Switch、2 个按钮 |
| `res/values*/strings.xml` | 新增 16 条文案（默认/简中/繁中） |
| `res/xml/network.xml` | 仅系统 CA；`debug-overrides` 放开用户 CA |
| `AndroidManifest.xml` | 新增 `FileProvider` |
| `app/build.gradle.kts` | `testOptions.unitTests.isReturnDefaultValues`；测试依赖 |
| `gradle/libs.versions.toml` | junit / mockwebserver / okhttp-tls / kxml2（仅 test） |
| `proguard-rules.pro` | 保留 `data.**` 字段、`SourceStats$Stat`、`UpdateManager` |

---

## 四、删除文件

| 文件 | 原因 |
|---|---|
| `requests/Tls12SocketFactory.kt` | 仅为 API 16~19 强制启用 TLS1.2；项目 minSdk=21 已默认启用，保留会成为"看似兼容"的死代码。删除不影响任何目标设备，且相关连接规格由 `TlsSecurity` 统一管理。 |

---

## 五、行为变化（用户可见）

1. **首次升级**：收藏会自动从"序号"迁移到"频道标识"，无需手动操作。
2. **远程配置默认关闭**：需在设置页手动开启；二维码链接带 30 分钟令牌。
3. **崩溃不再自动上传**：默认只写本地；设置页可开启上传、导出诊断、清除日志。
4. **设置页新增 4 个入口**：远程配置服务、上传崩溃日志、导出诊断、清除日志。
5. **视频比例**：由拉伸（fill）改为等比（fit），非 16:9 频道出现黑边而非变形。
6. **切台更"快失败"**：坏源最多尝试 6 次即报错，不再长时间转圈。
7. **自更新**：Android 7+ 可正常拉起安装器；非官方签名包会被拒绝。
8. **代理**：修改/清除立即生效，无需重启。

---

## 六、兼容性确认

- 配置格式：TXT / M3U / JSON 全部兼容，且比原实现更宽容；
- `channels.txt` 路径与文件名不变；
- SharedPreferences 文件名与既有键名不变；
- 播放器依赖（Media3 1.5.1 + FFmpeg AAR）不变；
- minSdk 21 / targetSdk 35 / compileSdk 35 不变；
- Android TV Leanback 启动入口与遥控器键位不变。

---

## 七、已知问题

1. `versionCode` 依赖 git tag；zip 构建下为 `1`，自更新比较退化为 versionName。
2. 自签名证书直播源不再可用（安全要求）。
3. 未提供定时更新（需新依赖，评估后放弃）。
4. 未在真机验证首帧/内存/CPU（环境限制）。
