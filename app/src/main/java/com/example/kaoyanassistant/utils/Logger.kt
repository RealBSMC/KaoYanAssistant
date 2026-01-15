package com.example.kaoyanassistant.utils

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 日志级别
 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR
}

/**
 * 日志管理器 - 记录调试信息
 * 对应Qt版本的Logger类
 */
object Logger {

    private const val TAG = "KaoyanAssistant"
    private var logFile: File? = null
    private var minLevel: LogLevel = LogLevel.DEBUG
    private var enableFileLogging = false

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /**
     * 初始化日志系统
     */
    fun init(logDir: File, enableFile: Boolean = false) {
        enableFileLogging = enableFile
        if (enableFile) {
            logDir.mkdirs()
            val fileName = "kaoyan_${SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())}.log"
            logFile = File(logDir, fileName)
        }
    }

    /**
     * 设置最小日志级别
     */
    fun setMinLevel(level: LogLevel) {
        minLevel = level
    }

    /**
     * Debug日志
     */
    fun debug(tag: String, message: String) {
        log(LogLevel.DEBUG, tag, message)
    }

    /**
     * Info日志
     */
    fun info(tag: String, message: String) {
        log(LogLevel.INFO, tag, message)
    }

    /**
     * Warning日志
     */
    fun warning(tag: String, message: String) {
        log(LogLevel.WARNING, tag, message)
    }

    /**
     * Error日志
     */
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.ERROR, tag, message)
        throwable?.let {
            log(LogLevel.ERROR, tag, it.stackTraceToString())
        }
    }

    /**
     * 核心日志方法
     */
    private fun log(level: LogLevel, tag: String, message: String) {
        if (level.ordinal < minLevel.ordinal) return

        val fullTag = "$TAG:$tag"

        // Android Logcat输出
        when (level) {
            LogLevel.DEBUG -> Log.d(fullTag, message)
            LogLevel.INFO -> Log.i(fullTag, message)
            LogLevel.WARNING -> Log.w(fullTag, message)
            LogLevel.ERROR -> Log.e(fullTag, message)
        }

        // 文件输出
        if (enableFileLogging) {
            writeToFile(level, tag, message)
        }
    }

    /**
     * 写入日志文件
     */
    private fun writeToFile(level: LogLevel, tag: String, message: String) {
        try {
            logFile?.let { file ->
                val timestamp = dateFormat.format(Date())
                val levelStr = level.name.padEnd(7)
                val logLine = "[$timestamp] [$levelStr] [$tag] $message\n"
                file.appendText(logLine)
            }
        } catch (e: Exception) {
            Log.e(TAG, "写入日志文件失败: ${e.message}")
        }
    }

    /**
     * 获取日志文件路径
     */
    fun getLogFilePath(): String? = logFile?.absolutePath

    /**
     * 清理旧日志文件（保留最近7天）
     */
    fun cleanOldLogs(logDir: File, keepDays: Int = 7) {
        try {
            val cutoffTime = System.currentTimeMillis() - keepDays * 24 * 60 * 60 * 1000L
            logDir.listFiles()?.filter {
                it.name.startsWith("kaoyan_") && it.name.endsWith(".log")
            }?.forEach { file ->
                if (file.lastModified() < cutoffTime) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理日志文件失败: ${e.message}")
        }
    }
}
