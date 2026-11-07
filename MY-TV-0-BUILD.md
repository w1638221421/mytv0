# MY-TV-0 构建与安装说明

## 1. 产物（v1.0.1 正式版）

```
dist/
├── my-tv-0-release-media3-1.8.0.apk    5,474,319 B   minSdk 21 / compileSdk 35 / Kotlin 2.0.0 / Media3 1.8.0（兼容版，老盒子用这个）
├── my-tv-0-release-media3-1.11.1.apk   8,367,055 B   minSdk 23 / compileSdk 36 / Kotlin 2.2.10 / Media3 1.11.1（最新内核版）
└── my-tv-0-audit-source.zip                        本次改动的完整源码快照（哈希见交付说明，避免自引用）
```

SHA-256：

```
1f6ce9a9180080558c8eafe813178cabb8e103424bc667e35f2cf62ae7693394  my-tv-0-release-media3-1.11.1.apk
1fdeab43aa4552a23a18e1ee57dea1cebd1aee370917ef84d4fdccd397148a7f  my-tv-0-release-media3-1.8.0.apk
```

版本信息：`versionCode=2`，`versionName=1.0.1`（设置页显示 `v1.0.1`）；包名 `com.lizongying.mytv0` 未变。

签名证书：`CN=my-tv-0 Release`，证书 SHA-256
`74705b9cb4892e3f5489b8f4527b7658b758d227e6dd81c8bbb015cc3f6f40d9`
（自查：`apksigner verify --print-certs dist/*.apk`）。

> 两个包内容一致，只有播放内核与兼容范围不同，**不能同时安装**（同包名）。
> 装错版本或跨签名安装会报「应用未安装」：先 `adb uninstall com.lizongying.mytv0` 再装另一个。
> 升级：与本次包同签名的旧版可直接覆盖安装（`adb install -r`）。

安装：

```bash
adb install -r dist/my-tv-0-release-media3-1.8.0.apk     # Android 5.0+，推荐老盒子
adb install -r dist/my-tv-0-release-media3-1.11.1.apk    # Android 6.0+
```

## 2. 本版可见变化

相对最初改造版（0.0.0-audit.1）：

- 设置页移除：更新应用、赞赏作者、汇出诊断、清除日志、远程配置服务开关、上传崩溃日志开关；
- 设置页新增：**线路记忆**（自动切源后，下次启动仍用上次出画的源）；
- 远程配置：应用启动即常驻监听，点「远程配置」直接出二维码（与原版逻辑一致）；
- 应用语言固定简体中文（不再强制繁体 locale）；
- 移除不再使用的权限：`REQUEST_INSTALL_PACKAGES`、`WRITE_EXTERNAL_STORAGE`；
- 崩溃日志只写本地，不再有任何上传路径。

v1.0.1 追加：

- **断网启动宽限 3 分钟**：启动后 3 分钟内持续重试（配置拉取每 6 秒一轮、单轮 15 秒超时；
  当前频道按网络类错误每 6 秒静默重试），期间联网即自动出画，3 分钟到点才彻底放手；
- **节目单换源**：默认地址改为 open-epg 中国区 XMLTV → 51zmt → fanmingming 镜像，
  旧默认 `live.fanmingming.cn` 域名已到期（解析到 `2a03:2880:...`，非自有主机）；
- **EPG 匹配增强**：`CCTV1` 能匹配 `CCTV-1 综合.cn` / `CCTV1高清`，但不会串到 `CCTV10`；
  `CCTV5` 与 `CCTV5+` 不再共用同一份节目单；
- 解析结果为空的镜像算失败（旧版会把"活着但没数据"的镜像当成功，挡住后面更新的源）。

## 3. 标准构建流程（Android Studio / 常规环境）

```bash
# 1) 设置 SDK 路径（或在 Android Studio 中打开自动生成）
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 2) 单元测试
./gradlew :app:testDebugUnitTest

# 3) 构建（未签名）
./gradlew :app:assembleRelease

# 4) 对齐 + 签名
$ANDROID_HOME/build-tools/35.0.0/zipalign -f -p 4 \
    app/build/outputs/apk/release/app-release-unsigned.apk /tmp/aligned.apk
$ANDROID_HOME/build-tools/35.0.0/apksigner sign --min-sdk-version 21 \
    --ks keystore/my-tv-0.jks --ks-pass pass:mytv0-release-2025 \
    --key-pass pass:mytv0-release-2025 --ks-key-alias mytv0 \
    --out dist/my-tv-0-release-media3-1.8.0.apk /tmp/aligned.apk
```

签名密钥：`keystore/my-tv-0.jks`（alias `mytv0`，密码 `mytv0-release-2025`，自签名，有效期 10000 天）。
**这个文件必须长期保留**：以后发版用同一把钥匙，老用户才能覆盖升级；换了钥匙只能卸载重装。

### 3.1 切换 Media3 内核版本

仓库默认是兼容版（Media3 1.8.0 / compileSdk 35 / minSdk 21 / Kotlin 2.0.0）。要出 1.11.1 版，改四处再构建：

```toml
# gradle/libs.versions.toml
media3 = "1.11.1"
kotlin-android = "2.2.10"
```

```kotlin
// app/build.gradle.kts
compileSdk = 36
minSdk = 23          // Media3 1.9.0 起要求 minSdk 23
```

构建完记得改回来。两版唯一的代码适配差异是 `ProgramFragment` 需要显式 `import MainViewModel`
（Kotlin 2.2 不再隐式导入默认包外的顶层符号）。

## 4. 本次审计环境的特殊配置（仅影响本机，不影响工程）

在无 Android Studio 的 aarch64 PRoot 容器中构建时遇到两个环境问题，已通过**全局** `~/.gradle/gradle.properties` 规避，工程文件未被污染：

| 现象 | 原因 | 处理 |
|---|---|---|
| `AAPT2 Daemon startup failed` / `Illegal instruction` | AGP 8.7.3 从 Maven 下载的 aapt2 二进制在本机 CPU 上不可执行 | `android.aapt2FromMavenOverride=/opt/android-sdk/build-tools/35.0.0/aapt2` |
| `Toolchain ... does not provide the required capabilities: [JAVA_COMPILER]` | 系统默认 JDK 21 为 JRE（无 javac） | `org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-arm64` |
| `gradlew` 报 `zip END header not found` | `~/.gradle/wrapper/dists` 里的发行包下载残缺 | 直接用 `/opt/gradle-8.12/bin/gradle` 构建 |

`local.properties` 中的 `sdk.dir` 仅指向本机 SDK，提交/分发前应删除。

## 5. 关键版本

| 组件 | 兼容版 | 最新内核版 |
|---|---|---|
| compileSdk / targetSdk | 35 / 35 | 36 / 35 |
| minSdk | 21（Android 5.0） | 23（Android 6.0） |
| Kotlin | 2.0.0 | 2.2.10 |
| Media3 | 1.8.0 | 1.11.1 |
| AGP / Gradle | 8.7.3 / 8.12 | 8.7.3 / 8.12 |
| OkHttp / Gson / NanoHTTPD | 4.12.0 / 2.11.0 / 2.3.1 | 同左 |
| FFmpeg 解码器 | `app/libs/lib-decoder-ffmpeg-release.aar` | 同左 |

## 6. 安装后自检

1. 启动后确认频道列表非空（首次启动从默认配置地址拉取，缓存到 `files/channels.txt`）。
2. 弹出设置页：应只剩 **远程配置 / 换视频源 / 恢复默认 / 退出应用** 四个按钮，以及
   13 个开关（最末为「线路记忆」），不再有更新、赞赏、诊断、日志、远程服务开关、崩溃上传开关。
3. 点「远程配置」→ 直接出现二维码，文字形如 `192.168.x.x:34567/?t=xxxxxxxx`；
   手机同局域网扫码可读配置（令牌 30 分钟有效，每次点开都会换新）。
4. 线路记忆验证：某频道自动切换到第 2 条源后出画 → 杀进程重开 → 该频道直接从第 2 条源起播；
   设置页关掉「线路记忆」后，回到配置里的第 1 条源。
5. 崩溃日志只落在 `files/crash/`（最多 5 个、单个 256KB、保留 14 天），无任何网络上报。
6. 断网启动验证：拔网线/关 Wi-Fi 开 APP，界面停在加载；20 秒后仍不应判死；
   3 分钟内恢复网络 → 自动出画；超过 3 分钟才允许彻底失败。
7. 节目单验证：进入某频道按「节目」键（或等待信息条），应显示当天节目；无节目时才提示「节目单为空」。

## 7. 测试依赖（仅 `testImplementation`，不进入 APK）

```kotlin
testImplementation(libs.junit)          // 4.13.2
testImplementation(libs.mockwebserver)  // 4.12.0
testImplementation(libs.okhttp.tls)     // 4.12.0（含 BouncyCastle，仅测试）
testImplementation(libs.kxml2)          // 2.3.0（JVM 上的 XmlPullParser 实现）
```
