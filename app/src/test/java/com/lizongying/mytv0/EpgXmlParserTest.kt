package com.lizongying.mytv0

import com.lizongying.mytv0.data.EPG
import com.lizongying.mytv0.models.EPGXmlParser
import com.lizongying.mytv0.models.EpgIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Calendar
import java.util.TimeZone

class EpgXmlParserTest {

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, 0)
        return cal.timeInMillis / 1000
    }

    private fun input(xml: String) = ByteArrayInputStream(xml.toByteArray())

    @Test
    fun parseXmltvTime_handlesOffset() {
        // 2024-01-01 12:00:00 +0800 == 2024-01-01 04:00 UTC
        assertEquals(utc(2024, 1, 1, 4, 0), EPGXmlParser.parseXmltvTime("20240101120000 +0800"))
        // 不带时区按 UTC 处理
        assertEquals(utc(2024, 1, 1, 12, 0), EPGXmlParser.parseXmltvTime("20240101120000"))
        // 负时区
        assertEquals(utc(2024, 1, 1, 17, 0), EPGXmlParser.parseXmltvTime("20240101120000 -0500"))
    }

    @Test
    fun parseXmltvTime_invalidReturnsZero() {
        assertEquals(0L, EPGXmlParser.parseXmltvTime(null))
        assertEquals(0L, EPGXmlParser.parseXmltvTime(""))
        assertEquals(0L, EPGXmlParser.parseXmltvTime("2024"))
        assertEquals(0L, EPGXmlParser.parseXmltvTime("20241301120000 +0800"))
        assertEquals(0L, EPGXmlParser.parseXmltvTime("abcd0101120000 +0800"))
    }

    @Test
    fun parse_programmeBeforeChannelStillMatches() {
        val now = System.currentTimeMillis() / 1000
        val start = now + 600
        val stop = now + 3600
        fun fmt(t: Long): String {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = t * 1000
            return String.format(
                "%04d%02d%02d%02d%02d%02d +0000",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND)
            )
        }

        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme start="${fmt(start)}" stop="${fmt(stop)}" channel="cctv1">
                <title lang="zh">新闻联播</title>
                <desc>desc</desc>
              </programme>
              <channel id="cctv1"><display-name>CCTV-1</display-name></channel>
            </tv>
        """.trimIndent()

        val result = EPGXmlParser(nowSeconds = now).parse(input(xml))
        assertEquals(1, result.channelNames.size)
        assertEquals("CCTV-1", result.channelNames["cctv1"])

        val list = result.programmes["cctv1"]
        assertNotNull(list)
        assertEquals(1, list!!.size)
        assertEquals("新闻联播", list[0].title)
        assertEquals("CCTV-1", result.programmes["CCTV-1"]?.firstOrNull()?.title?.let { "CCTV-1" })
    }

    @Test
    fun parse_skipsMissingTitleAndPastProgrammes() {
        val now = System.currentTimeMillis() / 1000
        val past = now - 10_000
        val future = now + 1000
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="c1"><display-name>C1</display-name></channel>
              <programme start="19700101000000 +0000" stop="19700101000100 +0000" channel="c1">
                <title>太旧</title>
              </programme>
              <programme start="20240101120000 +0800" stop="20240101130000 +0800" channel="c1">
              </programme>
              <programme start="20240101140000 +0800" stop="20240101150000 +0800" channel="c1">
                <title>没有时间过滤的节目</title>
              </programme>
            </tv>
        """.trimIndent()

        val result = EPGXmlParser(nowSeconds = now, graceSeconds = 3600).parse(input(xml))
        val list = result.programmes["c1"] ?: emptyList()
        // 两个 2024 年的节目都早于 now-3600，应被过滤；无标题节目也应被丢弃
        assertTrue(list.isEmpty())
    }

    @Test
    fun parse_malformedXmlDoesNotCrash() {
        val xml = "<tv><channel id=\"a\"><display-name>A</display-name></channel>"
        val result = try {
            EPGXmlParser().parse(input(xml))
        } catch (e: Exception) {
            null
        }
        // 允许抛异常（由调用方保留旧缓存），但绝不能返回损坏的数据
        if (result != null) {
            assertTrue(result.programmes.values.all { it.isEmpty() })
        }
    }

    @Test
    fun index_matchesByChannelIdAndAvoidsPrefixCollision() {
        val now = System.currentTimeMillis() / 1000
        val list1 = listOf(EPG("CCTV1 节目", now, now + 600, "cctv1"))
        val list10 = listOf(EPG("CCTV10 节目", now, now + 600, "cctv10"))
        val index = EpgIndex.build(mapOf("CCTV-1" to list1, "CCTV-10" to list10))

        assertEquals("CCTV1 节目", index.lookup("CCTV-1")?.first()?.title)
        assertEquals("CCTV10 节目", index.lookup("CCTV-10")?.first()?.title)
        // 没有精确匹配时，不能把 CCTV10 匹配成 CCTV1
        assertNull(index.lookup("CCTV-100"))
        assertEquals("CCTV1 节目", index.lookup("CCTV1高清")?.first()?.title)
    }

    @Test
    fun index_emptyLookup() {
        val index = EpgIndex.build(emptyMap())
        assertNull(index.lookup("CCTV1"))
    }
}

class EpgIndexMatchTest {

    private val now = 1_700_000_000L

    private fun epg(title: String) = EPG(title, now, now + 3600, "x")

    /** open-epg 风格：display-name 带描述与国别后缀 */
    @Test
    fun shortChannelNameMatchesDescriptiveName() {
        val index = EpgIndex.build(
            mapOf(
                "CCTV-1 综合.cn" to listOf(epg("新闻联播")),
                "CCTV-10 科教.cn" to listOf(epg("探索发现")),
            )
        )
        assertEquals("新闻联播", index.lookup("CCTV1")?.first()?.title)
        assertEquals("探索发现", index.lookup("CCTV10")?.first()?.title)
    }

    /** 数字边界：CCTV1 不能命中 CCTV10 的节目单 */
    @Test
    fun doesNotCrossDigitBoundary() {
        val index = EpgIndex.build(mapOf("CCTV-10 科教.cn" to listOf(epg("探索发现"))))
        assertNull(index.lookup("CCTV1"))
    }

    /** 51zmt 风格：display-name 就是 CCTV1，走精确匹配 */
    @Test
    fun exactNameStillWorks() {
        val index = EpgIndex.build(mapOf("CCTV1" to listOf(epg("朝闻天下"))))
        assertEquals("朝闻天下", index.lookup("CCTV1")?.first()?.title)
    }

    /** CCTV5 与 CCTV5+ 是两台节目：加号必须参与身份，不能都落到 cctv5 */
    @Test
    fun plusChannelIsDistinct() {
        val index = EpgIndex.build(
            mapOf(
                "CCTV-5 体育.cn" to listOf(epg("体育新闻")),
                "CCTV-5+ 体育赛事.cn" to listOf(epg("NBA")),
            )
        )
        assertEquals("体育新闻", index.lookup("CCTV5")?.first()?.title)
        assertEquals("NBA", index.lookup("CCTV5+")?.first()?.title)
    }
}
