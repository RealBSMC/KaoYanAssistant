package com.example.kaoyanassistant.core

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "study_plan")

/**
 * 学习任务
 */
@Serializable
data class StudyTask(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,                           // 任务标题
    val description: String = "",                // 任务描述
    val subject: String = "",                    // 科目（数学/英语/政治/专业课）
    val date: String,                            // 日期 yyyy-MM-dd
    val startTime: String = "",                  // 开始时间 HH:mm
    val endTime: String = "",                    // 结束时间 HH:mm
    val estimatedMinutes: Int = 0,               // 预计时长（分钟）
    val priority: TaskPriority = TaskPriority.MEDIUM, // 优先级
    val status: TaskStatus = TaskStatus.PENDING, // 状态
    val tags: List<String> = emptyList(),        // 标签
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val calendarEventId: Long? = null            // 日历事件ID
)

/**
 * 任务优先级
 */
@Serializable
enum class TaskPriority {
    LOW,      // 低
    MEDIUM,   // 中
    HIGH,     // 高
    URGENT    // 紧急
}

/**
 * 任务状态
 */
@Serializable
enum class TaskStatus {
    PENDING,     // 待完成
    IN_PROGRESS, // 进行中
    COMPLETED,   // 已完成
    SKIPPED      // 已跳过
}

/**
 * 每日学习计划
 */
@Serializable
data class DailyPlan(
    val date: String,                    // 日期 yyyy-MM-dd
    val tasks: List<StudyTask> = emptyList(),
    val totalMinutes: Int = 0,           // 总学习时长
    val completedMinutes: Int = 0,       // 已完成时长
    val note: String = ""                // 备注
)

/**
 * 学习计划配置
 */
@Serializable
data class StudyPlanConfig(
    val examDate: String = "",           // 考试日期
    val dailyStudyHours: Int = 8,        // 每日学习时长（小时）
    val subjects: List<SubjectConfig> = defaultSubjects,
    val restDays: List<Int> = emptyList(), // 休息日（周几，1-7）
    val reminderEnabled: Boolean = true,
    val reminderMinutesBefore: Int = 15
)

/**
 * 科目配置
 */
@Serializable
data class SubjectConfig(
    val name: String,
    val dailyMinutes: Int,               // 每日学习时长（分钟）
    val color: String = "#2196F3"        // 颜色
)

val defaultSubjects = listOf(
    SubjectConfig("数学", 180, "#2196F3"),
    SubjectConfig("英语", 120, "#4CAF50"),
    SubjectConfig("政治", 90, "#FF9800"),
    SubjectConfig("专业课", 150, "#9C27B0")
)

/**
 * 学习计划管理器
 */
class StudyPlanManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: StudyPlanManager? = null

        private val TASKS_KEY = stringPreferencesKey("study_tasks")
        private val CONFIG_KEY = stringPreferencesKey("study_plan_config")

        fun getInstance(context: Context): StudyPlanManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StudyPlanManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val dataStore = context.dataStore

    /**
     * 获取所有任务
     */
    val tasksFlow: Flow<List<StudyTask>> = dataStore.data.map { preferences ->
        val tasksJson = preferences[TASKS_KEY] ?: return@map emptyList()
        try {
            json.decodeFromString<List<StudyTask>>(tasksJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 获取配置
     */
    val configFlow: Flow<StudyPlanConfig> = dataStore.data.map { preferences ->
        val configJson = preferences[CONFIG_KEY] ?: return@map StudyPlanConfig()
        try {
            json.decodeFromString<StudyPlanConfig>(configJson)
        } catch (e: Exception) {
            StudyPlanConfig()
        }
    }

    /**
     * 获取指定日期的任务
     */
    fun getTasksForDate(date: LocalDate): Flow<List<StudyTask>> {
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        return tasksFlow.map { tasks ->
            tasks.filter { it.date == dateStr }.sortedBy { it.startTime }
        }
    }

    /**
     * 获取每日计划
     */
    fun getDailyPlan(date: LocalDate): Flow<DailyPlan> {
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        return tasksFlow.map { tasks ->
            val dayTasks = tasks.filter { it.date == dateStr }
            val totalMinutes = dayTasks.sumOf { it.estimatedMinutes }
            val completedMinutes = dayTasks
                .filter { it.status == TaskStatus.COMPLETED }
                .sumOf { it.estimatedMinutes }

            DailyPlan(
                date = dateStr,
                tasks = dayTasks.sortedBy { it.startTime },
                totalMinutes = totalMinutes,
                completedMinutes = completedMinutes
            )
        }
    }

    /**
     * 添加任务
     */
    suspend fun addTask(task: StudyTask): Result<StudyTask> {
        return try {
            dataStore.edit { preferences ->
                val tasksJson = preferences[TASKS_KEY]
                val tasks = if (tasksJson != null) {
                    json.decodeFromString<MutableList<StudyTask>>(tasksJson)
                } else {
                    mutableListOf()
                }
                tasks.add(task)
                preferences[TASKS_KEY] = json.encodeToString(tasks)
            }
            Result.success(task)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 批量添加任务
     */
    suspend fun addTasks(newTasks: List<StudyTask>): Result<Unit> {
        return try {
            dataStore.edit { preferences ->
                val tasksJson = preferences[TASKS_KEY]
                val tasks = if (tasksJson != null) {
                    json.decodeFromString<MutableList<StudyTask>>(tasksJson)
                } else {
                    mutableListOf()
                }
                tasks.addAll(newTasks)
                preferences[TASKS_KEY] = json.encodeToString(tasks)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 更新任务
     */
    suspend fun updateTask(task: StudyTask): Result<Unit> {
        return try {
            dataStore.edit { preferences ->
                val tasksJson = preferences[TASKS_KEY] ?: throw Exception("没有任务数据")
                val tasks = json.decodeFromString<MutableList<StudyTask>>(tasksJson)
                val index = tasks.indexOfFirst { it.id == task.id }
                if (index >= 0) {
                    tasks[index] = task
                    preferences[TASKS_KEY] = json.encodeToString(tasks)
                } else {
                    throw Exception("任务不存在")
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 完成任务
     */
    suspend fun completeTask(taskId: String): Result<Unit> {
        return try {
            dataStore.edit { preferences ->
                val tasksJson = preferences[TASKS_KEY] ?: throw Exception("没有任务数据")
                val tasks = json.decodeFromString<MutableList<StudyTask>>(tasksJson)
                val index = tasks.indexOfFirst { it.id == taskId }
                if (index >= 0) {
                    tasks[index] = tasks[index].copy(
                        status = TaskStatus.COMPLETED,
                        completedAt = System.currentTimeMillis()
                    )
                    preferences[TASKS_KEY] = json.encodeToString(tasks)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 删除任务
     */
    suspend fun deleteTask(taskId: String): Result<Unit> {
        return try {
            dataStore.edit { preferences ->
                val tasksJson = preferences[TASKS_KEY] ?: return@edit
                val tasks = json.decodeFromString<MutableList<StudyTask>>(tasksJson)
                tasks.removeAll { it.id == taskId }
                preferences[TASKS_KEY] = json.encodeToString(tasks)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 清空指定日期的任务
     */
    suspend fun clearTasksForDate(date: LocalDate): Result<Unit> {
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        return try {
            dataStore.edit { preferences ->
                val tasksJson = preferences[TASKS_KEY] ?: return@edit
                val tasks = json.decodeFromString<MutableList<StudyTask>>(tasksJson)
                tasks.removeAll { it.date == dateStr }
                preferences[TASKS_KEY] = json.encodeToString(tasks)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 保存配置
     */
    suspend fun saveConfig(config: StudyPlanConfig): Result<Unit> {
        return try {
            dataStore.edit { preferences ->
                preferences[CONFIG_KEY] = json.encodeToString(config)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取学习统计
     */
    suspend fun getStudyStats(startDate: LocalDate, endDate: LocalDate): StudyStats {
        val tasks = tasksFlow.first()
        val startStr = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val endStr = endDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val periodTasks = tasks.filter { it.date in startStr..endStr }
        val completedTasks = periodTasks.filter { it.status == TaskStatus.COMPLETED }

        val totalMinutes = periodTasks.sumOf { it.estimatedMinutes }
        val completedMinutes = completedTasks.sumOf { it.estimatedMinutes }

        val subjectStats = periodTasks.groupBy { it.subject }.mapValues { (_, subjectTasks) ->
            SubjectStats(
                totalMinutes = subjectTasks.sumOf { it.estimatedMinutes },
                completedMinutes = subjectTasks.filter { it.status == TaskStatus.COMPLETED }
                    .sumOf { it.estimatedMinutes },
                taskCount = subjectTasks.size,
                completedCount = subjectTasks.count { it.status == TaskStatus.COMPLETED }
            )
        }

        return StudyStats(
            totalMinutes = totalMinutes,
            completedMinutes = completedMinutes,
            totalTasks = periodTasks.size,
            completedTasks = completedTasks.size,
            subjectStats = subjectStats
        )
    }

    /**
     * 计算距离考试的天数
     */
    suspend fun getDaysUntilExam(): Int? {
        val config = configFlow.first()
        if (config.examDate.isBlank()) return null

        return try {
            val examDate = LocalDate.parse(config.examDate)
            val today = LocalDate.now()
            java.time.temporal.ChronoUnit.DAYS.between(today, examDate).toInt()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 创建日历事件Intent
     */
    fun createCalendarEventIntent(task: StudyTask): Intent {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI

            putExtra(CalendarContract.Events.TITLE, task.title)
            putExtra(CalendarContract.Events.DESCRIPTION, task.description)

            // 解析日期时间
            try {
                val date = LocalDate.parse(task.date)
                val startTime = if (task.startTime.isNotBlank()) {
                    LocalDateTime.of(date, java.time.LocalTime.parse(task.startTime))
                } else {
                    LocalDateTime.of(date, java.time.LocalTime.of(9, 0))
                }
                val endTime = if (task.endTime.isNotBlank()) {
                    LocalDateTime.of(date, java.time.LocalTime.parse(task.endTime))
                } else {
                    startTime.plusMinutes(task.estimatedMinutes.toLong())
                }

                val startMillis = startTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val endMillis = endTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            } catch (e: Exception) {
                // 使用默认时间
            }
        }
        return intent
    }

    /**
     * 创建备忘录Intent（通过分享）
     */
    fun createReminderIntent(task: StudyTask): Intent {
        val text = buildString {
            appendLine("【考研学习任务】")
            appendLine("任务：${task.title}")
            if (task.description.isNotBlank()) {
                appendLine("描述：${task.description}")
            }
            appendLine("日期：${task.date}")
            if (task.startTime.isNotBlank()) {
                appendLine("时间：${task.startTime} - ${task.endTime}")
            }
            appendLine("科目：${task.subject}")
            appendLine("预计时长：${task.estimatedMinutes}分钟")
        }

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "考研学习任务：${task.title}")
        }
    }
}

/**
 * 学习统计
 */
data class StudyStats(
    val totalMinutes: Int,
    val completedMinutes: Int,
    val totalTasks: Int,
    val completedTasks: Int,
    val subjectStats: Map<String, SubjectStats>
) {
    val completionRate: Float
        get() = if (totalTasks > 0) completedTasks.toFloat() / totalTasks else 0f

    val timeCompletionRate: Float
        get() = if (totalMinutes > 0) completedMinutes.toFloat() / totalMinutes else 0f
}

/**
 * 科目统计
 */
data class SubjectStats(
    val totalMinutes: Int,
    val completedMinutes: Int,
    val taskCount: Int,
    val completedCount: Int
)
