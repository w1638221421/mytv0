package com.lizongying.mytv0.data

import java.io.Serializable

/**
 * 单个播放源（一个频道可以拥有多个播放地址）。
 *
 * 设计约束：
 * 1. 保持与旧版 `TV.uris` 的双向兼容：[TV.uris] 仍然是播放顺序的唯一权威来源，
 *    [TVSource] 只是附带在每个地址上的元数据（请求头 / 优先级 / 启用状态）。
 * 2. 不参与频道配置文本（TXT / M3U）的序列化，因此不会破坏旧配置格式。
 * 3. 运行时统计信息（成功时间 / 失败次数）不写入频道配置，而是写入独立的统计表，
 *    避免每次播放都重写频道文件。
 */
data class TVSource(
    var id: String = "",
    var url: String = "",
    var priority: Int = 0,
    var headers: Map<String, String>? = null,
    var enabled: Boolean = true,
    var lastSuccessTime: Long = 0L,
    var lastErrorTime: Long = 0L,
    var failCount: Int = 0,
) : Serializable {

    fun normalized(): TVSource {
        if (id.isNotEmpty()) return this
        id = com.lizongying.mytv0.parser.ChannelIdentity.sourceId(url)
        return this
    }
}
