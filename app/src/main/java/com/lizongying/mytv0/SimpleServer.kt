package com.lizongying.mytv0


import MainViewModel
import MainViewModel.Companion.CACHE_FILE_NAME
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.Global.typeSourceList
import com.lizongying.mytv0.data.ReqSettings
import com.lizongying.mytv0.data.ReqSourceAdd
import com.lizongying.mytv0.data.ReqSources
import com.lizongying.mytv0.data.RespSettings
import com.lizongying.mytv0.data.Source
import com.lizongying.mytv0.parser.ChannelParser
import com.lizongying.mytv0.requests.HttpClient
import com.lizongying.mytv0.server.AccessToken
import com.lizongying.mytv0.server.RateLimiter
import fi.iki.elonen.NanoHTTPD
import io.github.lizongying.Gua
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * 局域网远程配置服务（NanoHTTPD）。
 *
 * 安全模型（相对旧版的根本变化）：
 * 1. **默认常驻**：远程配置是正式版的核心功能，应用启动即监听（与原版逻辑一致），
 *    设置页「远程配置」点开即二维码；
 * 2. **令牌鉴权**：所有接口（含首页）都要求 `X-Auth-Token` 头或 `?t=` 参数，
 *    令牌 128bit 随机、30 分钟有效、可在电视端随时失效；
 * 3. **限流**：按来源 IP 限制总请求数与写请求数，防止暴力试探；
 * 4. **请求体上限**：超限直接拒绝，避免内存/磁盘被撑爆；
 * 5. **错误信息不外泄**：异常统一返回通用错误码，不返回堆栈或内部路径；
 * 6. **端口释放**：stop() 关闭 socket；端口被占用时自动尝试 34567..34576。
 *
 * 说明：服务监听在局域网地址上（这是"手机扫码配置电视"这一功能的前提），
 * 而不是公网；在没有令牌的情况下，同网段设备无法读取或修改任何配置。
 */
class SimpleServer(
    private val context: Context,
    private val viewModel: MainViewModel,
    private val token: AccessToken,
    private val port: Int = PORT,
) : NanoHTTPD(port) {

    private val handler = Handler(Looper.getMainLooper())
    private val generalLimiter = RateLimiter(RATE_PER_MINUTE, 60_000L)
    private val writeLimiter = RateLimiter(RATE_WRITE_PER_MINUTE, 60_000L)
    private val sourcesCache = AtomicLong(0)

    @Volatile
    private var cachedSources: String = ""

    override fun serve(session: IHTTPSession): Response {
        val ip = try {
            session.remoteIpAddress ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

        if (!generalLimiter.allow(ip)) {
            return json(TOO_MANY_REQUESTS, """{"ok":false,"error":"rate_limited"}""")
        }

        if (!isAuthorized(session)) {
            // 接口返回 JSON；浏览器直接打开的页面给一个能看懂的提示，
            // 而不是把 {"ok":false} 这坨 JSON 怼到用户脸上
            val path = session.uri ?: "/"
            return if (path.startsWith("/api/")) {
                json(Response.Status.UNAUTHORIZED, """{"ok":false,"error":"unauthorized"}""")
            } else {
                unauthorizedPage()
            }
        }

        val uri = session.uri ?: "/"
        val isWrite = session.method == Method.POST
        if (isWrite && !writeLimiter.allow(ip)) {
            return json(TOO_MANY_REQUESTS, """{"ok":false,"error":"write_rate_limited"}""")
        }

        return try {
            when (uri) {
                "/api/ping" -> json(
                    Response.Status.OK,
                    """{"ok":true,"expires":${token.remainingMillis()}}"""
                )

                "/api/settings" -> handleSettings()
                "/api/sources" -> handleSources()
                "/api/import-text" -> handleImportText(session)
                "/api/import-uri" -> handleImportUri(session)
                "/api/proxy" -> handleProxy(session)
                "/api/epg" -> handleEPG(session)
                "/api/default-channel" -> handleDefaultChannel(session)
                "/api/remove-source" -> handleRemoveSource(session)
                else -> handleStaticContent()
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve $uri", e)
            json(Response.Status.INTERNAL_ERROR, """{"ok":false,"error":"internal"}""")
        }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val header = session.headers["x-auth-token"]
        if (token.isValid(header)) return true
        return token.isValid(queryToken(session))
    }

    /** 从查询串中提取令牌（不依赖 NanoHTTPD 不同版本 parms 的泛型差异） */
    private fun queryToken(session: IHTTPSession): String? {
        val query = try {
            session.queryParameterString
        } catch (e: Exception) {
            null
        }
        if (query.isNullOrEmpty()) return null
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            if (pair.substring(0, idx) != "t") continue
            val raw = pair.substring(idx + 1)
            return try {
                java.net.URLDecoder.decode(raw, "UTF-8")
            } catch (e: Exception) {
                raw
            }
        }
        return null
    }

    /** 令牌无效/过期时给浏览器的可读页面 */
    private fun unauthorizedPage(): Response {
        val html = """
            <!doctype html><html lang="zh"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>访问令牌无效</title>
            <style>
            body{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
                 background:#111;color:#eee;margin:0;padding:28px;line-height:1.8}
            h1{font-size:20px;margin:0 0 12px}
            p{margin:6px 0;color:#bbb;font-size:15px}
            hr{border:0;border-top:1px solid #333;margin:22px 0}
            code{background:#222;padding:2px 6px;border-radius:4px}
            </style></head><body>
            <h1>访问令牌无效或已过期</h1>
            <p>请回到电视/盒子，重新打开「远程配置」，用新二维码扫描。</p>
            <p>令牌有效期 30 分钟，每次重新打开都会刷新。</p>
            <p>如果二维码是之前截图的，也会失效。</p>
            <hr>
            <h1>Invalid or expired token</h1>
            <p>Reopen "Remote config" on the TV and scan the new QR code.</p>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(
            Response.Status.UNAUTHORIZED,
            "text/html; charset=utf-8",
            html
        ).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun json(status: Response.IStatus, body: String): Response {
        val response = newFixedLengthResponse(status, "application/json", body)
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("X-Content-Type-Options", "nosniff")
        return response
    }

    private fun bodyOf(session: IHTTPSession, maxBytes: Int): String? {
        val declared = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (declared > maxBytes) {
            Log.w(TAG, "body too large: $declared")
            return null
        }
        val map = HashMap<String, String>()
        session.parseBody(map)
        val body = map["postData"] ?: return null
        if (body.length > maxBytes) return null
        return body
    }

    // ------------------------------------------------------------ 读取配置

    private fun handleSettings(): Response {
        val str = try {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            ""
        }

        var history = mutableListOf<Source>()
        if (!SP.sources.isNullOrEmpty()) {
            try {
                history = gson.fromJson<List<Source>>(SP.sources!!, typeSourceList).toMutableList()
            } catch (e: Exception) {
                Log.w(TAG, "history parse failed")
            }
        }

        val resp = RespSettings(
            channelUri = SP.configUrl ?: "",
            channelText = str,
            channelDefault = SP.channel,
            proxy = SP.proxy ?: "",
            epg = SP.epg ?: "",
            history = history,
        )
        return json(Response.Status.OK, gson.toJson(resp) ?: "{}")
    }

    /**
     * 内置订阅列表。缓存 10 分钟，避免频繁开关网页反复请求外网。
     */
    private fun handleSources(): Response {
        val now = System.currentTimeMillis()
        if (cachedSources.isNotEmpty() && now - sourcesCache.get() < SOURCES_CACHE_MS) {
            return json(Response.Status.OK, cachedSources)
        }
        val body = try {
            val request = okhttp3.Request.Builder().url(SOURCES_URL).build()
            HttpClient.apiClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) "" else response.body?.string() ?: ""
            }
        } catch (e: Exception) {
            HttpClient.logFailure("sources", SOURCES_URL, e)
            ""
        }
        if (body.isEmpty()) {
            return json(Response.Status.OK, "[]")
        }
        val decoded = try {
            Gua().decode(body)
        } catch (e: Exception) {
            ""
        }
        val list = decoded.trim().split("\n").filter { it.isNotBlank() }
            .map { Source(uri = it.trim()) }
        // 只返回 URI 列表，避免把内部对象结构暴露给前端
        val out = gson.toJson(list.map { mapOf("uri" to it.uri) })
        cachedSources = out
        sourcesCache.set(now)
        return json(Response.Status.OK, out)
    }

    // ------------------------------------------------------------ 写入配置

    private fun handleImportText(session: IHTTPSession): Response {
        val body = bodyOf(session, MAX_IMPORT_BYTES) ?: return tooLarge()
        if (body.isBlank()) return badRequest("empty")
        // 提交前先做一次解析校验，格式错误直接拒绝，不覆盖现有配置
        val parsed = ChannelParser().parse(body)
        if (!parsed.isValid()) {
            return badRequest("invalid_config")
        }
        handler.post {
            viewModel.tryStr2Channels(body, null, "")
        }
        return ok()
    }

    private fun handleImportUri(session: IHTTPSession): Response {
        val body = bodyOf(session, MAX_SMALL_BYTES) ?: return tooLarge()
        val req = try {
            gson.fromJson(body, ReqSourceAdd::class.java)
        } catch (e: Exception) {
            return badRequest("invalid_json")
        } ?: return badRequest("invalid_json")

        val uri = try {
            Uri.parse(req.uri)
        } catch (e: Exception) {
            return badRequest("invalid_uri")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return badRequest("unsupported_scheme")
        }
        if (uri.host.isNullOrEmpty()) return badRequest("invalid_uri")

        val id = try {
            req.id ?: ""
        } catch (e: Exception) {
            ""
        }
        handler.post {
            viewModel.importFromUri(uri, id)
        }
        return ok()
    }

    private fun handleProxy(session: IHTTPSession): Response {
        val body = bodyOf(session, MAX_SMALL_BYTES) ?: return tooLarge()
        val req = try {
            gson.fromJson(body, ReqSettings::class.java)
        } catch (e: Exception) {
            return badRequest("invalid_json")
        } ?: return badRequest("invalid_json")

        val proxy = req.proxy ?: return badRequest("empty")
        if (proxy.isNotEmpty() && com.lizongying.mytv0.net.ProxyConfig.parse(proxy) == null) {
            return badRequest("invalid_proxy")
        }
        handler.post {
            SP.proxy = proxy
            HttpClient.invalidate()
            R.string.default_proxy_set_success.showToast()
        }
        return ok()
    }

    private fun handleEPG(session: IHTTPSession): Response {
        val body = bodyOf(session, MAX_SMALL_BYTES) ?: return tooLarge()
        val req = try {
            gson.fromJson(body, ReqSettings::class.java)
        } catch (e: Exception) {
            return badRequest("invalid_json")
        } ?: return badRequest("invalid_json")

        val epg = req.epg ?: return badRequest("empty")
        if (epg.length > MAX_URL_LENGTH) return badRequest("too_long")
        handler.post {
            SP.epg = epg
            viewModel.updateEPG()
            R.string.default_epg_set_success.showToast()
        }
        return ok()
    }

    private fun handleDefaultChannel(session: IHTTPSession): Response {
        val body = bodyOf(session, MAX_SMALL_BYTES) ?: return tooLarge()
        val req = try {
            gson.fromJson(body, ReqSettings::class.java)
        } catch (e: Exception) {
            return badRequest("invalid_json")
        } ?: return badRequest("invalid_json")

        val channel = req.channel ?: return badRequest("empty")
        if (channel < 0 || channel > ChannelParser.MAX_CHANNELS) return badRequest("out_of_range")
        handler.post {
            SP.channel = channel
            R.string.default_channel_set_success.showToast()
        }
        return ok()
    }

    private fun handleRemoveSource(session: IHTTPSession): Response {
        val body = bodyOf(session, MAX_SMALL_BYTES) ?: return tooLarge()
        val req = try {
            gson.fromJson(body, ReqSources::class.java)
        } catch (e: Exception) {
            return badRequest("invalid_json")
        } ?: return badRequest("invalid_json")

        val sourceId = try {
            req.sourceId ?: ""
        } catch (e: Exception) {
            ""
        }
        if (sourceId.isEmpty() || sourceId.length > 128) {
            return badRequest("invalid_id")
        }
        handler.post {
            val removed = viewModel.sources.removeSource(sourceId)
            Log.i(TAG, "remove source $sourceId -> $removed")
        }
        return ok()
    }

    // ------------------------------------------------------------ 静态页面

    private fun handleStaticContent(): Response {
        val html = try {
            context.resources.openRawResource(R.raw.index).bufferedReader(StandardCharsets.UTF_8)
                .use { it.readText() }
        } catch (e: Exception) {
            return json(Response.Status.INTERNAL_ERROR, """{"ok":false,"error":"internal"}""")
        }
        val response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("X-Content-Type-Options", "nosniff")
        return response
    }

    private fun ok(): Response = json(Response.Status.OK, """{"ok":true}""")

    private fun badRequest(error: String): Response =
        json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"$error"}""")

    private fun tooLarge(): Response =
        json(PAYLOAD_TOO_LARGE, """{"ok":false,"error":"too_large"}""")

    companion object {
        const val TAG = "SimpleServer"
        const val PORT = 34567

        /** NanoHTTPD 2.3.1 未提供 413/429，这里补上标准状态码 */
        private val PAYLOAD_TOO_LARGE = object : Response.IStatus {
            override fun getDescription(): String = "413 Payload Too Large"
            override fun getRequestStatus(): Int = 413
        }

        private val TOO_MANY_REQUESTS = object : Response.IStatus {
            override fun getDescription(): String = "429 Too Many Requests"
            override fun getRequestStatus(): Int = 429
        }

        private const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
        private const val MAX_SMALL_BYTES = 64 * 1024
        private const val MAX_URL_LENGTH = 2048
        private const val RATE_PER_MINUTE = 120
        private const val RATE_WRITE_PER_MINUTE = 20
        private const val SOURCES_CACHE_MS = 10 * 60 * 1000L
        private const val SOURCES_URL =
            "https://raw.githubusercontent.com/lizongying/my-tv-0/main/app/src/main/res/raw/sources.txt"

        /**
         * 依次尝试 34567..34576，返回真正监听成功的实例。
         * 端口被占用时不再抛出异常导致配置页无法使用。
         */
        fun start(context: Context, viewModel: MainViewModel, token: AccessToken): SimpleServer? {
            for (offset in 0 until 10) {
                val server = SimpleServer(context, viewModel, token, PORT + offset)
                try {
                    server.start()
                    Log.i(TAG, "server started on ${PORT + offset}")
                    return server
                } catch (e: Exception) {
                    Log.e(TAG, "start on ${PORT + offset} failed", e)
                }
            }
            return null
        }
    }
}
