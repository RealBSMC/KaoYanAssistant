package com.example.kaoyanassistant.ui.studyplan

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.kaoyanassistant.core.*
import com.example.kaoyanassistant.services.MessageRole
import com.example.kaoyanassistant.ui.components.MarkdownMathView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.*

/**
 * 学习计划界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyPlanScreen(
    viewModel: StudyPlanViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 错误提示
    uiState.error?.let { error ->
        LaunchedEffect(error) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("学习计划") },
                actions = {
                    IconButton(onClick = { viewModel.showConfigDialog() }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        floatingActionButton = {
            Column {
                // AI生成按钮
                SmallFloatingActionButton(
                    onClick = { viewModel.showPlanChat(uiState.selectedDate) },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "AI生成")
                }
                Spacer(modifier = Modifier.height(8.dp))
                // 添加任务按钮
                FloatingActionButton(
                    onClick = { viewModel.showAddTaskDialog() }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加任务")
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 错误提示
            AnimatedVisibility(visible = uiState.error != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = uiState.error ?: "",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // 生成进度提示
            AnimatedVisibility(visible = uiState.isGenerating) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = uiState.generatingProgress,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // 考试倒计时
            uiState.daysUntilExam?.let { days ->
                if (days > 0) {
                    ExamCountdown(days = days)
                }
            }

            // 周日历
            WeekCalendar(
                selectedDate = uiState.selectedDate,
                weekTasks = uiState.weekTasks,
                onDateSelected = { viewModel.selectDate(it) }
            )

            // 今日进度
            uiState.dailyPlan?.let { plan ->
                DailyProgress(plan = plan)
            }

            // 任务列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                val tasks = uiState.dailyPlan?.tasks ?: emptyList()

                if (tasks.isEmpty()) {
                    item {
                        EmptyTasksPlaceholder(
                            onGenerateClick = { viewModel.showPlanChat(uiState.selectedDate) }
                        )
                    }
                } else {
                    items(tasks, key = { it.id }) { task ->
                        TaskItem(
                            task = task,
                            onComplete = { viewModel.completeTask(task.id) },
                            onEdit = { viewModel.editTask(task) },
                            onDelete = { viewModel.deleteTask(task.id) },
                            onAddToCalendar = {
                                context.startActivity(viewModel.getCalendarIntent(task))
                            },
                            onShare = {
                                context.startActivity(
                                    android.content.Intent.createChooser(
                                        viewModel.getReminderIntent(task),
                                        "分享到"
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    // 添加任务对话框
    if (uiState.showAddTaskDialog) {
        AddTaskDialog(
            selectedDate = uiState.selectedDate,
            onDismiss = { viewModel.hideAddTaskDialog() },
            onConfirm = { task -> viewModel.addTask(task) }
        )
    }

    // 配置对话框
    if (uiState.showConfigDialog) {
        ConfigDialog(
            config = uiState.config,
            onDismiss = { viewModel.hideConfigDialog() },
            onConfirm = { config -> viewModel.saveConfig(config) }
        )
    }

    // 编辑任务对话框
    uiState.editingTask?.let { task ->
        EditTaskDialog(
            task = task,
            onDismiss = { viewModel.cancelEdit() },
            onConfirm = { updatedTask -> viewModel.updateTask(updatedTask) }
        )
    }

    if (uiState.showPlanChatDialog) {
        PlanChatBottomSheet(
            uiState = uiState,
            onDismiss = { viewModel.hidePlanChat() },
            onClear = { viewModel.clearPlanChat() },
            onInputChange = { viewModel.updatePlanChatInput(it) },
            onSend = { viewModel.sendPlanChatMessage(it) },
            onGeneratePlan = { viewModel.requestPlanGeneration() },
            onApplyPlan = { viewModel.requestApplyPlan() },
            onCancel = { viewModel.cancelPlanChatRequest() }
        )
    }

    if (uiState.showClearPlanDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelApplyPlan() },
            title = { Text("清除已有计划？") },
            text = { Text("当前日期已有学习计划，是否清除后应用新计划？") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmApplyPlan(clearExisting = true) }) {
                    Text("清除并应用")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.confirmApplyPlan(clearExisting = false) }) {
                    Text("保留并应用")
                }
            }
        )
    }
}

/**
 * 考试倒计时
 */
@Composable
private fun ExamCountdown(days: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                days <= 30 -> MaterialTheme.colorScheme.errorContainer
                days <= 90 -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.primaryContainer
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Timer,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "距离考试还有 ",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "$days",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = " 天",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

/**
 * 周日历
 */
@Composable
private fun WeekCalendar(
    selectedDate: LocalDate,
    weekTasks: Map<LocalDate, List<StudyTask>>,
    onDateSelected: (LocalDate) -> Unit
) {
    val today = LocalDate.now()
    val startOfWeek = today.minusDays(today.dayOfWeek.value.toLong() - 1)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 月份标题
            Text(
                text = selectedDate.format(DateTimeFormatter.ofPattern("yyyy年MM月")),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // 星期行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (i in 0..6) {
                    val date = startOfWeek.plusDays(i.toLong())
                    val isSelected = date == selectedDate
                    val isToday = date == today
                    val tasks = weekTasks[date] ?: emptyList()
                    val hasCompletedAll = tasks.isNotEmpty() && tasks.all { it.status == TaskStatus.COMPLETED }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    isSelected -> MaterialTheme.colorScheme.primaryContainer
                                    else -> Color.Transparent
                                }
                            )
                            .clickable { onDateSelected(date) }
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 星期
                        Text(
                            text = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINESE),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // 日期
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isToday && !isSelected -> MaterialTheme.colorScheme.primary
                                        else -> Color.Transparent
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    isToday && !isSelected -> MaterialTheme.colorScheme.onPrimary
                                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }

                        // 任务指示点
                        if (tasks.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (hasCompletedAll) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.tertiary
                                        }
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 每日进度
 */
@Composable
private fun DailyProgress(plan: DailyPlan) {
    val progress = if (plan.totalMinutes > 0) {
        plan.completedMinutes.toFloat() / plan.totalMinutes
    } else 0f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "今日进度",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "${plan.completedMinutes}/${plan.totalMinutes}分钟",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "${(progress * 100).toInt()}% 完成",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 任务项
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskItem(
    task: StudyTask,
    onComplete: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddToCalendar: () -> Unit,
    onShare: () -> Unit
) {
    val isCompleted = task.status == TaskStatus.COMPLETED
    var showMenu by remember { mutableStateOf(false) }

    val subjectColor = when (task.subject) {
        "数学" -> Color(0xFF2196F3)
        "英语" -> Color(0xFF4CAF50)
        "政治" -> Color(0xFFFF9800)
        "专业课" -> Color(0xFF9C27B0)
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isCompleted) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 完成复选框
            Checkbox(
                checked = isCompleted,
                onCheckedChange = { if (!isCompleted) onComplete() }
            )

            // 科目标签
            Surface(
                modifier = Modifier.padding(horizontal = 8.dp),
                shape = RoundedCornerShape(4.dp),
                color = subjectColor.copy(alpha = 0.2f)
            ) {
                Text(
                    text = task.subject,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = subjectColor
                )
            }

            // 任务内容
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (isCompleted) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (task.startTime.isNotBlank()) {
                    Text(
                        text = "${task.startTime} - ${task.endTime}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 时长
            Text(
                text = "${task.estimatedMinutes}分钟",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 更多菜单
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "更多"
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("添加到日历") },
                        onClick = {
                            showMenu = false
                            onAddToCalendar()
                        },
                        leadingIcon = { Icon(Icons.Default.CalendarMonth, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("分享") },
                        onClick = {
                            showMenu = false
                            onShare()
                        },
                        leadingIcon = { Icon(Icons.Default.Share, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = { Icon(Icons.Default.Delete, null) }
                    )
                }
            }
        }
    }
}

/**
 * 空任务占位
 */
@Composable
private fun EmptyTasksPlaceholder(onGenerateClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.EventNote,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "今天还没有学习任务",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "点击下方按钮与AI沟通后生成学习计划",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onGenerateClick) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("AI沟通生成")
        }
    }
}

/**
 * 添加任务对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTaskDialog(
    selectedDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (StudyTask) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("数学") }
    var startTime by remember { mutableStateOf("") }
    var endTime by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("60") }

    val subjects = listOf("数学", "英语", "政治", "专业课")

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("添加学习任务") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("任务内容") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // 科目选择
                Text("科目", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(subjects) { s ->
                        FilterChip(
                            selected = subject == s,
                            onClick = { subject = s },
                            label = { Text(s) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startTime,
                        onValueChange = { startTime = it },
                        label = { Text("开始时间") },
                        placeholder = { Text("09:00") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = endTime,
                        onValueChange = { endTime = it },
                        label = { Text("结束时间") },
                        placeholder = { Text("11:00") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter { c -> c.isDigit() } },
                    label = { Text("预计时长（分钟）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank()) {
                        onConfirm(StudyTask(
                            title = title,
                            subject = subject,
                            date = selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            startTime = startTime,
                            endTime = endTime,
                            estimatedMinutes = minutes.toIntOrNull() ?: 60
                        ))
                    }
                },
                enabled = title.isNotBlank()
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 编辑任务对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTaskDialog(
    task: StudyTask,
    onDismiss: () -> Unit,
    onConfirm: (StudyTask) -> Unit
) {
    var title by remember { mutableStateOf(task.title) }
    var subject by remember { mutableStateOf(task.subject) }
    var startTime by remember { mutableStateOf(task.startTime) }
    var endTime by remember { mutableStateOf(task.endTime) }
    var minutes by remember { mutableStateOf(task.estimatedMinutes.toString()) }

    val subjects = listOf("数学", "英语", "政治", "专业课")

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("编辑任务") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("任务内容") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text("科目", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(subjects) { s ->
                        FilterChip(
                            selected = subject == s,
                            onClick = { subject = s },
                            label = { Text(s) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startTime,
                        onValueChange = { startTime = it },
                        label = { Text("开始时间") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = endTime,
                        onValueChange = { endTime = it },
                        label = { Text("结束时间") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter { c -> c.isDigit() } },
                    label = { Text("预计时长（分钟）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank()) {
                        onConfirm(task.copy(
                            title = title,
                            subject = subject,
                            startTime = startTime,
                            endTime = endTime,
                            estimatedMinutes = minutes.toIntOrNull() ?: 60
                        ))
                    }
                },
                enabled = title.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 配置对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigDialog(
    config: StudyPlanConfig,
    onDismiss: () -> Unit,
    onConfirm: (StudyPlanConfig) -> Unit
) {
    val examMonth = 12
    val examDay = 20
    val now = LocalDate.now()
    val defaultYear = if (now.isAfter(LocalDate.of(now.year, examMonth, examDay))) {
        now.year + 1
    } else {
        now.year
    }
    val initialYear = config.examDate.takeIf { it.isNotBlank() }?.let {
        runCatching { LocalDate.parse(it).year }.getOrNull()
    } ?: defaultYear
    var selectedYear by remember { mutableStateOf(initialYear) }
    var expanded by remember { mutableStateOf(false) }
    val yearOptions = remember(defaultYear, initialYear) {
        val years = (defaultYear..(defaultYear + 4)).toMutableList()
        if (initialYear !in years) years.add(initialYear)
        years.sorted()
    }
    val examDate = LocalDate.of(selectedYear, examMonth, examDay)
        .format(DateTimeFormatter.ISO_LOCAL_DATE)
    var dailyHours by remember { mutableStateOf(config.dailyStudyHours.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("学习计划设置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = "${selectedYear}年",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("考生年份") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        }
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        yearOptions.forEach { year ->
                            DropdownMenuItem(
                                text = { Text("${year}年") },
                                onClick = {
                                    selectedYear = year
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                Text(
                    text = "预计考试日期：$examDate",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = dailyHours,
                    onValueChange = { dailyHours = it.filter { c -> c.isDigit() } },
                    label = { Text("每日学习时长（小时）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Text(
                    text = "科目时间分配可在后续版本中自定义",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(config.copy(
                        examDate = examDate,
                        dailyStudyHours = dailyHours.toIntOrNull() ?: 8
                    ))
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanChatBottomSheet(
    uiState: StudyPlanUiState,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onGeneratePlan: () -> Unit,
    onApplyPlan: () -> Unit,
    onCancel: () -> Unit
) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val canApply = uiState.planChatMessages.any { it.role == MessageRole.Assistant && it.content.isNotBlank() } &&
        !uiState.isGenerating

    LaunchedEffect(uiState.planChatMessages.size, uiState.planChatMessages.lastOrNull()?.content) {
        if (uiState.planChatMessages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.planChatMessages.size - 1)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AI学习计划沟通",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Refresh, contentDescription = "清空对话")
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }

            uiState.error?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.planChatMessages, key = { it.id }) { message ->
                    PlanChatMessageItem(message = message)
                }

                if (uiState.isGenerating && uiState.planChatMessages.lastOrNull()?.isStreaming != true) {
                    item {
                        PlanChatLoadingIndicator()
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onGeneratePlan,
                    enabled = !uiState.isGenerating,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("生成计划")
                }
                Button(
                    onClick = onApplyPlan,
                    enabled = canApply,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("应用到今日")
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = uiState.planChatInput,
                        onValueChange = onInputChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("描述你的需求或调整建议...") },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (uiState.planChatInput.isNotBlank() && !uiState.isGenerating) {
                                    onSend(uiState.planChatInput)
                                    focusManager.clearFocus()
                                }
                            }
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    if (uiState.isGenerating) {
                        IconButton(onClick = onCancel) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "取消",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (uiState.planChatInput.isNotBlank()) {
                                    onSend(uiState.planChatInput)
                                    focusManager.clearFocus()
                                }
                            },
                            enabled = uiState.planChatInput.isNotBlank() && !uiState.isGenerating
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                tint = if (uiState.planChatInput.isNotBlank() && !uiState.isGenerating)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanChatMessageItem(message: PlanChatMessage) {
    val isUser = message.role == MessageRole.User

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(
                topStart = if (isUser) 16.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            if (isUser) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Box(modifier = Modifier.padding(12.dp)) {
                    MarkdownMathView(
                        content = message.content + if (message.isStreaming) "▌" else "",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.secondary
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(20.dp),
                    tint = MaterialTheme.colorScheme.onSecondary
                )
            }
        }
    }
}

@Composable
private fun PlanChatLoadingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier
                    .padding(8.dp)
                    .size(20.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "正在回复...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
