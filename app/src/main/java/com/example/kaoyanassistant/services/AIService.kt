package com.example.kaoyanassistant.services

import com.example.kaoyanassistant.core.AIProvider
import com.example.kaoyanassistant.core.APIConfig
import com.example.kaoyanassistant.core.ConfigManager
import com.example.kaoyanassistant.utils.Logger
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 消息角色
 * 对应Qt版本的MessageRole枚举
 */
enum class MessageRole {
    System,
    User,
    Assistant
}

/**
 * 消息内容类型
 */
sealed class MessageContent {
    data class Text(val text: String) : MessageContent()
    data class Image(val base64Data: String, val mimeType: String = "image/jpeg") : MessageContent()
}

/**
 * 消息数据类
 * 对应Qt版本的Message结构体
 * 支持多模态内容（文本和图片）
 */
data class Message(
    val role: MessageRole,
    val content: String,
    val imageBase64: String? = null,
    val imageMimeType: String? = null
)

/**
 * AI服务响应状态
 */
sealed class AIResponseState {
    object Idle : AIResponseState()
    object Loading : AIResponseState()
    data class Streaming(val chunk: String, val accumulated: String) : AIResponseState()
    data class Success(val response: String) : AIResponseState()
    data class Error(val message: String) : AIResponseState()
}

/**
 * AI服务接口 - 封装与各种AI大模型的通信
 * 支持流式响应和普通响应
 * 对应Qt版本的AIService类
 */
class AIService(private val configManager: ConfigManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var currentEventSource: EventSource? = null
    private var systemPrompt: String = "你是一个专业的考研助手，擅长解答数学、英语、政治和专业课相关问题。请用清晰、准确的方式回答问题，必要时使用LaTeX格式的数学公式。"

    private val _responseState = MutableStateFlow<AIResponseState>(AIResponseState.Idle)
    val responseState: StateFlow<AIResponseState> = _responseState.asStateFlow()

    /**
     * 设置系统提示词
     */
    fun setSystemPrompt(prompt: String) {
        systemPrompt = prompt
    }

    fun getSystemPrompt(): String = systemPrompt

    /**
     * 发送消息（带上下文）- 流式响应
     */
    suspend fun sendMessage(
        message: String,
        context: List<Message> = emptyList(),
        config: APIConfig,
        provider: AIProvider
    ) {
        sendMessage(Message(MessageRole.User, message), context, config, provider)
    }

    suspend fun sendMessage(
        message: Message,
        context: List<Message> = emptyList(),
        config: APIConfig,
        provider: AIProvider
    ) {
        sendMessageInternal(message, context, emptyList(), config, provider)
    }

    /**
     * 发送带资料的消息
     */
    suspend fun sendMessageWithDocuments(
        message: String,
        documentContents: List<String>,
        context: List<Message> = emptyList(),
        config: APIConfig,
        provider: AIProvider
    ) {
        sendMessageWithDocuments(
            Message(MessageRole.User, message),
            documentContents,
            context,
            config,
            provider
        )
    }

    suspend fun sendMessageWithDocuments(
        message: Message,
        documentContents: List<String>,
        context: List<Message> = emptyList(),
        config: APIConfig,
        provider: AIProvider
    ) {
        sendMessageInternal(message, context, documentContents, config, provider)
    }

    private suspend fun sendMessageInternal(
        message: Message,
        context: List<Message>,
        documentContents: List<String>,
        config: APIConfig,
        provider: AIProvider
    ) {
        _responseState.value = AIResponseState.Loading

        try {
            val request = createRequest(message, context, documentContents, config, provider)
            sendStreamRequest(request, provider)
        } catch (e: Exception) {
            Logger.error("AIService", "发送消息失败: ${e.message}")
            _responseState.value = AIResponseState.Error(e.message ?: "未知错误")
        }
    }

    /**
     * 取消当前请求
     */
    fun cancelRequest() {
        currentEventSource?.cancel()
        currentEventSource = null
        _responseState.value = AIResponseState.Idle
    }

    /**
     * 检查服务是否可用
     */
    suspend fun isAvailable(config: APIConfig): Boolean {
        return config.apiKey.isNotBlank() && config.apiUrl.isNotBlank()
    }

    /**
     * 创建HTTP请求
     */
    private fun createRequest(
        message: Message,
        context: List<Message>,
        documents: List<String>,
        config: APIConfig,
        provider: AIProvider
    ): Request {
        val body = createRequestBody(message, context, documents, config, provider)
        val mediaType = "application/json; charset=utf-8".toMediaType()

        val requestBuilder = Request.Builder()
            .url(config.apiUrl)
            .post(body.toRequestBody(mediaType))

        // 根据不同服务商设置请求头
        when (provider) {
            AIProvider.Claude -> {
                requestBuilder.addHeader("x-api-key", config.apiKey)
                requestBuilder.addHeader("anthropic-version", "2023-06-01")
                requestBuilder.addHeader("content-type", "application/json")
            }
            AIProvider.Qwen -> {
                requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey}")
                requestBuilder.addHeader("Content-Type", "application/json")
                requestBuilder.addHeader("X-DashScope-SSE", "enable")
            }
            else -> {
                requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey}")
                requestBuilder.addHeader("Content-Type", "application/json")
            }
        }

        return requestBuilder.build()
    }

    /**
     * 创建请求体
     */
    private fun createRequestBody(
        message: Message,
        context: List<Message>,
        documents: List<String>,
        config: APIConfig,
        provider: AIProvider
    ): String {
        // 构建完整消息，包含文档内容
        val fullMessage = if (documents.isNotEmpty()) {
            val docsContent = documents.joinToString("\n\n---\n\n") { "参考资料：\n$it" }
            "$docsContent\n\n用户问题：${message.content}"
        } else {
            message.content
        }

        val messageForRequest = if (fullMessage == message.content) {
            message
        } else {
            message.copy(content = fullMessage)
        }

        return when (provider) {
            AIProvider.Claude -> createClaudeRequestBody(messageForRequest, context, config)
            AIProvider.Qwen -> createQwenRequestBody(messageForRequest, context, config)
            else -> createOpenAICompatibleRequestBody(messageForRequest, context, config)
        }
    }

    /**
     * 创建OpenAI兼容格式的请求体（OpenAI、DeepSeek、Custom）
     */
    private fun createOpenAICompatibleRequestBody(
        message: Message,
        context: List<Message>,
        config: APIConfig
    ): String {
        val messages = mutableListOf<Map<String, Any>>()

        // 添加系统提示
        messages.add(mapOf("role" to "system", "content" to systemPrompt))

        // 添加上下文
        context.forEach { msg ->
            messages.add(createOpenAIMessage(msg))
        }

        // 添加当前消息
        messages.add(createOpenAIMessage(message))

        val requestBody = mapOf(
            "model" to config.model,
            "messages" to messages,
            "stream" to true,
            "max_tokens" to 4096
        )

        return gson.toJson(requestBody)
    }

    /**
     * 创建OpenAI格式的消息（支持多模态）
     */
    private fun createOpenAIMessage(msg: Message): Map<String, Any> {
        val role = roleToString(msg.role)

        // 如果消息包含图片，使用多模态格式
        if (msg.imageBase64 != null && msg.imageMimeType != null) {
            val contentArray = mutableListOf<Map<String, Any>>()

            // 添加文本内容
            if (msg.content.isNotBlank()) {
                contentArray.add(mapOf(
                    "type" to "text",
                    "text" to msg.content
                ))
            }

            // 添加图片内容
            contentArray.add(mapOf(
                "type" to "image_url",
                "image_url" to mapOf(
                    "url" to "data:${msg.imageMimeType};base64,${msg.imageBase64}"
                )
            ))

            return mapOf("role" to role, "content" to contentArray)
        } else {
            // 纯文本消息
            return mapOf("role" to role, "content" to msg.content)
        }
    }

    /**
     * 创建Claude格式的请求体
     */
    private fun createClaudeRequestBody(
        message: Message,
        context: List<Message>,
        config: APIConfig
    ): String {
        val messages = mutableListOf<Map<String, Any>>()

        // Claude的上下文格式
        context.forEach { msg ->
            if (msg.role != MessageRole.System) {
                messages.add(createClaudeMessage(msg))
            }
        }

        // 添加当前消息
        messages.add(createClaudeMessage(message))

        val requestBody = mapOf(
            "model" to config.model,
            "max_tokens" to 4096,
            "system" to systemPrompt,
            "messages" to messages,
            "stream" to true
        )

        return gson.toJson(requestBody)
    }

    /**
     * 创建Claude格式的消息（支持多模态）
     */
    private fun createClaudeMessage(msg: Message): Map<String, Any> {
        val role = if (msg.role == MessageRole.User) "user" else "assistant"

        // 如果消息包含图片，使用多模态格式
        if (msg.imageBase64 != null && msg.imageMimeType != null) {
            val contentArray = mutableListOf<Map<String, Any>>()

            // 添加图片内容
            contentArray.add(mapOf(
                "type" to "image",
                "source" to mapOf(
                    "type" to "base64",
                    "media_type" to msg.imageMimeType,
                    "data" to msg.imageBase64
                )
            ))

            // 添加文本内容
            if (msg.content.isNotBlank()) {
                contentArray.add(mapOf(
                    "type" to "text",
                    "text" to msg.content
                ))
            }

            return mapOf("role" to role, "content" to contentArray)
        } else {
            // 纯文本消息
            return mapOf("role" to role, "content" to msg.content)
        }
    }

    /**
     * 创建通义千问格式的请求体
     */
    private fun createQwenRequestBody(
        message: Message,
        context: List<Message>,
        config: APIConfig
    ): String {
        val messages = mutableListOf<Map<String, Any>>()

        // 添加系统提示
        messages.add(mapOf("role" to "system", "content" to systemPrompt))

        // 添加上下文
        context.forEach { msg ->
            messages.add(createQwenMessage(msg))
        }

        // 添加当前消息
        messages.add(createQwenMessage(message))

        val requestBody = mapOf(
            "model" to config.model,
            "input" to mapOf("messages" to messages),
            "parameters" to mapOf(
                "result_format" to "message",
                "incremental_output" to true
            )
        )

        return gson.toJson(requestBody)
    }

    /**
     * 创建通义千问格式的消息（支持多模态）
     */
    private fun createQwenMessage(msg: Message): Map<String, Any> {
        val role = roleToString(msg.role)

        // 如果消息包含图片，使用多模态格式
        if (msg.imageBase64 != null && msg.imageMimeType != null) {
            val contentArray = mutableListOf<Map<String, Any>>()

            // 添加文本内容
            if (msg.content.isNotBlank()) {
                contentArray.add(mapOf(
                    "type" to "text",
                    "text" to msg.content
                ))
            }

            // 添加图片内容（通义千问使用image类型）
            contentArray.add(mapOf(
                "type" to "image",
                "image" to "data:${msg.imageMimeType};base64,${msg.imageBase64}"
            ))

            return mapOf("role" to role, "content" to contentArray)
        } else {
            // 纯文本消息
            return mapOf("role" to role, "content" to msg.content)
        }
    }

    /**
     * 发送流式请求
     */
    private suspend fun sendStreamRequest(request: Request, provider: AIProvider) {
        withContext(Dispatchers.IO) {
            var accumulatedResponse = ""

            val listener = object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    Logger.debug("AIService", "SSE连接已建立")
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    if (data == "[DONE]") {
                        _responseState.value = AIResponseState.Success(accumulatedResponse)
                        return
                    }

                    try {
                        val chunk = parseStreamResponse(data, provider)
                        if (chunk.isNotEmpty()) {
                            accumulatedResponse += chunk
                            _responseState.value = AIResponseState.Streaming(chunk, accumulatedResponse)
                        }
                    } catch (e: Exception) {
                        Logger.warning("AIService", "解析响应失败: ${e.message}")
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    Logger.debug("AIService", "SSE连接已关闭")
                    if (_responseState.value is AIResponseState.Streaming || _responseState.value is AIResponseState.Loading) {
                        _responseState.value = AIResponseState.Success(accumulatedResponse)
                    }
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    val errorMsg = t?.message ?: response?.message ?: "连接失败"
                    Logger.error("AIService", "SSE错误: $errorMsg")

                    // 如果已经有部分响应，仍然返回成功
                    if (accumulatedResponse.isNotEmpty()) {
                        _responseState.value = AIResponseState.Success(accumulatedResponse)
                    } else {
                        _responseState.value = AIResponseState.Error(errorMsg)
                    }
                }
            }

            try {
                val factory = EventSources.createFactory(client)
                currentEventSource = factory.newEventSource(request, listener)
            } catch (e: Exception) {
                Logger.error("AIService", "创建EventSource失败: ${e.message}")
                _responseState.value = AIResponseState.Error(e.message ?: "创建连接失败")
            }
        }
    }

    /**
     * 解析流式响应
     */
    private fun parseStreamResponse(data: String, provider: AIProvider): String {
        return try {
            val json = JsonParser.parseString(data).asJsonObject

            when (provider) {
                AIProvider.Claude -> {
                    // Claude的响应格式
                    when (json.get("type")?.asString) {
                        "content_block_delta" -> {
                            json.getAsJsonObject("delta")?.get("text")?.asString ?: ""
                        }
                        else -> ""
                    }
                }
                AIProvider.Qwen -> {
                    // 通义千问的响应格式
                    json.getAsJsonObject("output")
                        ?.getAsJsonArray("choices")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("message")
                        ?.get("content")?.asString ?: ""
                }
                else -> {
                    // OpenAI兼容格式（OpenAI、DeepSeek等）
                    json.getAsJsonArray("choices")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("delta")
                        ?.get("content")?.asString ?: ""
                }
            }
        } catch (e: Exception) {
            Logger.warning("AIService", "解析JSON失败: ${e.message}, data: $data")
            ""
        }
    }

    /**
     * 角色转字符串
     */
    private fun roleToString(role: MessageRole): String {
        return when (role) {
            MessageRole.System -> "system"
            MessageRole.User -> "user"
            MessageRole.Assistant -> "assistant"
        }
    }

    /**
     * 重置状态
     */
    fun resetState() {
        _responseState.value = AIResponseState.Idle
    }
}
