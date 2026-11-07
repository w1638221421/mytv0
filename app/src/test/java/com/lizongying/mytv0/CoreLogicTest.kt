package com.lizongying.mytv0

import com.lizongying.mytv0.net.NetErrorClassifier
import com.lizongying.mytv0.parser.ChannelIdentity
import com.lizongying.mytv0.playback.SourceMemory
import com.lizongying.mytv0.net.NetErrorKind
import com.lizongying.mytv0.net.ProxyConfig
import com.lizongying.mytv0.net.epgBackoffMillis
import com.lizongying.mytv0.requests.LogoCircuit
import com.lizongying.mytv0.requests.LogoUrls
import com.lizongying.mytv0.playback.FailureKind
import com.lizongying.mytv0.playback.PlaybackRecovery
import com.lizongying.mytv0.playback.RecoveryAction
import com.lizongying.mytv0.playback.RecoveryState
import com.lizongying.mytv0.server.AccessToken
import com.lizongying.mytv0.server.RateLimiter
import com.lizongying.mytv0.store.ChannelStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.ConnectException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class NetworkConfigTest {

    @Test
    fun proxyParsing() {
        val http = ProxyConfig.parse("192.168.1.2:7890")
        assertNotNull(http)
        assertEquals(Proxy.Type.HTTP, http!!.type)
        assertEquals("192.168.1.2", http.host)
        assertEquals(7890, http.port)

        val socks = ProxyConfig.parse("socks5://10.0.0.1:1080")
        assertEquals(Proxy.Type.SOCKS, socks!!.type)

        val withCredential = ProxyConfig.parse("socks5://user:pass@10.0.0.1:1080")
        assertEquals("10.0.0.1", withCredential!!.host)
        // 凭据不能出现在日志字符串里
        assertFalse(withCredential.toString().contains("pass"))

        assertNull(ProxyConfig.parse(""))
        assertNull(ProxyConfig.parse(null))
        assertNull(ProxyConfig.parse("ftp://1.2.3.4:21"))
        assertNull(ProxyConfig.parse("host:70000"))
    }

    @Test
    fun httpErrorClassification() {
        assertFalse(NetErrorClassifier.fromHttpCode(403).retryable)
        assertEquals(NetErrorKind.HTTP_403, NetErrorClassifier.fromHttpCode(403).kind)
        assertFalse(NetErrorClassifier.fromHttpCode(404).retryable)
        assertTrue(NetErrorClassifier.fromHttpCode(429).retryable)
        assertTrue(NetErrorClassifier.fromHttpCode(502).retryable)
        assertFalse(NetErrorClassifier.fromHttpCode(302).retryable)
    }

    @Test
    fun exceptionClassification() {
        assertEquals(
            NetErrorKind.DNS,
            NetErrorClassifier.fromThrowable(UnknownHostException("a")).kind
        )
        assertEquals(
            NetErrorKind.TLS,
            NetErrorClassifier.fromThrowable(SSLHandshakeException("bad")).kind
        )
        assertEquals(
            NetErrorKind.TIMEOUT,
            NetErrorClassifier.fromThrowable(SocketTimeoutException("t")).kind
        )
        assertEquals(
            NetErrorKind.CONNECT,
            NetErrorClassifier.fromThrowable(ConnectException("c")).kind
        )
        assertEquals(
            NetErrorKind.DNS,
            NetErrorClassifier.fromThrowable(IOException("wrap", UnknownHostException("a"))).kind
        )
        assertEquals(
            NetErrorKind.UNKNOWN,
            NetErrorClassifier.fromThrowable(IllegalStateException("x")).kind
        )
    }

    @Test
    fun backoffIsBounded() {
        for (kind in NetErrorKind.values()) {
            for (attempt in 1..10) {
                val delay = NetErrorClassifier.backoffMillis(attempt, kind)
                assertTrue("delay=$delay", delay in 0..8000)
            }
        }
    }
}

class PlaybackRecoveryTest {

    private val recovery = PlaybackRecovery(maxUrlAttempts = 2, maxTypeAttempts = 2, maxTotalAttempts = 6)

    @Test
    fun dnsErrorRetriesSameUrlFirst() {
        val action = recovery.decide(
            FailureKind.DNS,
            RecoveryState(urlAttempts = 0, urlCount = 3, hasMoreTypes = true)
        )
        assertEquals(RecoveryAction.RETRY_SAME, action)
    }

    @Test
    fun timeoutSwitchesUrlImmediatelyWhenAlternativeExists() {
        // 连接被黑洞吞掉时，重试同一地址只会再耗一个看门狗周期，应先换源
        val action = recovery.decide(
            FailureKind.TIMEOUT,
            RecoveryState(urlAttempts = 0, urlCount = 3, hasMoreTypes = true)
        )
        assertEquals(RecoveryAction.NEXT_URL, action)
    }

    @Test
    fun timeoutRetriesSameUrlWithoutAlternative() {
        val action = recovery.decide(
            FailureKind.TIMEOUT,
            RecoveryState(urlAttempts = 0, urlCount = 1, hasMoreTypes = false)
        )
        assertEquals(RecoveryAction.RETRY_SAME, action)
    }

    @Test
    fun timeoutGivesUpAfterUrlsAndRetriesExhausted() {
        val action = recovery.decide(
            FailureKind.TIMEOUT,
            RecoveryState(urlAttempts = 2, urlSwitches = 1, urlCount = 2, hasMoreTypes = false)
        )
        assertEquals(RecoveryAction.GIVE_UP, action)
    }

    @Test
    fun dnsErrorSwitchesTypeAfterUrlBudget() {
        val action = recovery.decide(
            FailureKind.DNS,
            RecoveryState(urlAttempts = 2, typeAttempts = 0, urlCount = 3, hasMoreTypes = true)
        )
        assertEquals(RecoveryAction.NEXT_SOURCE_TYPE, action)
    }

    @Test
    fun autoSwitchDisabledNeverSwitchesSource() {
        val state = RecoveryState(urlAttempts = 0, urlCount = 3, hasMoreTypes = true)
        assertEquals(
            RecoveryAction.GIVE_UP,
            recovery.decide(FailureKind.TIMEOUT, state, autoSwitch = false)
        )
        assertEquals(
            RecoveryAction.GIVE_UP,
            recovery.decide(FailureKind.NOT_FOUND, state, autoSwitch = false)
        )
        assertEquals(
            RecoveryAction.GIVE_UP,
            recovery.decide(FailureKind.DECODE, state, autoSwitch = false)
        )
    }

    @Test
    fun autoSwitchDisabledStillRetriesSameUrl() {
        val state = RecoveryState(urlAttempts = 0, urlCount = 1, hasMoreTypes = false)
        assertEquals(
            RecoveryAction.RETRY_SAME,
            recovery.decide(FailureKind.DNS, state, autoSwitch = false)
        )
    }

    @Test
    fun authErrorNeverRetriesSameUrl() {
        val action = recovery.decide(
            FailureKind.AUTH,
            RecoveryState(urlAttempts = 0, urlCount = 3, hasMoreTypes = true)
        )
        assertEquals(RecoveryAction.NEXT_URL, action)
    }

    @Test
    fun noMoreUrlsGivesUp() {
        val action = recovery.decide(
            FailureKind.NOT_FOUND,
            RecoveryState(urlAttempts = 0, urlCount = 1, hasMoreTypes = true)
        )
        assertEquals(RecoveryAction.GIVE_UP, action)
    }

    @Test
    fun totalBudgetAlwaysTerminates() {
        var state = RecoveryState(urlCount = 1000, hasMoreTypes = true)
        var attempts = 0
        var lastAction: RecoveryAction? = null
        while (attempts < 100) {
            val action = recovery.decide(FailureKind.TIMEOUT, state)
            lastAction = action
            if (action == RecoveryAction.GIVE_UP) break
            attempts++
            state = when (action) {
                RecoveryAction.RETRY_SAME -> state.copy(
                    urlAttempts = state.urlAttempts + 1,
                    totalAttempts = state.totalAttempts + 1
                )

                RecoveryAction.NEXT_SOURCE_TYPE -> state.copy(
                    typeAttempts = state.typeAttempts + 1,
                    totalAttempts = state.totalAttempts + 1
                )

                RecoveryAction.NEXT_URL -> state.copy(
                    urlSwitches = state.urlSwitches + 1,
                    urlAttempts = 0,
                    typeAttempts = 0,
                    totalAttempts = state.totalAttempts + 1
                )

                RecoveryAction.GIVE_UP -> state
            }
        }
        assertEquals(RecoveryAction.GIVE_UP, lastAction)
        assertTrue("attempts=$attempts", attempts <= 6)
    }

    @Test
    fun urlSwitchesAreBounded() {
        val state = RecoveryState(urlSwitches = 2, urlCount = 3)
        assertFalse(state.hasMoreUrls)
    }
}

class AccessTokenTest {

    @Test
    fun tokenValidatesAndExpires() {
        var now = 1_000L
        val token = AccessToken(ttlMillis = 100, clock = { now })
        val value = token.current()

        assertTrue(token.isValid(value))
        assertFalse(token.isValid(null))
        assertFalse(token.isValid(""))
        assertFalse(token.isValid("deadbeef"))

        now += 50
        assertTrue(token.isValid(value))

        now += 60
        assertFalse(token.isValid(value))
    }

    @Test
    fun regenerateInvalidatesOldToken() {
        var now = 0L
        val token = AccessToken(ttlMillis = 1000, clock = { now })
        val old = token.current()
        val new = token.regenerate()
        assertFalse(old == new)
        assertFalse(token.isValid(old))
        assertTrue(token.isValid(new))
        assertEquals(32, new.length)
    }

    @Test
    fun expireImmediatelyRejects() {
        val token = AccessToken(ttlMillis = 100_000, clock = { 0L })
        val value = token.current()
        assertTrue(token.isValid(value))
        token.expire()
        assertFalse(token.isValid(value))
    }
}

class RateLimiterTest {

    @Test
    fun limitsPerWindow() {
        var now = 0L
        val limiter = RateLimiter(maxRequests = 3, windowMillis = 1000, clock = { now })
        assertTrue(limiter.allow("ip"))
        assertTrue(limiter.allow("ip"))
        assertTrue(limiter.allow("ip"))
        assertFalse(limiter.allow("ip"))
        assertTrue(limiter.allow("other"))

        now += 1000
        assertTrue(limiter.allow("ip"))
    }

    @Test
    fun keysAreBounded() {
        var now = 0L
        val limiter = RateLimiter(maxRequests = 1, windowMillis = 10, clock = { now }, maxKeys = 8)
        repeat(100) { i ->
            now += 20
            limiter.allow("ip-$i")
        }
        assertTrue(limiter.size() <= 9)
    }
}

class ChannelStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun commitReadAndBackup() {
        val store = ChannelStore(folder.root)
        assertFalse(store.exists())
        assertNull(store.read())

        assertTrue(store.commit("first").success)
        assertEquals("first", store.read())

        assertTrue(store.commit("second").success)
        assertEquals("second", store.read())
        assertTrue(store.hasBackup())
        assertEquals("first", store.readBackup())
    }

    @Test
    fun emptyOrTooLargeContentRejected() {
        val store = ChannelStore(folder.root, maxBytes = 10)
        assertFalse(store.commit("").success)
        assertFalse(store.commit("01234567890123").success)
        assertFalse(store.exists())
    }

    @Test
    fun rollbackRestoresPreviousContent() {
        val store = ChannelStore(folder.root)
        store.commit("old")
        store.commit("new")
        assertEquals("new", store.read())
        assertTrue(store.rollback())
        assertEquals("old", store.read())
    }

    @Test
    fun rollbackWithoutBackupFails() {
        val store = ChannelStore(folder.root)
        assertFalse(store.rollback())
    }

    @Test
    fun clearRemovesFiles() {
        val store = ChannelStore(folder.root)
        store.commit("a")
        assertTrue(store.clear())
        assertFalse(store.exists())
    }
}

class SourceMemoryMatchTest {

    private val a = "http://a.example/live.m3u8"
    private val b = "http://b.example/live.m3u8"

    @Test
    fun matchesByIdentityNotIndex() {
        val id = ChannelIdentity.sourceId(b)
        // 配置顺序变了（b 从第 2 位挪到第 1 位），记忆依然命中同一个地址
        assertEquals(0, SourceMemory.matchIndex(listOf(b, a), id))
        assertEquals(1, SourceMemory.matchIndex(listOf(a, b), id))
    }

    @Test
    fun missingSourceReturnsMinusOne() {
        assertEquals(-1, SourceMemory.matchIndex(listOf(a, b), ChannelIdentity.sourceId("http://c/x")))
        assertEquals(-1, SourceMemory.matchIndex(listOf(a, b), ""))
        assertEquals(-1, SourceMemory.matchIndex(emptyList(), ChannelIdentity.sourceId(a)))
    }
}

class EpgBackoffTest {

    @Test
    fun growsExponentiallyAndCaps() {
        assertEquals(5 * 60_000L, epgBackoffMillis(1))
        assertEquals(10 * 60_000L, epgBackoffMillis(2))
        assertEquals(20 * 60_000L, epgBackoffMillis(3))
        assertEquals(30 * 60_000L, epgBackoffMillis(4))
        assertEquals(30 * 60_000L, epgBackoffMillis(99))
        assertEquals(5 * 60_000L, epgBackoffMillis(0))
    }
}

class LogoCircuitTest {

    @Test
    fun negativeCacheSkipsSameKeyUntilTtl() {
        var now = 0L
        val c = LogoCircuit(recentTtlMs = 1_000, maxConsecutive = 99, openMs = 5_000, clock = { now })
        assertFalse(c.shouldSkip("a"))
        c.onFailure("a")
        assertTrue(c.shouldSkip("a"))
        assertFalse(c.shouldSkip("b"))
        now = 1_001
        assertFalse(c.shouldSkip("a"))
    }

    @Test
    fun circuitOpensAfterConsecutiveFailuresAndClosesLater() {
        var now = 0L
        val c = LogoCircuit(recentTtlMs = 100, maxConsecutive = 3, openMs = 1_000, clock = { now })
        assertFalse(c.onFailure("k1"))
        assertFalse(c.onFailure("k2"))
        assertTrue(c.onFailure("k3"))
        assertTrue(c.isOpen())
        assertTrue(c.shouldSkip("new-key"))
        now = 1_001
        assertFalse(c.isOpen())
        assertFalse(c.shouldSkip("new-key"))
    }

    @Test
    fun successResetsStreak() {
        var now = 0L
        val c = LogoCircuit(recentTtlMs = 1_000, maxConsecutive = 2, openMs = 5_000, clock = { now })
        c.onFailure("a")
        c.onSuccess("a")
        assertFalse(c.onFailure("b"))
        assertEquals(1, c.consecutiveFailures())
    }
}

class LogoUrlsTest {

    @Test
    fun exactNameFirst() {
        val list = LogoUrls.candidates("CCTV1")
        assertEquals(LogoUrls.BASE + "CCTV1.png", list.first())
    }

    @Test
    fun addsUppercase4kVariant() {
        val list = LogoUrls.candidates("浙江卫视4k")
        assertTrue(list.any { it.endsWith(LogoUrls.encode("浙江卫视4K") + ".png") })
    }

    @Test
    fun stripsQualitySuffix() {
        val list = LogoUrls.candidates("湖南卫视高清")
        assertTrue(list.any { it.endsWith(LogoUrls.encode("湖南卫视") + ".png") })
    }

    @Test
    fun encodesPlusAndChinese() {
        assertEquals("CCTV5%2B", LogoUrls.encode("CCTV5+"))
        assertTrue(LogoUrls.encode("北京卫视").startsWith("%E5%8C%97"))
    }

    @Test
    fun stripsCctvPrefixAsLastResort() {
        val list = LogoUrls.candidates("CCTV兵器科技")
        assertTrue(list.any { it.endsWith(LogoUrls.encode("兵器科技") + ".png") })
    }

    @Test
    fun emptyNameHasNoCandidates() {
        assertTrue(LogoUrls.candidates("  ").isEmpty())
    }

    @Test
    fun notFoundDoesNotTripCircuit() {
        var now = 0L
        val c = LogoCircuit(recentTtlMs = 1_000, maxConsecutive = 2, openMs = 5_000, clock = { now })
        c.onNotFound("a")
        c.onNotFound("b")
        assertFalse(c.isOpen())
        assertTrue(c.shouldSkip("a"))
        assertEquals(0, c.consecutiveFailures())
    }
}
