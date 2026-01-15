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
    val selectedDocumentIds: List<String> = emptyList()
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
    private val contextManager = ContextManager()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val conversationHistory = mutableListOf<Message>()
    private var messageIdCounter = 0

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
    fun sendImageMessage(imageUri: Uri, textContent: String = "") {
        viewModelScope.launch {
            try {
                // 显示用户消息到UI
                val userMessage = ChatMessage(
                    id = generateMessageId(),
                    role = MessageRole.User,
                    content = textContent,
                    imageUri = imageUri.toString()
                )
                addMessageToUI(userMessage)

                // 转换图片为Base64
                val imageBase64 = ImageUtils.uriToBase64(context, imageUri)
                if (imageBase64 == null) {
                    _uiState.update { it.copy(error = "图片处理失败，请重试") }
                    return@launch
                }

                // 获取MIME类型
                val mimeType = ImageUtils.getMimeType(context, imageUri)

                // 添加到对话历史（包含图片数据）
                val messageContent = if (textContent.isNotBlank()) textContent else "请帮我分析这张图片"
                conversationHistory.add(
                    Message(
                        role = MessageRole.User,
                        content = messageContent,
                        imageBase64 = imageBase64,
                        imageMimeType = mimeType
                    )
                )

                // 检查上下文是否需要压缩
                if (contextManager.isContextOverLimit(conversationHistory, "")) {
                    val compressed = contextManager.compressContext(conversationHistory)
                    conversationHistory.clear()
                    conversationHistory.addAll(compressed)
                    Logger.info("ChatViewModel", "上下文已压缩")
                }

                // 更新上下文使用率
                updateContextUsage()

                // 获取当前配置
                val config: APIConfig
                val provider: AIProvider
                try {
                    config = configManager.getCurrentAPIConfig()
                    provider = configManager.getCurrentProvider()
                } catch (e: Exception) {
                    Logger.error("ChatViewModel", "获取配置失败", e)
                    _uiState.update { it.copy(error = "获取配置失败，请检查设置") }
                    return@launch
                }

                // 检查配置是否有效
                if (config.apiKey.isBlank()) {
                    _uiState.update { it.copy(error = "请先在设置中配置API Key") }
                    return@launch
                }

                if (config.apiUrl.isBlank()) {
                    _uiState.update { it.copy(error = "API URL未配置") }
                    return@launch
                }

                // 添加AI响应占位消息
                val aiMessage = ChatMessage(
                    id = generateMessageId(),
                    role = MessageRole.Assistant,
                    content = "",
                    isStreaming = true
                )
                addMessageToUI(aiMessage)

                // 发送请求（不包含当前消息）
                aiService.sendMessage(
                    messageContent,
                    conversationHistory.dropLast(1),
                    config,
                    provider
                )

            } catch (e: Exception) {
                Logger.error("ChatViewModel", "发送图片消息失败", e)
                _uiState.update { it.copy(error = "发送失败: ${e.message}") }
            }
        }
    }

    /**
     * 发送消息
     */
    fun sendMessage(content: String) {
        if (content.isBlank()) return

        viewModelScope.launch {
            try {
                // 添加用户消息到UI
                val userMessage = ChatMessage(
                    id = generateMessageId(),
                    role = MessageRole.User,
                    content = content
                )
                addMessageToUI(userMessage)

                // 添加到对话历史
                conversationHistory.add(Message(MessageRole.User, content))

                // 检查上下文是否需要压缩
                if (contextManager.isContextOverLimit(conversationHistory, "")) {
                    val compressed = contextManager.compressContext(conversationHistory)
                    conversationHistory.clear()
                    conversationHistory.addAll(compressed)
                    Logger.info("ChatViewModel", "上下文已压缩")
                }

                // 更新上下文使用率
                updateContextUsage()

                // 获取当前配置
                val config: APIConfig
                val provider: AIProvider
                try {
                    config = configManager.getCurrentAPIConfig()
                    provider = configManager.getCurrentProvider()
                } catch (e: Exception) {
                    Logger.error("ChatViewModel", "获取配置失败", e)
                    _uiState.update { it.copy(error = "获取配置失败，请检查设置") }
                    return@launch
                }

                // 检查配置是否有效
                if (config.apiKey.isBlank()) {
                    _uiState.update { it.copy(error = "请先在设置中配置API Key") }
                    return@launch
                }

                if (config.apiUrl.isBlank()) {
                    _uiState.update { it.copy(error = "API URL未配置") }
                    return@launch
                }

                // 添加AI响应占位消息
                val aiMessage = ChatMessage(
                    id = generateMessageId(),
                    role = MessageRole.Assistant,
                    content = "",
                    isStreaming = true
                )
                addMessageToUI(aiMessage)

                // 获取选中的文档内容
                val documentContents = try {
                    documentManager.getSelectedDocumentsContent(
                        _uiState.value.selectedDocumentIds
                    )
                } catch (e: Exception) {
                    Logger.warning("ChatViewModel", "获取文档内容失败: ${e.message}")
                    emptyList()
                }

                // 发送请求（保留完整的Message对象，包括图片数据）
                if (documentContents.isNotEmpty()) {
                    aiService.sendMessageWithDocuments(
                        content,
                        documentContents,
                        conversationHistory.dropLast(1),
                        config,
                        provider
                    )
                } else {
                    aiService.sendMessage(
                        content,
                        conversationHistory.dropLast(1),
                        config,
                        provider
                    )
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
        _uiState.update { it.copy(messages = emptyList(), contextUsageRatio = 0.0) }
        aiService.resetState()
    }

    /**
     * 取消当前请求
     */
    fun cancelRequest() {
        aiService.cancelRequest()
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
