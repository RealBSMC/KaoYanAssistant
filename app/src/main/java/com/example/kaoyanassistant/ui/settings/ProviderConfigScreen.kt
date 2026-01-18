package com.example.kaoyanassistant.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.kaoyanassistant.core.AIProvider
import com.example.kaoyanassistant.core.APIConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderConfigScreen(
    viewModel: SettingsViewModel,
    target: String,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    val isCustomReasoning = target == ProviderConfigTargets.CustomReasoning
    val isCustomVision = target == ProviderConfigTargets.CustomVision
    val provider = if (!isCustomReasoning && !isCustomVision) {
        runCatching { AIProvider.valueOf(target) }.getOrNull()
    } else {
        null
    }

    val config = when {
        isCustomReasoning -> uiState.multimodalReasoningCustomConfig
        isCustomVision -> uiState.multimodalVisionCustomConfig
        provider != null -> uiState.apiConfigs[provider] ?: APIConfig()
        else -> null
    }

    val providerForCard = when {
        isCustomReasoning || isCustomVision -> AIProvider.Custom
        provider != null -> provider
        else -> null
    }

    val title = when {
        isCustomReasoning -> "推理自定义"
        isCustomVision -> "图像自定义"
        provider != null -> viewModel.getProviderDisplayName(provider)
        else -> "未知配置"
    }

    val onSave: (APIConfig) -> Unit = { newConfig ->
        when {
            isCustomReasoning -> viewModel.updateMultimodalReasoningCustomConfig(newConfig)
            isCustomVision -> viewModel.updateMultimodalVisionCustomConfig(newConfig)
            provider != null -> viewModel.updateAPIConfig(provider, newConfig)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (config == null || providerForCard == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("未找到对应的配置")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                APIConfigCard(
                    provider = providerForCard,
                    config = config,
                    providerName = title,
                    onSave = onSave,
                    isSaving = uiState.isSaving
                )
            }

            item {
                AdvancedSettingsCard(
                    config = config,
                    onSave = onSave,
                    isSaving = uiState.isSaving
                )
            }
        }
    }
}

@Composable
private fun AdvancedSettingsCard(
    config: APIConfig,
    onSave: (APIConfig) -> Unit,
    isSaving: Boolean
) {
    var maxTokens by remember(config) { mutableStateOf(config.maxContextTokens.toString()) }
    var keepRecent by remember(config) { mutableStateOf(config.keepRecentMessages.toString()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "高级设置",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))

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
                    onSave(config.copy(
                        maxContextTokens = maxTokens.toIntOrNull() ?: 8000,
                        keepRecentMessages = keepRecent.toIntOrNull() ?: 10
                    ))
                },
                modifier = Modifier.align(Alignment.End),
                enabled = !isSaving
            ) {
                Text("保存高级设置")
            }
        }
    }
}
