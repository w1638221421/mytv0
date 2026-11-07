package com.lizongying.mytv0.server

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 远程配置服务的一次性访问令牌。
 *
 * - 128 bit 随机数（SecureRandom），十六进制表示；
 * - 带有效期（默认 30 分钟），过期后所有写操作被拒绝；
 * - 可主动失效（用户关闭服务 / 重新生成）；
 * - 比较使用 MessageDigest.isEqual，避免时间侧信道。
 */
class AccessToken(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val random = SecureRandom()

    @Volatile
    private var value: String = newValue()

    @Volatile
    private var expiresAt: Long = clock() + ttlMillis

    fun current(): String = value

    fun remainingMillis(): Long = (expiresAt - clock()).coerceAtLeast(0)

    fun isExpired(): Boolean = clock() >= expiresAt

    fun isValid(candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        if (isExpired()) return false
        val a = candidate.toByteArray(Charsets.UTF_8)
        val b = value.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(a, b)
    }

    /** 重新生成令牌（旧令牌立即失效） */
    @Synchronized
    fun regenerate(): String {
        value = newValue()
        expiresAt = clock() + ttlMillis
        return value
    }

    /** 主动失效 */
    @Synchronized
    fun expire() {
        expiresAt = 0
    }

    private fun newValue(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val sb = StringBuilder(32)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 30 * 60 * 1000L
    }
}
