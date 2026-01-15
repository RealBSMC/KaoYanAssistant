package com.example.kaoyanassistant.ui.document

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.kaoyanassistant.services.DocumentInfo
import com.example.kaoyanassistant.services.DocumentType
import java.text.SimpleDateFormat
import java.util.*

/**
 * 文档管理界面
 * 对应Qt版本的DocumentPanel
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    viewModel: DocumentViewModel,
    onNavigateBack: () -> Unit,
    onDocumentsSelected: (List<String>) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            // 获取真实路径并导入
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val fileName = uri.lastPathSegment ?: "unknown"
                val tempFile = java.io.File(context.cacheDir, fileName)
                tempFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                viewModel.importDocument(tempFile.absolutePath)
            }
        }
    }

    var showCategoryDialog by remember { mutableStateOf(false) }
    var selectedDocForCategory by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文档管理") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 全选按钮
                    IconButton(onClick = { viewModel.toggleSelectAll() }) {
                        Icon(
                            if (uiState.selectedDocumentIds.size == uiState.documents.size && uiState.documents.isNotEmpty())
                                Icons.Default.CheckBox
                            else
                                Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = "全选"
                        )
                    }

                    // 确认选择按钮
                    if (uiState.selectedDocumentIds.isNotEmpty()) {
                        TextButton(onClick = {
                            onDocumentsSelected(viewModel.getSelectedDocumentIds())
                            onNavigateBack()
                        }) {
                            Text("确认(${uiState.selectedDocumentIds.size})")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    filePickerLauncher.launch(arrayOf(
                        "text/plain",
                        "text/markdown",
                        "application/pdf",
                        "application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    ))
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加文档")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 搜索栏
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索文档...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "清除")
                        }
                    }
                },
                singleLine = true
            )

            // 分类筛选
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = uiState.selectedCategory == null,
                        onClick = { viewModel.setSelectedCategory(null) },
                        label = { Text("全部") }
                    )
                }
                items(uiState.categories) { category ->
                    FilterChip(
                        selected = uiState.selectedCategory == category,
                        onClick = { viewModel.setSelectedCategory(category) },
                        label = { Text(category) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 文档列表
            if (uiState.documents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "暂无文档",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "点击右下角按钮添加考研资料",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.documents, key = { it.id }) { document ->
                        DocumentItem(
                            document = document,
                            isSelected = document.id in uiState.selectedDocumentIds,
                            onToggleSelection = { viewModel.toggleDocumentSelection(document.id) },
                            onDelete = { viewModel.removeDocument(document.id) },
                            onChangeCategory = {
                                selectedDocForCategory = document.id
                                showCategoryDialog = true
                            }
                        )
                    }
                }
            }
        }
    }

    // 分类选择对话框
    if (showCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false },
            title = { Text("选择分类") },
            text = {
                Column {
                    uiState.categories.forEach { category ->
                        TextButton(
                            onClick = {
                                // TODO: 实现修改分类功能
                                showCategoryDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(category)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCategoryDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 文档列表项
 */
@Composable
fun DocumentItem(
    document: DocumentInfo,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit,
    onChangeCategory: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleSelection() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 选择框
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() }
            )

            // 文档图标
            Icon(
                imageVector = when (document.type) {
                    DocumentType.PlainText -> Icons.Default.Description
                    DocumentType.Markdown -> Icons.Default.Article
                    DocumentType.PDF -> Icons.Default.PictureAsPdf
                    DocumentType.Word -> Icons.Default.Description
                    DocumentType.Image -> Icons.Default.Image
                    DocumentType.Unknown -> Icons.Default.InsertDriveFile
                },
                contentDescription = null,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            // 文档信息
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = document.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = onChangeCategory,
                        label = { Text(document.category, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(24.dp)
                    )
                    Text(
                        text = dateFormat.format(Date(document.importTime)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // 更多操作
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null)
                        }
                    )
                }
            }
        }
    }
}
