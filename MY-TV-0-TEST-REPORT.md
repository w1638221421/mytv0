# MY-TV-0 自动化测试报告

## 1. 环境

| 项 | 值 |
|---|---|
| OS | Ubuntu 24.04 (PRoot, aarch64) |
| JDK | 17.0.20.1 (构建), 21.0.12 (系统默认，仅供 Gradle 启动) |
| Gradle | 8.12（AGP 8.7.3） |
| 测试框架 | JUnit 4.13.2 + OkHttp MockWebServer 4.12.0 + okhttp-tls 4.12.0 + kxml2 2.3.0 |
| 设备/模拟器 | 真机 2 台（见第 6 节）；无可用模拟器 |
| 执行命令 | `./gradlew :app:testDebugUnitTest` |

## 2. 结果总览

```
64 tests completed, 0 failed
BUILD SUCCESSFUL
```

| 测试类 | 用例数 | 结果 | 耗时 |
|---|---:|---|---:|
| `ChannelParserTest` | 13 | ✅ | 0.047s |
| `PlaybackRecoveryTest` | 11 | ✅ | 0.002s |
| `EpgXmlParserTest` | 7 | ✅ | 0.018s |
| `ChannelIdentityTest` | 6 | ✅ | 0.018s |
| `ChannelStoreTest` | 5 | ✅ | 0.022s |
| `NetworkConfigTest` | 4 | ✅ | 0.001s |
| `VersionPolicyTest` | 3 | ✅ | 0.002s |
| `LogoCircuitTest` | 3 | ✅ | 0.001s |
| `HostnameVerificationTest` | 3 | ✅ | 0.140s |
| `AccessTokenTest` | 3 | ✅ | 0.004s |
| `TlsSecurityTest` | 2 | ✅ | 0.192s |
| `RateLimiterTest` | 2 | ✅ | 0.001s |
| `TvModelKeyTest` | 1 | ✅ | 0.000s |
| `EpgBackoffTest` | 1 | ✅ | 0.000s |
| **合计** | **64** | **全部通过** | — |

## 3. 用例明细

### 3.1 直播源解析（13）

| 用例 | 验证点 |
|---|---|
| `parseTxt_mergesDuplicateChannelAndKeepsOrder` | 同名频道多行合并、地址去重、顺序保持、分组归属 |
| `parseTxt_ignoresInvalidLines` | 无逗号/空名/非法地址行被跳过并计数 |
| `parseM3u_readsAttributesHeadersAndEpgUrl` | `tvg-id/name/logo/chno/group-title`、`#EXTVLCOPT` 头、`x-tvg-url`、重复频道合并 |
| `parseM3u_titleWithCommaIsPreserved` | 标题含逗号不被截断（原实现会截断） |
| `parseJson_minimalFieldsDoNotCrash` | 仅 `title+uris` 的 JSON 不崩溃（原实现 NPE） |
| `parseJson_nullFieldsAreTolerated` | 显式 `null` 字段不崩溃 |
| `parseJson_perSourceHeadersAndPriority` | 每地址请求头与优先级 |
| `parseJson_wrappedChannelsObject` | `{"channels":[...]}` 兼容 |
| `parseJson_invalidSyntaxReturnsError` | 语法错误返回 error 而非抛异常 |
| `parse_emptyOrGarbageIsInvalid` | 空/空白/纯文本/`[]` 均判为无效 |
| `parse_jsonWithBomStillWorks` | UTF-8 BOM |
| `parse_limitsUrlsPerChannel` | 单频道 32 地址上限 |
| `urlValidation_acceptsIptvSchemes` | http/rtsp/rtmp/rtp/udp 通过，空白地址拒绝 |

### 3.2 EPG（7）

| 用例 | 验证点 |
|---|---|
| `parseXmltvTime_handlesOffset` | `+0800` / 无时区 / `-0500` 三种 |
| `parseXmltvTime_invalidReturnsZero` | 长度不足、月份越界、非数字 → 0 |
| `parse_programmeBeforeChannelStillMatches` | programme 在 channel 之前（原实现丢失） |
| `parse_skipsMissingTitleAndPastProgrammes` | 空 `<title>` 与过期节目被过滤 |
| `parse_malformedXmlDoesNotCrash` | 截断 XML 不产生脏数据 |
| `index_matchesByChannelIdAndAvoidsPrefixCollision` | `CCTV1` 不匹配 `CCTV10`，`CCTV1高清` 可匹配 `CCTV1` |
| `index_emptyLookup` | 空索引返回 null |

### 3.3 安全（5）

| 用例 | 验证点 |
|---|---|
| `selfSignedCertificateIsRejected` | 自签名 HTTPS 必须失败（防回归到 trust-all） |
| `cleartextHttpStillWorks` | HTTP 仍可用（IPTV 兼容） |
| `clientRejectsWrongHostname` | 域名不匹配时 `verify()` 返回 false |
| `trustManagerHasRealAnchors` | `acceptedIssuers` 非空 |
| `connectionSpecsAllowCleartextButKeepTls` | 同时包含 TLS 与 CLEARTEXT 规格 |

### 3.4 令牌与限流（5）

| 用例 | 验证点 |
|---|---|
| `tokenValidatesAndExpires` | 有效期内通过、过期后拒绝、空值拒绝 |
| `regenerateInvalidatesOldToken` | 旧令牌立即失效，新令牌 32 字符 |
| `expireImmediatelyRejects` | 主动失效 |
| `limitsPerWindow` | 固定窗口内第 4 次请求被拒，窗口滚动后恢复 |
| `keysAreBounded` | 大量来源 IP 不会撑爆内存 |

### 3.5 网络配置与错误分类（4）

| 用例 | 验证点 |
|---|---|
| `proxyParsing` | http/socks/带凭据/默认端口/非法 scheme/越界端口 |
| `httpErrorClassification` | 403/404 不重试，429/5xx 可重试 |
| `exceptionClassification` | DNS/TLS/超时/连接/包装异常 |
| `backoffIsBounded` | 退避时间恒在 0~8s |

### 3.6 播放恢复（6）

| 用例 | 验证点 |
|---|---|
| `transientErrorRetriesSameUrlFirst` | 超时先重试当前地址 |
| `transientErrorSwitchesTypeAfterUrlBudget` | 地址预算耗尽后换封装类型 |
| `authErrorNeverRetriesSameUrl` | 403 直接换源 |
| `noMoreUrlsGivesUp` | 单地址失败立即放弃 |
| `totalBudgetAlwaysTerminates` | 1000 个地址也只能尝试 ≤6 次 |
| `urlSwitchesAreBounded` | 地址轮换次数受限 |

### 3.7 配置持久化（5）

| 用例 | 验证点 |
|---|---|
| `commitReadAndBackup` | 提交/读取/自动备份 |
| `emptyOrTooLargeContentRejected` | 空内容与超限内容被拒绝且不落盘 |
| `rollbackRestoresPreviousContent` | 回滚恢复上一版本 |
| `rollbackWithoutBackupFails` | 无备份时安全失败 |
| `clearRemovesFiles` | 清理 |

### 3.8 标识、版本、模型（10）

`ChannelIdentityTest` 6 例覆盖：key 归一化稳定性、分组区分、不同频道区分、旧序号迁移、已迁移 key 保留、sourceId 确定性。
`VersionPolicyTest` 3 例覆盖：versionCode 比较、versionName 回退、前缀/后缀解析。
`TvModelKeyTest` 1 例覆盖：`showName()` 回退链。

## 4. 与验收标准的对应

| 验收项 | 证据 |
|---|---|
| HTTPS 证书验证正常 | `TlsSecurityTest` + `HostnameVerificationTest` |
| 远程配置接口具有有效认证 | `AccessTokenTest` + `RateLimiterTest` + 代码审查 |
| 播放器状态管理统一 | `PlaybackController` 单一状态机（代码审查） |
| 切换任务无竞争 | generation + 主线程串行（代码审查） |
| 播放失败明确提示 | `FailureKind` → 5 类文案（strings 新增 4 条） |
| 备用源切换次数有限 | `PlaybackRecoveryTest.totalBudgetAlwaysTerminates` |
| 更新失败不破坏旧配置 | `ChannelStoreTest` + `parse_emptyOrGarbageIsInvalid` |
| 收藏不丢失 | `ChannelIdentityTest.migrateLikes_*` |
| EPG 更新不影响播放 | EPG 全流程在 IO/主线程分离（代码审查） |
| 崩溃日志默认不上传 | `SP.DEFAULT_CRASH_REPORT = false` + 代码审查 |
| 更新流程完整错误处理 | `UpdateManager` 重写 + `VersionPolicyTest` |
| Debug/Release 构建成功 | 见第 5 节 |

## 5. 构建验证

| 任务 | 结果 | 产物 |
|---|---|---|
| `:app:testDebugUnitTest` | ✅ 55/55 | `app/build/test-results/` |
| `:app:assembleDebug` | ✅ | `dist/my-tv-0-debug.apk`（13,413,031 B） |
| `:app:assembleRelease` | ✅（R8 minify + 资源压缩） | 未签名 5,452,161 B |
| `apksigner sign` | ✅ | `dist/my-tv-0-release.apk`（5,491,456 B） |
| `apksigner verify` | ✅ | 签名证书 SHA-256 `26950a41…` |
| `aapt2 dump badging` | ✅ | minSdk 21 / targetSdk 35 / FileProvider 就位 |

## 6. 局限说明（重要）

1. **没有真机/模拟器**：本环境为 aarch64 PRoot，无 Android 运行时。因此：
   - 未执行 Instrumented 测试（`androidTest`）；
   - 未实测首帧时间、内存占用、CPU 占用；
   - 播放器行为依赖代码审查与单元级策略测试。
2. **未使用真实直播源**：所有解析/播放测试使用构造数据与本地 MockWebServer，符合"不依赖未授权真实源"的要求。
3. **性能基线未实测**：缺少设备，无法给出冷启动/切台/首帧的毫秒级数据；`MY-TV-0-IMPLEMENTATION-ROADMAP.md` 末尾给出了真机验证清单。

## 7. 复现命令

```bash
# 单元测试
./gradlew :app:testDebugUnitTest

# 查看报告
open app/build/reports/tests/testDebugUnitTest/index.html

# 构建
./gradlew :app:assembleDebug :app:assembleRelease
```

## 6. 真机验证（2026-11-07）

### 6.1 设备

| 设备 | 型号 | SoC | Android | 分辨率 | 角色 |
|---|---|---|---|---|---|
| 设备 A | Redmi K30 Pro（M2007J3SC） | 骁龙 865 | 14（API 34，HyperOS 系） | 1080×2400 | 主力验证：切台压力、鉴权、解码对比 |
| 设备 B | 小米 6（sagit） | 骁龙 835 | 13（API 33，MIUI 14.0.13） | 1080×1920 | 低端验证：首帧、CPU、内存、退避策略 |

安装与驱动方式：设备 A 可直接 `adb install`；设备 B 的 MIUI 14 对 `adb install` 静默拒绝
（`INSTALL_FAILED_USER_RESTRICTED`），需在系统内开启「USB 安装」后由 adb 安装，或由用户手动点装。

两台设备的 MIUI/HyperOS 均禁用了输入事件注入（`input keyevent/tap`、`monkey` 均报
`SecurityException: INJECT_EVENTS`，`/dev/input` 不可写），因此无法用按键/触摸驱动 UI。
为此在 `MainActivity` 增加**仅 debuggable 构建生效**的调试入口（release 中是判断后立即返回的死路径）：

```bash
adb shell am start -n com.lizongying.mytv0/.MainActivity --es dbg "play:34"   # 切换频道
adb shell am start -n com.lizongying.mytv0/.MainActivity --es dbg "next"      # 下一个
adb shell am start -n com.lizongying.mytv0/.MainActivity --es dbg "refresh"   # 重新拉取配置
adb shell am start -n com.lizongying.mytv0/.MainActivity --es dbg "pos"       # 打印当前频道
```

### 6.2 验证结果

| 项 | 设备 A | 设备 B | 结论 |
|---|---|---|---|
| 安装 / 冷启动 | ✅ 1.2s | ✅ 1.8s | — |
| 直播源导入（TXT，43,858 B / 86 频道） | ✅ `channels.txt` 原子替换 + `.bak` 保留 | ✅ 同左 | 通过 |
| 首帧 | ✅ CCTV1 | ✅ 湖南卫视 **0.47s**（PREPARING 38.521 → PLAYING 38.987） | 通过 |
| 连续切台 | ✅ 15 次全部出画面，0 watchdog / 0 播放错误 / 0 崩溃 | — | 通过 |
| HEVC 解码路径 | 硬解 `OMX.qcom.video.decoder.hevc`（1920×1080） | 硬解 `OMX.qcom.video.decoder.hevc` | 候选列表完整、失败可回落 |
| 远程配置服务 | ✅ 监听 `*:34567`，令牌 128 bit | ✅ 同左 | 通过 |
| 令牌鉴权 | 无令牌/错令牌 → 401（`{"ok":false,"error":"unauthorized"}`）；带令牌 → 200 + `{"ok":true,"expires":…}` | ✅ 同样结果 | 通过 |
| 读限流 | 132 次连发：前 119 次 401、第 120 次起 429 | — | 与 120 req/min 一致 |
| 写限流 | 20 次/分钟；超出后 `429 write_rate_limited` | — | 一致 |
| 体积上限 | 70 KB → `/api/proxy` 413；3 MB → `/api/import-text` 413 | — | 一致 |
| 响应头 | `Cache-Control: no-store`、`X-Content-Type-Options: nosniff` | — | 通过 |
| EPG 失败退避 | 首次 `backoff 300s (streak=1)` → 再启动 `backoff 600s (streak=2)` | ✅ 同左 | 通过 |
| 台标熔断 | — | 8 次连续失败后 `pause preload for 600s`，失败请求由 ~160 次降至 **18 次** | 通过 |
| 内存 | PSS 145 MB → 151 MB（20 次切台后） | PSS 65 MB（默认源）/ 115 MB（86 频道源） | 无泄漏迹象 |
| 手动切源（INFO/红键） | — | `manual switch source -> 2/3` → `state PLAYING`，画面正常 | 通过（真机，MI 6） |

### 6.3 真机暴露并已修复的问题

| # | 问题 | 现象 | 修复 |
|---|---|---|---|
| R1 | 超时后重试同一地址 | 某频道首地址连接被黑洞吞掉，每次切台要耗两个 15s 看门狗周期（30s 黑屏）才换源 | `PlaybackRecovery`：`TIMEOUT` 且存在备用地址时直接 `NEXT_URL`；无备用地址才重试（新增 4 条单测） |
| R2 | EPG 镜像全线失效仍每次启动重试 | 3 个镜像 × 20s 超时 = 每次启动 60s 后台空转 | 增加持久化退避：连续失败 5min→10min→20min→30min，成功清零（`epgBackoffMillis`，含单测） |
| R3 | 台标镜像不可达时逐频道空转 | 80 个频道 × 2 镜像逐个超时，后台持续数分钟 | 新增 `LogoCircuit`（负缓存 6h + 连续 8 次失败熔断 10min），失败请求降到 1/9 |

### 6.4 解码器 A/B（设备 A，CCTV8，20s 采样 ×2）

| 路径 | 组件 | CPU（单核占比） |
|---|---|---|
| 硬件 | `OMX.qcom.video.decoder.hevc` | 60.9% / 59.6% |
| 软件 | `c2.android.hevc.decoder` | 44.5% / 43.8% |

结论：该 MIUI 版本的 OMX→Codec2 兼容层反而比软件解码更贵。但**不能据此写死软解**：
电视盒子的 CPU 通常无法实时解码 1080p/4K HEVC，硬解是平台推荐路径；原实现把 H.265 候选
砍到只剩 `c2.android.hevc.decoder`，等于同时废掉了 `setEnableDecoderFallback`。
现策略为「硬件优先 + 完整候选 + 自动回落 + 用户软解开关」，设备 B 硬解 CPU 仅 38.8%，验证策略可用。

### 6.5 本环境无法验证的项

- UI 交互（菜单、设置页、开关、遥控器按键路径）：两台设备均禁止输入注入，且无模拟器；
- 自更新安装流程：需要一次真实版本升级与 `REQUEST_INSTALL_PACKAGES` 授权；
- 弱网/断网恢复：本次未构造网络抖动（可通过飞行模式开关验证，但会中断 adb 无线连接）；
- 长时间稳定性（>30 分钟）与温升：受测试时段限制未执行。

## 7. 4K 频道专项（2026-11-07）

### 7.1 被测频道（来自用户提供的直播源 `4K专区` 分组）

| 频道 | 地址 | 实测参数 |
|---|---|---|
| 浙江卫视4k | `ali-xwl.cztv.com/live/channel4k2160p.m3u8` | HEVC **3840×2160 @50fps**，7.4 Mbps，BT.2020 + **HLG(HDR)** |
| 北京卫视4k | `dbiptv.sn.chinamobile.com/.../3221227112/index.m3u8` | HEVC 3840×2160（帧率未在清单声明） |
| CCTV4K | `dbiptv.sn.chinamobile.com/.../3221227140/index.m3u8` | HEVC 3840×2160 |

码流合法性（离线解析 TS 的 PAT/PMT + PES 头）：浙江卫视4k 单个 6s 分片含 **300 帧、帧间隔恒为 20.00ms、50.00fps**，时间戳无抖动 → **源本身是干净的**。

### 7.2 设备实测（小米 6 / 骁龙 835 / Android 13）

| 频道 | 解码路径 | 首帧 | 丢帧 | CPU |
|---|---|---|---|---|
| 浙江卫视4k | `OMX.qcom.video.decoder.hevc`（硬解） | 正常 | **≈25 帧/秒（每 2s 丢 50 帧）** | 37% |
| 北京卫视4k | 同上 | 正常 | 基本为 0 | — |
| CCTV4K | 同上 | 正常 | 基本为 0 | — |
| 1080p HEVC 对照 | 同上 | 正常 | 0 | 38.8% |

结论：**只有 4K50 这一路在 SD835 上卡**。CPU 仅 37% 而持续丢帧，属于硬件解码器吞吐上限
（SD835 的 HEVC 硬解规格为 4K@30），不是播放器调度或缓冲区问题。

### 7.3 内核版本对照（排除“内核太旧”假设）

| Media3 | 4K50 丢帧 | 1080p | 远程配置服务 | 结论 |
|---|---|---|---|---|
| 1.5.1（原） | 每 2s 丢 50 帧 | 正常 | 正常 | — |
| 1.8.0（升级后） | **完全一致，仍是每 2s 丢 50 帧** | 正常 | 正常 | 换内核不改变硬件上限 |

升级边界（由 Maven 元数据 + 构建验证得出）：

| 版本 | minSdk | minCompileSdk | 是否可用 |
|---|---|---|---|
| 1.8.0 | 21 | 35 | ✅ 可用（当前采用） |
| 1.9.0 | 23 | 35 | ❌ 违反 minSdk 21 约束 |
| 1.10.0+ | 23 | 36 | ❌ 违反 minSdk 21 / compileSdk 35 |

### 7.4 顺带发现并修复的问题

**“软解”开关从未对视频生效**：`lib-decoder-ffmpeg-release.aar` 内的视频渲染器类名为
`ExperimentalFfmpegVideoRenderer`，而 ExoPlayer 的扩展反射只查找 `FfmpegVideoRenderer`，
因此设置 `softDecode=true` 后解码组件仍是 `OMX.qcom.video.decoder.hevc`（真机日志确认）。

修复：`H265SafeCodecSelector(preferSoftware)` —— 软解时优先平台软件解码器（`c2.android.*`），
硬解时优先厂商解码器，两种模式都保留完整候选由 decoder fallback 兜底。
真机验证：软解模式下解码组件变为 `c2.android.hevc.decoder`，播放正常。

### 7.5 对“电视上也卡”的判断

若在电视端使用的是**改造前**的构建，H.265 会被强制收敛到 `c2.android.hevc.decoder`（软件解码），
4K50 在任何 CPU 上都无法实时解码 —— 这与“电视和所有手机都卡”的现象完全吻合。
改造版已改为硬件优先（本次真机日志中 4K 频道走的是厂商硬解），建议在电视端用新版 APK 复测一次后再判断。

## 8. 默认源 / 台标 / 多线路（2026-11-07）

### 8.1 内置源移除

| 项 | 改前 | 改后 |
|---|---|---|
| 频道来源 | `res/raw/channels.txt`（169,536 B，2025-05 快照，地址大量失效） | 远程 `https://g.w163.de5.net/mytv.txt/wy163` |
| 首次启动 | 本地内置列表（大量死链） | 直接拉远程源，拉取中显示加载页 |
| 拉取失败 | — | 错误页 + `获取直播源失败，请检查网络，或在设置中更换直播源地址` |
| APK 体积 | — | release 减少约 169 KB |

顺带修复：`channelsOk` 原先在 `init` 里无条件置位，空列表启动时导入完成后不会补播（黑屏），
现改为在 `applyChannels` 首次成功时置位。

### 8.2 台标库覆盖率（离线实测，86 个频道全量）

| 结果 | 数量 |
|---|---|
| 精确名命中 | 74 |
| 4K / 去后缀 / 去 CCTV 前缀变体命中 | 7 |
| 未命中（源里的小众台与占位名） | 5 |
| **覆盖率** | **81/86 = 94.2%** |

未命中的 5 个为：`爱谍战`、`咸阳新闻综合`、`渭南新闻综合`、`备用央视#`、`备用卫视#`（后两个是源里的占位名），
由 `LogoFallback` 画文字台标兜底，界面上不会出现空白。

### 8.3 待真机验证项

测试机当时离线（`No route to host`），以下三项需设备恢复后确认：
首次启动拉取流程、台标实际加载与落盘、信息条「线路 x/y」显示。
