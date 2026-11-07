package com.lizongying.mytv0.net

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

enum class NetErrorKind {
    NONE,
    DNS,
    TLS,
    TIMEOUT,
    CONNECT,
    CLEARTEXT_BLOCKED,
    HTTP_401,
    HTTP_403,
    HTTP_404,
    HTTP_429,
    HTTP_5XX,
    EMPTY_BODY,
    CANCELLED,
    UNKNOWN,
}

data class NetError(
    val kind: NetErrorKind,
    val retryable: Boolean,
    val detail: String = "",
) {
    override fun toString(): String = "NetError($kind, retryable=$retryable${if (detail.isEmpty()) "" else ", $detail"})"
}

/**
 * 网络错误分类：不同错误使用不同的重试策略。
 *
 * - DNS / 超时 / 连接失败：可重试（有限次数）
 * - 401 / 403 / 404：不重试（重试无意义，直接换源）
 * - 429：可重试，但需要更长退避
 * - 5xx：可重试一次
 * - TLS：不重试（证书/域名问题不会自愈）
 */
object NetErrorClassifier {

    fun fromHttpCode(code: Int): NetError = when (code) {
        in 200..299 -> NetError(NetErrorKind.NONE, false)
        401 -> NetError(NetErrorKind.HTTP_401, false, "unauthorized")
        403 -> NetError(NetErrorKind.HTTP_403, false, "forbidden")
        404 -> NetError(NetErrorKind.HTTP_404, false, "not found")
        429 -> NetError(NetErrorKind.HTTP_429, true, "too many requests")
        in 500..599 -> NetError(NetErrorKind.HTTP_5XX, true, "server error")
        else -> NetError(NetErrorKind.UNKNOWN, false, "http $code")
    }

    fun fromThrowable(e: Throwable?): NetError {
        var t: Throwable? = e
        var guard = 0
        while (t != null && guard++ < 8) {
            when (t) {
                is UnknownHostException -> return NetError(NetErrorKind.DNS, true, "dns")
                is SSLPeerUnverifiedException -> return NetError(NetErrorKind.TLS, false, "peer unverified")
                is SSLHandshakeException -> return NetError(NetErrorKind.TLS, false, "handshake")
                is SSLException -> return NetError(NetErrorKind.TLS, false, "ssl")
                is SocketTimeoutException -> return NetError(NetErrorKind.TIMEOUT, true, "timeout")
                is InterruptedIOException -> return NetError(NetErrorKind.TIMEOUT, true, "io timeout")
                is UnknownServiceException -> return NetError(NetErrorKind.CLEARTEXT_BLOCKED, false, "cleartext")
                is PortUnreachableException -> return NetError(NetErrorKind.CONNECT, true, "port unreachable")
                is NoRouteToHostException -> return NetError(NetErrorKind.CONNECT, true, "no route")
                is ConnectException -> return NetError(NetErrorKind.CONNECT, true, "connect")
            }
            t = t.cause
        }
        return NetError(NetErrorKind.UNKNOWN, false, e?.javaClass?.simpleName ?: "")
    }

    /** 重试前的退避时间（毫秒），避免失败源被高频重试 */
    fun backoffMillis(attempt: Int, kind: NetErrorKind): Long {
        val base = when (kind) {
            NetErrorKind.HTTP_429 -> 3000L
            NetErrorKind.DNS -> 1200L
            NetErrorKind.TIMEOUT -> 800L
            NetErrorKind.CONNECT -> 600L
            else -> 500L
        }
        val multiplier = (attempt.coerceIn(1, 4)).toLong()
        return (base * multiplier).coerceAtMost(8000L)
    }
}

/**
 * EPG 拉取失败后的退避时长：首次 5 分钟，连续失败逐次翻倍，上限 30 分钟。
 * 纯函数，便于单元测试。
 */
fun epgBackoffMillis(streak: Int): Long {
    val n = streak.coerceIn(1, 8)
    return (5 * 60 * 1000L shl (n - 1)).coerceAtMost(30 * 60 * 1000L)
}
