package com.lizongying.mytv0.models

import com.lizongying.mytv0.data.EPG
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone

/**
 * XMLTV 节目单解析。
 *
 * 相对旧实现的修复：
 * 1. 不再使用 SimpleDateFormat（线程不安全 & 无法表达 ±HHMM 时区），改为手工解析；
 * 2. 时间戳使用 Long，规避 2038 溢出；
 * 3. `<programme>` 出现在 `<channel>` 之前也能正确归属（按 channel id 二次映射）；
 * 4. `<title>` 缺失、空标签、嵌套标签、超长文件均不会导致整体失败；
 * 5. 只保留"最近一小时 + 未来"的节目，并按频道数量上限截断，控制内存占用；
 * 6. 使用 XmlPullParserFactory（Android 与 JVM 单元测试均可运行）。
 */
class EPGXmlParser(
    private val maxChannels: Int = MAX_CHANNELS,
    private val maxProgrammesPerChannel: Int = MAX_PROGRAMMES_PER_CHANNEL,
    private val maxProgrammes: Int = MAX_PROGRAMMES,
    private val nowSeconds: Long = System.currentTimeMillis() / 1000,
    private val graceSeconds: Long = GRACE_SECONDS,
) {

    data class Result(
        /** channelId（或 display-name）→ 节目列表（已按开始时间升序） */
        val programmes: Map<String, List<EPG>>,
        /** channelId → display-name */
        val channelNames: Map<String, String>,
        val channelCount: Int,
        val programmeCount: Int,
        val truncated: Boolean,
    )

    private data class Raw(
        val channelId: String,
        val title: String,
        val start: Long,
        val stop: Long,
    )

    fun parse(input: InputStream): Result {
        val raws = ArrayList<Raw>(256)
        val channelNames = LinkedHashMap<String, String>()
        var truncated = false

        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(input, null)

        var event = parser.eventType
        var depth = 0
        var currentChannelId = ""
        var insideProgramme = false
        var programmeChannel = ""
        var programmeStart = 0L
        var programmeStop = 0L
        var programmeTitle = ""
        var titleCaptured = false
        var guard = 0

        while (event != XmlPullParser.END_DOCUMENT) {
            if (guard++ > MAX_EVENTS) {
                truncated = true
                break
            }
            when (event) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.name?.lowercase()) {
                        "channel" -> {
                            currentChannelId = parser.getAttributeValue(null, "id") ?: ""
                            if (currentChannelId.isNotEmpty() && channelNames.size < maxChannels) {
                                channelNames.putIfAbsent(currentChannelId, "")
                            }
                        }

                        "display-name" -> {
                            if (currentChannelId.isNotEmpty()) {
                                val text = readText(parser)
                                if (text.isNotEmpty() && channelNames[currentChannelId].isNullOrEmpty()) {
                                    channelNames[currentChannelId] = text
                                }
                            }
                        }

                        "programme" -> {
                            insideProgramme = true
                            programmeChannel = parser.getAttributeValue(null, "channel") ?: ""
                            programmeStart = parseXmltvTime(parser.getAttributeValue(null, "start"))
                            programmeStop = parseXmltvTime(parser.getAttributeValue(null, "stop"))
                            programmeTitle = ""
                            titleCaptured = false
                        }

                        "title" -> {
                            if (insideProgramme && !titleCaptured) {
                                programmeTitle = readText(parser)
                                titleCaptured = true
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    depth--
                    when (parser.name?.lowercase()) {
                        "channel" -> currentChannelId = ""
                        "programme" -> {
                            if (insideProgramme) {
                                insideProgramme = false
                                if (programmeChannel.isNotEmpty() &&
                                    programmeTitle.isNotEmpty() &&
                                    programmeStart > 0 &&
                                    programmeStop > programmeStart &&
                                    programmeStop > nowSeconds - graceSeconds
                                ) {
                                    if (raws.size < maxProgrammes) {
                                        raws.add(
                                            Raw(
                                                programmeChannel,
                                                programmeTitle,
                                                programmeStart,
                                                programmeStop
                                            )
                                        )
                                    } else {
                                        truncated = true
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
            if (depth <= 0 && event == XmlPullParser.END_DOCUMENT) break
        }

        // 按 channel 归并
        val grouped = LinkedHashMap<String, MutableList<EPG>>()
        for (raw in raws) {
            val list = grouped.getOrPut(raw.channelId) { ArrayList() }
            if (list.size >= maxProgrammesPerChannel) {
                truncated = true
                continue
            }
            list.add(EPG(raw.title, raw.start, raw.stop, raw.channelId))
        }

        val result = LinkedHashMap<String, List<EPG>>(grouped.size)
        for ((channelId, list) in grouped) {
            val sorted = list.sortedBy { it.beginTime }
            result[channelId] = dedupe(sorted)
            // 同时以 display-name 作为键，方便按名称匹配
            val name = channelNames[channelId]
            if (!name.isNullOrEmpty() && !result.containsKey(name)) {
                result[name] = result[channelId]!!
            }
        }

        return Result(
            programmes = result,
            channelNames = channelNames,
            channelCount = channelNames.size,
            programmeCount = raws.size,
            truncated = truncated,
        )
    }

    private fun dedupe(list: List<EPG>): List<EPG> {
        if (list.size < 2) return list
        val out = ArrayList<EPG>(list.size)
        var lastKey = ""
        for (e in list) {
            val key = "${e.beginTime}|${e.endTime}|${e.title}"
            if (key == lastKey) continue
            lastKey = key
            out.add(e)
        }
        return out
    }

    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        var guard = 0
        while (depth > 0 && guard++ < MAX_TEXT_TOKENS) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT -> if (depth == 1) sb.append(parser.text ?: "")
                XmlPullParser.END_DOCUMENT -> return sb.toString().trim()
            }
        }
        return sb.toString().trim()
    }

    companion object {
        const val MAX_CHANNELS = 4000
        const val MAX_PROGRAMMES_PER_CHANNEL = 400
        const val MAX_PROGRAMMES = 200_000
        const val MAX_EVENTS = 4_000_000
        const val MAX_TEXT_TOKENS = 2000
        const val GRACE_SECONDS = 3600L

        /**
         * 解析 XMLTV 时间：`yyyyMMddHHmmss`，可选 ` +0800` / ` -0500` / ` +08` 时区。
         * 返回 Unix 秒；无法解析返回 0。
         */
        fun parseXmltvTime(raw: String?): Long {
            if (raw.isNullOrBlank()) return 0L
            val s = raw.trim()
            if (s.length < 14) return 0L

            fun num(from: Int, to: Int): Int? {
                if (to > s.length) return null
                var v = 0
                for (i in from until to) {
                    val c = s[i]
                    if (c < '0' || c > '9') return null
                    v = v * 10 + (c - '0')
                }
                return v
            }

            val year = num(0, 4) ?: return 0L
            val month = num(4, 6) ?: return 0L
            val day = num(6, 8) ?: return 0L
            val hour = num(8, 10) ?: return 0L
            val minute = num(10, 12) ?: return 0L
            val second = num(12, 14) ?: return 0L

            if (year < 1970 || year > 2200) return 0L
            if (month !in 1..12 || day !in 1..31) return 0L
            if (hour !in 0..23 || minute !in 0..59 || second !in 0..60) return 0L

            var offsetSeconds = 0
            val zoneStart = s.indexOfFirst { it == '+' || it == '-' }
            if (zoneStart > 0) {
                val zone = s.substring(zoneStart).replace(":", "")
                val sign = if (zone[0] == '-') -1 else 1
                val zoneDigits = zone.substring(1).trim()
                val zh = zoneDigits.take(2).toIntOrNull()
                val zm = if (zoneDigits.length >= 4) zoneDigits.substring(2, 4).toIntOrNull() else 0
                if (zh != null) {
                    offsetSeconds = sign * (zh * 3600 + (zm ?: 0) * 60)
                }
            }

            return try {
                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                cal.clear()
                cal.set(year, month - 1, day, hour, minute, second.coerceAtMost(59))
                cal.timeInMillis / 1000 - offsetSeconds
            } catch (_: Exception) {
                0L
            }
        }
    }
}
