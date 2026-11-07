# MY-TV-0 可执行开发任务清单

状态标记：`[x]` 已完成并验证　`[~]` 部分完成　`[ ]` 未实施（附原因）

---

## 阶段 0：环境与基线（已完成）

- [x] 解压源码、核对目录结构（51 个 Kotlin 文件，约 7,100 行）
- [x] 确认构建基线：compileSdk 35 / targetSdk 35 / minSdk 21 / AGP 8.7.3 / Kotlin 2.0.0 / Media3 1.5.1
- [x] 复现原始构建：`assembleDebug` 成功，APK 13,303,800 字节
- [x] 记录本机构建环境特殊项（见 `MY-TV-0-BUILD.md`）：
  - AGP 自带 aapt2 在 PRoot 下 `Illegal instruction`，改用 build-tools aapt2；
  - 默认 JDK21 无 javac，指定 JDK17。

## 阶段 1：P0 安全修复（已完成）

- [x] `requests/TlsSecurity.kt`：系统 CA + 默认域名校验 + TLS1.2/1.3 + COMPATIBLE_TLS + CLEARTEXT
- [x] 删除 `requests/Tls12SocketFactory.kt`（minSdk 21 下无作用，见 ISSUES P0-1 说明）
- [x] `requests/HttpClient.kt` 重写：媒体/接口双客户端、代理键分离、LRU 上限、错误日志脱敏
- [x] `server/AccessToken.kt` + `server/RateLimiter.kt`
- [x] `SimpleServer.kt` 重写：默认关闭、令牌鉴权、限流、body 上限、端口回退、`stop()` 释放
- [x] `MainActivity`：服务生命周期 + 令牌轮换 + 访问地址生成
- [x] `SettingFragment`：新增"远程配置服务"开关（默认关）
- [x] `res/raw/index.html`：令牌注入、401 提示、去 innerHTML 注入、适配新 `/api/sources` 格式
- [x] `MyTVExceptionHandler.kt` 重写：默认本地、脱敏、轮转、可选上传、导出/清理
- [x] `res/xml/network.xml`：发布版仅系统 CA，debug 放开用户 CA
- [x] `UpdateManager.kt`：FileProvider 安装 + 包名/签名校验 + Cursor 安全 + 可取消
- [x] manifest 增加 `FileProvider`，新增 `res/xml/file_paths.xml`

## 阶段 2：播放稳定性（已完成）

- [x] `playback/PlaybackState.kt`（并入 PlaybackRecovery.kt）
- [x] `playback/PlaybackRecovery.kt`：失败分类 → 动作 + 退避
- [x] `playback/PlaybackController.kt`：唯一播放器、监听器、generation、三级看门狗、重试预算、解码回退
- [x] `PlayerFragment.kt` 重写为薄壳：状态回调、错误文案、音量条、生命周期释放
- [x] 修复 `onVideoSizeChanged` 除零（改用 `AspectRatioFrameLayout.FIT`）
- [x] `onPause/onResume` 改为 `pause()/resume()`，不再 `stop()`
- [x] 软硬解切换只重建播放器，不重建 Fragment
- [x] `playback/SourceStats.kt`：成功源记忆（内存 + 定时落盘）

## 阶段 3：直播源与配置事务（已完成）

- [x] `parser/ChannelParser.kt`：TXT/M3U/JSON 统一解析 + 统计 + 上限
- [x] `parser/ChannelIdentity.kt`：稳定 key + 收藏迁移
- [x] `store/ChannelStore.kt`：原子写 + 备份 + 回滚
- [x] `MainViewModel` 重构：解析→校验→提交→应用；失败回滚；自动更新"骤降"保护
- [x] `SP.kt`：收藏线程安全 + 稳定 key + 新配置键（远程服务/崩溃上报/更新时间/版本/统计）
- [x] `TV`/`TVSource` 扩展：`key/displayName/enabled/sortOrder/tvgId/sources`
- [x] `TVModel` 扩展：每地址请求头、优先级、成功记忆、`sortOrder` 排序
- [x] 数字键选台错位修复（`MainActivity.play()`）
- [x] 观察者泄漏修复（`watchedTvModels`）
- [x] `TVGroupModel` 边界与默认分组降级

## 阶段 4：EPG（已完成）

- [x] `EPGXmlParser` 重写：手工时间解析、时区、Long、programme 前置、容错、上限
- [x] `models/EpgIndex.kt`：id/名称/边界感知匹配
- [x] `mainViewModel.readEPG`：IO 线程解析、主线程应用、IO 线程写缓存、总量上限
- [x] `data/EPG.kt`：`Int` → `Long`，新增 `channelId`

## 阶段 5：体验与维护性（部分完成）

- [x] 设置页新增：远程服务开关、崩溃上报开关、导出诊断、清除日志
- [x] 二维码有效期 10s → 60s
- [x] 版本比较抽为 `VersionPolicy`（严格大于）
- [x] 台标下载大小上限 + 临时文件
- [x] `MenuFragment` adapter 强转防护
- [ ] 频道列表焦点恢复的完整重写（现有 `updateList` + `scrollToPositionAndSelect` 已可用，未重构）
- [ ] 4K 布局专项适配（现有 `px2Px` 等比缩放已覆盖，未做逐控件核查）

## 阶段 6：测试（已完成）

- [x] `ChannelParserTest`（13）
- [x] `EpgXmlParserTest`（7）
- [x] `ChannelIdentityTest`（6）
- [x] `HostnameVerificationTest`（3）
- [x] `TlsSecurityTest`（2）
- [x] `NetworkConfigTest`（4）
- [x] `PlaybackRecoveryTest`（6）
- [x] `AccessTokenTest`（3）
- [x] `RateLimiterTest`（2）
- [x] `ChannelStoreTest`（5）
- [x] `VersionPolicyTest`（3）
- [x] `TvModelKeyTest`（1）
- 合计 **55 个用例，全部通过**

## 阶段 7：构建与交付（已完成）

- [x] `assembleDebug` 成功
- [x] `assembleRelease`（R8）成功
- [x] release APK 签名 + `apksigner verify` 通过
- [x] 产物归档到 `dist/`
- [x] 7 份文档 + 构建说明

---

## 未实施项与理由

| 项 | 原因 | 后续建议 |
|---|---|---|
| 定时更新直播源 | 需新增 WorkManager 依赖，与"不引入不必要依赖"冲突 | 若确有需求，用 `AlarmManager` + 现有协程实现，避免新依赖 |
| DataStore/Room 迁移 | 迁移风险 > 收益；已用锁 + commit 解决并发 | 长期可评估，需保留 SP 双读一段时间 |
| 双播放器无缝切台 | 低内存设备会触发解码器争用 | 不做 |
| 播放器画面参数（亮度/对比度） | 需要 Surface 级处理，风险高 | 不做 |
| Instrumented / UI 自动化测试 | 本次环境无 Android 设备/模拟器 | 建议在真机上补 `androidTest` |

---

## 建议的真机验证清单（交付后执行）

1. 冷启动 → 首帧时间（目标 < 5s）
2. 连续切台 50 次（上下键/数字键/菜单选择），观察是否黑屏、错台
3. 断网 30s 再恢复，确认自动重试并恢复播放
4. 收藏 5 个频道 → 更换直播源 → 收藏仍在
5. 修改代理 → 立即生效；清除代理 → 立即生效
6. 开启远程服务 → 手机扫码 → 修改 EPG → 电视生效；关闭服务后端口不可访问
7. 令牌过期（改系统时间 +31min）→ 页面提示重新扫码
8. 检查更新 → 下载 → 安装（Android 7/9/11/13 各一台）
9. 崩溃一次 → 确认无外发请求、`files/crash/` 有日志且已脱敏
10. 弱网（限速 1Mbps）播放 HLS，确认不会因 10s 读超时断流
