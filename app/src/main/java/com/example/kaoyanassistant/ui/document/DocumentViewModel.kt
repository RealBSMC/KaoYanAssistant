package com.example.kaoyanassistant.ui.document

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.kaoyanassistant.services.DocumentInfo
import com.example.kaoyanassistant.services.DocumentManager
import com.example.kaoyanassistant.services.DocumentType
import com.example.kaoyanassistant.services.IndexDocumentWorker
import com.example.kaoyanassistant.services.RagIndexManager
import com.example.kaoyanassistant.services.RagIndexProgress
import com.example.kaoyanassistant.services.RagStage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 文档界面UI状态
 */
data class DocumentUiState(
    val documents: List<DocumentInfo> = emptyList(),
    val categories: List<String> = emptyList(),
    val selectedCategory: String? = null,
    val searchQuery: String = "",
    val selectedDocumentIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val ragIndexing: RagIndexUiState? = null,
    val lastIndexSummary: String? = null
)

data class RagIndexUiState(
    val documentId: String,
    val documentName: String,
    val stage: RagStage,
    val current: Int = 0,
    val total: Int = 0,
    val message: String = "",
    val processedTokens: Int = 0,
    val estimatedTokens: Int = 0
)

/**
 * 文档管理ViewModel
 * 对应Qt版本的DocumentPanel逻辑
 */
class DocumentViewModel(
    private val appContext: Context,
    private val documentManager: DocumentManager,
    private val ragIndexManager: RagIndexManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentUiState())
    val uiState: StateFlow<DocumentUiState> = _uiState.asStateFlow()
    private val workManager = WorkManager.getInstance(appContext)
    private val indexObservers = mutableMapOf<String, Job>()

    init {
        // 监听文档变化
        viewModelScope.launch {
            documentManager.documentsFlow.collect { documents ->
                updateFilteredDocuments()
            }
        }

        // 监听分类变化
        viewModelScope.launch {
            documentManager.categoriesFlow.collect { categories ->
                _uiState.update { it.copy(categories = categories) }
            }
        }

        // 初始化
        _uiState.update {
            it.copy(
                documents = documentManager.getAllDocuments(),
                categories = documentManager.getCategories()
            )
        }
    }

    /**
     * 导入文档
     */
    fun importDocument(filePath: String, category: String = "其他") {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val docInfo = documentManager.importDocument(filePath, category)
            if (docInfo == null) {
                _uiState.update { it.copy(error = "导入文档失败") }
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            _uiState.update { it.copy(isLoading = false) }
            updateFilteredDocuments()
            buildRagIndex(docInfo)
        }
    }

    /**
     * 删除文档
     */
    fun removeDocument(id: String) {
        viewModelScope.launch {
            workManager.cancelUniqueWork(IndexDocumentWorker.uniqueWorkName(id))
            documentManager.removeDocument(id)
            ragIndexManager.removeIndex(id)
            _uiState.update { currentState ->
                currentState.copy(
                    selectedDocumentIds = currentState.selectedDocumentIds - id
                )
            }
            updateFilteredDocuments()
        }
    }

    /**
     * 切换文档选中状态
     */
    fun toggleDocumentSelection(id: String) {
        _uiState.update { currentState ->
            val newSelection = if (id in currentState.selectedDocumentIds) {
                currentState.selectedDocumentIds - id
            } else {
                currentState.selectedDocumentIds + id
            }
            currentState.copy(selectedDocumentIds = newSelection)
        }
    }

    /**
     * 全选/取消全选
     */
    fun toggleSelectAll() {
        _uiState.update { currentState ->
            val allIds = currentState.documents.map { it.id }.toSet()
            val newSelection = if (currentState.selectedDocumentIds == allIds) {
                emptySet()
            } else {
                allIds
            }
            currentState.copy(selectedDocumentIds = newSelection)
        }
    }

    /**
     * 清空选择
     */
    fun clearSelection() {
        _uiState.update { it.copy(selectedDocumentIds = emptySet()) }
    }

    /**
     * 设置搜索关键词
     */
    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        updateFilteredDocuments()
    }

    /**
     * 设置分类筛选
     */
    fun setSelectedCategory(category: String?) {
        _uiState.update { it.copy(selectedCategory = category) }
        updateFilteredDocuments()
    }

    /**
     * 更新筛选后的文档列表
     */
    private fun updateFilteredDocuments() {
        val allDocuments = documentManager.getAllDocuments()
        val filtered = allDocuments.filter { doc ->
            val matchesCategory = _uiState.value.selectedCategory?.let {
                doc.category == it
            } ?: true

            val matchesSearch = _uiState.value.searchQuery.let { query ->
                if (query.isBlank()) true
                else doc.name.contains(query, ignoreCase = true) ||
                        doc.content.contains(query, ignoreCase = true)
            }

            matchesCategory && matchesSearch
        }

        _uiState.update { it.copy(documents = filtered) }
    }

    /**
     * 获取选中的文档ID列表
     */
    fun getSelectedDocumentIds(): List<String> {
        return _uiState.value.selectedDocumentIds.toList()
    }

    /**
     * 清除错误
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearIndexSummary() {
        _uiState.update { it.copy(lastIndexSummary = null) }
    }

    private suspend fun buildRagIndex(docInfo: DocumentInfo) {
        _uiState.update {
            it.copy(
                ragIndexing = RagIndexUiState(
                    documentId = docInfo.id,
                    documentName = docInfo.name,
                    stage = RagStage.Preparing,
                    message = "准备索引..."
                ),
                error = null,
                lastIndexSummary = null
            )
        }

        enqueueIndexWork(docInfo)
    }

    private fun enqueueIndexWork(docInfo: DocumentInfo) {
        val constraints = Constraints.Builder().apply {
            if (docInfo.type == DocumentType.PDF || docInfo.type == DocumentType.Image) {
                setRequiredNetworkType(NetworkType.CONNECTED)
            }
        }.build()

        val request = OneTimeWorkRequestBuilder<IndexDocumentWorker>()
            .setInputData(workDataOf(IndexDocumentWorker.KeyDocId to docInfo.id))
            .setConstraints(constraints)
            .addTag(IndexDocumentWorker.WorkTag)
            .build()

        val workName = IndexDocumentWorker.uniqueWorkName(docInfo.id)
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
        observeIndexWork(docInfo, workName)
    }

    private fun observeIndexWork(docInfo: DocumentInfo, workName: String) {
        indexObservers[docInfo.id]?.cancel()
        indexObservers[docInfo.id] = viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(workName).collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                val progress = info.progress
                val stage = progress.getString(IndexDocumentWorker.KeyStage)
                    ?.let { runCatching { RagStage.valueOf(it) }.getOrNull() }
                    ?: RagStage.Preparing
                val current = progress.getInt(IndexDocumentWorker.KeyCurrent, 0)
                val total = progress.getInt(IndexDocumentWorker.KeyTotal, 0)
                val processedTokens = progress.getInt(IndexDocumentWorker.KeyProcessedTokens, 0)
                val estimatedTokens = progress.getInt(IndexDocumentWorker.KeyEstimatedTokens, 0)
                val message = progress.getString(IndexDocumentWorker.KeyMessage).orEmpty()
                val displayMessage = if (message.isNotBlank()) {
                    message
                } else if (info.state == WorkInfo.State.ENQUEUED) {
                    "等待系统调度..."
                } else {
                    "处理中..."
                }

                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> {
                        updateIndexProgress(docInfo, RagIndexProgress(
                            stage = stage,
                            current = current,
                            total = total,
                            message = displayMessage,
                            processedTokens = processedTokens,
                            estimatedTokens = estimatedTokens
                        ))
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val chunkCount = info.outputData.getInt(IndexDocumentWorker.KeyChunkCount, 0)
                        val totalTokens = info.outputData.getInt(IndexDocumentWorker.KeyEstimatedTokens, 0)
                        _uiState.update {
                            it.copy(
                                ragIndexing = null,
                                lastIndexSummary = if (chunkCount > 0) {
                                    "索引完成，约消耗 $totalTokens tokens"
                                } else {
                                    "索引完成，但未生成可用内容"
                                }
                            )
                        }
                        indexObservers.remove(docInfo.id)?.cancel()
                    }
                    WorkInfo.State.FAILED -> {
                        val error = info.outputData.getString(IndexDocumentWorker.KeyError)
                            ?: "索引失败，请检查OCR与向量配置"
                        _uiState.update {
                            it.copy(
                                ragIndexing = null,
                                error = error,
                                lastIndexSummary = null
                            )
                        }
                        indexObservers.remove(docInfo.id)?.cancel()
                    }
                    WorkInfo.State.CANCELLED -> {
                        _uiState.update {
                            it.copy(
                                ragIndexing = null,
                                error = "索引任务已取消",
                                lastIndexSummary = null
                            )
                        }
                        indexObservers.remove(docInfo.id)?.cancel()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun updateIndexProgress(docInfo: DocumentInfo, progress: RagIndexProgress) {
        _uiState.update {
            it.copy(
                ragIndexing = RagIndexUiState(
                    documentId = docInfo.id,
                    documentName = docInfo.name,
                    stage = progress.stage,
                    current = progress.current,
                    total = progress.total,
                    message = progress.message,
                    processedTokens = progress.processedTokens,
                    estimatedTokens = progress.estimatedTokens
                )
            )
        }
    }

    override fun onCleared() {
        indexObservers.values.forEach { it.cancel() }
        indexObservers.clear()
        super.onCleared()
    }

    class Factory(
        private val appContext: Context,
        private val documentManager: DocumentManager,
        private val ragIndexManager: RagIndexManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DocumentViewModel(appContext, documentManager, ragIndexManager) as T
        }
    }

}
