package com.example.kaoyanassistant.ui.school

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.kaoyanassistant.services.MessageRole
import com.example.kaoyanassistant.ui.components.MarkdownMathView
import kotlinx.coroutines.launch

/**
 * 择校界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchoolSelectionScreen(
    viewModel: SchoolSelectionViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // 自动滚动到底部
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

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
                title = { Text("智能择校") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearChat() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "清空对话")
                    }
                }
            )
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

            // 消息列表
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.messages, key = { it.id }) { message ->
                    ChatMessageItem(message = message)
                }

                // 加载指示器
                if (uiState.isLoading && uiState.messages.lastOrNull()?.isStreaming != true) {
                    item {
                        LoadingIndicator()
                    }
                }
            }

            // 快捷查询按钮
            if (uiState.messages.size <= 1) {
                QuickQueryButtons(
                    queries = uiState.quickQueries,
                    onQueryClick = { query ->
                        viewModel.updateInputText(query.query)
                    }
                )
            }

            // 输入区域
            InputArea(
                inputText = uiState.inputText,
                isLoading = uiState.isLoading,
                onInputChange = { viewModel.updateInputText(it) },
                onSend = {
                    viewModel.sendMessage(uiState.inputText)
                    focusManager.clearFocus()
                },
                onCancel = { viewModel.cancelRequest() }
            )
        }
    }
}

/**
 * 聊天消息项
 */
@Composable
private fun ChatMessageItem(message: SchoolChatMessage) {
    val isUser = message.role == MessageRole.User

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            // AI头像
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    imageVector = Icons.Default.School,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        // 消息气泡
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
                    if (message.isStreaming) {
                        val streamingText = if (message.content.isEmpty()) "▌" else "${message.content}▌"
                        Text(
                            text = streamingText,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        key(message.id, message.content.hashCode()) {
                            MarkdownMathView(
                                content = message.content,
                                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            // 用户头像
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

/**
 * 快捷查询按钮
 */
@Composable
private fun QuickQueryButtons(
    queries: List<QuickQuery>,
    onQueryClick: (QuickQuery) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "快捷查询",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(queries) { query ->
                SuggestionChip(
                    onClick = { onQueryClick(query) },
                    label = { Text(query.title) },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
        }
    }
}

/**
 * 输入区域
 */
@Composable
private fun InputArea(
    inputText: String,
    isLoading: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom)),
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // 输入框
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入院校、专业或问题...") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (inputText.isNotBlank() && !isLoading) {
                            onSend()
                        }
                    }
                ),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 发送/取消按钮
            if (isLoading) {
                FilledIconButton(
                    onClick = onCancel,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "取消")
                }
            } else {
                FilledIconButton(
                    onClick = onSend,
                    enabled = inputText.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

/**
 * 加载指示器
 */
@Composable
private fun LoadingIndicator() {
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
                imageVector = Icons.Default.School,
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
                    text = "正在查询...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
