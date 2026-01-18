package com.example.kaoyanassistant.services

import com.example.kaoyanassistant.core.EmbeddingConfig
import com.example.kaoyanassistant.utils.Logger
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class EmbeddingService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun embed(text: String, config: EmbeddingConfig): FloatArray? {
        if (config.apiUrl.isBlank() || config.apiKey.isBlank() || config.model.isBlank()) {
            Logger.warning("EmbeddingService", "向量模型配置不完整")
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val payload = """
                    {
                      "model": "${config.model}",
                      "input": ${escapeJsonString(text)}
                    }
                """.trimIndent()

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val request = Request.Builder()
                    .url(config.apiUrl)
                    .post(payload.toRequestBody(mediaType))
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Logger.error("EmbeddingService", "向量请求失败: ${response.code}")
                        return@withContext null
                    }
                    val body = response.body?.string() ?: return@withContext null
                    parseEmbedding(body)
                }
            } catch (e: Exception) {
                Logger.error("EmbeddingService", "向量请求异常: ${e.message}")
                null
            }
        }
    }

    private fun parseEmbedding(body: String): FloatArray? {
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            val data = json.getAsJsonArray("data") ?: return null
            if (data.size() == 0) return null
            val embedding = data[0].asJsonObject.getAsJsonArray("embedding") ?: return null
            val array = FloatArray(embedding.size())
            for (i in 0 until embedding.size()) {
                array[i] = embedding[i].asFloat
            }
            array
        } catch (e: Exception) {
            Logger.error("EmbeddingService", "解析向量失败: ${e.message}")
            null
        }
    }

    private fun escapeJsonString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
