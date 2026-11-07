package com.lizongying.mytv0.requests

/**
 * 台标预加载的负缓存 + 熔断器。
 *
 * 背景：台标镜像（live.fanmingming.cn / gh.llkk.cc）在部分网络下整组不可达，
 * 而预加载会对每个频道依次尝试所有镜像。80 个频道 × 2 个镜像 × 单次超时，
 * 会形成数分钟的后台空转（真机日志可见每 2.5s 一条下载失败）。
 *
 * 策略：
 * - 负缓存：同一频道失败后 [recentTtlMs] 内不再重试；
 * - 熔断：连续失败 [maxConsecutive] 次后暂停预加载 [openMs]，成功一次即复位。
 *
 * 纯逻辑，无 Android 依赖，可直接单测。
 */
class LogoCircuit(
    private val recentTtlMs: Long = DEFAULT_RECENT_TTL_MS,
    private val maxConsecutive: Int = DEFAULT_MAX_CONSECUTIVE,
    private val openMs: Long = DEFAULT_OPEN_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val failedAt = HashMap<String, Long>()
    private var consecutive = 0
    private var openUntil = 0L

    /** 熔断是否处于打开状态（打开期间跳过所有预加载） */
    fun isOpen(): Boolean = clock() < openUntil

    /** 该 key 是否应当跳过（负缓存命中或熔断打开） */
    @Synchronized
    fun shouldSkip(key: String): Boolean {
        if (clock() < openUntil) return true
        val at = failedAt[key] ?: return false
        if (clock() - at >= recentTtlMs) {
            failedAt.remove(key)
            return false
        }
        return true
    }

    @Synchronized
    fun onSuccess(key: String) {
        failedAt.remove(key)
        consecutive = 0
    }

    /** 图库里确实没有这个频道的图：只做负缓存，不计入主机故障 */
    @Synchronized
    fun onNotFound(key: String) {
        failedAt[key] = clock()
    }

    /** @return true 表示本次失败触发了熔断 */
    @Synchronized
    fun onFailure(key: String): Boolean {
        failedAt[key] = clock()
        consecutive++
        if (consecutive >= maxConsecutive && clock() >= openUntil) {
            openUntil = clock() + openMs
            return true
        }
        return false
    }

    /** 仅用于测试/诊断 */
    @Synchronized
    fun consecutiveFailures(): Int = consecutive

    companion object {
        const val DEFAULT_RECENT_TTL_MS = 6 * 60 * 60 * 1000L
        const val DEFAULT_MAX_CONSECUTIVE = 8
        const val DEFAULT_OPEN_MS = 10 * 60 * 1000L
    }
}
