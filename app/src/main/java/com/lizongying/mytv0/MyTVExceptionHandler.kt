package com.lizongying.mytv0

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 崩溃日志处理。
 *
 * 与旧实现的关键差异：
 * 1. **只写本地，不上传**：正式版不向任何服务器发送崩溃信息（原上传开关已移除）；
 * 2. **本地优先**：崩溃现场先同步写入本地文件（最多 5 个、单个 256KB、保留 14 天）；
 * 3. **隐私过滤**：写盘前剔除 URL 查询参数、代理凭据、token/密码字段；
 * 4. **不再死锁**：旧实现用 runBlocking + Dispatchers.Main 在网络请求里阻塞崩溃线程，
 *    崩溃发生在主线程时会直接卡死（ANR 而不是退出）。
 */
class MyTVExceptionHandler(val context: Context) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val info = buildReport(t, e)
            val writer = Thread({ persist(info) }, "mytv-crash-writer")
            writer.isDaemon = true
            writer.start()
            writer.join(WRITE_TIMEOUT_MS)
        } catch (inner: Throwable) {
            Log.e(TAG, "failed to write crash log", inner)
        }

        try {
            android.os.Process.killProcess(android.os.Process.myPid())
        } catch (_: Throwable) {
        }
        exitProcess(10)
    }

    private fun buildReport(t: Thread, e: Throwable): String {
        val sb = StringBuilder(4096)
        sb.append("time: ").append(timestamp()).append('\n')
        sb.append("app: ").append(appVersion()).append('\n')
        sb.append("android: ").append(Build.VERSION.RELEASE).append(" (").append(Build.VERSION.SDK_INT)
            .append(")\n")
        sb.append("model: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("thread: ").append(t.name).append('\n')
        sb.append("exception: ").append(e.javaClass.name).append('\n')
        sb.append("message: ").append(e.message ?: "").append('\n')
        sb.append("stack:\n").append(Log.getStackTraceString(e)).append('\n')
        return redact(sb.toString())
    }

    private fun appVersion(): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.versionCode})"
    } catch (_: Exception) {
        "unknown"
    }

    private fun timestamp(): String = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    } catch (_: Exception) {
        System.currentTimeMillis().toString()
    }

    private fun persist(info: String) {
        try {
            val dir = logDir(context)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "crash-${System.currentTimeMillis()}.log")
            file.writeText(info)
            cleanup(dir)
        } catch (e: Exception) {
            Log.e(TAG, "persist crash log", e)
        }
    }

    companion object {
        private const val TAG = "MyTVException"
        private const val WRITE_TIMEOUT_MS = 1500L
        private const val MAX_FILES = 5
        private const val MAX_FILE_BYTES = 256 * 1024L
        private const val KEEP_DAYS = 14L

        fun logDir(context: Context): File = File(context.filesDir, "crash")

        fun cleanup(dir: File) {
            try {
                val now = System.currentTimeMillis()
                val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                    ?: return
                files.forEachIndexed { index, file ->
                    val expired = now - file.lastModified() > TimeUnit.DAYS.toMillis(KEEP_DAYS)
                    if (index >= MAX_FILES || expired || file.length() > MAX_FILE_BYTES) {
                        file.delete()
                    }
                }
            } catch (_: Exception) {
            }
        }

        /**
         * 脱敏：
         * - http(s)/rtsp/rtmp URL 只保留 scheme + host + path（去掉 query 与凭据）；
         * - token / password / authorization / cookie 等字段值替换为 ***。
         */
        fun redact(text: String): String {
            var s = text
            s = try {
                Regex("([a-zA-Z][a-zA-Z0-9+.\\-]*://)([^/\\s@]+)@").replace(s) { m ->
                    "${m.groupValues[1]}***@"
                }
            } catch (_: Exception) {
                s
            }
            s = try {
                // 保留 scheme + host + path，丢弃 query（可能含 token / 账号）
                Regex("([a-zA-Z][a-zA-Z0-9+.\\-]*://)([^/\\s?#]+)([^\\s?#]*)(\\?[^\\s]*)?").replace(s) { m ->
                    m.groupValues[1] + m.groupValues[2] + m.groupValues[3]
                }
            } catch (_: Exception) {
                s
            }
            s = try {
                Regex("(?i)(token|password|passwd|pwd|authorization|cookie|secret)(\\s*[=:]\\s*)(\\S+)")
                    .replace(s) { m -> "${m.groupValues[1]}${m.groupValues[2]}***" }
            } catch (_: Exception) {
                s
            }
            return s
        }
    }
}
