package com.lizongying.mytv0

import com.lizongying.mytv0.requests.TlsSecurity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * HTTPS 行为回归测试。
 *
 * 旧实现 `checkServerTrusted {}` + `hostnameVerifier { _, _ -> true }` 会接受任何证书，
 * 本测试用真实自签名证书的本地服务器验证：当前实现必须拒绝。
 */
class TlsSecurityTest {

    @Test
    fun selfSignedCertificateIsRejected() {
        val held = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(held)
            .build()

        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("ok"))
        server.start()

        val builder = OkHttpClient.Builder()
        TlsSecurity.apply(builder)
        val client = builder.build()

        var failed = false
        try {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { }
        } catch (e: IOException) {
            failed = true
        } finally {
            server.shutdown()
        }
        assertTrue("自签名证书必须被拒绝", failed)
    }

    @Test
    fun cleartextHttpStillWorks() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()

        val builder = OkHttpClient.Builder()
        TlsSecurity.apply(builder)
        val client = builder.build()

        val body = try {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().use {
                it.body!!.string()
            }
        } finally {
            server.shutdown()
        }
        assertEquals("ok", body)
    }
}
