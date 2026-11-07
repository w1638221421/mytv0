# MY-TV-0 安全审计报告

> 范围：HTTPS/TLS、局域网配置服务、崩溃日志与隐私、自更新供应链、配置持久化、IPC/导出组件。
> 结论：**未修复前存在 4 个 P0 级安全缺陷（TLS 全信任、远程配置任意可写、崩溃日志外传、更新包无签名校验），已全部修复。**

---

## 1. 威胁模型

| 攻击者 | 能力 | 受影响资产 |
|---|---|---|
| 同网段设备（合租、酒店、公司网络） | 可访问电视 `:34567` | 直播源、代理、EPG、默认频道、订阅历史 |
| 网络中间人（公共 WiFi、被控路由） | 可劫持/篡改 HTTP(S) | 直播流、EPG、版本信息、APK 下载 |
| 恶意直播源提供方 | 控制返回内容 | 频道列表、请求头、URL |
| 恶意 APK 分发者 | 可篡改下载地址 | 设备控制权 |
| 应用自身 | 崩溃日志 | 设备信息、频道 URL |

**不在范围**：Root 设备、物理接触、系统级漏洞、DRM 保护内容。

---

## 2. 修复前后对照

| # | 项目 | 修复前 | 修复后 | 等级 |
|---|---|---|---|---|
| 1 | HTTPS 证书校验 | `checkServerTrusted` 空实现 + `hostnameVerifier{true}` | 系统 CA + OkHttp 默认严格域名校验 | **P0** |
| 2 | 远程配置鉴权 | 无 | 128bit 令牌 + 30min TTL + 常量时间比较 | **P0** |
| 3 | 远程服务默认状态 | 启动即开启 | 默认关闭，用户显式开启 | **P0** |
| 4 | 远程接口限流 | 无 | 120 req/min/IP，写 20 req/min/IP | **P1** |
| 5 | 请求体大小 | 无限制 | 导入 2MB，其它 64KB | **P1** |
| 6 | 崩溃日志 | 默认上传（含设备信息与堆栈） | 默认仅本地 + 脱敏 + 用户可选上传 | **P0** |
| 7 | APK 更新校验 | 无 | 包名 + 签名一致 + 大小下限 | **P0** |
| 8 | APK 安装方式 | `file://`（Android 7+ 崩溃） | FileProvider `content://` | **P0** |
| 9 | 发布版 CA 信任 | 系统 + 用户 CA | 仅系统 CA（debug 才信任用户 CA） | **P1** |
| 10 | 配置页 XSS | `innerHTML` 拼接直播源 | DOM API + `value` 赋值 | **P2** |
| 11 | 日志脱敏 | 无 | 去 query、去 userinfo、token/password 掩码 | **P1** |

---

## 3. 远程配置服务安全设计

### 3.1 鉴权

```
二维码 URL: http://<lan-ip>:34567/?t=<32 hex token>
请求头：X-Auth-Token: <token>   或   ?t=<token>
```

- 令牌：`SecureRandom` 128 bit，十六进制 32 字符；
- 有效期：30 分钟（`AccessToken.DEFAULT_TTL_MILLIS`）；
- 轮换：每次在设置页开启服务时 `regenerate()`，旧令牌立即失效；
- 比较：`MessageDigest.isEqual`（常量时间）。

### 3.2 接口权限矩阵（全部需要令牌）

| 接口 | 方法 | 作用 | 额外限制 |
|---|---|---|---|
| `/api/ping` | GET | 校验令牌剩余时间 | - |
| `/api/settings` | GET | 读取配置 | 限流 |
| `/api/sources` | GET | 内置订阅列表 | 10 分钟缓存 |
| `/api/import-text` | POST | 导入频道文本 | ≤2MB，**先解析校验**再提交 |
| `/api/import-uri` | POST | 订阅 URL | 仅 http/https，≤64KB |
| `/api/proxy` | POST | 设置代理 | 代理格式校验 |
| `/api/epg` | POST | 设置 EPG | URL 长度 ≤2048 |
| `/api/default-channel` | POST | 默认频道 | 0 ≤ n ≤ 20000 |
| `/api/remove-source` | POST | 删除订阅 | id ≤128 字符 |

### 3.3 输入校验

- `Content-Length` 超限直接拒绝；`parseBody` 后再校验实际长度；
- 异常统一返回 `{"ok":false,"error":"internal"}`，不返回 `e.message`/堆栈/路径；
- 配置文件路径固定为 `filesDir/channels.txt`，不存在路径穿越面。

### 3.4 端口与生命周期

- 34567 起尝试 10 个端口，避免"端口占用导致功能不可用"；
- `stop()` 关闭 socket；`MainActivity.onDestroy` 调用；
- 关闭开关立即停止监听。

---

## 4. TLS 实现细节

```kotlin
// 系统信任锚
TrustManagerFactory.getInstance("X509").init(null as KeyStore?)
// 连接规格
MODERN_TLS(TLS 1.3, TLS 1.2) + COMPATIBLE_TLS + CLEARTEXT
// 不设置 hostnameVerifier → OkHttp 默认 OkHostnameVerifier
```

- 保留 `CLEARTEXT`：IPTV 大量使用 `http/rtsp/rtmp/udp`，禁用会直接破坏核心功能；
- 保留 `COMPATIBLE_TLS`：部分老旧源仍用 TLS1.0/1.1，属于"兼容"而非"降级校验"；
- 不提供"信任全部证书"开关，也不提供 per-host 例外；需要自签名的用户应通过受信任反代或系统级安装 CA（debug 构建已放开用户 CA）。

**验证证据**
- `TlsSecurityTest.selfSignedCertificateIsRejected`：MockWebServer + BouncyCastle 生成的自签名证书 → 客户端握手失败；
- `HostnameVerificationTest.clientRejectsWrongHostname`：证书 SAN=`example.org`，`verify("example.com", session) == false`、`verify("192.168.1.10", session) == false`；
- `HostnameVerificationTest.trustManagerHasRealAnchors`：`acceptedIssuers` 非空（旧实现返回 `emptyArray()`）。

---

## 5. 隐私

### 5.1 崩溃日志

- 默认写入 `filesDir/crash/crash-<ts>.log`，**不上传**；
- 仅在用户开启"上传崩溃日志"后才 POST；
- 写盘与上传前均执行 `redact()`：
  - `scheme://user:pass@host` → `scheme://***@host`
  - URL 查询串整体丢弃（避免 token/账号泄漏）
  - `token|password|pwd|authorization|cookie|secret` 值替换为 `***`
- 单文件 ≤256KB，最多 5 个，保留 14 天；
- 提供"导出诊断"（`cacheDir/mytv-diagnostics.txt`）与"清除日志"。

### 5.2 其它

- 不采集设备唯一标识（IMEI/Android ID/MAC）；
- 不上传频道列表、收藏、代理配置；
- 默认不访问局域网扫描（仅读取本机网卡地址用于生成本地 URL）。

---

## 6. 自更新供应链

```
下载 → 校验文件存在且 >1MB → 解析 APK 包名 == 当前包名
     → 签名摘要 == 当前应用签名 → FileProvider 安装
```

- 任一校验失败：删除文件 + 提示"安装包校验失败"；
- 下载地址仅接受 http/https；文件名必须以 `.apk` 结尾；
- 旧 APK 在下次下载前清理，避免磁盘堆积。

**残留风险**：首次安装仍依赖用户侧渠道可信；应用内更新无法防御"系统级"替换。

---

## 7. 配置持久化

- 频道文件原子写 + `.bak`；写入前校验非空、≤8MB；
- 收藏 `commit()` 同步落盘，`synchronized` 保护；
- 新增键不覆盖旧键，旧版本回退仍可读取（除收藏语义变化，见回滚说明）。

---

## 8. 残余风险与建议

| 风险 | 影响 | 建议 |
|---|---|---|
| 远程服务监听 `0.0.0.0` | 局域网内可见端口 | 已用令牌 + 默认关闭 + 限流；如需更强隔离，可增加"仅允许首次绑定设备 IP" |
| 令牌出现在二维码/屏幕 | 旁人拍照可获取 | 30 分钟 TTL + 可重新生成；后续可加"确认码"二次校验 |
| `usesCleartextTraffic=true` | 明文流量可被嗅探 | IPTV 协议限制，无法全局禁用；敏感接口（配置/更新）已尽量走 HTTPS |
| 直播源本身为 http | 内容可被替换 | 应用侧无法解决，属源站问题 |
| 无证书固定（pinning） | 系统 CA 被攻破时可 MITM | 对更新接口可考虑 pinning，但会增加运维成本，暂不做 |

---

## 9. 安全验收清单

- [x] 自签名证书被拒绝
- [x] 错误域名证书被拒绝
- [x] 系统 CA 站点正常访问
- [x] HTTP 明文源正常播放
- [x] 远程接口无令牌返回 401
- [x] 令牌过期后写操作被拒绝
- [x] 关闭开关后端口不可访问
- [x] 超限请求体被拒绝
- [x] 高频请求被限流
- [x] 默认配置下无崩溃日志外发
- [x] 签名不一致的 APK 被拒绝安装
- [x] 发布版不信任用户安装的 CA
