package com.lizongying.mytv0.parser

import java.security.MessageDigest

/**
 * 频道 / 播放源标识工具。
 *
 * 目标：频道身份不再依赖"解析顺序"，从而在直播源更新后保持收藏、排序与播放历史。
 * 说明：分段使用 MD5 只用于生成稳定 key（非加密用途），不涉及任何安全场景。
 */
object ChannelIdentity {

    private val SPACE = Regex("[\\s\\u00A0]+")
    private val PUNCT = Regex("[\\p{Punct}，。、·・「」『』【】《》（）]+")

    /**
     * 归一化名称：忽略大小写、空白与常见标点差异。
     * 例："CCTV-1 综合" 与 "cctv1综合" 归一化后一致。
     */
    fun normalizeName(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        var s = raw.trim().lowercase()
        s = SPACE.replace(s, "")
        s = PUNCT.replace(s, "")
        return s
    }

    /** 稳定频道 key：分组 + 名称摘要 */
    fun key(group: String?, name: String?): String {
        val g = normalizeName(group)
        val n = normalizeName(name)
        return md5("$g\u0001$n")
    }

    /** 播放源 id：URL 摘要，用于统计表与去重 */
    fun sourceId(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return md5(url).substring(0, 16)
    }

    /** 旧版收藏数据使用频道序号（"0"、"12"），此处判断并解析 */
    fun legacyId(value: String?): Int? {
        if (value.isNullOrEmpty()) return null
        if (value.length > 9) return null
        return value.toIntOrNull()
    }

    /**
     * 收藏数据迁移：旧版按序号存储，新版按稳定 key 存储。
     * 只迁移能明确对应到当前频道的条目，无法对应的直接丢弃（宁可丢收藏，不错配频道）。
     */
    fun migrateLikes(old: Set<String>, idToKey: Map<Int, String>): Set<String> {
        val result = LinkedHashSet<String>(old.size)
        for (raw in old) {
            val id = legacyId(raw)
            if (id != null) {
                idToKey[id]?.let { if (it.isNotEmpty()) result.add(it) }
            } else if (raw.isNotEmpty()) {
                // 已经是新版 key
                result.add(raw)
            }
        }
        return result
    }

    fun md5(input: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                if (v < 0x10) sb.append('0')
                sb.append(Integer.toHexString(v))
            }
            sb.toString()
        } catch (_: Exception) {
            // 极端环境（缺少算法）下退化到 hashCode，保证不崩溃
            Integer.toHexString(input.hashCode())
        }
    }
}
