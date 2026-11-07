package com.lizongying.mytv0.requests

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS 安全配置。
 *
 * 旧实现使用"信任全部证书 + 跳过域名校验"，等于关闭 HTTPS 保护，
 * 任何中间人都可以替换直播流与配置内容。此处改为：
 *
 * 1. 使用系统 CA 证书库（TrustManagerFactory，默认 KeyStore）；
 * 2. 使用 OkHttp 默认 HostnameVerifier（严格校验证书域名）；
 * 3. 允许 TLS 1.2/1.3 与老服务器常用的 COMPATIBLE_TLS（兼容 API 21 设备）；
 * 4. 明确允许明文 HTTP（IPTV 大量使用 http/rtsp/rtmp），但不影响 HTTPS 校验。
 *
 * 说明：minSdk 21 已默认启用 TLS 1.2，历史上为 API 16~19 打补丁的
 * Tls12SocketFactory 不再需要；保留该文件会造成"看似兼容"的死代码，
 * 因此由本对象统一接管连接规格。
 */
object TlsSecurity {

    @Volatile
    private var cachedTrustManager: X509TrustManager? = null

    fun systemTrustManager(): X509TrustManager {
        cachedTrustManager?.let { return it }
        synchronized(this) {
            cachedTrustManager?.let { return it }
            val tm = createTrustManager()
            cachedTrustManager = tm
            return tm
        }
    }

    private fun createTrustManager(): X509TrustManager {
        val factory = try {
            TrustManagerFactory.getInstance("X509")
        } catch (_: Exception) {
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        }
        factory.init(null as KeyStore?)
        val managers = factory.trustManagers
        for (m in managers) {
            if (m is X509TrustManager) return m
        }
        throw IllegalStateException("no X509TrustManager available")
    }

    fun sslContext(trustManager: X509TrustManager): SSLContext {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(trustManager), SecureRandom())
        return ctx
    }

    /**
     * 将系统级 TLS 配置应用到 OkHttp。
     * 不设置 hostnameVerifier，保持 OkHttp 默认的严格域名校验。
     */
    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val tm = systemTrustManager()
        val ctx = sslContext(tm)
        builder.sslSocketFactory(ctx.socketFactory, tm)
        builder.connectionSpecs(connectionSpecs())
        return builder
    }

    fun connectionSpecs(): List<ConnectionSpec> = listOf(
        ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .build(),
        ConnectionSpec.COMPATIBLE_TLS,
        ConnectionSpec.CLEARTEXT,
    )

    /** 供诊断使用：当前可用协议 */
    fun supportedProtocols(): List<String> {
        return try {
            val ctx = sslContext(systemTrustManager())
            ctx.supportedSSLParameters.protocols?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
