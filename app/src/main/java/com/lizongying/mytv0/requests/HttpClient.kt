package com.lizongying.mytv0.requests

import android.util.Log
import com.lizongying.mytv0.SP
import com.lizongying.mytv0.net.NetErrorClassifier
import com.lizongying.mytv0.net.ProxyConfig
import com.lizongying.mytv0.net.ProxySpec
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局 HTTP 客户端。
 *
 * 与旧实现的关键差异：
 * 1. **不再信任全部证书**：见 [TlsSecurity]，使用系统 CA + 默认域名校验；
 * 2. **代理客户端不复用旧配置**：客户端按代理配置分别缓存，代理变化立即生效；
 * 3. **区分媒体与接口超时**：直播流 readTimeout = 0，接口请求 15s 超时，
 *    避免"缓冲几秒就被判定失败"或"接口卡死无限等待"；
 * 4. **客户端缓存有上限**：LRU 最多 4 个，避免反复切换代理造成对象泄漏；
 * 5. **错误分类统一**：见 [NetErrorClassifier]，便于按错误类型决定重试策略。
 */
object HttpClient {
    const val TAG = "HttpClient"

    private const val MAX_CACHED_CLIENTS = 4
    private const val MEDIA_CONNECT_TIMEOUT_SECONDS = 10L
    private const val API_CONNECT_TIMEOUT_SECONDS = 8L
    private const val API_READ_TIMEOUT_SECONDS = 15L
    private const val API_WRITE_TIMEOUT_SECONDS = 15L
    private const val API_CALL_TIMEOUT_SECONDS = 35L

    /** 全进程共享一份 DNS 缓存，减少重复解析 */
    private val dnsCache = DnsCache()

    private val clients = object : LinkedHashMap<String, OkHttpClient>(MAX_CACHED_CLIENTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OkHttpClient>?): Boolean {
            // 只移除引用，不主动关闭连接池：可能仍有播放器在使用旧客户端
            return size > MAX_CACHED_CLIENTS
        }
    }

    /** 媒体播放使用：无读超时，适配直播长连接 */
    val okHttpClient: OkHttpClient
        get() = client(media = true)

    /** 配置 / EPG / 版本接口使用：带完整超时 */
    val apiClient: OkHttpClient
        get() = client(media = false)

    fun client(media: Boolean, proxy: String? = SP.proxy): OkHttpClient {
        val spec = ProxyConfig.parse(proxy)
        val key = buildString {
            append(if (media) "m:" else "a:")
            append(spec?.toString() ?: "direct")
        }
        synchronized(clients) {
            clients[key]?.let { return it }
            val created = build(spec, media)
            clients[key] = created
            return created
        }
    }

    private fun build(spec: ProxySpec?, media: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
        TlsSecurity.apply(builder)

        builder.dns(dnsCache)
        builder.retryOnConnectionFailure(true)
        builder.followRedirects(true)
        builder.followSslRedirects(true)

        if (media) {
            builder.connectTimeout(MEDIA_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            builder.readTimeout(0, TimeUnit.MILLISECONDS)
            builder.writeTimeout(0, TimeUnit.MILLISECONDS)
            builder.callTimeout(0, TimeUnit.MILLISECONDS)
        } else {
            builder.connectTimeout(API_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            builder.readTimeout(API_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            builder.writeTimeout(API_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            builder.callTimeout(API_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        spec?.let {
            try {
                builder.proxy(it.toProxy())
                Log.i(TAG, "apply proxy $it")
            } catch (e: Exception) {
                Log.e(TAG, "invalid proxy $it")
            }
        }

        return builder.build()
    }

    /**
     * 代理配置变化后调用（例如设置页修改代理、远程接口修改代理）。
     * 后续请求会自动使用新配置的客户端。
     */
    fun invalidate() {
        synchronized(clients) {
            clients.clear()
        }
    }

    fun clearDnsCache() {
        dnsCache.clear()
    }

    /**
     * 记录失败原因（不含敏感信息）。
     * URL 中的查询参数可能包含 token / 账号，因此日志只保留 scheme + host + path。
     */
    fun logFailure(scope: String, url: String, e: Throwable?) {
        val err = NetErrorClassifier.fromThrowable(e)
        Log.e(TAG, "$scope ${safeUrl(url)} -> $err")
    }

    fun safeUrl(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return try {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd <= 0) return url.substringBefore('?')
            val rest = url.substring(schemeEnd + 3)
            val hostEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            if (hostEnd < 0) return url
            val host = rest.substring(0, hostEnd)
            val path = rest.substring(hostEnd).substringBefore('?').substringBefore('#')
            url.substring(0, schemeEnd + 3) + host + path
        } catch (_: Exception) {
            ""
        }
    }
}
