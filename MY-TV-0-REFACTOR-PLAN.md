# MY-TV-0 重构方案

> 目标：在**不改变构建基线、不删除既有功能、不破坏配置格式**的前提下，把安全、播放、配置三条主链路重构为可验证的模块。

---

## 1. 约束与取舍

| 约束 | 落地方式 |
|---|---|
| 不升级 minSdk/targetSdk/AGP/Kotlin/Media3 | 全部保持原版本；新增代码只用 API 21 可用能力（`java.io`/`javax.net.ssl`/`Calendar`） |
| 保留 FFmpeg AAR | 未改动 `libs/lib-decoder-ffmpeg-release.aar`，`DefaultRenderersFactory` 配置方式不变 |
| 保留 TXT/M3U/JSON | `ChannelParser` 覆盖三种格式，且比原实现更宽容（BOM、逗号标题、`#EXTHTTP`） |
| 保留收藏/频道切换/代理/EPG/远程配置 | 全部保留，仅替换内部实现；UI 入口不变 |
| 低内存设备 | 单播放器、无预加载解码器、台标预加载上限 80、EPG 缓存上限 60k 条 |
| 可回滚 | 频道文件保留 `.bak`，`ChannelStore.rollback()`；代码层面按包隔离，可单独回退 |

**不引入运行时依赖**：所有新能力用标准库 + 已有 OkHttp/Gson 实现。

---

## 2. 目标架构

```
┌──────────────────────────── UI 层 ────────────────────────────┐
│ MainActivity（按键/手势/Fragment 调度/服务生命周期）           │
│ PlayerFragment（PlayerView + 音量条，仅转发）                  │
│ SettingFragment（开关/二维码/诊断）                            │
└───────────────▲───────────────────────────▲──────────────────┘
                │ LiveData(errInfo/ready)    │ 调用
┌───────────────┴──────────────┐  ┌─────────┴──────────────────┐
│ playback.PlaybackController  │  │ MainViewModel              │
│  · 状态机 / 看门狗 / 重试预算 │  │  · 解析→校验→提交→应用     │
│  · generation 令牌           │  │  · EPG 索引与缓存          │
└───────────────▲──────────────┘  └─────────▲──────────────────┘
                │ MediaSource               │ ParseResult
┌───────────────┴──────────────┐  ┌─────────┴──────────────────┐
│ models.TVModel               │  │ parser.ChannelParser       │
│  · 多源排序 / 每源请求头      │  │ parser.ChannelIdentity     │
│  · SourceStats 记忆          │  │ store.ChannelStore         │
└───────────────▲──────────────┘  └─────────▲──────────────────┘
                │ OkHttp                    │
┌───────────────┴──────────────────────────────────────────────┐
│ requests.HttpClient / TlsSecurity / DnsCache                 │
│ net.ProxyConfig / net.NetErrorClassifier                     │
└──────────────────────────────────────────────────────────────┘
```

### 2.1 播放状态机

```
IDLE ──play()──▶ PREPARING ──▶ BUFFERING ──▶ READY ──▶ PLAYING ──▶ PAUSED
                    │              │            │           │
                    └──── 超时/错误 ─┴────────────┴───────────┘
                                   ▼
                        PlaybackRecovery.decide()
              ┌────────────┬───────────────┬────────────┐
         RETRY_SAME   NEXT_SOURCE_TYPE   NEXT_URL      GIVE_UP
              │             │               │            │
              └─────────────┴───────────────┘            ▼
                        prepareCurrent()                ERROR
                                                          │
                                                     release() → RELEASED
```

不变式：
1. 所有状态变化只在主线程发生，且只有一个 `player` 实例；
2. 每次 `play()` 递增 `generation`，所有延时任务与回调都对 `generation` 做校验；
3. `release()` 幂等，可在任意状态调用。

### 2.2 配置更新事务

```
fetch → decode(Gua?) → parse(ChannelParser) → validate(≥1 有效频道, 自动更新额外检查骤降)
      → build(models) → store.commit(原子+备份) → swap(groupModel) → notify
                                          └─ 失败：store.rollback() + 保留旧内存模型
```

关键点：**先构建、再落盘、最后替换内存**（改造前是"先落盘再构建"）。

---

## 3. 新增模块说明

### 3.1 `parser/ChannelParser.kt`

- 输入上限 8MB、频道上限 20000、单频道地址上限 32；
- JSON 手工映射（不依赖 Gson 对非空类型的隐式 null 注入）；
- M3U 支持 `tvg-*`、`group-title`、`#EXTVLCOPT`、`#EXTHTTP`、`#EXTGRP`、`#KODIPROP`、`x-tvg-url`；
- TXT 支持 `#genre#` 分组、同频道多地址合并；
- 输出 `ParseResult(channels, stats, epgUrls, error)`。

### 3.2 `parser/ChannelIdentity.kt`

`key = md5(normalize(group) + "\u0001" + normalize(name))`；`sourceId = md5(url)[0..16]`。
仅用于稳定标识，不承担任何加密职责。

### 3.3 `store/ChannelStore.kt`

`tmp → fsync → 备份旧文件 → rename`；`rollback()` 用 `.bak` 覆盖回去。

### 3.4 `playback/`

| 类 | 职责 |
|---|---|
| `PlaybackState` | 8 态枚举 |
| `PlaybackRecovery` | 纯函数：失败类型 → 动作（可测试） |
| `PlaybackController` | 唯一播放器、监听器、看门狗、重试预算、generation |
| `SourceStats` | 每地址成功/失败记忆（JSON 持久化，上限 3000 条） |

### 3.5 `net/`

`ProxyConfig.parse()`（HTTP/SOCKS、IPv6、去凭据）、`NetErrorClassifier`（HTTP 码/异常 → 分类 + 退避）。

### 3.6 `server/`

`AccessToken`（128bit、30min、常量时间比较）、`RateLimiter`（固定窗口、key 上限）。

---

## 4. 关键设计决策与理由

| 决策 | 备选方案 | 选择理由 |
|---|---|---|
| 不引入 DataStore/Room | 迁移到 DataStore | 低内存盒子收益有限；迁移失败风险高于收益；已用锁 + commit 解决并发 |
| 保留单播放器 | 双播放器预加载 | 双解码器在 1GB 内存盒子上会触发系统杀进程；明确禁止 |
| 保留 SharedPreferences 键名 | 重命名 | 避免用户配置丢失；仅新增键 |
| 收藏用 key 而非重排 id | 保持序号 | 序号在换源后必然错位，无法通过"更聪明的排序"解决 |
| EPG 匹配用索引而非双循环 | 维持 `contains` | 双循环 O(n×m) 且会串台；索引预归一化后 O(1)~O(k) |
| 令牌放 URL query | 仅 Header | 二维码扫码后浏览器直接打开，query 最自然；同时支持 Header |
| 服务仍监听 0.0.0.0 | 仅 127.0.0.1 | 手机扫码必须跨设备访问；用令牌 + 默认关闭 + 限流控制风险 |

---

## 5. 兼容性矩阵

| 场景 | 改造前 | 改造后 |
|---|---|---|
| TXT `分组,#genre#` | 支持 | 支持（新增同频道多地址合并） |
| M3U `#EXTINF` 属性 | 支持 | 支持（新增 `tvg-id`、`#EXTGRP`、`#KODIPROP`） |
| JSON 数组 | 支持（缺字段崩溃） | 支持（缺字段安全） |
| Gua 编码源 | 支持 | 支持（解析前解码） |
| 收藏（旧序号） | 支持 | 首次启动自动迁移为稳定 key |
| 默认频道（`SP.channel`） | 支持 | 支持 |
| 上次播放位置 | 支持 | 支持（分组/列表两级） |
| 代理 | 支持（清除无效） | 支持（即时生效） |
| EPG | 支持 | 支持（时区/溢出/串台修复） |
| 远程配置 | 无鉴权 | 令牌鉴权 + 默认关闭 |
| 自更新 | Android 7+ 崩溃 | FileProvider + 签名校验 |

---

## 6. 回滚策略

1. **配置层**：`channels.txt.bak` 始终保留上一次成功配置；`ChannelStore.rollback()` 可恢复。
2. **代码层**：新逻辑集中在 `parser/ store/ playback/ net/ server/` 五个包，若需回退，只需恢复 `MainViewModel`/`PlayerFragment`/`SimpleServer` 三个入口文件并删除新包。
3. **数据层**：新增 SP 键（`like_version`、`remote_server`、`crash_report`、`last_update_*`、`source_stats`）对旧版本不可见，回退旧版本不会报错，仅收藏需重新迁移（旧版本仍能读取 `like` 中的 key 字符串，只是匹配不到序号——因此回退前建议先在设置页清除收藏）。
4. **构建层**：未修改 Gradle 版本与 SDK 级别，回退只需 `git revert`。

---

## 7. 已知取舍与残留问题

1. 自签名证书的私有直播源不再可用（安全要求）。
2. 远程服务仍监听局域网（功能前提），依赖令牌 + 默认关闭 + 限流；公网暴露需用户在路由器上自行映射，应用侧无法完全阻止。
3. EPG 仍为全量解析后缓存，超大 EPG（>200k 节目）会被截断（有上限保护）。
4. `versionCode` 依赖 git tag，zip 构建下为 `1`，自更新比较会退化为 versionName 比较。
5. 未实现"定时更新"，需要 WorkManager 新依赖，评估后放弃。
