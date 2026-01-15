package com.example.kaoyanassistant.ui.studyplan

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kaoyanassistant.core.*
import com.example.kaoyanassistant.services.AIService
import com.example.kaoyanassistant.services.AIResponseState
import com.example.kaoyanassistant.services.Message
import com.example.kaoyanassistant.services.MessageRole
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 学习计划界面状态
 */
data class PlanChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
)

data class StudyPlanUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val dailyPlan: DailyPlan? = null,
    val weekTasks: Map<LocalDate, List<StudyTask>> = emptyMap(),
    val config: StudyPlanConfig = StudyPlanConfig(),
    val daysUntilExam: Int? = null,
    val isLoading: Boolean = false,
    val isGenerating: Boolean = false,
    val generatingProgress: String = "",
    val error: String? = null,
    val currentUser: UserInfo? = null,
    val showAddTaskDialog: Boolean = false,
    val showConfigDialog: Boolean = false,
    val editingTask: StudyTask? = null,
    val showClearPlanDialog: Boolean = false,
    val showPlanChatDialog: Boolean = false,
    val planChatMessages: List<PlanChatMessage> = emptyList(),
    val planChatInput: String = ""
)

/**
 * 学习计划ViewModel
 */
class StudyPlanViewModel(
    private val studyPlanManager: StudyPlanManager,
    private val userManager: UserManager,
    private val configManager: ConfigManager
) : ViewModel() {

    private val aiService = AIService(configManager)
    private val planChatContext = mutableListOf<Message>()
    private var planChatDate: LocalDate? = null
    private var planBaseContext: Message? = null
    private var pendingApplyPlanContent: String? = null
    private var pendingApplyPlanDate: LocalDate? = null

    private val _uiState = MutableStateFlow(StudyPlanUiState())
    val uiState: StateFlow<StudyPlanUiState> = _uiState.asStateFlow()

    init {
        // 监听用户信息
        viewModelScope.launch {
            userManager.currentUserFlow.collect { user ->
                _uiState.update { it.copy(currentUser = user) }
            }
        }

        // 监听配置
        viewModelScope.launch {
            studyPlanManager.configFlow.collect { config ->
                _uiState.update { it.copy(config = config) }
            }
        }

        // 监听AI响应
        viewModelScope.launch {
            aiService.responseState.collect { state ->
                handleAIResponse(state)
            }
        }

        // 加载初始数据
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            // 加载距离考试天数
            val daysUntilExam = studyPlanManager.getDaysUntilExam()
            _uiState.update { it.copy(daysUntilExam = daysUntilExam) }

            // 加载当天计划
            loadDailyPlan(_uiState.value.selectedDate)

            // 加载本周任务
            loadWeekTasks()
        }
    }

    /**
     * 选择日期
     */
    fun selectDate(date: LocalDate) {
        _uiState.update { it.copy(selectedDate = date) }
        loadDailyPlan(date)
    }

    /**
     * 加载每日计划
     */
    private fun loadDailyPlan(date: LocalDate) {
        viewModelScope.launch {
            studyPlanManager.getDailyPlan(date).collect { plan ->
                _uiState.update { it.copy(dailyPlan = plan) }
            }
        }
    }

    /**
     * 加载本周任务
     */
    private fun loadWeekTasks() {
        viewModelScope.launch {
            val today = LocalDate.now()
            val startOfWeek = today.minusDays(today.dayOfWeek.value.toLong() - 1)

            studyPlanManager.tasksFlow.collect { allTasks ->
                val weekTasks = mutableMapOf<LocalDate, List<StudyTask>>()
                for (i in 0..6) {
                    val date = startOfWeek.plusDays(i.toLong())
                    val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                    weekTasks[date] = allTasks.filter { it.date == dateStr }
                }
                _uiState.update { it.copy(weekTasks = weekTasks) }
            }
        }
    }

    /**
     * 添加任务
     */
    fun addTask(task: StudyTask) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            studyPlanManager.addTask(task).fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false, showAddTaskDialog = false) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
            )
        }
    }

    /**
     * 更新任务
     */
    fun updateTask(task: StudyTask) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            studyPlanManager.updateTask(task).fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false, editingTask = null) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
            )
        }
    }

    /**
     * 完成任务
     */
    fun completeTask(taskId: String) {
        viewModelScope.launch {
            studyPlanManager.completeTask(taskId)
        }
    }

    /**
     * 删除任务
     */
    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            studyPlanManager.deleteTask(taskId)
        }
    }

    /**
     * 保存配置
     */
    fun saveConfig(config: StudyPlanConfig) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            studyPlanManager.saveConfig(config).fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false, showConfigDialog = false) }
                    // 重新计算考试倒计时
                    val daysUntilExam = studyPlanManager.getDaysUntilExam()
                    _uiState.update { it.copy(daysUntilExam = daysUntilExam) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
            )
        }
    }

    /**
     * 显示学习计划沟通面板
     */
    fun showPlanChat(date: LocalDate) {
        val shouldReset = planChatDate != null && planChatDate != date
        if (shouldReset) {
            clearPlanChat()
        }
        planChatDate = date
        viewModelScope.launch {
            val user = _uiState.value.currentUser
            val config = _uiState.value.config
            val daysUntilExam = studyPlanManager.getDaysUntilExam()
            _uiState.update { it.copy(daysUntilExam = daysUntilExam) }

            planBaseContext = buildPlanBaseContextMessage(user, config, daysUntilExam, date)

            if (_uiState.value.planChatMessages.isEmpty()) {
                _uiState.update { it.copy(planChatMessages = listOf(buildPlanChatWelcomeMessage())) }
            }

            _uiState.update { it.copy(showPlanChatDialog = true) }
        }
    }

    fun hidePlanChat() {
        _uiState.update { it.copy(showPlanChatDialog = false) }
    }

    fun clearPlanChat() {
        aiService.cancelRequest()
        planChatContext.clear()
        _uiState.update {
            it.copy(
                planChatMessages = listOf(buildPlanChatWelcomeMessage()),
                planChatInput = "",
                isGenerating = false,
                generatingProgress = ""
            )
        }
    }

    fun updatePlanChatInput(text: String) {
        _uiState.update { it.copy(planChatInput = text) }
    }

    fun sendPlanChatMessage(content: String, displayContent: String = content) {
        if (content.isBlank() || _uiState.value.isGenerating) return

        val userMessage = PlanChatMessage(
            role = MessageRole.User,
            content = displayContent
        )
        _uiState.update {
            it.copy(
                planChatMessages = it.planChatMessages + userMessage,
                planChatInput = "",
                isGenerating = true,
                generatingProgress = "AI正在回复..."
            )
        }

        viewModelScope.launch {
            val provider = configManager.currentProviderFlow.first()
            val apiConfig = configManager.getAPIConfigFlow(provider).first()
            val context = buildPlanChatContext()

            aiService.setSystemPrompt(STUDY_PLAN_SYSTEM_PROMPT)
            aiService.sendMessage(content, context, apiConfig, provider)
            planChatContext.add(Message(MessageRole.User, content))
        }
    }

    fun requestPlanGeneration() {
        val date = planChatDate ?: _uiState.value.selectedDate
        val command = buildPlanGenerationCommand(date)
        sendPlanChatMessage(command, displayContent = "请根据目前沟通生成学习计划")
    }

    fun requestApplyPlan() {
        if (_uiState.value.isGenerating) return
        val planContent = latestPlanContent()
        if (planContent.isNullOrBlank()) {
            _uiState.update { it.copy(error = "还没有可应用的计划，请先让AI输出计划") }
            return
        }

        val date = planChatDate ?: _uiState.value.selectedDate
        val hasTasks = _uiState.value.dailyPlan?.tasks?.isNotEmpty() == true

        pendingApplyPlanContent = planContent
        pendingApplyPlanDate = date

        if (hasTasks) {
            _uiState.update { it.copy(showClearPlanDialog = true) }
        } else {
            applyPlanContent(planContent, date, clearExisting = false)
        }
    }

    fun confirmApplyPlan(clearExisting: Boolean) {
        val planContent = pendingApplyPlanContent
        val date = pendingApplyPlanDate ?: _uiState.value.selectedDate
        pendingApplyPlanContent = null
        pendingApplyPlanDate = null
        _uiState.update { it.copy(showClearPlanDialog = false) }

        if (!planContent.isNullOrBlank()) {
            applyPlanContent(planContent, date, clearExisting)
        }
    }

    fun cancelApplyPlan() {
        pendingApplyPlanContent = null
        pendingApplyPlanDate = null
        _uiState.update { it.copy(showClearPlanDialog = false) }
    }

    fun cancelPlanChatRequest() {
        aiService.cancelRequest()
        _uiState.update { it.copy(isGenerating = false, generatingProgress = "") }
    }

    private fun buildPlanChatWelcomeMessage(): PlanChatMessage {
        return PlanChatMessage(
            role = MessageRole.Assistant,
            content = "你好！我们可以先聊聊你的时间安排、薄弱科目和近期目标，我会根据你的情况生成更合适的学习计划。"
        )
    }

    /**
     * 处理AI响应
     */
    private fun handleAIResponse(state: AIResponseState) {
        when (state) {
            is AIResponseState.Loading -> {
                _uiState.update { it.copy(isGenerating = true, generatingProgress = "AI正在回复...") }
            }
            is AIResponseState.Streaming -> {
                val messages = _uiState.value.planChatMessages.toMutableList()
                val lastMessage = messages.lastOrNull()
                if (lastMessage?.role == MessageRole.Assistant && lastMessage.isStreaming) {
                    messages[messages.lastIndex] = lastMessage.copy(
                        content = state.accumulated,
                        isStreaming = true
                    )
                } else {
                    messages.add(PlanChatMessage(
                        role = MessageRole.Assistant,
                        content = state.accumulated,
                        isStreaming = true
                    ))
                }
                _uiState.update {
                    it.copy(
                        planChatMessages = messages,
                        isGenerating = true,
                        generatingProgress = "AI正在回复..."
                    )
                }
            }
            is AIResponseState.Success -> {
                val messages = _uiState.value.planChatMessages.toMutableList()
                val lastMessage = messages.lastOrNull()
                if (lastMessage?.role == MessageRole.Assistant && lastMessage.isStreaming) {
                    messages[messages.lastIndex] = lastMessage.copy(
                        content = state.response,
                        isStreaming = false
                    )
                } else if (state.response.isNotBlank()) {
                    messages.add(PlanChatMessage(
                        role = MessageRole.Assistant,
                        content = state.response,
                        isStreaming = false
                    ))
                }
                _uiState.update { it.copy(planChatMessages = messages, isGenerating = false, generatingProgress = "") }
                if (state.response.isNotBlank()) {
                    planChatContext.add(Message(MessageRole.Assistant, state.response))
                }
                aiService.resetState()
            }
            is AIResponseState.Error -> {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        generatingProgress = "",
                        error = "回复失败: ${state.message}"
                    )
                }
                aiService.resetState()
            }
            else -> {}
        }
    }

    /**
     * 从AI响应中解析任务
     */
    private fun parseTasksFromAIResponse(
        response: String,
        date: LocalDate,
        allowFallback: Boolean
    ): List<StudyTask> {
        val tasks = mutableListOf<StudyTask>()
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

        // 简单解析：查找时间段和任务描述
        val timePattern = Regex("""(\d{1,2}:\d{2})\s*[-–]\s*(\d{1,2}:\d{2})\s*[：:]\s*(.+)""")
        val taskPattern = Regex("""[•\-\d.]+\s*【?(\S+?)】?\s*[：:]\s*(.+)""")

        val lines = response.lines()
        var currentSubject = ""

        for (line in lines) {
            // 尝试匹配时间格式
            val timeMatch = timePattern.find(line)
            if (timeMatch != null) {
                val (startTime, endTime, content) = timeMatch.destructured
                val subject = detectSubject(content)
                val minutes = calculateMinutes(startTime, endTime)

                tasks.add(StudyTask(
                    title = content.trim(),
                    subject = subject,
                    date = dateStr,
                    startTime = startTime,
                    endTime = endTime,
                    estimatedMinutes = minutes,
                    priority = TaskPriority.MEDIUM
                ))
                continue
            }

            // 尝试匹配任务格式
            val taskMatch = taskPattern.find(line)
            if (taskMatch != null) {
                val (subject, content) = taskMatch.destructured
                currentSubject = subject

                tasks.add(StudyTask(
                    title = content.trim(),
                    subject = subject,
                    date = dateStr,
                    estimatedMinutes = 60, // 默认60分钟
                    priority = TaskPriority.MEDIUM
                ))
            }
        }

        // 如果没有解析到任务，创建默认任务
        if (tasks.isEmpty() && allowFallback) {
            val config = _uiState.value.config
            config.subjects.forEach { subject ->
                tasks.add(StudyTask(
                    title = "${subject.name}学习",
                    subject = subject.name,
                    date = dateStr,
                    estimatedMinutes = subject.dailyMinutes,
                    priority = TaskPriority.MEDIUM
                ))
            }
        }

        return tasks
    }

    private fun latestPlanContent(): String? {
        return _uiState.value.planChatMessages
            .lastOrNull { it.role == MessageRole.Assistant && it.content.isNotBlank() }
            ?.content
    }

    private fun applyPlanContent(content: String, date: LocalDate, clearExisting: Boolean) {
        viewModelScope.launch {
            try {
                val tasks = parseTasksFromAIResponse(content, date, allowFallback = false)
                if (tasks.isEmpty()) {
                    _uiState.update { it.copy(error = "未识别到可应用的计划，请让AI按格式输出") }
                    return@launch
                }
                if (clearExisting) {
                    studyPlanManager.clearTasksForDate(date)
                }
                studyPlanManager.addTasks(tasks)
                _uiState.update { it.copy(showPlanChatDialog = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "解析计划失败: ${e.message}") }
            }
        }
    }

    private fun buildPlanChatContext(): List<Message> {
        val context = mutableListOf<Message>()
        planBaseContext?.let { context.add(it) }
        if (planChatContext.isNotEmpty()) {
            context.addAll(planChatContext.takeLast(10))
        }
        return context
    }

    /**
     * 检测科目
     */
    private fun detectSubject(content: String): String {
        return when {
            content.contains("数学") || content.contains("高数") || content.contains("线代") || content.contains("概率") -> "数学"
            content.contains("英语") || content.contains("单词") || content.contains("阅读") || content.contains("作文") -> "英语"
            content.contains("政治") || content.contains("马原") || content.contains("毛概") || content.contains("史纲") -> "政治"
            else -> "专业课"
        }
    }

    /**
     * 计算时长（分钟）
     */
    private fun calculateMinutes(startTime: String, endTime: String): Int {
        return try {
            val start = java.time.LocalTime.parse(startTime)
            val end = java.time.LocalTime.parse(endTime)
            java.time.Duration.between(start, end).toMinutes().toInt()
        } catch (e: Exception) {
            60 // 默认60分钟
        }
    }

    /**
     * 构建学习计划基础上下文
     */
    private fun buildPlanBaseContextMessage(
        user: UserInfo?,
        config: StudyPlanConfig,
        daysUntilExam: Int?,
        date: LocalDate
    ): Message? {
        val examDate = config.examDate.takeIf { it.isNotBlank() }
        val examYear = examDate?.let {
            runCatching { LocalDate.parse(it).year }.getOrNull()
        }
        val remainingDays = daysUntilExam?.let { if (it < 0) 0 else it }
        val content = buildString {
            appendLine("【计划日期】${date.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))}")

            if (user != null) {
                appendLine("【我的信息】")
                if (user.targetSchool.isNotBlank()) appendLine("目标院校：${user.targetSchool}")
                if (user.targetMajor.isNotBlank()) appendLine("目标专业：${user.targetMajor}")
                appendLine()
            }

            if (examYear != null || examDate != null || remainingDays != null) {
                appendLine("【考试信息】")
                if (examYear != null) appendLine("考生年份：${examYear}年")
                if (examDate != null) appendLine("预计考试日期：$examDate")
                if (remainingDays != null) appendLine("距离考试还有 $remainingDays 天")
                appendLine()
            }

            appendLine("【学习配置】")
            appendLine("每日学习时长：${config.dailyStudyHours}小时")
            appendLine("科目分配：")
            config.subjects.forEach { subject ->
                appendLine("- ${subject.name}：${subject.dailyMinutes}分钟")
            }
        }
        return content.trim().takeIf { it.isNotBlank() }?.let { Message(MessageRole.User, it) }
    }

    private fun buildPlanGenerationCommand(date: LocalDate): String {
        return buildString {
            appendLine("请根据我们目前的沟通，输出${date.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))}的最终学习计划。")
            appendLine("请严格按以下格式输出（每行一条）：")
            appendLine("08:00-10:00 : 【数学】高等数学第三章复习")
            appendLine("10:15-12:00 : 【英语】阅读理解练习")
            appendLine("只输出计划列表，不要额外说明。")
            appendLine("如果需要调整，请输出完整的新计划。")
        }
    }

    /**
     * 显示添加任务对话框
     */
    fun showAddTaskDialog() {
        _uiState.update { it.copy(showAddTaskDialog = true) }
    }

    /**
     * 隐藏添加任务对话框
     */
    fun hideAddTaskDialog() {
        _uiState.update { it.copy(showAddTaskDialog = false) }
    }

    /**
     * 显示配置对话框
     */
    fun showConfigDialog() {
        _uiState.update { it.copy(showConfigDialog = true) }
    }

    /**
     * 隐藏配置对话框
     */
    fun hideConfigDialog() {
        _uiState.update { it.copy(showConfigDialog = false) }
    }

    /**
     * 编辑任务
     */
    fun editTask(task: StudyTask) {
        _uiState.update { it.copy(editingTask = task) }
    }

    /**
     * 取消编辑
     */
    fun cancelEdit() {
        _uiState.update { it.copy(editingTask = null) }
    }

    /**
     * 清除错误
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * 获取添加到日历的Intent
     */
    fun getCalendarIntent(task: StudyTask): Intent {
        return studyPlanManager.createCalendarEventIntent(task)
    }

    /**
     * 获取分享到备忘录的Intent
     */
    fun getReminderIntent(task: StudyTask): Intent {
        return studyPlanManager.createReminderIntent(task)
    }

    companion object {
        private const val STUDY_PLAN_SYSTEM_PROMPT = """你是一个专业的考研学习规划师，擅长制定科学合理的学习计划。

工作方式：
1. 先通过对话了解学生的时间安排、薄弱科目、目标阶段和偏好，信息不足时主动追问
2. 当用户明确要求“生成/输出/给出计划”时，再给出完整计划
3. 用户要求调整时，请输出一份完整的新计划，而不是只给修改点

计划输出格式（必须严格遵守，每行一条）：
08:00-10:00 : 【数学】高等数学第三章复习
10:15-12:00 : 【英语】阅读理解练习

注意事项：
1. 早上适合学习需要高度集中注意力的科目（如数学）
2. 下午可以安排英语阅读、专业课等
3. 晚上适合复习和背诵（如政治、英语单词）
4. 每学习1.5-2小时应安排10-15分钟休息
5. 根据距离考试的时间调整复习策略：
   - 基础阶段（>6个月）：注重基础知识学习
   - 强化阶段（3-6个月）：大量做题，查漏补缺
   - 冲刺阶段（<3个月）：真题模拟，重点突破"""
    }

    class Factory(
        private val studyPlanManager: StudyPlanManager,
        private val userManager: UserManager,
        private val configManager: ConfigManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StudyPlanViewModel(studyPlanManager, userManager, configManager) as T
        }
    }
}
