package com.example.kaoyanassistant.ui.school

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kaoyanassistant.core.ConfigManager
import com.example.kaoyanassistant.core.UserInfo
import com.example.kaoyanassistant.core.UserManager
import com.example.kaoyanassistant.services.Message
import com.example.kaoyanassistant.services.MessageRole
import com.example.kaoyanassistant.services.SchoolQueryState
import com.example.kaoyanassistant.services.SchoolService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 聊天消息
 */
data class SchoolChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
)

/**
 * 择校界面状态
 */
data class SchoolSelectionUiState(
    val messages: List<SchoolChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentUser: UserInfo? = null,
    val inputText: String = "",
    val quickQueries: List<QuickQuery> = defaultQuickQueries
)

/**
 * 快捷查询
 */
data class QuickQuery(
    val title: String,
    val query: String
)

val defaultQuickQueries = listOf(
    QuickQuery("查询考试科目", "请帮我查询某某大学某某专业的考试科目"),
    QuickQuery("择校建议", "根据我的背景，请给我一些择校建议"),
    QuickQuery("复录比分析", "请帮我分析一下目标院校的复录比情况"),
    QuickQuery("院校对比", "请帮我对比几所目标院校的优劣势")
)

/**
 * 择校ViewModel
 */
class SchoolSelectionViewModel(
    private val configManager: ConfigManager,
    private val userManager: UserManager
) : ViewModel() {

    private val schoolService = SchoolService(configManager)

    private val _uiState = MutableStateFlow(SchoolSelectionUiState())
    val uiState: StateFlow<SchoolSelectionUiState> = _uiState.asStateFlow()

    init {
        // 监听用户信息
        viewModelScope.launch {
            userManager.currentUserFlow.collect { user ->
                _uiState.update { it.copy(currentUser = user) }
            }
        }

        // 监听AI响应状态
        viewModelScope.launch {
            schoolService.queryState.collect { state ->
                handleQueryState(state)
            }
        }

        // 添加欢迎消息
        addWelcomeMessage()
    }

    private fun addWelcomeMessage() {
        val welcomeMessage = SchoolChatMessage(
            role = MessageRole.Assistant,
            content = """欢迎使用考研择校助手！

我可以帮助你：
- 查询院校和专业的考试科目（数学一/二/三、专业课代码等）
- 分析院校的招生情况（复录比、分数线、招生人数）
- 提供择校建议和风险提醒
- 对比不同院校的优劣势

你可以直接输入问题，或者点击下方的快捷查询按钮开始。

提示：为了给你更准确的建议，建议先在设置中完善你的个人信息（本科院校、目标专业等）。"""
        )
        _uiState.update { it.copy(messages = listOf(welcomeMessage)) }
    }

    /**
     * 发送消息
     */
    fun sendMessage(content: String) {
        if (content.isBlank()) return

        viewModelScope.launch {
            // 添加用户消息
            val userMessage = SchoolChatMessage(
                role = MessageRole.User,
                content = content
            )
            _uiState.update {
                it.copy(
                    messages = it.messages + userMessage,
                    inputText = "",
                    isLoading = true
                )
            }

            // 构建上下文
            val context = buildContext()

            // 获取当前配置
            val provider = configManager.currentProviderFlow.first()
            val config = configManager.getAPIConfigFlow(provider).first()

            // 增强消息内容（添加用户背景信息）
            val enhancedContent = enhanceMessageWithUserInfo(content)

            // 发送请求
            schoolService.sendSchoolQuery(enhancedContent, context, config, provider)
        }
    }

    /**
     * 查询考试科目
     */
    fun queryExamSubjects(schoolName: String, majorName: String) {
        viewModelScope.launch {
            val userMessage = SchoolChatMessage(
                role = MessageRole.User,
                content = "请查询${schoolName}${majorName}专业的考试科目和招生信息"
            )
            _uiState.update {
                it.copy(
                    messages = it.messages + userMessage,
                    isLoading = true
                )
            }

            val provider = configManager.currentProviderFlow.first()
            val config = configManager.getAPIConfigFlow(provider).first()

            schoolService.queryExamSubjects(schoolName, majorName, config, provider)
        }
    }

    /**
     * 更新输入文本
     */
    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    /**
     * 取消请求
     */
    fun cancelRequest() {
        schoolService.cancelRequest()
        _uiState.update { it.copy(isLoading = false) }
    }

    /**
     * 清除错误
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * 清空对话
     */
    fun clearChat() {
        schoolService.resetState()
        _uiState.update { it.copy(messages = emptyList(), error = null) }
        addWelcomeMessage()
    }

    /**
     * 处理查询状态
     */
    private fun handleQueryState(state: SchoolQueryState) {
        when (state) {
            is SchoolQueryState.Idle -> {
                // 空闲状态
            }
            is SchoolQueryState.Loading -> {
                _uiState.update { it.copy(isLoading = true) }
            }
            is SchoolQueryState.Streaming -> {
                val messages = _uiState.value.messages.toMutableList()
                val lastMessage = messages.lastOrNull()

                if (lastMessage?.role == MessageRole.Assistant && lastMessage.isStreaming) {
                    // 更新现有的流式消息
                    messages[messages.lastIndex] = lastMessage.copy(
                        content = state.accumulated,
                        isStreaming = true
                    )
                } else {
                    // 添加新的流式消息
                    messages.add(SchoolChatMessage(
                        role = MessageRole.Assistant,
                        content = state.accumulated,
                        isStreaming = true
                    ))
                }

                _uiState.update { it.copy(messages = messages, isLoading = true) }
            }
            is SchoolQueryState.Success -> {
                val messages = _uiState.value.messages.toMutableList()
                val lastMessage = messages.lastOrNull()

                if (lastMessage?.role == MessageRole.Assistant && lastMessage.isStreaming) {
                    // 完成流式消息
                    messages[messages.lastIndex] = lastMessage.copy(
                        content = state.response,
                        isStreaming = false
                    )
                } else if (state.response.isNotEmpty()) {
                    // 添加完整响应
                    messages.add(SchoolChatMessage(
                        role = MessageRole.Assistant,
                        content = state.response,
                        isStreaming = false
                    ))
                }

                _uiState.update { it.copy(messages = messages, isLoading = false) }
                schoolService.resetState()
            }
            is SchoolQueryState.Error -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = state.message
                    )
                }
                schoolService.resetState()
            }
        }
    }

    /**
     * 构建上下文
     */
    private fun buildContext(): List<Message> {
        return _uiState.value.messages
            .filter { it.role != MessageRole.System }
            .takeLast(10) // 保留最近10条消息作为上下文
            .map { Message(it.role, it.content) }
    }

    /**
     * 增强消息内容（添加用户背景信息）
     */
    private fun enhanceMessageWithUserInfo(content: String): String {
        val user = _uiState.value.currentUser ?: return content

        val backgroundInfo = buildString {
            if (user.currentSchool.isNotBlank()) {
                append("【我的背景】本科院校：${user.currentSchool}")
                if (user.currentEducation.isNotBlank()) {
                    append("，学历：${user.currentEducation}")
                }
                append("\n")
            }
            if (user.targetSchool.isNotBlank()) {
                append("【目标院校】${user.targetSchool}")
                if (user.targetMajor.isNotBlank()) {
                    append(" - ${user.targetMajor}")
                }
                append("\n")
            }
            if (user.examYear > 0) {
                append("【考研年份】${user.examYear}年\n")
            }
        }

        return if (backgroundInfo.isNotBlank()) {
            "$backgroundInfo\n【问题】$content"
        } else {
            content
        }
    }

    class Factory(
        private val configManager: ConfigManager,
        private val userManager: UserManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SchoolSelectionViewModel(configManager, userManager) as T
        }
    }
}
