package com.example.kaoyanassistant.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.kaoyanassistant.core.EmbeddingMode
import com.example.kaoyanassistant.utils.DeviceUtils
import java.util.Locale

private const val OPENROUTER_EMBEDDING_URL = "https://openrouter.ai/api/v1/embeddings"
private const val DEFAULT_EMBEDDING_MODEL = "text-embedding-3-large"
private const val LOCAL_MODEL_LABEL = "Qwen3-Embedding-4B (Q4_K_M)"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbeddingConfigScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val config = uiState.embeddingConfig
    var apiUrl by remember(config) { mutableStateOf(config.apiUrl) }
    var apiKey by remember(config) { mutableStateOf(config.apiKey) }
    var model by remember(config) { mutableStateOf(config.model) }
    var showApiKey by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val totalMemBytes = remember { DeviceUtils.getTotalMemoryBytes(context) }
    val enoughMemory = remember { DeviceUtils.hasEnoughMemory(context) }
    val isArm64 = remember { DeviceUtils.isArm64Device() }
    val localSupported = enoughMemory && isArm64
    val totalMemGb = remember {
        totalMemBytes / 1024.0 / 1024.0 / 1024.0
    }

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            kotlinx.coroutines.delay(2000)
            viewModel.clearSaveSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("向量模型配置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = {
            if (uiState.saveSuccess) {
                Snackbar(modifier = Modifier.padding(16.dp)) {
                    Text("保存成功")
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "向量模型模式",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = uiState.embeddingMode == EmbeddingMode.LocalPreferred,
                            onClick = { viewModel.setEmbeddingMode(EmbeddingMode.LocalPreferred) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("本地优先")
                            Text(
                                text = "优先使用本地模型，失败自动回退远程",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = uiState.embeddingMode == EmbeddingMode.RemoteOnly,
                            onClick = { viewModel.setEmbeddingMode(EmbeddingMode.RemoteOnly) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("仅远程")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "本地模型：$LOCAL_MODEL_LABEL",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "模型通过资源包下载，首次使用会复制到私有目录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "设备内存约 ${String.format(Locale.US, "%.1f", totalMemGb)}GB，最低要求 8GB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!localSupported) {
                        Text(
                            text = "当前设备不满足本地推理条件，将自动使用远程向量模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "向量模型设置",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "预设",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedButton(onClick = {
                            apiUrl = OPENROUTER_EMBEDDING_URL
                            model = DEFAULT_EMBEDDING_MODEL
                        }) {
                            Text("OpenRouter")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = apiUrl,
                        onValueChange = { apiUrl = it },
                        label = { Text("API URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(OPENROUTER_EMBEDDING_URL) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showApiKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
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
                        placeholder = { Text(DEFAULT_EMBEDDING_MODEL) },
                        supportingText = { Text("可选: text-embedding-3-large, text-embedding-3-small") }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            viewModel.updateEmbeddingConfig(
                                config.copy(
                                    apiUrl = apiUrl.ifBlank { OPENROUTER_EMBEDDING_URL },
                                    apiKey = apiKey,
                                    model = model.ifBlank { DEFAULT_EMBEDDING_MODEL }
                                )
                            )
                        },
                        modifier = Modifier.align(Alignment.End),
                        enabled = !uiState.isSaving
                    ) {
                        if (uiState.isSaving) {
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

            Text(
                text = "用于文档向量化与检索，需与RAG索引服务配合使用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
