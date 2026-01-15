package com.example.kaoyanassistant.services

import com.example.kaoyanassistant.core.AIProvider
import com.example.kaoyanassistant.core.APIConfig
import com.example.kaoyanassistant.core.ConfigManager
import com.example.kaoyanassistant.utils.Logger
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * 院校信息
 */
@Serializable
data class SchoolInfo(
    val name: String,                    // 学校名称
    val location: String = "",           // 所在地
    val is985: Boolean = false,          // 是否985
    val is211: Boolean = false,          // 是否211
    val isDoubleFirstClass: Boolean = false, // 是否双一流
    val level: String = "",              // 学校层次
    val website: String = ""             // 官网
)

/**
 * 专业信息
 */
@Serializable
data class MajorInfo(
    val code: String,                    // 专业代码
    val name: String,                    // 专业名称
    val category: String = "",           // 学科门类
    val examSubjects: List<ExamSubject> = emptyList(), // 考试科目
    val admissionRatio: String = "",     // 复录比
    val enrollmentCount: Int = 0,        // 招生人数
    val remarks: String = ""             // 备注
)

/**
 * 考试科目
 */
@Serializable
data class ExamSubject(
    val code: String,                    // 科目代码
    val name: String,                    // 科目名称
    val type: String = ""                // 类型（公共课/专业课）
)

/**
 * 择校建议
 */
@Serializable
data class SchoolAdvice(
    val schoolName: String,
    val majorName: String,
    val advantages: List<String> = emptyList(),    // 优势
    val disadvantages: List<String> = emptyList(), // 劣势
    val warnings: List<String> = emptyList(),      // 警告提醒
    val difficulty: String = "",                    // 难度评估
    val recommendation: String = ""                 // 推荐建议
)

/**
 * 院校查询响应状态
 */
sealed class SchoolQueryState {
    object Idle : SchoolQueryState()
    object Loading : SchoolQueryState()
    data class Streaming(val chunk: String, val accumulated: String) : SchoolQueryState()
    data class Success(val response: String) : SchoolQueryState()
    data class Error(val message: String) : SchoolQueryState()
}

/**
 * 院校服务 - 提供院校信息查询和择校建议
 */
class SchoolService(private val configManager: ConfigManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var currentEventSource: EventSource? = null

    private val _queryState = MutableStateFlow<SchoolQueryState>(SchoolQueryState.Idle)
    val queryState: StateFlow<SchoolQueryState> = _queryState.asStateFlow()

    companion object {
        // 择校咨询系统提示词
        private const val SCHOOL_SELECTION_PROMPT = """你是一个专业的考研择校顾问，具有丰富的考研指导经验。你需要帮助学生分析和选择适合的目标院校和专业。

你的职责包括：
1. 根据学生的背景（本科院校、专业、成绩等）提供个性化的择校建议
2. 查询并提供院校和专业的详细信息，包括：
   - 考试科目（数学一/二/三、英语一/二、政治、专业课代码和名称）
   - 招生人数、复录比、分数线
   - 学校层次（985/211/双一流）
3. 提供择校风险提醒：
   - 某些学校可能存在"出身歧视"，对本科院校有隐性要求
   - 复录比过高（超过1:1.5）的专业需要谨慎
   - 专业课难度和压分情况
   - 调剂难度
4. 综合评估学生的竞争力，给出合理的院校梯度建议（冲刺、稳妥、保底）

请用专业、客观的态度回答问题，给出具体的数据和建议。如果不确定某些信息，请明确说明并建议学生查阅官方渠道。

重要提醒：
- 回答要简洁明了，重点突出
- 涉及具体数据时，说明数据来源和时效性
- 对于敏感话题（如出身歧视），要客观陈述，不做过度渲染"""

        // 考试科目查询提示词
        private const val EXAM_SUBJECTS_PROMPT = """请查询并提供以下院校专业的考试科目信息：

学校：{school}
专业：{major}

请按以下格式回复：
1. 初试科目：
   - 政治：（101思想政治理论）
   - 外语：（201英语一/204英语二）
   - 业务课一：（301数学一/302数学二/303数学三/其他）
   - 业务课二：（专业课代码+名称）

2. 参考书目（如有）

3. 历年分数线（近3年）

4. 招生人数和复录比

如果某些信息不确定，请明确说明。"""
    }

    /**
     * 发送择校咨询消息
     */
    suspend fun sendSchoolQuery(
        message: String,
        context: List<Message> = emptyList(),
        config: APIConfig,
        provider: AIProvider
    ) {
        _queryState.value = SchoolQueryState.Loading

        try {
            val request = createRequest(message, context, config, provider, SCHOOL_SELECTION_PROMPT)
            sendStreamRequest(request, provider)
        } catch (e: Exception) {
            Logger.error("SchoolService", "查询失败: ${e.message}")
            _queryState.value = SchoolQueryState.Error(e.message ?: "未知错误")
        }
    }

    /**
     * 查询考试科目
     */
    suspend fun queryExamSubjects(
        schoolName: String,
        majorName: String,
        config: APIConfig,
        provider: AIProvider
    ) {
        _queryState.value = SchoolQueryState.Loading

        val prompt = EXAM_SUBJECTS_PROMPT
            .replace("{school}", schoolName)
            .replace("{major}", majorName)

        try {
            val request = createRequest(
                "请查询${schoolName}${majorName}专业的考试科目和招生信息",
                emptyList(),
                config,
                provider,
                prompt
            )
            sendStreamRequest(request, provider)
        } catch (e: Exception) {
            Logger.error("SchoolService", "查询考试科目失败: ${e.message}")
            _queryState.value = SchoolQueryState.Error(e.message ?: "未知错误")
        }
    }

    /**
     * 获取择校建议
     */
    suspend fun getSchoolAdvice(
        userBackground: String,
        targetSchools: List<String>,
        config: APIConfig,
        provider: AIProvider
    ) {
        _queryState.value = SchoolQueryState.Loading

        val message = """
请根据以下信息，为我提供择校建议：

【我的背景】
$userBackground

【目标院校】
${targetSchools.joinToString("、")}

请分析：
1. 各目标院校的优劣势
2. 我的竞争力评估
3. 需要注意的风险点
4. 推荐的院校梯度（冲刺/稳妥/保底）
        """.trimIndent()

        try {
            val request = createRequest(message, emptyList(), config, provider, SCHOOL_SELECTION_PROMPT)
            sendStreamRequest(request, provider)
        } catch (e: Exception) {
            Logger.error("SchoolService", "获取择校建议失败: ${e.message}")
            _queryState.value = SchoolQueryState.Error(e.message ?: "未知错误")
        }
    }

    /**
     * 取消当前请求
     */
    fun cancelRequest() {
        currentEventSource?.cancel()
        currentEventSource = null
        _queryState.value = SchoolQueryState.Idle
    }

    /**
     * 重置状态
     */
    fun resetState() {
        _queryState.value = SchoolQueryState.Idle
    }

    /**
     * 创建HTTP请求
     */
    private fun createRequest(
        message: String,
        context: List<Message>,
        config: APIConfig,
        provider: AIProvider,
        systemPrompt: String
    ): Request {
        val body = createRequestBody(message, context, config, provider, systemPrompt)
        val mediaType = "application/json; charset=utf-8".toMediaType()

        val requestBuilder = Request.Builder()
            .url(config.apiUrl)
            .post(body.toRequestBody(mediaType))

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
        message: String,
        context: List<Message>,
        config: APIConfig,
        provider: AIProvider,
        systemPrompt: String
    ): String {
        return when (provider) {
            AIProvider.Claude -> createClaudeRequestBody(message, context, config, systemPrompt)
            AIProvider.Qwen -> createQwenRequestBody(message, context, config, systemPrompt)
            else -> createOpenAICompatibleRequestBody(message, context, config, systemPrompt)
        }
    }

    private fun createOpenAICompatibleRequestBody(
        message: String,
        context: List<Message>,
        config: APIConfig,
        systemPrompt: String
    ): String {
        val messages = mutableListOf<Map<String, String>>()
        messages.add(mapOf("role" to "system", "content" to systemPrompt))

        context.forEach { msg ->
            messages.add(mapOf(
                "role" to when (msg.role) {
                    MessageRole.System -> "system"
                    MessageRole.User -> "user"
                    MessageRole.Assistant -> "assistant"
                },
                "content" to msg.content
            ))
        }

        messages.add(mapOf("role" to "user", "content" to message))

        val requestBody = mapOf(
            "model" to config.model,
            "messages" to messages,
            "stream" to true,
            "max_tokens" to 4096
        )

        return gson.toJson(requestBody)
    }

    private fun createClaudeRequestBody(
        message: String,
        context: List<Message>,
        config: APIConfig,
        systemPrompt: String
    ): String {
        val messages = mutableListOf<Map<String, String>>()

        context.forEach { msg ->
            if (msg.role != MessageRole.System) {
                messages.add(mapOf(
                    "role" to if (msg.role == MessageRole.User) "user" else "assistant",
                    "content" to msg.content
                ))
            }
        }

        messages.add(mapOf("role" to "user", "content" to message))

        val requestBody = mapOf(
            "model" to config.model,
            "max_tokens" to 4096,
            "system" to systemPrompt,
            "messages" to messages,
            "stream" to true
        )

        return gson.toJson(requestBody)
    }

    private fun createQwenRequestBody(
        message: String,
        context: List<Message>,
        config: APIConfig,
        systemPrompt: String
    ): String {
        val messages = mutableListOf<Map<String, String>>()
        messages.add(mapOf("role" to "system", "content" to systemPrompt))

        context.forEach { msg ->
            messages.add(mapOf(
                "role" to when (msg.role) {
                    MessageRole.System -> "system"
                    MessageRole.User -> "user"
                    MessageRole.Assistant -> "assistant"
                },
                "content" to msg.content
            ))
        }

        messages.add(mapOf("role" to "user", "content" to message))

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
     * 发送流式请求
     */
    private suspend fun sendStreamRequest(request: Request, provider: AIProvider) {
        withContext(Dispatchers.IO) {
            var accumulatedResponse = ""

            val listener = object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    Logger.debug("SchoolService", "SSE连接已建立")
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    if (data == "[DONE]") {
                        _queryState.value = SchoolQueryState.Success(accumulatedResponse)
                        return
                    }

                    try {
                        val chunk = parseStreamResponse(data, provider)
                        if (chunk.isNotEmpty()) {
                            accumulatedResponse += chunk
                            _queryState.value = SchoolQueryState.Streaming(chunk, accumulatedResponse)
                        }
                    } catch (e: Exception) {
                        Logger.warning("SchoolService", "解析响应失败: ${e.message}")
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    Logger.debug("SchoolService", "SSE连接已关闭")
                    if (_queryState.value is SchoolQueryState.Streaming || _queryState.value is SchoolQueryState.Loading) {
                        _queryState.value = SchoolQueryState.Success(accumulatedResponse)
                    }
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    val errorMsg = t?.message ?: response?.message ?: "连接失败"
                    Logger.error("SchoolService", "SSE错误: $errorMsg")

                    if (accumulatedResponse.isNotEmpty()) {
                        _queryState.value = SchoolQueryState.Success(accumulatedResponse)
                    } else {
                        _queryState.value = SchoolQueryState.Error(errorMsg)
                    }
                }
            }

            try {
                val factory = EventSources.createFactory(client)
                currentEventSource = factory.newEventSource(request, listener)
            } catch (e: Exception) {
                Logger.error("SchoolService", "创建EventSource失败: ${e.message}")
                _queryState.value = SchoolQueryState.Error(e.message ?: "创建连接失败")
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
                    when (json.get("type")?.asString) {
                        "content_block_delta" -> {
                            json.getAsJsonObject("delta")?.get("text")?.asString ?: ""
                        }
                        else -> ""
                    }
                }
                AIProvider.Qwen -> {
                    json.getAsJsonObject("output")
                        ?.getAsJsonArray("choices")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("message")
                        ?.get("content")?.asString ?: ""
                }
                else -> {
                    json.getAsJsonArray("choices")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("delta")
                        ?.get("content")?.asString ?: ""
                }
            }
        } catch (e: Exception) {
            Logger.warning("SchoolService", "解析JSON失败: ${e.message}")
            ""
        }
    }
}
