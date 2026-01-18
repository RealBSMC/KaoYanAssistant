package com.example.kaoyanassistant.services

import com.example.kaoyanassistant.utils.Logger

class LocalEmbeddingEngine {
    companion object {
        private val nativeAvailable: Boolean = runCatching {
            System.loadLibrary("qwen3_embedding")
        }.onFailure {
            Logger.warning("LocalEmbeddingEngine", "未加载本地向量库: ${it.message}")
        }.isSuccess
    }

    private var modelHandle: Long = 0
    private var modelPath: String? = null

    fun isAvailable(): Boolean = nativeAvailable

    fun embed(modelPath: String, text: String): FloatArray? {
        if (!nativeAvailable) return null
        if (!ensureLoaded(modelPath)) return null
        return runCatching {
            nativeEmbed(modelHandle, text)
        }.onFailure {
            Logger.error("LocalEmbeddingEngine", "本地向量推理失败: ${it.message}")
        }.getOrNull()
    }

    private fun ensureLoaded(path: String): Boolean {
        if (modelHandle != 0L && modelPath == path) return true
        if (modelHandle != 0L) {
            runCatching { nativeRelease(modelHandle) }
            modelHandle = 0
        }
        modelPath = path
        modelHandle = runCatching { nativeInit(path) }.getOrElse {
            Logger.error("LocalEmbeddingEngine", "本地模型加载失败: ${it.message}")
            0L
        }
        return modelHandle != 0L
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeEmbed(handle: Long, text: String): FloatArray?
    private external fun nativeRelease(handle: Long)
}
