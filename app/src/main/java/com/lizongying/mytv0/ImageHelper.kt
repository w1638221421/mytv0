package com.lizongying.mytv0

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import com.bumptech.glide.Glide
import com.lizongying.mytv0.requests.HttpClient
import com.lizongying.mytv0.requests.LogoCircuit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap


class ImageHelper(private val context: Context) {
    private val cacheDir = context.cacheDir

    private var dir: File = File(cacheDir, LOGO)
    private val files = ConcurrentHashMap<String, File>()

    private val circuit = LogoCircuit()

    init {
        if (!dir.exists()) {
            dir.mkdir()
        }
        dir.listFiles()?.forEach { file ->
            files[file.name] = file
        }
    }

    /** 单次下载结果：用来区分"这个频道没有图"和"这台网络连不上图床" */
    private enum class LogoResult { OK, NOT_FOUND, FAILED }

    /**
     * 下载台标。
     *
     * 旧实现直接写目标文件：下载中断会留下半个文件且永远命中缓存；
     * 另外没有大小限制，异常源可以写入任意大文件。
     * 这里改为"临时文件 + 大小上限 + 成功后原子改名"。
     */
    private suspend fun downloadImage(url: String, file: File): LogoResult {
        return withContext(Dispatchers.IO) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .build()

                HttpClient.apiClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // 404/403 说明图库里没有这个频道；其它状态码当作图床异常
                        return@withContext when (response.code) {
                            403, 404, 410 -> LogoResult.NOT_FOUND
                            else -> LogoResult.FAILED
                        }
                    }
                    val body = response.bodyAlias() ?: return@withContext LogoResult.FAILED
                    val declared = body.contentLength()
                    if (declared > MAX_LOGO_BYTES) {
                        Log.w(TAG, "logo too large $declared")
                        return@withContext LogoResult.FAILED
                    }
                    var total = 0L
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buffer = ByteArray(8 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_LOGO_BYTES) {
                                    tmp.delete()
                                    return@withContext LogoResult.FAILED
                                }
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    if (total <= 0L) {
                        tmp.delete()
                        return@withContext LogoResult.FAILED
                    }
                    tmp.renameTo(file)
                    LogoResult.OK
                }
            } catch (e: Exception) {
                Log.e(TAG, "downloadImage error ${HttpClient.safeUrl(url)}")
                tmp.delete()
                LogoResult.FAILED
            }
        }
    }

    suspend fun preloadImage(
        key: String,
        urlList: List<String>,
    ) {
        val file = files[key]
        if (file != null) {
            Log.d(TAG, "image exists ${file.absolutePath}")
            return
        }

        if (urlList.isEmpty()) {
            return
        }

        // 图床整体不可达时熔断，避免几十个频道逐个超时；确实没有该频道的图只做负缓存
        if (circuit.shouldSkip(key)) {
            return
        }

        val target = File(cacheDir, "$LOGO/$key")
        for (url in urlList) {
            when (downloadImage(url, target)) {
                LogoResult.OK -> {
                    files[target.name] = target
                    circuit.onSuccess(key)
                    Log.d(TAG, "downloadImage success ${target.absolutePath}")
                    return
                }

                LogoResult.NOT_FOUND -> continue

                LogoResult.FAILED -> {
                    if (circuit.onFailure(key)) {
                        Log.w(
                            TAG,
                            "logo host unreachable, pause preload for ${LogoCircuit.DEFAULT_OPEN_MS / 1000}s"
                        )
                    }
                    return
                }
            }
        }

        // 所有候选都 404：记住这个频道没有图，界面用文字台标兜底
        circuit.onNotFound(key)
        Log.d(TAG, "no logo for $key")
    }

    fun loadImage(
        key: String,
        imageView: androidx.appcompat.widget.AppCompatImageView,
        bitmap: Bitmap,
        url: String,
    ) {
        val file = files[key]
        if (file != null) {
            Log.d(TAG, "image exists ${file.absolutePath}")
            Glide.with(context)
                .load(file)
                .fitCenter()
                .into(imageView)
            return
        }

        if (url.isEmpty()) {
            Log.i(TAG, "$key image bitmap")
            Glide.with(context)
                .load(bitmap)
                .fitCenter()
                .into(imageView)
        } else {
            Log.i(TAG, "$key image $url")
            Glide.with(context)
                .load(url)
                .placeholder(BitmapDrawable(context.resources, bitmap))
                .fitCenter()
                .into(imageView)
        }
    }

    fun clearImage() {
        val dir = File(cacheDir, LOGO)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    companion object {
        const val TAG = "ImageHelper"
        const val LOGO = "logo"
        private const val MAX_LOGO_BYTES = 2 * 1024 * 1024L
    }
}
