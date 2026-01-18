package com.example.kaoyanassistant.services

import android.content.Context
import com.example.kaoyanassistant.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 文档类型
 * 对应Qt版本的DocumentType枚举
 */
enum class DocumentType {
    PlainText,  // .txt
    Markdown,   // .md
    PDF,        // .pdf
    Word,       // .docx
    Image,      // 图片
    Unknown
}

/**
 * 文档信息数据类
 * 对应Qt版本的DocumentInfo结构体
 */
@Serializable
data class DocumentInfo(
    val id: String,
    val name: String,
    val path: String,
    val type: DocumentType,
    val content: String,
    val importTime: Long,
    val size: Long,
    val category: String
)

/**
 * 文档管理器 - 管理用户上传的考研资料
 * 支持多种文档格式的导入和内容提取
 * 对应Qt版本的DocumentManager类
 */
class DocumentManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: DocumentManager? = null

        // 支持的文件格式
        private val SUPPORTED_EXTENSIONS = listOf("txt", "md", "pdf", "docx", "doc", "png", "jpg", "jpeg")

        // 默认分类
        private val DEFAULT_CATEGORIES = listOf("数学", "英语", "政治", "专业课", "其他")

        // 限制文本读取长度，避免超大文档导致内存占用过高
        private const val MAX_TEXT_CHARS = 200_000

        // 索引文件过大时跳过加载，防止启动时内存溢出
        private const val MAX_INDEX_FILE_BYTES = 5L * 1024 * 1024

        fun getInstance(context: Context): DocumentManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DocumentManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun supportedFormats(): List<String> = SUPPORTED_EXTENSIONS

        fun getFileFilter(): String {
            return SUPPORTED_EXTENSIONS.joinToString(", ") { "*.$it" }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val documents = mutableMapOf<String, DocumentInfo>()
    private val categories = DEFAULT_CATEGORIES.toMutableList()

    private val _documentsFlow = MutableStateFlow<List<DocumentInfo>>(emptyList())
    val documentsFlow: StateFlow<List<DocumentInfo>> = _documentsFlow.asStateFlow()

    private val _categoriesFlow = MutableStateFlow<List<String>>(DEFAULT_CATEGORIES)
    val categoriesFlow: StateFlow<List<String>> = _categoriesFlow.asStateFlow()

    private val indexFile: File
        get() = File(context.filesDir, "documents_index.json")

    private val documentsDir: File
        get() = File(context.filesDir, "documents").also { it.mkdirs() }

    init {
        loadIndex()
    }

    /**
     * 导入文档
     */
    suspend fun importDocument(filePath: String, category: String = "其他"): DocumentInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val sourceFile = File(filePath)
                if (!sourceFile.exists()) {
                    Logger.error("DocumentManager", "文件不存在: $filePath")
                    return@withContext null
                }

                val id = generateId()
                val type = detectType(filePath)
                val content = extractContent(sourceFile, type)

                // 复制文件到应用目录
                val destFile = File(documentsDir, "${id}_${sourceFile.name}")
                sourceFile.copyTo(destFile, overwrite = true)

                val docInfo = DocumentInfo(
                    id = id,
                    name = sourceFile.name,
                    path = destFile.absolutePath,
                    type = type,
                    content = content,
                    importTime = System.currentTimeMillis(),
                    size = sourceFile.length(),
                    category = category
                )

                documents[id] = docInfo
                updateDocumentsFlow()
                saveIndex()

                Logger.info("DocumentManager", "文档导入成功: ${sourceFile.name}")
                docInfo
            } catch (e: Exception) {
                Logger.error("DocumentManager", "导入文档失败: ${e.message}")
                null
            }
        }
    }

    /**
     * 批量导入文档
     */
    suspend fun importDocuments(filePaths: List<String>, category: String = "其他"): List<DocumentInfo> {
        val imported = mutableListOf<DocumentInfo>()
        filePaths.forEach { path ->
            val doc = importDocument(path, category)
            if (doc != null) {
                imported.add(doc)
            }
        }
        return imported
    }

    /**
     * 获取所有文档
     */
    fun getAllDocuments(): List<DocumentInfo> {
        return documents.values.toList()
    }

    /**
     * 按分类获取文档
     */
    fun getDocumentsByCategory(category: String): List<DocumentInfo> {
        return documents.values.filter { it.category == category }
    }

    /**
     * 获取单个文档
     */
    fun getDocument(id: String): DocumentInfo? {
        return documents[id]
    }

    /**
     * 删除文档
     */
    suspend fun removeDocument(id: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val doc = documents[id] ?: return@withContext false

                // 删除文件
                File(doc.path).delete()

                documents.remove(id)
                updateDocumentsFlow()
                saveIndex()

                Logger.info("DocumentManager", "文档删除成功: ${doc.name}")
                true
            } catch (e: Exception) {
                Logger.error("DocumentManager", "删除文档失败: ${e.message}")
                false
            }
        }
    }

    /**
     * 清空所有文档
     */
    suspend fun clearAllDocuments() {
        withContext(Dispatchers.IO) {
            documents.values.forEach { doc ->
                File(doc.path).delete()
            }
            documents.clear()
            updateDocumentsFlow()
            saveIndex()
            Logger.info("DocumentManager", "已清空所有文档")
        }
    }

    /**
     * 获取文档内容
     */
    fun getDocumentContent(id: String): String {
        return documents[id]?.content ?: ""
    }

    /**
     * 获取选中文档的内容列表
     */
    fun getSelectedDocumentsContent(ids: List<String>): List<String> {
        return ids.mapNotNull { documents[it]?.content }
    }

    /**
     * 获取所有分类
     */
    fun getCategories(): List<String> = categories.toList()

    /**
     * 添加分类
     */
    fun addCategory(category: String) {
        if (category !in categories) {
            categories.add(category)
            _categoriesFlow.value = categories.toList()
        }
    }

    /**
     * 删除分类
     */
    fun removeCategory(category: String) {
        if (category !in DEFAULT_CATEGORIES) {
            categories.remove(category)
            _categoriesFlow.value = categories.toList()
        }
    }

    /**
     * 搜索文档
     */
    fun searchDocuments(keyword: String): List<DocumentInfo> {
        if (keyword.isBlank()) return getAllDocuments()

        return documents.values.filter { doc ->
            doc.name.contains(keyword, ignoreCase = true) ||
                    doc.content.contains(keyword, ignoreCase = true)
        }
    }

    /**
     * 生成唯一ID
     */
    private fun generateId(): String {
        return UUID.randomUUID().toString().replace("-", "").take(16)
    }

    /**
     * 检测文件类型
     */
    private fun detectType(filePath: String): DocumentType {
        val extension = filePath.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "txt" -> DocumentType.PlainText
            "md", "markdown" -> DocumentType.Markdown
            "pdf" -> DocumentType.PDF
            "doc", "docx" -> DocumentType.Word
            "png", "jpg", "jpeg", "gif", "bmp" -> DocumentType.Image
            else -> DocumentType.Unknown
        }
    }

    /**
     * 提取文档内容
     */
    private fun extractContent(file: File, type: DocumentType): String {
        return when (type) {
            DocumentType.PlainText -> extractTextContent(file)
            DocumentType.Markdown -> extractMarkdownContent(file)
            DocumentType.PDF -> extractPdfContent(file)
            DocumentType.Word -> extractWordContent(file)
            DocumentType.Image -> "[图片文件: ${file.name}]"
            DocumentType.Unknown -> "[未知格式: ${file.name}]"
        }
    }

    /**
     * 提取纯文本内容
     */
    private fun extractTextContent(file: File): String {
        return readTextWithLimit(file, MAX_TEXT_CHARS, appendNotice = true)
    }

    /**
     * 提取Markdown内容
     */
    private fun extractMarkdownContent(file: File): String {
        return extractTextContent(file)
    }

    /**
     * 提取PDF内容（需要额外库支持，这里返回占位符）
     */
    private fun extractPdfContent(file: File): String {
        // TODO: 集成PDF解析库（如PdfBox-Android或iText）
        return "[PDF文件: ${file.name}，内容提取功能待实现]"
    }

    /**
     * 提取Word内容（需要额外库支持，这里返回占位符）
     */
    private fun extractWordContent(file: File): String {
        // TODO: 集成Word解析库（如Apache POI）
        return "[Word文件: ${file.name}，内容提取功能待实现]"
    }

    /**
     * 更新文档Flow
     */
    private fun updateDocumentsFlow() {
        _documentsFlow.value = documents.values.toList()
    }

    /**
     * 保存索引
     */
    private fun saveIndex() {
        try {
            val indexData = documents.values.toList()
            indexFile.writeText(json.encodeToString(indexData))
        } catch (e: Exception) {
            Logger.error("DocumentManager", "保存索引失败: ${e.message}")
        }
    }

    /**
     * 加载索引
     */
    private fun loadIndex() {
        try {
            if (indexFile.exists()) {
                if (indexFile.length() > MAX_INDEX_FILE_BYTES) {
                    Logger.warning("DocumentManager", "索引文件过大，尝试重建索引以避免内存问题")
                    rebuildIndexFromFiles()
                    return
                }
                val indexData = json.decodeFromString<List<DocumentInfo>>(indexFile.readText())
                documents.clear()
                indexData.forEach { doc ->
                    // 验证文件是否存在
                    if (File(doc.path).exists()) {
                        documents[doc.id] = doc
                    }
                }
                updateDocumentsFlow()
            }
        } catch (e: Exception) {
            Logger.error("DocumentManager", "加载索引失败: ${e.message}")
        }
    }

    private fun rebuildIndexFromFiles() {
        documents.clear()
        val files = documentsDir.listFiles().orEmpty()
        files.forEach { file ->
            if (!file.isFile) return@forEach
            val (docId, originalName) = parseStoredFileName(file.name)
            val type = detectType(originalName)
            val content = when (type) {
                DocumentType.PlainText, DocumentType.Markdown -> readTextWithLimit(file, MAX_TEXT_CHARS, appendNotice = true)
                DocumentType.PDF -> "[PDF文件: $originalName，内容提取功能待实现]"
                DocumentType.Word -> "[Word文件: $originalName，内容提取功能待实现]"
                DocumentType.Image -> "[图片文件: $originalName]"
                DocumentType.Unknown -> "[未知格式: $originalName]"
            }
            documents[docId] = DocumentInfo(
                id = docId,
                name = originalName,
                path = file.absolutePath,
                type = type,
                content = content,
                importTime = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
                size = file.length(),
                category = "其他"
            )
        }
        updateDocumentsFlow()
        saveIndex()
    }

    private fun parseStoredFileName(fileName: String): Pair<String, String> {
        val separatorIndex = fileName.indexOf('_')
        if (separatorIndex in 1 until fileName.length - 1) {
            val id = fileName.substring(0, separatorIndex)
            val originalName = fileName.substring(separatorIndex + 1)
            return id to originalName
        }
        val fallbackId = fileName.substringBeforeLast('.', fileName).ifBlank { generateId() }
        return fallbackId to fileName
    }

    private fun readTextWithLimit(file: File, maxChars: Int, appendNotice: Boolean): String {
        return try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                val builder = StringBuilder()
                var total = 0
                var truncated = false
                while (true) {
                    val read = reader.read(buffer)
                    if (read == -1) break
                    val remaining = maxChars - total
                    if (remaining <= 0) {
                        truncated = true
                        break
                    }
                    val toAppend = if (read > remaining) {
                        truncated = true
                        remaining
                    } else {
                        read
                    }
                    if (toAppend > 0) {
                        builder.append(buffer, 0, toAppend)
                        total += toAppend
                    }
                    if (truncated) break
                }
                if (truncated) {
                    Logger.warning("DocumentManager", "文本内容过长，已截断: ${file.name}")
                    if (appendNotice) {
                        builder.append("\n[内容过长，已截断]")
                    }
                }
                builder.toString()
            }
        } catch (e: Exception) {
            Logger.warning("DocumentManager", "读取文本文件失败: ${e.message}")
            ""
        }
    }
}
