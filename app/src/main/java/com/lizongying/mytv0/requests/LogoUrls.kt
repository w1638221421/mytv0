package com.lizongying.mytv0.requests

import java.net.URLEncoder

/**
 * 台标地址生成。
 *
 * 数据源：`https://gitee.com/mytv-android/myTVlogo`（国内可直连，实测 CCTV / 卫视 / 数字频道
 * 覆盖率约 95%）。同一频道按「精确名 → 4K 大小写变体 → 去掉 4K/高清/HD 后缀」依次尝试，
 * 仍不命中时由 [com.lizongying.mytv0.LogoFallback] 画文字台标，界面上不会出现空白。
 *
 * 纯函数，无 Android 依赖，可直接单测。
 */
object LogoUrls {

    const val BASE = "https://gitee.com/mytv-android/myTVlogo/raw/main/img/"

    /** 最多返回几个候选（避免一次绑定时发出过多请求） */
    const val MAX_CANDIDATES = 4

    fun candidates(name: String): List<String> {
        val raw = name.trim()
        if (raw.isEmpty()) return emptyList()

        val names = LinkedHashSet<String>()
        names += raw

        // 4k / 4K 大小写不一致是常见情况（源里叫"浙江卫视4k"，图库是"浙江卫视4K"）
        if (raw.contains("4k", ignoreCase = true)) {
            names += raw.replace(Regex("(?i)4k"), "4K")
        }

        // 带清晰度后缀时，退回主频道名
        val stripped = raw.replace(Regex("(?i)(4k|高清|超清|蓝光|hd)$"), "").trim()
        if (stripped.isNotEmpty() && stripped != raw) {
            names += stripped
        }

        // 图库里部分频道不带 CCTV 前缀（"CCTV兵器科技" → "兵器科技"）
        if (raw.startsWith("CCTV", ignoreCase = true)) {
            val noPrefix = raw.substring(4).trim()
            if (noPrefix.isNotEmpty()) names += noPrefix
        }

        return names.take(MAX_CANDIDATES).map { BASE + encode(it) + ".png" }
    }

    /** 路径段编码：中文按 UTF-8 转义，空格转 %20，'+' 转 %2B */
    fun encode(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
}
