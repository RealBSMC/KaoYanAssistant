package com.example.kaoyanassistant.services

import android.content.Context
import com.example.kaoyanassistant.utils.DeviceUtils
import com.example.kaoyanassistant.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalEmbeddingService(private val context: Context) {
    companion object {
        const val ModelAssetPath = "models/qwen3-embedding-4b-q4_k_m.gguf"
        private const val ModelFileName = "qwen3-embedding-4b-q4_k_m.gguf"
    }

    private val engine = LocalEmbeddingEngine()
    private var cachedModelFile: File? = null

    fun isSupported(): Boolean {
        if (!engine.isAvailable()) return false
        if (!DeviceUtils.isArm64Device()) return false
        if (!DeviceUtils.hasEnoughMemory(context)) return false
        return true
    }

    suspend fun ensureModelReady(onMessage: ((String) -> Unit)? = null): File? {
        cachedModelFile?.let { return it }
        val modelDir = File(context.filesDir, "models")
        val target = File(modelDir, ModelFileName)
        if (target.exists()) {
            cachedModelFile = target
            return target
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                if (!modelDir.exists()) {
                    modelDir.mkdirs()
                }
                onMessage?.invoke("正在复制本地向量模型...")
                context.assets.open(ModelAssetPath).use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 1024 * 1024)
                    }
                }
                onMessage?.invoke("本地向量模型准备完成")
                cachedModelFile = target
                target
            }.onFailure {
                Logger.error("LocalEmbeddingService", "复制本地模型失败: ${it.message}")
            }.getOrNull()
        }
    }

    suspend fun embed(text: String, onMessage: ((String) -> Unit)? = null): FloatArray? {
        if (!isSupported()) return null
        val modelFile = ensureModelReady(onMessage) ?: return null
        return engine.embed(modelFile.absolutePath, text)
    }
}
