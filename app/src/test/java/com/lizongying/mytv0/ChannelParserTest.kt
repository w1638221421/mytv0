package com.lizongying.mytv0

import com.lizongying.mytv0.parser.ChannelFormat
import com.lizongying.mytv0.parser.ChannelParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelParserTest {

    private val parser = ChannelParser()

    private val txt = """
        央视频道,#genre#
        CCTV-1,http://a.example.com/1.m3u8,http://b.example.com/1.m3u8
        CCTV-1,http://c.example.com/1.m3u8
        CCTV-10,http://a.example.com/10.m3u8
        卫视频道,#genre#
        湖南卫视,http://hunan.example.com/live.m3u8
    """.trimIndent()

    @Test
    fun parseTxt_mergesDuplicateChannelAndKeepsOrder() {
        val result = parser.parse(txt)
        assertEquals(ChannelFormat.TXT, result.stats.format)
        assertEquals(3, result.channels.size)
        assertEquals(3, result.validChannelCount)

        val cctv1 = result.channels.first { it.name == "CCTV-1" }
        assertEquals(3, cctv1.uris.size)
        assertEquals("央视频道", cctv1.group)
        assertEquals("http://a.example.com/1.m3u8", cctv1.uris[0])

        val hunan = result.channels.first { it.name == "湖南卫视" }
        assertEquals("卫视频道", hunan.group)
        assertTrue(hunan.key.isNotEmpty())
    }

    @Test
    fun parseTxt_ignoresInvalidLines() {
        val text = """
            分组,#genre#
            坏频道
            ,http://no-name.example.com/a.m3u8
            好频道,not-a-url
            正常,http://ok.example.com/a.m3u8
        """.trimIndent()
        val result = parser.parse(text)
        assertEquals(1, result.channels.size)
        assertEquals("正常", result.channels[0].name)
        assertTrue(result.stats.skipped >= 2)
    }

    @Test
    fun parseM3u_readsAttributesHeadersAndEpgUrl() {
        val text = """
            #EXTM3U x-tvg-url="http://epg.example.com/e.xml"
            #EXTINF:-1 tvg-id="cctv1" tvg-name="CCTV-1" tvg-logo="http://logo/1.png" tvg-chno="1" group-title="央视",CCTV-1 综合
            #EXTVLCOPT:http-user-agent=MyUA/1.0
            #EXTGRP:央视
            http://a.example.com/1.m3u8
            #EXTINF:-1 tvg-name="CCTV-1" group-title="央视",CCTV-1
            http://b.example.com/1.m3u8
            #EXTINF:-1 tvg-name="湖南卫视",湖南卫视
            #KODIPROP:inputstream.adaptive.manifest_type=hls
            http://hunan.example.com/live.m3u8
        """.trimIndent()

        val result = parser.parse(text)
        assertEquals(ChannelFormat.M3U, result.stats.format)
        assertEquals(2, result.channels.size)
        assertEquals(listOf("http://epg.example.com/e.xml"), result.epgUrls)

        val cctv1 = result.channels.first { it.name == "CCTV-1" }
        assertEquals("cctv1", cctv1.tvgId)
        assertEquals("http://logo/1.png", cctv1.logo)
        assertEquals(1, cctv1.number)
        assertEquals("CCTV-1 综合", cctv1.title)
        assertEquals(2, cctv1.uris.size)
        assertEquals("MyUA/1.0", cctv1.sources?.firstOrNull()?.headers?.get("user-agent"))
    }

    @Test
    fun parseM3u_titleWithCommaIsPreserved() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-name="新闻",CCTV-13, 新闻频道
            http://news.example.com/live.m3u8
        """.trimIndent()
        val result = parser.parse(text)
        assertEquals(1, result.channels.size)
        assertEquals("CCTV-13, 新闻频道", result.channels[0].title)
    }

    @Test
    fun parseJson_minimalFieldsDoNotCrash() {
        val json = """[{"title":"CCTV1","uris":["http://x.example.com/1.m3u8"]}]"""
        val result = parser.parse(json)
        assertTrue(result.isValid())
        val tv = result.channels[0]
        assertEquals("CCTV1", tv.name)
        assertEquals("", tv.group)
        assertEquals("", tv.logo)
        assertEquals(1, tv.uris.size)
        assertTrue(tv.key.isNotEmpty())
    }

    @Test
    fun parseJson_nullFieldsAreTolerated() {
        val json = """[{"name":null,"title":null,"uris":null},{"name":"A","uris":["http://a/1.m3u8"],"headers":null}]"""
        val result = parser.parse(json)
        assertEquals(1, result.channels.size)
        assertEquals("A", result.channels[0].name)
    }

    @Test
    fun parseJson_perSourceHeadersAndPriority() {
        val json = """
            [{"name":"A","group":"G","uris":[
              {"url":"http://a/1.m3u8","headers":{"Referer":"http://ref"},"priority":2},
              {"url":"http://a/2.m3u8","priority":1}
            ]}]
        """.trimIndent()
        val result = parser.parse(json)
        val tv = result.channels[0]
        assertEquals(2, tv.uris.size)
        assertEquals(1, tv.sources?.get(1)?.priority)
        assertEquals("http://ref", tv.sources?.get(0)?.headers?.get("Referer"))
    }

    @Test
    fun parseJson_wrappedChannelsObject() {
        val json = """{"channels":[{"name":"A","uris":["http://a/1.m3u8"]}]}"""
        val result = parser.parse(json)
        assertTrue(result.isValid())
    }

    @Test
    fun parseJson_invalidSyntaxReturnsError() {
        val result = parser.parse("[{\"name\":\"A\",]")
        assertNotNull(result.error)
        assertFalse(result.isValid())
    }

    @Test
    fun parse_emptyOrGarbageIsInvalid() {
        assertFalse(parser.parse("").isValid())
        assertFalse(parser.parse("   \n  ").isValid())
        assertFalse(parser.parse("this is not a channel list").isValid())
        assertFalse(parser.parse("[]").isValid())
    }

    @Test
    fun parse_jsonWithBomStillWorks() {
        val json = "\uFEFF" + """[{"name":"A","uris":["http://a/1.m3u8"]}]"""
        assertTrue(parser.parse(json).isValid())
    }

    @Test
    fun parse_limitsUrlsPerChannel() {
        val urls = (1..50).joinToString(",") { "http://a/$it.m3u8" }
        val result = parser.parse("频道,#genre#\nA,$urls")
        assertEquals(ChannelParser.MAX_URLS_PER_CHANNEL, result.channels[0].uris.size)
    }

    @Test
    fun urlValidation_acceptsIptvSchemes() {
        assertTrue(ChannelParser.isAcceptableUrl("http://a/b.m3u8"))
        assertTrue(ChannelParser.isAcceptableUrl("rtsp://a/b"))
        assertTrue(ChannelParser.isAcceptableUrl("rtmp://a/b"))
        assertTrue(ChannelParser.isAcceptableUrl("rtp://239.0.0.1:1234"))
        assertTrue(ChannelParser.isAcceptableUrl("udp://@239.0.0.1:1234"))
        assertFalse(ChannelParser.isAcceptableUrl("http://a/b c.m3u8"))
        assertFalse(ChannelParser.isAcceptableUrl("no scheme"))
    }
}
