package com.lizongying.mytv0.models

import com.lizongying.mytv0.data.EPG
import com.lizongying.mytv0.parser.ChannelIdentity

/**
 * EPG 频道匹配索引。
 *
 * XMLTV 的频道标识（id / display-name）与直播源里的频道名往往不一致：
 * 源里常写短名（`CCTV1`），而 XMLTV 里可能是 `CCTV-1 综合`、`CCTV1高清`、`CCTV-1 综合.cn`。
 * 旧实现用 `name.contains(epgName)` 双重循环，CCTV1 会命中 CCTV10。
 *
 * 本索引按以下优先级匹配：
 * 1. 频道 id（tvg-id / XMLTV channel id）完全一致；
 * 2. 归一化名称完全一致；
 * 3. 归一化名称互相包含，但命中位置的边界必须"干净"：
 *    后一位不能是数字（CCTV1 ≠ CCTV10），也不能是拉丁字母（CCTV5 ≠ CCTV5Plus）。
 *
 * 归一化额外处理：
 * - `+` 先替换成 `plus`（否则 CCTV5+ 会被当成 CCTV5，拿到同一份节目单）；
 * - 去掉 `.cn` / `.com` 这类地域后缀（open-epg 之类源的习惯写法）。
 */
class EpgIndex private constructor(
    private val exact: Map<String, List<EPG>>,
    private val keysByLengthDesc: List<String>,
) {

    fun lookup(name: String?, channelId: String? = null): List<EPG>? {
        if (!channelId.isNullOrEmpty()) {
            val id = matchKey(channelId)
            exact[id]?.let { if (it.isNotEmpty()) return it }
        }
        val n = matchKey(name)
        if (n.isEmpty()) return null

        exact[n]?.let { if (it.isNotEmpty()) return it }

        // 频道名里带 EPG 名（"cctv1综合" 命中 "cctv1"）
        for (key in keysByLengthDesc) {
            if (key.length < 2) continue
            if (!n.contains(key)) continue
            val idx = n.indexOf(key)
            if (!boundaryOk(n.getOrNull(idx + key.length))) continue
            exact[key]?.let { if (it.isNotEmpty()) return it }
        }

        // EPG 名里带频道名（"cctv1" 命中 "cctv1综合"）：源里是短名，XMLTV 里带描述后缀
        for (key in keysByLengthDesc) {
            if (key.length <= n.length) continue
            if (!key.startsWith(n)) continue
            if (!boundaryOk(key.getOrNull(n.length))) continue
            exact[key]?.let { if (it.isNotEmpty()) return it }
        }
        return null
    }

    fun size(): Int = exact.size

    companion object {
        fun empty(): EpgIndex = EpgIndex(emptyMap(), emptyList())

        /** XMLTV 里常见的国别后缀，如 "CCTV-1 综合.cn" */
        private val REGION_SUFFIX = Regex("(?i)\\.(cn|com|net|org|tv)$")

        /**
         * 归一化成匹配用的 key。
         * `+`（含全角）先换成 `plus`，再做大小写/空白/标点归一，避免 CCTV5 与 CCTV5+ 串台。
         */
        fun matchKey(raw: String?): String {
            if (raw.isNullOrEmpty()) return ""
            val withPlus = raw.replace('＋', '+').replace("+", "plus")
            return ChannelIdentity.normalizeName(REGION_SUFFIX.replace(withPlus, ""))
        }

        /**
         * 边界是否允许命中：
         * 数字 → 拒绝（CCTV1 不能吃 CCTV10）；拉丁字母 → 拒绝（CCTV5 不能吃 CCTV5Plus）；
         * 中文、结尾、其它符号 → 允许（CCTV1 可以吃 CCTV1综合 / CCTV1高清）。
         */
        fun boundaryOk(ch: Char?): Boolean {
            if (ch == null) return true
            if (ch.isDigit()) return false
            if (ch in 'a'..'z') return false
            return true
        }

        fun build(programmes: Map<String, List<EPG>>): EpgIndex {
            if (programmes.isEmpty()) return empty()
            val normalized = LinkedHashMap<String, List<EPG>>(programmes.size)
            for ((key, value) in programmes) {
                if (value.isEmpty()) continue
                val k = matchKey(key)
                if (k.isEmpty()) continue
                // 同一个 key 保留节目更多的一份（不同 <channel> 可能重名）
                val old = normalized[k]
                if (old == null || value.size > old.size) normalized[k] = value
            }
            val keys = normalized.keys.sortedByDescending { it.length }
            return EpgIndex(normalized, keys)
        }
    }
}
