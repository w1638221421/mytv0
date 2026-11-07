package com.lizongying.mytv0.data


/**
 * 节目单条目。
 *
 * 时间单位：Unix 秒（Long）。
 * 旧版使用 Int，2038-01-19 之后会溢出；此处改为 Long，
 * Gson 读取旧的 Int 缓存值不会丢失精度。
 */
data class EPG(
    val title: String,
    val beginTime: Long,
    val endTime: Long,
    val channelId: String = "",
)
