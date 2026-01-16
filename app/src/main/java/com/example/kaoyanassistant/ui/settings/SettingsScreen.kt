package com.example.kaoyanassistant.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.kaoyanassistant.core.AIProvider
import com.example.kaoyanassistant.core.APIConfig
import com.example.kaoyanassistant.core.MultimodalMode
import com.example.kaoyanassistant.core.UserInfo

/**
 * 设置界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedProvider by remember { mutableStateOf(uiState.currentProvider) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showUserInfoDialog by remember { mutableStateOf(false) }
    var showVisionProviderMenu by remember { mutableStateOf(false) }
    var showReasoningProviderMenu by remember { mutableStateOf(false) }

    // 保存成功提示
    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            kotlinx.coroutines.delay(2000)
            viewModel.clearSaveSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = {
            if (uiState.saveSuccess) {
                Snackbar(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text("保存成功")
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 用户信息卡片
            item {
                UserInfoCard(
                    userInfo = uiState.currentUser,
                    onEditClick = { showUserInfoDialog = true },
                    onLogoutClick = { showLogoutDialog = true }
                )
            }

            // AI服务商选择
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "AI服务商",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        AIProvider.entries.forEach { provider ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedProvider = provider
                                        viewModel.setCurrentProvider(provider)
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = uiState.currentProvider == provider,
                                    onClick = {
                                        selectedProvider = provider
                                        viewModel.setCurrentProvider(provider)
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = viewModel.getProviderDisplayName(provider),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = getProviderDescription(provider),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 多模态模式
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "多模态模式",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setMultimodalMode(MultimodalMode.Single) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.multimodalMode == MultimodalMode.Single,
                                onClick = { viewModel.setMultimodalMode(MultimodalMode.Single) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "单模型",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "上传图片时直接由当前模型处理",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setMultimodalMode(MultimodalMode.Split) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.multimodalMode == MultimodalMode.Split,
                                onClick = { viewModel.setMultimodalMode(MultimodalMode.Split) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "双模型协同",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "先用图片模型解析，再用推理模型生成回答",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }

                        if (uiState.multimodalMode == MultimodalMode.Split) {
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "图片模型",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Box {
                                    OutlinedButton(onClick = { showVisionProviderMenu = true }) {
                                        Text(viewModel.getProviderDisplayName(uiState.multimodalVisionProvider))
                                    }
                                    DropdownMenu(
                                        expanded = showVisionProviderMenu,
                                        onDismissRequest = { showVisionProviderMenu = false }
                                    ) {
                                        AIProvider.entries.forEach { provider ->
                                            DropdownMenuItem(
                                                text = { Text(viewModel.getProviderDisplayName(provider)) },
                                                onClick = {
                                                    showVisionProviderMenu = false
                                                    viewModel.setMultimodalVisionProvider(provider)
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "推理模型",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Box {
                                    OutlinedButton(onClick = { showReasoningProviderMenu = true }) {
                                        Text(viewModel.getProviderDisplayName(uiState.multimodalReasoningProvider))
                                    }
                                    DropdownMenu(
                                        expanded = showReasoningProviderMenu,
                                        onDismissRequest = { showReasoningProviderMenu = false }
                                    ) {
                                        AIProvider.entries.forEach { provider ->
                                            DropdownMenuItem(
                                                text = { Text(viewModel.getProviderDisplayName(provider)) },
                                                onClick = {
                                                    showReasoningProviderMenu = false
                                                    viewModel.setMultimodalReasoningProvider(provider)
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "提示：请确保图片模型与推理模型都已在上方配置好API信息。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            if (uiState.multimodalMode == MultimodalMode.Split &&
                uiState.multimodalVisionProvider == AIProvider.Custom
            ) {
                item {
                    APIConfigCard(
                        provider = AIProvider.Custom,
                        config = uiState.multimodalVisionCustomConfig,
                        providerName = "图片模型(自定义)",
                        onSave = { config ->
                            viewModel.updateMultimodalVisionCustomConfig(config)
                        },
                        isSaving = uiState.isSaving
                    )
                }
            }

            if (uiState.multimodalMode == MultimodalMode.Split &&
                uiState.multimodalReasoningProvider == AIProvider.Custom
            ) {
                item {
                    APIConfigCard(
                        provider = AIProvider.Custom,
                        config = uiState.multimodalReasoningCustomConfig,
                        providerName = "推理模型(自定义)",
                        onSave = { config ->
                            viewModel.updateMultimodalReasoningCustomConfig(config)
                        },
                        isSaving = uiState.isSaving
                    )
                }
            }

            // API配置
            item {
                APIConfigCard(
                    provider = selectedProvider,
                    config = uiState.apiConfigs[selectedProvider] ?: APIConfig(),
                    providerName = viewModel.getProviderDisplayName(selectedProvider),
                    onSave = { config ->
                        viewModel.updateAPIConfig(selectedProvider, config)
                    },
                    isSaving = uiState.isSaving
                )
            }

            // 高级设置
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "高级设置",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val config = uiState.apiConfigs[selectedProvider] ?: APIConfig()
                        var maxTokens by remember(config) { mutableStateOf(config.maxContextTokens.toString()) }
                        var keepRecent by remember(config) { mutableStateOf(config.keepRecentMessages.toString()) }

                        OutlinedTextField(
                            value = maxTokens,
                            onValueChange = { maxTokens = it },
                            label = { Text("最大上下文Token数") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = { Text("建议值：4000-32000") }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = keepRecent,
                            onValueChange = { keepRecent = it },
                            label = { Text("压缩时保留消息数") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = { Text("上下文压缩时保留最近的消息数量") }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                val newConfig = config.copy(
                                    maxContextTokens = maxTokens.toIntOrNull() ?: 8000,
                                    keepRecentMessages = keepRecent.toIntOrNull() ?: 10
                                )
                                viewModel.updateAPIConfig(selectedProvider, newConfig)
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("保存高级设置")
                        }
                    }
                }
            }

            // 关于
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "关于",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "考研AI助手 v1.0.0",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "一款帮助考研学生学习的AI助手应用",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "支持数学公式渲染、智能择校、学习计划、文档管理等功能",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }

    // 登出确认对话框
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
            title = { Text("确认退出登录") },
            text = { Text("退出登录后需要重新登录才能使用应用") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        viewModel.logout()
                        onLogout()
                    }
                ) {
                    Text("退出登录")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 用户信息编辑对话框
    if (showUserInfoDialog) {
        UserInfoEditDialog(
            userInfo = uiState.currentUser,
            onDismiss = { showUserInfoDialog = false },
            onSave = { userInfo ->
                viewModel.updateUserInfo(userInfo)
                showUserInfoDialog = false
            }
        )
    }
}

/**
 * 用户信息卡片
 */
@Composable
private fun UserInfoCard(
    userInfo: UserInfo?,
    onEditClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "个人信息",
                    style = MaterialTheme.typography.titleMedium
                )
                Row {
                    IconButton(onClick = onEditClick) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑")
                    }
                    IconButton(onClick = onLogoutClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "退出登录",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (userInfo != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.padding(12.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = userInfo.nickname.ifBlank { userInfo.username },
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "@${userInfo.username}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 考研信息
                if (userInfo.targetSchool.isNotBlank() || userInfo.targetMajor.isNotBlank()) {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    if (userInfo.targetSchool.isNotBlank()) {
                        InfoRow(label = "目标院校", value = userInfo.targetSchool)
                    }
                    if (userInfo.targetMajor.isNotBlank()) {
                        InfoRow(label = "目标专业", value = userInfo.targetMajor)
                    }
                    if (userInfo.examYear > 0) {
                        InfoRow(label = "考研年份", value = "${userInfo.examYear}年")
                    }
                    if (userInfo.currentSchool.isNotBlank()) {
                        InfoRow(label = "本科院校", value = userInfo.currentSchool)
                    }
                }
            } else {
                Text(
                    text = "未登录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * 用户信息编辑对话框
 */
@Composable
private fun UserInfoEditDialog(
    userInfo: UserInfo?,
    onDismiss: () -> Unit,
    onSave: (UserInfo) -> Unit
) {
    var nickname by remember { mutableStateOf(userInfo?.nickname ?: "") }
    var targetSchool by remember { mutableStateOf(userInfo?.targetSchool ?: "") }
    var targetMajor by remember { mutableStateOf(userInfo?.targetMajor ?: "") }
    var examYear by remember { mutableStateOf(userInfo?.examYear?.toString() ?: "") }
    var currentSchool by remember { mutableStateOf(userInfo?.currentSchool ?: "") }
    var currentEducation by remember { mutableStateOf(userInfo?.currentEducation ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("编辑个人信息") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("昵称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = targetSchool,
                    onValueChange = { targetSchool = it },
                    label = { Text("目标院校") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("如：北京大学") }
                )

                OutlinedTextField(
                    value = targetMajor,
                    onValueChange = { targetMajor = it },
                    label = { Text("目标专业") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("如：计算机科学与技术") }
                )

                OutlinedTextField(
                    value = examYear,
                    onValueChange = { examYear = it.filter { c -> c.isDigit() } },
                    label = { Text("考研年份") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("如：2026") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                OutlinedTextField(
                    value = currentSchool,
                    onValueChange = { currentSchool = it },
                    label = { Text("本科院校") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = currentEducation,
                    onValueChange = { currentEducation = it },
                    label = { Text("当前学历") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("如：本科在读、本科毕业") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    userInfo?.let {
                        onSave(it.copy(
                            nickname = nickname,
                            targetSchool = targetSchool,
                            targetMajor = targetMajor,
                            examYear = examYear.toIntOrNull() ?: 0,
                            currentSchool = currentSchool,
                            currentEducation = currentEducation
                        ))
                    }
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

/**
 * API配置卡片
 */
@Composable
fun APIConfigCard(
    provider: AIProvider,
    config: APIConfig,
    providerName: String,
    onSave: (APIConfig) -> Unit,
    isSaving: Boolean
) {
    var apiUrl by remember(config) { mutableStateOf(config.apiUrl) }
    var apiKey by remember(config) { mutableStateOf(config.apiKey) }
    var model by remember(config) { mutableStateOf(config.model) }
    var showApiKey by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "$providerName 配置",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = apiUrl,
                onValueChange = { apiUrl = it },
                label = { Text("API URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(getDefaultApiUrl(provider)) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showApiKey)
                    VisualTransformation.None
                else
                    PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKey) "隐藏" else "显示"
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(getDefaultModel(provider)) },
                supportingText = { Text(getModelHint(provider)) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    onSave(config.copy(
                        apiUrl = apiUrl.ifBlank { getDefaultApiUrl(provider) },
                        apiKey = apiKey,
                        model = model.ifBlank { getDefaultModel(provider) },
                        enabled = apiKey.isNotBlank()
                    ))
                },
                modifier = Modifier.align(Alignment.End),
                enabled = !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("保存配置")
            }
        }
    }
}

private fun getProviderDescription(provider: AIProvider): String {
    return when (provider) {
        AIProvider.OpenAI -> "GPT-4, GPT-3.5等模型"
        AIProvider.Claude -> "Claude 3 Opus, Sonnet等模型"
        AIProvider.DeepSeek -> "DeepSeek Chat, DeepSeek Coder"
        AIProvider.Qwen -> "通义千问系列模型"
        AIProvider.Doubao -> "豆包系列模型"
        AIProvider.Custom -> "自定义OpenAI兼容API"
    }
}

private fun getDefaultApiUrl(provider: AIProvider): String {
    return when (provider) {
        AIProvider.OpenAI -> "https://api.openai.com/v1/chat/completions"
        AIProvider.Claude -> "https://api.anthropic.com/v1/messages"
        AIProvider.DeepSeek -> "https://api.deepseek.com/v1/chat/completions"
        AIProvider.Qwen -> "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation"
        AIProvider.Doubao -> "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
        AIProvider.Custom -> ""
    }
}

private fun getDefaultModel(provider: AIProvider): String {
    return when (provider) {
        AIProvider.OpenAI -> "gpt-4"
        AIProvider.Claude -> "claude-3-opus-20240229"
        AIProvider.DeepSeek -> "deepseek-chat"
        AIProvider.Qwen -> "qwen-turbo"
        AIProvider.Doubao -> "doubao-pro-32k"
        AIProvider.Custom -> ""
    }
}

private fun getModelHint(provider: AIProvider): String {
    return when (provider) {
        AIProvider.OpenAI -> "可选: gpt-4, gpt-4-turbo, gpt-3.5-turbo"
        AIProvider.Claude -> "可选: claude-3-opus, claude-3-sonnet, claude-3-haiku"
        AIProvider.DeepSeek -> "可选: deepseek-chat, deepseek-reasoner"
        AIProvider.Qwen -> "可选: qwen-turbo, qwen-plus, qwen-max"
        AIProvider.Doubao -> "可选: doubao-pro-32k, doubao-lite-4k"
        AIProvider.Custom -> "输入自定义模型名称"
    }
}
