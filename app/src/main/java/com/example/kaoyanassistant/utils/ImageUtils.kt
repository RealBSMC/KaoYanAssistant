package com.example.kaoyanassistant.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 图片处理工具类
 */
object ImageUtils {

    /**
     * 将图片URI转换为Base64编码字符串
     * @param context Android上下文
     * @param uri 图片URI
     * @param maxSize 最大尺寸（宽或高），用于压缩大图片，默认1024
     * @param quality 压缩质量 0-100，默认85
     * @return Base64编码的图片字符串，失败返回null
     */
    fun uriToBase64(
        context: Context,
        uri: Uri,
        maxSize: Int = 1024,
        quality: Int = 85
    ): String? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream?.use { stream ->
                // 读取原始图片
                val originalBitmap = BitmapFactory.decodeStream(stream)

                // 压缩图片
                val resizedBitmap = resizeBitmap(originalBitmap, maxSize)

                // 转换为JPEG格式的字节数组
                val byteArrayOutputStream = ByteArrayOutputStream()
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, byteArrayOutputStream)
                val byteArray = byteArrayOutputStream.toByteArray()

                // 释放资源
                if (resizedBitmap != originalBitmap) {
                    resizedBitmap.recycle()
                }
                originalBitmap.recycle()

                // 转换为Base64
                Base64.encodeToString(byteArray, Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Logger.error("ImageUtils", "图片转Base64失败: ${e.message}")
            null
        }
    }

    /**
     * 将Bitmap转换为Base64编码字符串
     */
    fun bitmapToBase64(
        bitmap: Bitmap,
        maxSize: Int = 1024,
        quality: Int = 85
    ): String? {
        return try {
            val resizedBitmap = resizeBitmap(bitmap, maxSize)
            val byteArrayOutputStream = ByteArrayOutputStream()
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, byteArrayOutputStream)
            val byteArray = byteArrayOutputStream.toByteArray()
            if (resizedBitmap != bitmap) {
                resizedBitmap.recycle()
            }
            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (e: Exception) {
            Logger.error("ImageUtils", "Bitmap转Base64失败: ${e.message}")
            null
        }
    }

    /**
     * 从文件读取Bitmap
     */
    fun decodeBitmap(file: java.io.File): Bitmap? {
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Logger.error("ImageUtils", "读取Bitmap失败: ${e.message}")
            null
        }
    }

    /**
     * 调整图片尺寸，保持宽高比
     * @param bitmap 原始图片
     * @param maxSize 最大尺寸（宽或高）
     * @return 调整后的图片
     */
    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // 如果图片已经足够小，直接返回
        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }

        // 计算缩放比例
        val scale = if (width > height) {
            maxSize.toFloat() / width
        } else {
            maxSize.toFloat() / height
        }

        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * 获取图片的MIME类型
     * @param context Android上下文
     * @param uri 图片URI
     * @return MIME类型，默认返回"image/jpeg"
     */
    fun getMimeType(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.getType(uri) ?: "image/jpeg"
        } catch (e: Exception) {
            Logger.error("ImageUtils", "获取MIME类型失败: ${e.message}")
            "image/jpeg"
        }
    }
}
