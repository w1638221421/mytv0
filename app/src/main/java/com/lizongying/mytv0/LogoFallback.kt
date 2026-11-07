package com.lizongying.mytv0

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat

/**
 * 文字台标兜底。
 *
 * 背景：直播源（尤其是 TXT 格式）通常不提供台标地址，而外部台标镜像在国内网络下经常整组不可达。
 * 旧实现在没有台标时只画一个频道号，用户看到的是"有的频道有台标、有的没有"。
 *
 * 这里统一生成文字台标：优先画频道名（截断到 4 个字符），没有名字时退回频道号；
 * 字号按画布宽度自适应，保证不溢出。同一个频道每次生成的位图一致，不会闪烁。
 */
object LogoFallback {

    fun bitmap(
        context: Context,
        name: String,
        number: Int,
        width: Int = 300,
        height: Int = 180,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.title_blur)
            textAlign = Paint.Align.CENTER
        }

        val label = when {
            name.isBlank() -> number.toString()
            name.length <= 4 -> name
            else -> name.take(4)
        }

        // 自适应字号：先按高度取基准，再按宽度收缩
        var size = height * 0.42f
        paint.textSize = size
        val maxWidth = width * 0.86f
        while (paint.measureText(label) > maxWidth && size > 10f) {
            size -= 4f
            paint.textSize = size
        }

        val y = height / 2f - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(label, width / 2f, y, paint)
        return bmp
    }
}
