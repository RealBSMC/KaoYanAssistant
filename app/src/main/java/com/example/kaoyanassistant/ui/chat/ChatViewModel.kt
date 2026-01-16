package com.example.kaoyanassistant.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kaoyanassistant.core.AIProvider
import com.example.kaoyanassistant.core.APIConfig
import com.example.kaoyanassistant.core.ConfigManager
import com.example.kaoyanassistant.core.ContextManager
import com.example.kaoyanassistant.core.MultimodalMode
import com.example.kaoyanassistant.services.AIResponseState
import com.example.kaoyanassistant.services.AIService
import com.example.kaoyanassistant.services.DocumentManager
import com.example.kaoyanassistant.services.Message
import com.example.kaoyanassistant.services.MessageRole
import com.example.kaoyanassistant.utils.ImageUtils
import com.example.kaoyanassistant.utils.Logger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 聊天消息UI状态
 */
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val imageUri: String? = null,
    val isStreaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 聊天界面UI状态
 */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentProvider: AIProvider = AIProvider.DeepSeek,
    val contextUsageRatio: Double = 0.0,
    val selectedDocumentIds: List<String> = emptyList(),
    val activeModelLabel: String? = null
)

/**
 * 聊天界面ViewModel
 * 对应Qt版本的ChatWidget逻辑
 */
class ChatViewModel(
    private val configManager: ConfigManager,
    private val documentManager: DocumentManager,
    private val context: Context
) : ViewModel() {

    private val aiService = AIService(configManager)
    private var visionService: AIService? = null
    private val contextManager = ContextManager()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val conversationHistory = mutableListOf<Message>()
    private var messageIdCounter = 0
    private enum class ModelRole {
        Single,
        Vision,
        Reasoning
    }

    init {
        // 监听配置变化
        viewModelScope.launch {
            configManager.currentProviderFlow.collect { provider ->
                _uiState.update { it.copy(currentProvider = provider) }
            }
        }

        // 监听AI响应状态
        viewModelScope.launch {
            aiService.responseState.collect { state ->
                handleAIResponse(state)
            }
        }
    }

    /**
     * 发送图片消息
     */
    fun sendMessageWithImage(imageUri: Uri, textContent: String = "") {
        sendMessageInternal(textContent, imageUri)
    }

    /**
     * 发送消息
     */
    fun sendMessage(content: String) {
        sendMessageInternal(content, null)
    }

    private fun sendMessageInternal(content: String, imageUri: Uri?) {
        if (imageUri == null && content.isBlank()) return

        viewModelScope.launch {
            try {
                val messageContent = if (content.isNotBlank()) content else "请帮我分析这张图片"

                // 添加用户消息到UI
                val userMessage = ChatMessage(
                    id = generateMessageId(),
                    role = MessageRole.User,
                    content = messageContent,
                    imageUri = imageUri?.toString()
                )
                addMessageToUI(userMessage)

                val imageBase64: String?
                val mimeType: String?
                if (imageUri != null) {
                    // 转换图片为Base64
                    imageBase64 = ImageUtils.uriToBase64(context, imageUri)
                    if (imageBase64 == null) {
                        _uiState.update { it.copy(error = "图片处理失败，请重试") }
                        return@launch
                    }
                    // 获取MIME类型
                    mimeType = ImageUtils.getMimeType(context, imageUri)
                } else {
                    imageBase64 = null
                    mimeType = null
                }
                val multimodalMode = configManager.getMultimodalMode()
                val useSplit = multimodalMode == MultimodalMode.Split && imageUri != null

                if (useSplit) {
                    _uiState.update { it.copy(isLoading = true, error = null) }
                    handleSplitImageMessage(messageContent, imageBase64!!, mimeType!!)
                } else {
                    handleSingleMessage(messageContent, imageBase64, mimeType, multimodalMode)
                }

            } catch (e: Exception) {
                Logger.error("ChatViewModel", "发送消息失败", e)
                _uiState.update { it.copy(error = "发送失败: ${e.message}", isLoading = false) }
            }
        }
    }

    /**
     * 处理AI响应
     */
    private fun handleAIResponse(state: AIResponseState) {
        when (state) {
            is AIResponseState.Idle -> {
                _uiState.update { it.copy(isLoading = false) }
            }
            is AIResponseState.Loading -> {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }
            is AIResponseState.Streaming -> {
                // 更新最后一条消息的内容
                _uiState.update { currentState ->
                    val messages = currentState.messages.toMutableList()
                    val lastIndex = messages.lastIndex
                    if (lastIndex >= 0 && messages[lastIndex].role == MessageRole.Assistant) {
                        messages[lastIndex] = messages[lastIndex].copy(
                            content = state.accumulated,
                            isStreaming = true
                        )
                    }
                    currentState.copy(messages = messages)
                }
            }
            is AIResponseState.Success -> {
                // 完成响应
                _uiState.update { currentState ->
                    val messages = currentState.messages.toMutableList()
                    val lastIndex = messages.lastIndex
                    if (lastIndex >= 0 && messages[lastIndex].role == MessageRole.Assistant) {
                        messages[lastIndex] = messages[lastIndex].copy(
                            content = state.response,
                            isStreaming = false
                        )
                    }
                    currentState.copy(messages = messages, isLoading = false)
                }

                // 添加到对话历史
                conversationHistory.add(Message(MessageRole.Assistant, state.response))
                updateContextUsage()
            }
            is AIResponseState.Error -> {
                _uiState.update { it.copy(error = state.message, isLoading = false) }
                // 移除空的AI消息
                _uiState.update { currentState ->
                    val messages = currentState.messages.toMutableList()
                    if (messages.isNotEmpty() && messages.last().role == MessageRole.Assistant && messages.last().content.isEmpty()) {
                        messages.removeLast()
                    }
                    currentState.copy(messages = messages)
                }
            }
        }
    }

    /**
     * 添加消息到UI
     */
    private fun addMessageToUI(message: ChatMessage) {
        _uiState.update { currentState ->
            currentState.copy(messages = currentState.messages + message)
        }
    }

    /**
     * 更新上下文使用率
     */
    private fun updateContextUsage() {
        val ratio = contextManager.getUsageRatio(conversationHistory)
        _uiState.update { it.copy(contextUsageRatio = ratio) }
    }

    /**
     * 生成消息ID
     */
    private fun generateMessageId(): String {
        return "msg_${++messageIdCounter}_${System.currentTimeMillis()}"
    }

    /**
     * 清空对话
     */
    fun clearConversation() {
        conversationHistory.clear()
        _uiState.update { it.copy(messages = emptyList(), contextUsageRatio = 0.0, activeModelLabel = null) }
        aiService.resetState()
        visionService?.resetState()
    }

    /**
     * 取消当前请求
     */
    fun cancelRequest() {
        aiService.cancelRequest()
        visionService?.cancelRequest()
    }

    /**
     * 设置选中的文档
     */
    fun setSelectedDocuments(ids: List<String>) {
        _uiState.update { it.copy(selectedDocumentIds = ids) }
    }

    /**
     * 清除错误
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * 显示错误
     */
    fun showError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    /**
     * 设置系统提示词
     */
    fun setSystemPrompt(prompt: String) {
        aiService.setSystemPrompt(prompt)
    }

    /**
     * 更新上下文管理器配置
     */
    fun updateContextConfig(maxTokens: Int, keepRecentCount: Int) {
        contextManager.setMaxTokens(maxTokens)
        contextManager.setKeepRecentCount(keepRecentCount)
    }

    private suspend fun handleSingleMessage(
        messageContent: String,
        imageBase64: String?,
        mimeType: String?,
        multimodalMode: MultimodalMode
    ) {
        val provider = if (multimodalMode == MultimodalMode.Split) {
            configManager.getMultimodalReasoningProvider()
        } else {
            configManager.getCurrentProvider()
        }

        val config = try {
            val role = if (multimodalMode == MultimodalMode.Split) {
                ModelRole.Reasoning
            } else {
                ModelRole.Single
            }
            loadProviderConfig(provider, role)
        } catch (e: Exception) {
            Logger.error("ChatViewModel", "获取配置失败", e)
            _uiState.update { it.copy(error = "获取配置失败，请检查设置", isLoading = false) }
            return
        }

        val roleLabel = if (multimodalMode == MultimodalMode.Split) "推理模型" else "当前模型"
        if (!ensureConfigValid(config, roleLabel)) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }

        val messageForHistory = Message(
            role = MessageRole.User,
            content = messageContent,
            imageBase64 = imageBase64,
            imageMimeType = mimeType
        )
        conversationHistory.add(messageForHistory)

        if (contextManager.isContextOverLimit(conversationHistory, "")) {
            val compressed = contextManager.compressContext(conversationHistory)
            conversationHistory.clear()
            conversationHistory.addAll(compressed)
            Logger.info("ChatViewModel", "上下文已压缩")
        }

        updateContextUsage()
        updateActiveModelLabel(buildModelLabel(roleLabel, provider, config))

        val aiMessage = ChatMessage(
            id = generateMessageId(),
            role = MessageRole.Assistant,
            content = "",
            isStreaming = true
        )
        addMessageToUI(aiMessage)

        val documentContents = try {
            documentManager.getSelectedDocumentsContent(
                _uiState.value.selectedDocumentIds
            )
        } catch (e: Exception) {
            Logger.warning("ChatViewModel", "获取文档内容失败: ${e.message}")
            emptyList()
        }

        val contextForRequest = if (multimodalMode == MultimodalMode.Split) {
            sanitizeContextForTextProvider(conversationHistory.dropLast(1))
        } else {
            conversationHistory.dropLast(1)
        }

        if (documentContents.isNotEmpty()) {
            aiService.sendMessageWithDocuments(
                messageForHistory,
                documentContents,
                contextForRequest,
                config,
                provider
            )
        } else {
            aiService.sendMessage(
                messageForHistory,
                contextForRequest,
                config,
                provider
            )
        }
    }

    private suspend fun handleSplitImageMessage(
        messageContent: String,
        imageBase64: String,
        mimeType: String
    ) {
        val visionProvider = configManager.getMultimodalVisionProvider()
        val reasoningProvider = configManager.getMultimodalReasoningProvider()

        val visionConfig = try {
            loadProviderConfig(visionProvider, ModelRole.Vision)
        } catch (e: Exception) {
            Logger.error("ChatViewModel", "获取图片模型配置失败", e)
            _uiState.update { it.copy(error = "获取图片模型配置失败，请检查设置", isLoading = false) }
            return
        }

        val reasoningConfig = try {
            loadProviderConfig(reasoningProvider, ModelRole.Reasoning)
        } catch (e: Exception) {
            Logger.error("ChatViewModel", "获取推理模型配置失败", e)
            _uiState.update { it.copy(error = "获取推理模型配置失败，请检查设置", isLoading = false) }
            return
        }

        if (!ensureConfigValid(visionConfig, "图片模型") || !ensureConfigValid(reasoningConfig, "推理模型")) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }

        updateActiveModelLabel(buildModelLabel("图片理解", visionProvider, visionConfig))

        val visionPrompt = buildVisionPrompt(messageContent)
        val visionMessage = Message(
            role = MessageRole.User,
            content = visionPrompt,
            imageBase64 = imageBase64,
            imageMimeType = mimeType
        )

        val description = requestVisionDescription(visionMessage, visionConfig, visionProvider)
        if (description.isNullOrBlank()) {
            if (_uiState.value.error == null) {
                _uiState.update { it.copy(error = "图片解析失败，请重试", isLoading = false) }
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
            return
        }

        val finalContent = buildReasoningPrompt(messageContent, description)
        val messageForHistory = Message(
            role = MessageRole.User,
            content = finalContent
        )
        conversationHistory.add(messageForHistory)

        if (contextManager.isContextOverLimit(conversationHistory, "")) {
            val compressed = contextManager.compressContext(conversationHistory)
            conversationHistory.clear()
            conversationHistory.addAll(compressed)
            Logger.info("ChatViewModel", "上下文已压缩")
        }

        updateContextUsage()
        updateActiveModelLabel(buildModelLabel("推理模型", reasoningProvider, reasoningConfig))

        val aiMessage = ChatMessage(
            id = generateMessageId(),
            role = MessageRole.Assistant,
            content = "",
            isStreaming = true
        )
        addMessageToUI(aiMessage)

        val documentContents = try {
            documentManager.getSelectedDocumentsContent(
                _uiState.value.selectedDocumentIds
            )
        } catch (e: Exception) {
            Logger.warning("ChatViewModel", "获取文档内容失败: ${e.message}")
            emptyList()
        }

        val contextForRequest = sanitizeContextForTextProvider(conversationHistory.dropLast(1))

        if (documentContents.isNotEmpty()) {
            aiService.sendMessageWithDocuments(
                messageForHistory,
                documentContents,
                contextForRequest,
                reasoningConfig,
                reasoningProvider
            )
        } else {
            aiService.sendMessage(
                messageForHistory,
                contextForRequest,
                reasoningConfig,
                reasoningProvider
            )
        }
    }

    private suspend fun requestVisionDescription(
        message: Message,
        config: APIConfig,
        provider: AIProvider
    ): String? {
        val service = AIService(configManager)
        visionService = service
        service.resetState()
        service.sendMessage(message, emptyList(), config, provider)

        val result = service.responseState
            .dropWhile { it is AIResponseState.Idle }
            .first { it is AIResponseState.Success || it is AIResponseState.Error || it is AIResponseState.Idle }

        return when (result) {
            is AIResponseState.Success -> result.response.trim()
            is AIResponseState.Error -> {
                Logger.error("ChatViewModel", "图片模型调用失败: ${result.message}")
                _uiState.update { it.copy(error = result.message, isLoading = false) }
                null
            }
            is AIResponseState.Idle -> {
                _uiState.update { it.copy(isLoading = false) }
                null
            }
            else -> null
        }.also { visionService = null }
    }

    private fun buildVisionPrompt(userText: String): String {
        return if (userText.isBlank()) {
            "请用简洁文字描述图片内容，包含关键细节。"
        } else {
            "请先描述图片内容，突出与问题相关的要点。\n用户问题：$userText"
        }
    }

    private fun buildReasoningPrompt(userText: String, description: String): String {
        return if (userText.isBlank()) {
            "图片描述：$description"
        } else {
            "用户问题：$userText\n\n图片描述：$description"
        }
    }

    private fun sanitizeContextForTextProvider(messages: List<Message>): List<Message> {
        return messages.map { message ->
            if (message.imageBase64 != null || message.imageMimeType != null) {
                val safeContent = if (message.content.isNotBlank()) {
                    message.content
                } else {
                    "用户上传了一张图片"
                }
                message.copy(content = safeContent, imageBase64 = null, imageMimeType = null)
            } else {
                message
            }
        }
    }

    private suspend fun loadProviderConfig(provider: AIProvider, role: ModelRole): APIConfig {
        if (provider != AIProvider.Custom) {
            return configManager.getAPIConfigFlow(provider).first()
        }

        return when (role) {
            ModelRole.Vision -> configManager.getMultimodalVisionCustomConfig()
            ModelRole.Reasoning -> configManager.getMultimodalReasoningCustomConfig()
            ModelRole.Single -> configManager.getAPIConfigFlow(provider).first()
        }
    }

    private fun ensureConfigValid(config: APIConfig, roleLabel: String): Boolean {
        return when {
            config.apiKey.isBlank() -> {
                _uiState.update { it.copy(error = "$roleLabel API Key未配置") }
                false
            }
            config.apiUrl.isBlank() -> {
                _uiState.update { it.copy(error = "$roleLabel API URL未配置") }
                false
            }
            else -> true
        }
    }

    private fun updateActiveModelLabel(label: String) {
        _uiState.update { it.copy(activeModelLabel = label) }
    }

    private fun buildModelLabel(roleLabel: String, provider: AIProvider, config: APIConfig): String {
        val providerName = getProviderDisplayName(provider)
        val modelName = if (config.model.isNotBlank()) config.model else "未设置模型"
        return "$roleLabel: $providerName / $modelName"
    }

    private fun getProviderDisplayName(provider: AIProvider): String {
        return when (provider) {
            AIProvider.OpenAI -> "OpenAI"
            AIProvider.Claude -> "Claude"
            AIProvider.DeepSeek -> "DeepSeek"
            AIProvider.Qwen -> "通义千问"
            AIProvider.Doubao -> "豆包"
            AIProvider.Custom -> "自定义"
        }
    }

    class Factory(
        private val configManager: ConfigManager,
        private val documentManager: DocumentManager,
        private val context: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(configManager, documentManager, context) as T
        }
    }
}
