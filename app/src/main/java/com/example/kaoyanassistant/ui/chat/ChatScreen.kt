package com.example.kaoyanassistant.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.kaoyanassistant.services.MessageRole
import com.example.kaoyanassistant.ui.components.MarkdownMathView
import java.io.File

/**
 * 聊天界面
 * 对应Qt版本的ChatWidget
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToDocuments: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    var inputText by remember { mutableStateOf("") }
    var showImageMenu by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.sendImageMessage(uri)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            viewModel.sendImageMessage(uri)
        }
        pendingCameraUri = null
    }

    val launchCamera = {
        val imagesDir = File(context.cacheDir, "camera").apply { mkdirs() }
        val imageFile = File.createTempFile("chat_", ".jpg", imagesDir)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )
        pendingCameraUri = uri
        cameraLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            viewModel.showError("需要相机权限才能拍照")
        }
    }

    // 自动滚动到底部
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.content) {
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
                title = { Text("考研AI助手") },
                actions = {
                    // 上下文使用进度
                    if (uiState.contextUsageRatio > 0) {
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .width(60.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { uiState.contextUsageRatio.toFloat() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = if (uiState.contextUsageRatio > 0.8)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    IconButton(onClick = onNavigateToDocuments) {
                        BadgedBox(
                            badge = {
                                if (uiState.selectedDocumentIds.isNotEmpty()) {
                                    Badge {
                                        Text("${uiState.selectedDocumentIds.size}")
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Description, contentDescription = "文档")
                        }
                    }

                    IconButton(onClick = { viewModel.clearConversation() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "清空对话")
                    }

                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
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
            uiState.error?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
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

            // 消息列表
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = uiState.messages,
                    key = { "${it.id}_${it.isStreaming}" }  // 使用复合key，streaming状态变化时重建
                ) { message ->
                    MessageBubble(message = message)
                }

                // 加载指示器
                if (uiState.isLoading && uiState.messages.lastOrNull()?.isStreaming != true) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .size(24.dp)
                            )
                        }
                    }
                }
            }

            // 输入区域
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom)),
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Box {
                        IconButton(
                            onClick = { showImageMenu = true },
                            enabled = !uiState.isLoading
                        ) {
                            Icon(
                                Icons.Default.AddPhotoAlternate,
                                contentDescription = "上传图片"
                            )
                        }
                        DropdownMenu(
                            expanded = showImageMenu,
                            onDismissRequest = { showImageMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("从图库选择") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.PhotoLibrary,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showImageMenu = false
                                    galleryLauncher.launch("image/*")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("拍照") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.PhotoCamera,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showImageMenu = false
                                    val hasPermission = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.CAMERA
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasPermission) {
                                        launchCamera()
                                    } else {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入问题...") },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (inputText.isNotBlank() && !uiState.isLoading) {
                                    viewModel.sendMessage(inputText)
                                    inputText = ""
                                }
                            }
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // 发送/取消按钮
                    if (uiState.isLoading) {
                        IconButton(
                            onClick = { viewModel.cancelRequest() }
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "取消",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    viewModel.sendMessage(inputText)
                                    inputText = ""
                                }
                            },
                            enabled = inputText.isNotBlank()
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                tint = if (inputText.isNotBlank())
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

/**
 * 消息气泡组件
 * 对应Qt版本的MessageBubble
 */
@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == MessageRole.User

    val bubbleColor = if (isUser)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    val textColor = if (isUser)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            // AI头像
            Surface(
                modifier = Modifier
                    .size(36.dp)
                    .padding(end = 8.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .widthIn(max = 320.dp),
            shape = RoundedCornerShape(
                topStart = if (isUser) 16.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp
            ),
            color = bubbleColor
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                when {
                    message.imageUri != null -> {
                        AsyncImage(
                            model = message.imageUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 240.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        if (message.content.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = message.content,
                                color = textColor,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    message.isStreaming -> {
                        // 流式响应期间使用纯文本并显示光标
                        val streamingText = if (message.content.isEmpty()) "▌" else "${message.content}▌"
                        Text(
                            text = streamingText,
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    message.content.isEmpty() -> {
                        // 空内容，显示占位
                        Text(
                            text = "...",
                            color = textColor.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    isUser -> {
                        // 用户消息使用简单文本
                        Text(
                            text = message.content,
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    else -> {
                        // AI消息完成后使用 MathJax 渲染（支持数学公式）
                        // 使用 key 确保内容变化时重新创建
                        key(message.id, message.content.hashCode()) {
                            MarkdownMathView(
                                content = message.content,
                                textColor = textColor,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // 流式响应指示器
                if (message.isStreaming) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "正在输入...",
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        if (isUser) {
            // 用户头像
            Surface(
                modifier = Modifier
                    .size(36.dp)
                    .padding(start = 8.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.secondary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
