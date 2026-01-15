package com.example.kaoyanassistant.ui.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 登录界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onLoginSuccess: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    // 登录成功后跳转
    LaunchedEffect(uiState.loginSuccess) {
        if (uiState.loginSuccess) {
            onLoginSuccess()
        }
    }

    // 错误提示
    uiState.error?.let { error ->
        LaunchedEffect(error) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearError()
        }
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // Logo/标题
            Icon(
                imageVector = Icons.Default.School,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "考研AI助手",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = if (uiState.isRegisterMode) "创建新账号" else "欢迎回来",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(48.dp))

            // 错误提示
            AnimatedVisibility(visible = uiState.error != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = uiState.error ?: "",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // 用户名输入
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 注册模式下显示昵称
            AnimatedVisibility(visible = uiState.isRegisterMode) {
                Column {
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it },
                        label = { Text("昵称（可选）") },
                        leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // 密码输入
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "隐藏密码" else "显示密码"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (uiState.isRegisterMode) ImeAction.Next else ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    onDone = {
                        if (!uiState.isRegisterMode && username.isNotBlank() && password.isNotBlank()) {
                            viewModel.login(username, password)
                        }
                    }
                )
            )

            // 注册模式下显示确认密码
            AnimatedVisibility(visible = uiState.isRegisterMode) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("确认密码") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (username.isNotBlank() && password.isNotBlank() && confirmPassword.isNotBlank()) {
                                    viewModel.register(username, password, confirmPassword, nickname)
                                }
                            }
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 登录/注册按钮
            Button(
                onClick = {
                    if (uiState.isRegisterMode) {
                        viewModel.register(username, password, confirmPassword, nickname)
                    } else {
                        viewModel.login(username, password)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = !uiState.isLoading && username.isNotBlank() && password.isNotBlank() &&
                        (!uiState.isRegisterMode || confirmPassword.isNotBlank())
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(if (uiState.isRegisterMode) "注册" else "登录")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 切换登录/注册
            TextButton(
                onClick = {
                    viewModel.toggleMode()
                    // 清空输入
                    confirmPassword = ""
                    nickname = ""
                }
            ) {
                Text(
                    if (uiState.isRegisterMode) "已有账号？点击登录" else "没有账号？点击注册"
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 功能介绍
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "功能特色",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FeatureItem(icon = Icons.Default.SmartToy, text = "AI智能问答，支持数学公式")
                    FeatureItem(icon = Icons.Default.School, text = "智能择校分析，考试科目查询")
                    FeatureItem(icon = Icons.Default.CalendarMonth, text = "个性化学习计划，日历提醒")
                    FeatureItem(icon = Icons.Default.Description, text = "考研资料管理，智能分析")
                }
            }
        }
    }
}

@Composable
private fun FeatureItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
