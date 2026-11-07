package com.lizongying.mytv0.data

import java.io.Serializable

/**
 * 频道模型。
 *
 * 兼容性说明：
 * - 保留原有字段及顺序语义（id / name / title / uris / videoIndex / headers / group / sourceType / number / child）。
 * - 新增字段全部带默认值，并且 Gson 解析走 [com.lizongying.mytv0.parser.ChannelParser] 的手工映射，
 *   不会因为用户 JSON 缺少字段而出现 null 注入导致的崩溃。
 * - [key] 是稳定频道标识（分组 + 名称的摘要），收藏、播放统计都以它为准；
 *   [id] 仍然保留为列表序号，仅供 UI 与遥控器数字键使用，避免破坏既有交互。
 */
data class TV(
    var id: Int = -1,
    var name: String = "",
    var title: String = "",
    var description: String? = null,
    var logo: String = "",
    var image: String? = null,
    var uris: List<String> = emptyList(),
    var videoIndex: Int = 0,
    var headers: Map<String, String>? = null,
    var group: String = "",
    var sourceType: SourceType = SourceType.UNKNOWN,
    var number: Int = -1,
    var child: List<TV> = emptyList(),

    /** 稳定频道标识，见 [com.lizongying.mytv0.parser.ChannelIdentity] */
    var key: String = "",
    /** 展示名称，空值时回退到 title */
    var displayName: String = "",
    /** 是否启用（禁用后不进入频道列表） */
    var enabled: Boolean = true,
    /** 显式排序权重，数值小的靠前，默认按配置顺序 */
    var sortOrder: Int = 0,
    /** 每个播放地址的元数据，与 [uris] 一一对应（可为空） */
    var sources: List<TVSource>? = null,
    /** XMLTV 频道标识（tvg-id），用于节目单精确匹配 */
    var tvgId: String = "",
) : Serializable {

    /** 用于 UI 展示的名称 */
    fun showName(): String = when {
        displayName.isNotEmpty() -> displayName
        title.isNotEmpty() -> title
        name.isNotEmpty() -> name
        else -> ""
    }

    /** 用于 EPG 匹配 / 频道标识的名称 */
    fun matchName(): String = if (name.isNotEmpty()) name else showName()

    fun firstUri(): String? = uris.firstOrNull { it.isNotEmpty() }

    override fun toString(): String {
        return "TV{" +
                "id=" + id +
                ", key='" + key + '\'' +
                ", name='" + name + '\'' +
                ", title='" + title + '\'' +
                ", description='" + description + '\'' +
                ", logo='" + logo + '\'' +
                ", image='" + image + '\'' +
                ", uris=" + uris +
                ", headers=" + headers +
                ", group='" + group + '\'' +
                ", sourceType='" + sourceType + '\'' +
                ", number=" + number +
                '}'
    }
}
