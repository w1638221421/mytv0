package com.lizongying.mytv0

import com.lizongying.mytv0.data.TV
import com.lizongying.mytv0.parser.ChannelIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSession

class ChannelIdentityTest {

    @Test
    fun key_isStableAcrossWhitespaceAndPunctuation() {
        val a = ChannelIdentity.key("央视", "CCTV-1")
        val b = ChannelIdentity.key(" 央视 ", "cctv1")
        val c = ChannelIdentity.key("央视", "CCTV 1")
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals(32, a.length)
    }

    @Test
    fun key_differsByGroup() {
        assertNotEquals(
            ChannelIdentity.key("央视", "CCTV-1"),
            ChannelIdentity.key("卫视", "CCTV-1")
        )
    }

    @Test
    fun key_differsForDifferentChannels() {
        assertNotEquals(
            ChannelIdentity.key("央视", "CCTV-1"),
            ChannelIdentity.key("央视", "CCTV-10")
        )
    }

    @Test
    fun migrateLikes_mapsLegacyNumericIds() {
        val idToKey = mapOf(0 to "k0", 1 to "k1", 2 to "k2")
        val migrated = ChannelIdentity.migrateLikes(setOf("0", "2", "99"), idToKey)
        assertEquals(setOf("k0", "k2"), migrated)
    }

    @Test
    fun migrateLikes_keepsAlreadyMigratedKeys() {
        val migrated = ChannelIdentity.migrateLikes(setOf("abcdef0123456789"), emptyMap())
        assertEquals(setOf("abcdef0123456789"), migrated)
    }

    @Test
    fun sourceId_isDeterministic() {
        val a = ChannelIdentity.sourceId("http://a/1.m3u8")
        val b = ChannelIdentity.sourceId("http://a/1.m3u8")
        assertEquals(a, b)
        assertEquals(16, a.length)
        assertTrue(a != ChannelIdentity.sourceId("http://a/2.m3u8"))
    }
}

/**
 * 主机名校验回归测试：旧实现 hostnameVerifier 直接 return true，
 * 这里用真实证书 + 真实 OkHttp 校验器证明当前实现会拒绝错误域名。
 */
class HostnameVerificationTest {

    private fun sessionWith(cert: X509Certificate): SSLSession {
        return java.lang.reflect.Proxy.newProxyInstance(
            SSLSession::class.java.classLoader,
            arrayOf(SSLSession::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getPeerCertificates" -> arrayOf(cert)
                "getProtocol" -> "TLSv1.3"
                "getCipherSuite" -> "TLS_AES_128_GCM_SHA256"
                "getId" -> ByteArray(32)
                "isValid" -> true
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as SSLSession
    }

    @Test
    fun clientRejectsWrongHostname() {
        val held = okhttp3.tls.HeldCertificate.Builder()
            .commonName("example.org")
            .addSubjectAlternativeName("example.org")
            .build()

        val builder = okhttp3.OkHttpClient.Builder()
        com.lizongying.mytv0.requests.TlsSecurity.apply(builder)
        val client = builder.build()
        val verifier = client.hostnameVerifier
        assertTrue(verifier != null)

        val session = sessionWith(held.certificate)
        assertTrue(verifier!!.verify("example.org", session))
        assertTrue(!verifier.verify("example.com", session))
        assertTrue(!verifier.verify("192.168.1.10", session))
    }

    @Test
    fun trustManagerHasRealAnchors() {
        val tm = com.lizongying.mytv0.requests.TlsSecurity.systemTrustManager()
        // 旧实现返回 emptyArray() 并跳过所有校验；系统 TrustManager 必须持有真实 CA
        assertTrue(tm.acceptedIssuers.isNotEmpty())
    }

    @Test
    fun connectionSpecsAllowCleartextButKeepTls() {
        val specs = com.lizongying.mytv0.requests.TlsSecurity.connectionSpecs()
        assertTrue(specs.any { !it.isTls })
        assertTrue(specs.any { it.isTls })
    }
}

class TvModelKeyTest {
    @Test
    fun tvShowNameFallsBack() {
        assertEquals("标题", TV(title = "标题").showName())
        assertEquals("名称", TV(title = "标题", displayName = "名称").showName())
        assertEquals("", TV().showName())
    }
}
