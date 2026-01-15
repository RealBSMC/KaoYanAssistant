package com.example.kaoyanassistant.ui.document

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kaoyanassistant.services.DocumentInfo
import com.example.kaoyanassistant.services.DocumentManager
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
    val error: String? = null
)

/**
 * 文档管理ViewModel
 * 对应Qt版本的DocumentPanel逻辑
 */
class DocumentViewModel(
    private val documentManager: DocumentManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentUiState())
    val uiState: StateFlow<DocumentUiState> = _uiState.asStateFlow()

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
            val success = documentManager.importDocument(filePath, category)
            if (!success) {
                _uiState.update { it.copy(error = "导入文档失败") }
            }
            _uiState.update { it.copy(isLoading = false) }
            updateFilteredDocuments()
        }
    }

    /**
     * 删除文档
     */
    fun removeDocument(id: String) {
        viewModelScope.launch {
            documentManager.removeDocument(id)
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

    class Factory(
        private val documentManager: DocumentManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DocumentViewModel(documentManager) as T
        }
    }
}
