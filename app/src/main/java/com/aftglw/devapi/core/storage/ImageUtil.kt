package com.aftglw.devapi.core.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片工具：从 Uri 读取并压缩到内部存储 chat_images/ 目录。
 *
 * 用法：
 *   ImageUtil.savePickedImage(ctx, uri) { file -> ... }   // 返回压缩后的本地文件
 *
 * 压缩策略：长边 > 1024px 时按比例缩放到 1024px，质量 80，JPEG。
 * 这样既保留足够细节给 Vision 模型，又控制 base64 大小（避免内存爆掉）。
 */
object ImageUtil {

    private const val MAX_LONG_EDGE = 1024
    private const val JPEG_QUALITY = 80

    /** 子目录名：filesDir/chat_images/ */
    private const val SUB_DIR = "chat_images"

    /**
     * 将 [uri] 指向的图片读取、压缩、保存到 filesDir/chat_images/，
     * 调用 [onSaved] 返回最终文件。失败时调用 [onError] 传递错误信息。
     *
     * 内部 Bitmap 解码 + JPEG 压缩 + 文件写入均在 [Dispatchers.IO] 上执行，
     * 应在协程中调用以避免阻塞主线程。
     */
    suspend fun savePickedImage(
        context: Context,
        uri: Uri,
        onSaved: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                val input = context.contentResolver.openInputStream(uri)
                if (input == null) {
                    onError("无法打开图片流")
                    return@withContext
                }
                // 第一遍：仅解码尺寸
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                input.use { BitmapFactory.decodeStream(it, null, opts) }
                val w = opts.outWidth.coerceAtLeast(1)
                val h = opts.outHeight.coerceAtLeast(1)
                val longEdge = maxOf(w, h)
                // 计算 inSampleSize：2 的幂，使得解码后长边仍 >= MAX_LONG_EDGE / 2
                var sample = 1
                while (longEdge / sample > MAX_LONG_EDGE * 2) sample *= 2

                // 第二遍：真正解码（带 sample）
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOpts)
                }
                if (bmp == null) {
                    onError("图片解码失败")
                    return@withContext
                }

                // 进一步缩放到 MAX_LONG_EDGE
                val scaled = scaleIfNeeded(bmp, MAX_LONG_EDGE)
                if (scaled !== bmp) bmp.recycle()

                // 写入文件
                val dir = File(context.filesDir, SUB_DIR).apply { mkdirs() }
                val file = File(dir, "img_${System.currentTimeMillis()}.jpg")
                file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                scaled.recycle()
                onSaved(file)
            } catch (e: Exception) {
                Log.e("ImageUtil", "savePickedImage failed", e)
                onError(e.message ?: "保存失败")
            }
        }
    }

    /** 如果长边超过 [maxEdge]，按比例缩放；否则原样返回 */
    private fun scaleIfNeeded(bmp: Bitmap, maxEdge: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val longEdge = maxOf(w, h)
        if (longEdge <= maxEdge) return bmp
        val scale = maxEdge.toFloat() / longEdge
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, newW, newH, true)
    }
}

/**
 * 解码 Bitmap 时按 [reqW]/[reqH] 计算下采样 inSampleSize，避免加载原图占用过多内存。
 * 用 inJustDecodeBounds=true 探测尺寸，再算 inSampleSize，最后 inJustDecodeBounds=false 解码。
 *
 * Task 2.6: 用于聊天背景 / 主背景异步解码，避免主线程解码原图导致 OOM 或卡顿。
 */
fun decodeSampledBitmap(path: String, reqW: Int, reqH: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, opts)
    var sample = 1
    var (h, w) = opts.outHeight to opts.outWidth
    while (h / sample > reqH * 2 || w / sample > reqW * 2) sample *= 2
    opts.inSampleSize = sample
    opts.inJustDecodeBounds = false
    return BitmapFactory.decodeFile(path, opts)
}
