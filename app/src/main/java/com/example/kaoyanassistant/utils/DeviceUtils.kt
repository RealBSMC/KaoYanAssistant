package com.example.kaoyanassistant.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build

object DeviceUtils {
    const val MinEmbeddingRamBytes: Long = 8L * 1024 * 1024 * 1024

    fun getTotalMemoryBytes(context: Context): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.totalMem
    }

    fun hasEnoughMemory(context: Context, minBytes: Long = MinEmbeddingRamBytes): Boolean {
        return getTotalMemoryBytes(context) >= minBytes
    }

    fun isArm64Device(): Boolean {
        return Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
    }
}
