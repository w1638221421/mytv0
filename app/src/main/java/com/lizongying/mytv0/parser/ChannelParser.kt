package com.lizongying.mytv0.parser

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lizongying.mytv0.data.SourceType
import com.lizongying.mytv0.data.TV
import com.lizongying.mytv0.data.TVSource

enum class ChannelFormat { JSON, M3U, TXT, UNKNOWN }

data class ParseStats(
    val format: ChannelFormat = ChannelFormat.UNKNOWN,
    val totalLines: Int = 0,
    val channelCount: Int = 0,
    val mergedDuplicates: Int = 0,
    val skipped: Int = 0,
    val invalidUrls: Int = 0,
    val truncated: Boolean = false,
)

data class ParseResult(
    val channels: List<TV> = emptyList(),
    val stats: ParseStats = ParseStats(),
    val epgUrls: List<String> = emptyList(),
    val error: String? = null,
) {
    /** 有效频道数：至少有一个可播放地址 */
    val validChannelCount: Int
        get() = channels.count { it.uris.isNotEmpty() }

    fun isValid(minChannels: Int = 1): Boolean = error == null && validChannelCount >= minChannels
}

/**
 * 直播源解析器（纯 Kotlin，无 Android 依赖，可单元测试）。
 *
 * 支持：
 * - JSON（数组，或 `{"channels": [...]}`）
 * - M3U / M3U8 播放列表（含 #EXTINF 属性、#EXTVLCOPT、#EXTHTTP、#EXTGRP、#KODIPROP）
 * - TXT（`分组,#genre#` + `名称,地址1,地址2`）
 *
 * 设计原则：
 * - 单条脏数据不影响整体解析；
 * - 任何字段缺失都不会产生 null 注入；
 * - 输入、频道数、地址数均有上限，避免恶意/异常配置导致 OOM。
 */
class ChannelParser(
    private val maxChannels: Int = MAX_CHANNELS,
    private val maxUrlsPerChannel: Int = MAX_URLS_PER_CHANNEL,
    private val maxInputChars: Int = MAX_INPUT_CHARS,
) {

    fun parse(raw: String): ParseResult {
        if (raw.isBlank()) {
            return ParseResult(error = "empty")
        }
        val text = stripBom(raw)
        val truncated = text.length > maxInputChars
        val body = if (truncated) text.substring(0, maxInputChars) else text

        val head = body.trimStart()
        return try {
            when {
                head.startsWith("[") || head.startsWith("{") -> parseJson(body).let {
                    it.copy(stats = it.stats.copy(truncated = truncated))
                }

                looksLikeM3u(body) -> parseM3u(body).let {
                    it.copy(stats = it.stats.copy(truncated = truncated))
                }

                else -> parseTxt(body).let {
                    it.copy(stats = it.stats.copy(truncated = truncated))
                }
            }
        } catch (e: Exception) {
            ParseResult(error = e.javaClass.simpleName)
        }
    }

    private fun stripBom(s: String): String {
        var start = 0
        while (start < s.length && (s[start] == '\uFEFF' || s[start] == '\u0000')) start++
        return if (start == 0) s else s.substring(start)
    }

    private fun looksLikeM3u(s: String): Boolean {
        if (s.contains("#EXTINF", ignoreCase = true)) return true
        val first = s.trimStart().lineSequence().firstOrNull { it.isNotBlank() } ?: return false
        return first.startsWith("#EXTM3U", ignoreCase = true)
    }

    // ---------------------------------------------------------------- JSON

    private fun parseJson(body: String): ParseResult {
        val root: JsonElement = try {
            JsonParser.parseString(body)
        } catch (e: Exception) {
            return ParseResult(error = "json_syntax", stats = ParseStats(ChannelFormat.JSON))
        }

        val array: JsonArray = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> {
                val o = root.asJsonObject
                when {
                    o.has("channels") && o.get("channels").isJsonArray -> o.getAsJsonArray("channels")
                    o.has("data") && o.get("data").isJsonArray -> o.getAsJsonArray("data")
                    else -> return ParseResult(error = "json_shape", stats = ParseStats(ChannelFormat.JSON))
                }
            }

            else -> return ParseResult(error = "json_shape", stats = ParseStats(ChannelFormat.JSON))
        }

        val out = ArrayList<TV>(array.size().coerceAtMost(maxChannels))
        val index = LinkedHashMap<String, TV>()
        var skipped = 0
        var merged = 0
        var invalidUrls = 0
        var count = 0

        for (element in array) {
            if (count >= maxChannels) break
            if (!element.isJsonObject) {
                skipped++
                continue
            }
            val tv = tvFromJson(element.asJsonObject) ?: run {
                skipped++
                null
            } ?: continue
            if (tv.uris.isEmpty()) {
                skipped++
                continue
            }
            invalidUrls += tv.uris.count { !isAcceptableUrl(it) }
            tv.uris = tv.uris.filter { isAcceptableUrl(it) }.take(maxUrlsPerChannel)
            if (tv.uris.isEmpty()) {
                skipped++
                continue
            }
            tv.sources = buildSources(tv)
            val existing = index[tv.key]
            if (existing == null) {
                index[tv.key] = tv
                out.add(tv)
                count++
            } else {
                merged++
                mergeInto(existing, tv)
            }
        }

        val stats = ParseStats(
            format = ChannelFormat.JSON,
            totalLines = body.count { it == '\n' } + 1,
            channelCount = out.size,
            mergedDuplicates = merged,
            skipped = skipped,
            invalidUrls = invalidUrls,
        )
        return ParseResult(out, stats)
    }

    private fun tvFromJson(o: JsonObject): TV? {
        fun str(vararg names: String): String {
            for (n in names) {
                val e = o.get(n) ?: continue
                if (e.isJsonNull) continue
                if (e.isJsonPrimitive) {
                    val v = e.asString
                    if (v.isNotEmpty()) return v
                }
            }
            return ""
        }

        fun int(def: Int, vararg names: String): Int {
            for (n in names) {
                val e = o.get(n) ?: continue
                if (e.isJsonPrimitive) {
                    val v = try {
                        if (e.asJsonPrimitive.isNumber) e.asInt else e.asString.toIntOrNull()
                    } catch (_: Exception) {
                        null
                    }
                    if (v != null) return v
                }
            }
            return def
        }

        fun bool(def: Boolean, vararg names: String): Boolean {
            for (n in names) {
                val e = o.get(n) ?: continue
                if (e.isJsonPrimitive) {
                    return try {
                        e.asBoolean
                    } catch (_: Exception) {
                        def
                    }
                }
            }
            return def
        }

        fun headersFrom(e: JsonElement?): Map<String, String>? {
            if (e == null || !e.isJsonObject) return null
            val map = LinkedHashMap<String, String>()
            for ((k, v) in e.asJsonObject.entrySet()) {
                if (v.isJsonPrimitive && isAcceptableHeaderName(k)) {
                    map[k] = v.asString
                }
            }
            return map.ifEmpty { null }
        }

        val name = str("name", "tvg-name")
        val title = str("title", "tvg-title")
        if (name.isEmpty() && title.isEmpty()) return null

        val group = str("group", "group-title")
        val displayName = str("displayName").ifEmpty { title.ifEmpty { name } }
        val channelHeaders = headersFrom(o.get("headers"))

        val uris = ArrayList<String>()
        val sources = ArrayList<TVSource>()
        val urisElement = o.get("uris")
        if (urisElement != null && urisElement.isJsonArray) {
            for (u in urisElement.asJsonArray) {
                when {
                    u.isJsonPrimitive -> {
                        val url = u.asString.trim()
                        if (url.isNotEmpty()) {
                            uris.add(url)
                            sources.add(TVSource(url = url, headers = channelHeaders, priority = sources.size))
                        }
                    }

                    u.isJsonObject -> {
                        val uo = u.asJsonObject
                        val url = jsonString(uo, "url", "uri")
                        if (url.isNotEmpty()) {
                            uris.add(url)
                            sources.add(
                                TVSource(
                                    url = url,
                                    headers = headersFrom(uo.get("headers")) ?: channelHeaders,
                                    priority = jsonInt(uo, sources.size, "priority"),
                                    enabled = jsonBool(uo, true, "enabled"),
                                )
                            )
                        }
                    }
                }
            }
        }

        if (uris.isEmpty()) {
            val single = str("url", "uri")
            if (single.isNotEmpty()) {
                uris.add(single)
                sources.add(TVSource(url = single, headers = channelHeaders, priority = 0))
            }
        }

        val sourceType = str("sourceType", "type").let { raw ->
            SourceType.values().firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: SourceType.UNKNOWN
        }

        return TV(
            id = int(-1, "id"),
            name = name.ifEmpty { title },
            title = title.ifEmpty { name },
            description = str("description").ifEmpty { null },
            logo = str("logo", "tvg-logo"),
            image = str("image").ifEmpty { null },
            uris = uris,
            videoIndex = int(0, "videoIndex").coerceAtLeast(0),
            headers = channelHeaders,
            group = group,
            sourceType = sourceType,
            number = int(-1, "number", "tvg-chno"),
            key = ChannelIdentity.key(group, name.ifEmpty { title }),
            displayName = displayName,
            enabled = bool(true, "enabled"),
            sortOrder = int(0, "sortOrder"),
            sources = sources.takeIf { it.isNotEmpty() }?.map { it.normalized() },
            tvgId = str("tvg-id", "tvgId", "epgId"),
        )
    }

    private fun jsonString(o: JsonObject, vararg names: String): String {
        for (n in names) {
            val e = o.get(n) ?: continue
            if (e.isJsonPrimitive) {
                val s = try {
                    e.asString
                } catch (_: Exception) {
                    ""
                }
                if (s.isNotEmpty()) return s.trim()
            }
        }
        return ""
    }

    private fun jsonInt(o: JsonObject, def: Int, vararg names: String): Int {
        for (n in names) {
            val e = o.get(n) ?: continue
            if (e.isJsonPrimitive) {
                return try {
                    if (e.asJsonPrimitive.isNumber) e.asInt else e.asString.toIntOrNull() ?: def
                } catch (_: Exception) {
                    def
                }
            }
        }
        return def
    }

    private fun jsonBool(o: JsonObject, def: Boolean, vararg names: String): Boolean {
        for (n in names) {
            val e = o.get(n) ?: continue
            if (e.isJsonPrimitive) {
                return try {
                    e.asBoolean
                } catch (_: Exception) {
                    def
                }
            }
        }
        return def
    }

    // ---------------------------------------------------------------- M3U

    private fun parseM3u(body: String): ParseResult {
        val out = ArrayList<TV>()
        val index = LinkedHashMap<String, TV>()
        val epgUrls = LinkedHashSet<String>()

        var title = ""
        var name = ""
        var logo = ""
        var group = ""
        var number = -1
        var tvgId = ""
        var type = SourceType.UNKNOWN
        var pendingHeaders: MutableMap<String, String>? = null
        var pendingUris = ArrayList<String>()
        var pendingHeadersList = ArrayList<Map<String, String>?>()

        var skipped = 0
        var merged = 0
        var invalidUrls = 0
        var totalLines = 0
        var count = 0

        fun flush() {
            if (pendingUris.isEmpty()) {
                title = ""; name = ""; logo = ""; group = ""; number = -1; tvgId = ""
                type = SourceType.UNKNOWN; pendingHeaders = null
                pendingHeadersList = ArrayList()
                return
            }
            if (count < maxChannels) {
                val channelName = if (name.isNotEmpty()) name else title
                val tv = TV(
                    id = -1,
                    name = channelName,
                    title = title.ifEmpty { channelName },
                    logo = logo,
                    uris = pendingUris,
                    group = group,
                    number = number,
                    sourceType = type,
                    key = ChannelIdentity.key(group, channelName),
                    displayName = title.ifEmpty { channelName },
                    tvgId = tvgId,
                )
                tv.sources = pendingUris.mapIndexed { i, u ->
                    TVSource(url = u, headers = pendingHeadersList.getOrNull(i), priority = i).normalized()
                }
                val existing = index[tv.key]
                if (existing == null) {
                    index[tv.key] = tv
                    out.add(tv)
                    count++
                } else {
                    merged++
                    mergeInto(existing, tv)
                }
            } else {
                skipped++
            }
            title = ""; name = ""; logo = ""; group = ""; number = -1; tvgId = ""
            type = SourceType.UNKNOWN; pendingHeaders = null
            pendingUris = ArrayList()
            pendingHeadersList = ArrayList()
        }

        for (rawLine in body.lineSequence()) {
            totalLines++
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    Regex("""(?:x-tvg-url|url-tvg)="([^"]+)"""", RegexOption.IGNORE_CASE)
                        .findAll(line).forEach { m ->
                            m.groupValues[1].split(',').map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .forEach { epgUrls.add(it) }
                        }
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    flush()
                    val attrsEnd = firstCommaOutsideQuotes(line)
                    val attrs = if (attrsEnd > 0) line.substring(0, attrsEnd) else line
                    val t = if (attrsEnd > 0) line.substring(attrsEnd + 1).trim() else ""
                    title = t
                    name = attr(attrs, "tvg-name") ?: ""
                    logo = attr(attrs, "tvg-logo") ?: ""
                    group = attr(attrs, "group-title") ?: attr(attrs, "group") ?: ""
                    number = attr(attrs, "tvg-chno")?.toIntOrNull() ?: -1
                    tvgId = attr(attrs, "tvg-id") ?: ""
                }

                line.startsWith("#EXTVLCOPT", ignoreCase = true) -> {
                    val opt = line.substringAfter('#').substringAfter(':')
                    val keyValue = opt.split("=", limit = 2)
                    if (keyValue.size == 2) {
                        val key = keyValue[0].removePrefix("http-").trim()
                        if (isAcceptableHeaderName(key)) {
                            val m = pendingHeaders ?: LinkedHashMap<String, String>().also {
                                pendingHeaders = it
                            }
                            m[key] = keyValue[1].trim()
                        }
                    }
                }

                line.startsWith("#EXTHTTP:", ignoreCase = true) -> {
                    val json = line.substringAfter(':').trim()
                    try {
                        val e = JsonParser.parseString(json)
                        if (e.isJsonObject) {
                            val m = pendingHeaders ?: LinkedHashMap<String, String>().also {
                                pendingHeaders = it
                            }
                            for ((k, v) in e.asJsonObject.entrySet()) {
                                if (v.isJsonPrimitive && isAcceptableHeaderName(k)) m[k] = v.asString
                            }
                        }
                    } catch (_: Exception) {
                        // 非法头部声明直接忽略
                    }
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    group = line.substringAfter(':').trim()
                }

                line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                    val kv = line.substringAfter(':').split("=", limit = 2)
                    if (kv.size == 2 && kv[0].contains("manifest_type", ignoreCase = true)) {
                        type = when (kv[1].trim().lowercase()) {
                            "m3u8", "hls" -> SourceType.HLS
                            "mpd", "dash" -> SourceType.DASH
                            "rtsp" -> SourceType.RTSP
                            "rtmp" -> SourceType.RTMP
                            "ism" -> SourceType.DASH
                            "mp4", "progressive" -> SourceType.PROGRESSIVE
                            else -> type
                        }
                    }
                }

                line.startsWith("#") -> Unit // 注释

                else -> {
                    val url = line
                    if (!isAcceptableUrl(url)) {
                        invalidUrls++
                        continue
                    }
                    if (pendingUris.size >= maxUrlsPerChannel) continue
                    pendingUris.add(url)
                    pendingHeadersList.add(pendingHeaders)
                    pendingHeaders = null
                }
            }
        }
        flush()

        val stats = ParseStats(
            format = ChannelFormat.M3U,
            totalLines = totalLines,
            channelCount = out.size,
            mergedDuplicates = merged,
            skipped = skipped,
            invalidUrls = invalidUrls,
        )
        return ParseResult(out, stats, epgUrls.toList())
    }

    /** 返回第一个不在引号内的逗号位置 */
    private fun firstCommaOutsideQuotes(line: String): Int {
        var inQuote = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            if (c == ',' && !inQuote) return i
        }
        return -1
    }

    private fun attr(attrs: String, name: String): String? {
        val r = Regex("""$name="([^"]*)"""", RegexOption.IGNORE_CASE)
        return r.find(attrs)?.groupValues?.get(1)
    }

    // ---------------------------------------------------------------- TXT

    private fun parseTxt(body: String): ParseResult {
        val out = ArrayList<TV>()
        val index = LinkedHashMap<String, TV>()
        var group = ""
        var skipped = 0
        var merged = 0
        var invalidUrls = 0
        var totalLines = 0
        var count = 0

        for (rawLine in body.lineSequence()) {
            totalLines++
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") && !line.contains("#genre#")) continue

            if (line.contains("#genre#")) {
                group = line.substringBefore("#genre#").substringBefore(',').trim()
                continue
            }

            val comma = line.indexOf(',')
            if (comma <= 0) {
                skipped++
                continue
            }
            val channelName = line.substring(0, comma).trim()
            if (channelName.isEmpty()) {
                skipped++
                continue
            }
            val urls = line.substring(comma + 1).split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter { isAcceptableUrl(it) }
                .distinct()
                .take(maxUrlsPerChannel)
            invalidUrls += line.substring(comma + 1).split(',').count {
                val t = it.trim()
                t.isNotEmpty() && !isAcceptableUrl(t)
            }
            if (urls.isEmpty()) {
                skipped++
                continue
            }
            if (count >= maxChannels) {
                skipped++
                continue
            }

            val key = ChannelIdentity.key(group, channelName)
            val existing = index[key]
            if (existing == null) {
                val tv = TV(
                    id = -1,
                    name = channelName,
                    title = channelName,
                    group = group,
                    uris = urls,
                    key = key,
                    displayName = channelName,
                )
                tv.sources = buildSources(tv)
                index[key] = tv
                out.add(tv)
                count++
            } else {
                merged++
                val extra = TV(name = channelName, title = channelName, group = group, uris = urls)
                mergeInto(existing, extra)
            }
        }

        val stats = ParseStats(
            format = ChannelFormat.TXT,
            totalLines = totalLines,
            channelCount = out.size,
            mergedDuplicates = merged,
            skipped = skipped,
            invalidUrls = invalidUrls,
        )
        return ParseResult(out, stats)
    }

    // ---------------------------------------------------------------- common

    private fun buildSources(tv: TV): List<TVSource> {
        val existing = tv.sources
        return tv.uris.mapIndexed { i, url ->
            val meta = existing?.getOrNull(i)
            (if (meta != null && meta.url == url) {
                meta
            } else {
                TVSource(url = url, headers = tv.headers, priority = i)
            }).normalized()
        }
    }

    private fun mergeInto(target: TV, extra: TV) {
        val uris = LinkedHashSet<String>(target.uris)
        val sources = ArrayList<TVSource>(target.sources ?: emptyList())
        for ((i, url) in extra.uris.withIndex()) {
            if (uris.add(url)) {
                val meta = extra.sources?.getOrNull(i)
                    ?: TVSource(url = url, headers = extra.headers, priority = sources.size)
                sources.add(meta.normalized())
            }
        }
        target.uris = uris.toList()
        target.sources = sources.takeIf { it.isNotEmpty() }
        if (target.logo.isEmpty()) target.logo = extra.logo
        if (target.number < 0 && extra.number >= 0) target.number = extra.number
        if (target.description.isNullOrEmpty() && !extra.description.isNullOrEmpty()) {
            target.description = extra.description
        }
    }

    private fun isAcceptableHeaderName(name: String): Boolean {
        if (name.isEmpty() || name.length > 64) return false
        // OkHttp 对头部名称有严格校验，非法名称会在播放时抛异常，这里提前过滤
        return name.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }
    }

    companion object {
        const val MAX_CHANNELS = 20000
        const val MAX_URLS_PER_CHANNEL = 32
        const val MAX_INPUT_CHARS = 8 * 1024 * 1024

        private val URL_RE = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://.*")

        /**
         * 地址合法性：允许标准 scheme、协议相对地址与本地文件路径；
         * 拒绝空白、控制字符与明显的垃圾行。
         */
        fun isAcceptableUrl(url: String): Boolean {
            if (url.isEmpty() || url.length > 4096) return false
            if (url.any { it.isWhitespace() || it.code < 0x20 }) return false
            if (url.startsWith("/")) return true
            return URL_RE.matches(url)
        }
    }
}
