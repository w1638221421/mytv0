package com.lizongying.mytv0.playback

/** 播放状态机：所有播放器状态变化都必须经过这里，禁止 UI 层自行推断 */
enum class PlaybackState {
    IDLE,
    PREPARING,
    BUFFERING,
    READY,
    PLAYING,
    PAUSED,
    ERROR,
    RELEASED;

    val isActive: Boolean
        get() = this == PREPARING || this == BUFFERING || this == READY || this == PLAYING || this == PAUSED
}

/** 播放失败类型（用于决定"重试 / 换类型 / 换源 / 放弃"） */
enum class FailureKind {
    TIMEOUT,
    DNS,
    TLS,
    AUTH,
    NOT_FOUND,
    RATE_LIMIT,
    SERVER,
    DECODE,
    FORMAT,
    EMPTY,
    UNKNOWN;

    /**
     * 网络类失败：断网 / DNS 不通 / 服务端不可达 / 限流。
     * 启动宽限期里只对这类失败反复重试——联网之后它自己就会好；
     * 404、解码、格式错误重试再多次也没意义。
     */
    val isNetwork: Boolean
        get() = this == TIMEOUT || this == DNS || this == TLS ||
                this == SERVER || this == RATE_LIMIT
}

enum class RecoveryAction {
    RETRY_SAME,
    NEXT_SOURCE_TYPE,
    NEXT_URL,
    GIVE_UP,
}

data class RecoveryState(
    val urlAttempts: Int = 0,
    val typeAttempts: Int = 0,
    val totalAttempts: Int = 0,
    val urlSwitches: Int = 0,
    val urlCount: Int = 1,
    val hasMoreTypes: Boolean = false,
) {
    val hasMoreUrls: Boolean
        get() = urlCount > 1 && urlSwitches < urlCount - 1
}

/**
 * 播放恢复策略（纯 Kotlin，可单元测试）。
 *
 * 硬约束：
 * - 单个地址最多重试 [maxUrlAttempts] 次；
 * - 单个地址最多切换 [maxTypeAttempts] 次封装类型（HLS ↔ PROGRESSIVE）；
 * - 整个切台过程总尝试次数不超过 [maxTotalAttempts]；
 * - 地址最多轮换 urlCount-1 次，杜绝"无限换源"。
 */
class PlaybackRecovery(
    private val maxUrlAttempts: Int = 2,
    private val maxTypeAttempts: Int = 2,
    private val maxTotalAttempts: Int = 6,
) {

    /**
     * @param autoSwitch 用户是否允许自动切换播放源。关闭后只允许"同一地址有限重试"，
     *   任何换地址 / 换封装类型的动作都直接判定为放弃，并立刻把错误抛给 UI。
     */
    fun decide(
        kind: FailureKind,
        state: RecoveryState,
        autoSwitch: Boolean = true,
    ): RecoveryAction {
        val action = decideInternal(kind, state)
        if (!autoSwitch &&
            (action == RecoveryAction.NEXT_URL || action == RecoveryAction.NEXT_SOURCE_TYPE)
        ) {
            return RecoveryAction.GIVE_UP
        }
        return action
    }

    private fun decideInternal(kind: FailureKind, state: RecoveryState): RecoveryAction {
        if (state.totalAttempts >= maxTotalAttempts) return RecoveryAction.GIVE_UP

        return when (kind) {
            // 换容器类型就能解决的情形
            FailureKind.DECODE, FailureKind.FORMAT -> when {
                state.hasMoreTypes && state.typeAttempts < maxTypeAttempts ->
                    RecoveryAction.NEXT_SOURCE_TYPE

                state.hasMoreUrls -> RecoveryAction.NEXT_URL
                else -> RecoveryAction.GIVE_UP
            }

            // 重试无意义，直接换源
            FailureKind.TLS, FailureKind.AUTH, FailureKind.NOT_FOUND, FailureKind.EMPTY ->
                if (state.hasMoreUrls) RecoveryAction.NEXT_URL else RecoveryAction.GIVE_UP

            // 超时：连接被黑洞吞掉时，重试同一地址几乎必然再次超时（实测某卫视首地址
            // 持续挂起，白白耗掉两个 15s 看门狗周期）。所以只要还有备用地址就先换源，
            // 没有备用地址时才做有限重试。
            FailureKind.TIMEOUT ->
                when {
                    state.hasMoreUrls -> RecoveryAction.NEXT_URL
                    state.urlAttempts < maxUrlAttempts -> RecoveryAction.RETRY_SAME
                    state.hasMoreTypes && state.typeAttempts < maxTypeAttempts ->
                        RecoveryAction.NEXT_SOURCE_TYPE

                    else -> RecoveryAction.GIVE_UP
                }

            // 瞬时错误：允许有限重试
            FailureKind.DNS, FailureKind.SERVER, FailureKind.RATE_LIMIT ->
                when {
                    state.urlAttempts < maxUrlAttempts -> RecoveryAction.RETRY_SAME
                    state.hasMoreTypes && state.typeAttempts < maxTypeAttempts ->
                        RecoveryAction.NEXT_SOURCE_TYPE

                    state.hasMoreUrls -> RecoveryAction.NEXT_URL
                    else -> RecoveryAction.GIVE_UP
                }

            FailureKind.UNKNOWN ->
                if (state.hasMoreUrls) RecoveryAction.NEXT_URL else RecoveryAction.GIVE_UP
        }
    }

    fun backoffMillis(kind: FailureKind, attempt: Int): Long {
        val base = when (kind) {
            FailureKind.RATE_LIMIT -> 3000L
            FailureKind.DNS -> 1200L
            FailureKind.TIMEOUT -> 900L
            FailureKind.SERVER -> 800L
            else -> 400L
        }
        return (base * attempt.coerceIn(1, 4)).coerceAtMost(8000L)
    }
}
