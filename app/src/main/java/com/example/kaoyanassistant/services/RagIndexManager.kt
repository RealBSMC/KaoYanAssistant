package com.example.kaoyanassistant.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.example.kaoyanassistant.core.AIProvider
import com.example.kaoyanassistant.core.ConfigManager
import com.example.kaoyanassistant.core.ContextManager
import com.example.kaoyanassistant.core.EmbeddingConfig
import com.example.kaoyanassistant.core.EmbeddingMode
import com.example.kaoyanassistant.utils.ImageUtils
import com.example.kaoyanassistant.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.sqrt

enum class RagStage {
    Preparing,
    Ocr,
    Chunking,
    Vectorizing,
    Saving,
    Completed,
    Error
}

data class RagIndexProgress(
    val stage: RagStage,
    val current: Int = 0,
    val total: Int = 0,
    val message: String = "",
    val processedTokens: Int = 0,
    val estimatedTokens: Int = 0
)

data class RagIndexResult(
    val documentId: String,
    val chunkCount: Int,
    val estimatedTokens: Int
)

@Serializable
data class RagChunk(
    val id: String,
    val docId: String,
    val text: String,
    val pageStart: Int? = null,
    val pageEnd: Int? = null,
    val vector: List<Float>
)

@Serializable
data class RagIndexFile(
    val version: Int = 1,
    val docId: String,
    val tokenEstimate: Int,
    val chunks: List<RagChunk>
)

data class RagMatch(
    val chunk: RagChunk,
    val score: Float
)

class RagIndexManager(
    private val context: Context,
    private val configManager: ConfigManager
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val indexDir: File = File(context.filesDir, "rag_indexes").also { it.mkdirs() }
    private val contextManager = ContextManager()
    private val embeddingService = EmbeddingService()
    private val localEmbeddingService = LocalEmbeddingService(context)

    private data class EmbeddingBackendState(
        val useLocal: Boolean,
        val remoteConfig: EmbeddingConfig?
    )

    private companion object {
        const val QueryInstruction = "Given a web search query, retrieve relevant passages that answer the query"
        const val SectionEndMarker = "[[SECTION_END]]"
        const val MAX_TEXT_CHARS = 1_000_000
    }

    suspend fun buildIndex(
        document: DocumentInfo,
        onProgress: (RagIndexProgress) -> Unit
    ): RagIndexResult = withContext(Dispatchers.IO) {
        onProgress(RagIndexProgress(stage = RagStage.Preparing, message = "准备索引..."))

        val textPages = when (document.type) {
            DocumentType.PDF -> extractPdfWithOcr(File(document.path), onProgress)
            DocumentType.Image -> extractImageWithOcr(File(document.path), onProgress)
            else -> {
                val text = readFileText(File(document.path))
                listOf(PageText(null, text, contextManager.estimateTokens(text)))
            }
        }

        val hasText = textPages.any { it.text.isNotBlank() }
        if (!hasText) {
            val message = when (document.type) {
                DocumentType.PDF, DocumentType.Image ->
                    "未识别到文字，请检查OCR配置或文件清晰度"
                else -> "文件内容为空或读取失败"
            }
            onProgress(RagIndexProgress(stage = RagStage.Error, message = message))
            return@withContext RagIndexResult(document.id, 0, 0)
        }

        val ocrTokens = textPages.sumOf { it.estimatedTokens }
        onProgress(RagIndexProgress(
            stage = RagStage.Chunking,
            message = "正在切分内容...",
            processedTokens = ocrTokens,
            estimatedTokens = ocrTokens
        ))
        val chunks = buildChunks(document.id, textPages)
        val embeddingTokens = chunks.sumOf { contextManager.estimateTokens(it.text) }
        val totalTokens = ocrTokens + embeddingTokens

        val backend = resolveEmbeddingBackend(onProgress)
        if (backend == null) {
            onProgress(RagIndexProgress(
                stage = RagStage.Error,
                message = "向量模型未配置，请在设置中填写远程向量模型，或确认本地模型可用"
            ))
            return@withContext RagIndexResult(document.id, 0, totalTokens)
        }

        onProgress(RagIndexProgress(
            stage = RagStage.Vectorizing,
            message = "正在生成向量...",
            processedTokens = ocrTokens,
            estimatedTokens = totalTokens
        ))
        val vectorized = mutableListOf<RagChunk>()
        var embeddingUsedTokens = 0
        var useLocal = backend.useLocal
        chunks.forEachIndexed { index, chunk ->
            val partLabel = chunk.pageStart?.let { "第${it}页" } ?: "全文"
            onProgress(RagIndexProgress(
                stage = RagStage.Vectorizing,
                current = index + 1,
                total = chunks.size,
                message = "向量上传第 ${index + 1}/${chunks.size} 段 ($partLabel)",
                processedTokens = ocrTokens + embeddingUsedTokens,
                estimatedTokens = totalTokens
            ))

            val embedding = if (useLocal) {
                localEmbeddingService.embed(
                    prepareEmbeddingText(chunk.text, isQuery = false),
                    onMessage = { msg ->
                        onProgress(RagIndexProgress(stage = RagStage.Vectorizing, message = msg))
                    }
                )
            } else {
                null
            } ?: run {
                useLocal = false
                backend.remoteConfig?.let { config ->
                    embeddingService.embed(prepareEmbeddingText(chunk.text, isQuery = false), config)
                }
            } ?: throw IllegalStateException("向量模型调用失败")

            embeddingUsedTokens += contextManager.estimateTokens(chunk.text)
            vectorized.add(chunk.copy(vector = embedding.toList()))
        }

        onProgress(RagIndexProgress(
            stage = RagStage.Saving,
            message = "正在保存索引...",
            processedTokens = totalTokens,
            estimatedTokens = totalTokens
        ))
        saveIndex(document.id, RagIndexFile(
            docId = document.id,
            tokenEstimate = totalTokens,
            chunks = vectorized
        ))

        onProgress(RagIndexProgress(
            stage = RagStage.Completed,
            message = "索引完成",
            processedTokens = totalTokens,
            estimatedTokens = totalTokens
        ))

        RagIndexResult(
            documentId = document.id,
            chunkCount = vectorized.size,
            estimatedTokens = totalTokens
        )
    }

    fun isIndexed(docId: String): Boolean = indexFile(docId).exists()

    fun removeIndex(docId: String) {
        indexFile(docId).delete()
    }

    suspend fun search(query: String, docIds: List<String>, topK: Int): List<RagMatch> {
        if (query.isBlank() || docIds.isEmpty()) return emptyList()
        val backend = resolveEmbeddingBackend()
        if (backend == null) {
            Logger.warning("RagIndexManager", "向量模型未配置，无法检索")
            return emptyList()
        }
        var queryVector = if (backend.useLocal) {
            localEmbeddingService.embed(prepareEmbeddingText(query, isQuery = true))
        } else {
            null
        }
        if (queryVector == null) {
            queryVector = backend.remoteConfig?.let { config ->
                embeddingService.embed(prepareEmbeddingText(query, isQuery = true), config)
            }
        }
        if (queryVector == null) return emptyList()
        val matches = mutableListOf<RagMatch>()

        docIds.forEach { docId ->
            val index = loadIndex(docId) ?: return@forEach
            index.chunks.forEach { chunk ->
                val score = cosineSimilarity(queryVector, chunk.vector)
                matches.add(RagMatch(chunk, score))
            }
        }

        return matches.sortedByDescending { it.score }.take(topK)
    }

    private suspend fun resolveEmbeddingBackend(
        onProgress: ((RagIndexProgress) -> Unit)? = null
    ): EmbeddingBackendState? {
        val embeddingMode = configManager.getEmbeddingMode()
        var useLocal = embeddingMode == EmbeddingMode.LocalPreferred && localEmbeddingService.isSupported()
        if (useLocal) {
            onProgress?.invoke(RagIndexProgress(stage = RagStage.Preparing, message = "使用本地向量模型"))
            val model = localEmbeddingService.ensureModelReady { message ->
                onProgress?.invoke(RagIndexProgress(stage = RagStage.Preparing, message = message))
            }
            if (model == null) {
                useLocal = false
            }
        }

        val embeddingConfig = loadEmbeddingConfig()
        val remoteConfig = if (embeddingConfigValid(embeddingConfig)) embeddingConfig else null

        if (useLocal) {
            return EmbeddingBackendState(useLocal = true, remoteConfig = remoteConfig)
        }

        if (remoteConfig == null) return null
        if (embeddingMode == EmbeddingMode.LocalPreferred) {
            onProgress?.invoke(RagIndexProgress(stage = RagStage.Preparing, message = "本地模型不可用，使用远程向量模型"))
        }
        return EmbeddingBackendState(useLocal = false, remoteConfig = remoteConfig)
    }

    private fun prepareEmbeddingText(text: String, isQuery: Boolean): String {
        if (!isQuery) return text
        return "Instruct: $QueryInstruction\nQuery:$text"
    }

    private data class PageText(
        val pageNumber: Int?,
        val text: String,
        val estimatedTokens: Int = 0
    )

    private data class SectionText(
        val text: String,
        val pageStart: Int?,
        val pageEnd: Int?
    )

    private suspend fun extractPdfWithOcr(
        file: File,
        onProgress: (RagIndexProgress) -> Unit
    ): List<PageText> {
        val results = mutableListOf<PageText>()
        val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fileDescriptor)
        try {
            val pageCount = renderer.pageCount
            var tokenTotal = 0

            for (pageIndex in 0 until pageCount) {
                val pageNumber = pageIndex + 1
                val message = "OCR上传第 $pageNumber/$pageCount 页"
                onProgress(RagIndexProgress(
                    stage = RagStage.Ocr,
                    current = pageNumber,
                    total = pageCount,
                    message = message,
                    processedTokens = tokenTotal
                ))

                val page = renderer.openPage(pageIndex)
                val bitmap = renderPageToBitmap(page)
                page.close()

                val base64 = ImageUtils.bitmapToBase64(bitmap) ?: ""
                bitmap.recycle()

                val ocrText = if (base64.isNotBlank()) {
                    requestOcrText(base64, "image/jpeg", "第 $pageNumber 页")
                } else {
                    ""
                }

                val estimate = contextManager.estimateTokens(ocrText)
                tokenTotal += estimate
                results.add(PageText(pageNumber, ocrText, estimate))

                onProgress(RagIndexProgress(
                    stage = RagStage.Ocr,
                    current = pageNumber,
                    total = pageCount,
                    message = "已完成第 $pageNumber/$pageCount 页",
                    processedTokens = tokenTotal
                ))
            }
        } finally {
            renderer.close()
            fileDescriptor.close()
        }

        return results
    }

    private suspend fun extractImageWithOcr(
        file: File,
        onProgress: (RagIndexProgress) -> Unit
    ): List<PageText> {
        onProgress(RagIndexProgress(
            stage = RagStage.Ocr,
            current = 1,
            total = 1,
            message = "OCR上传图片 1/1"
        ))
        val bitmap = ImageUtils.decodeBitmap(file)
        if (bitmap == null) {
            return listOf(PageText(null, ""))
        }
        val base64 = ImageUtils.bitmapToBase64(bitmap) ?: ""
        bitmap.recycle()

        val ocrText = if (base64.isNotBlank()) {
            requestOcrText(base64, "image/jpeg", "图片")
        } else {
            ""
        }

        val estimate = contextManager.estimateTokens(ocrText)
        onProgress(RagIndexProgress(
            stage = RagStage.Ocr,
            current = 1,
            total = 1,
            message = "已完成图片识别",
            processedTokens = estimate
        ))
        return listOf(PageText(null, ocrText, estimate))
    }

    private fun buildChunks(docId: String, pages: List<PageText>): List<RagChunk> {
        val chunks = mutableListOf<RagChunk>()
        var chunkId = 0
        val sections = splitIntoSections(pages)

        sections.forEach { section ->
            val sectionChunks = splitIntoChunks(section.text)
            sectionChunks.forEach { chunkText ->
                chunks.add(
                    RagChunk(
                        id = "chunk_${docId}_${chunkId++}",
                        docId = docId,
                        text = chunkText,
                        pageStart = section.pageStart,
                        pageEnd = section.pageEnd,
                        vector = emptyList()
                    )
                )
            }
        }

        return chunks
    }

    private fun splitIntoSections(pages: List<PageText>): List<SectionText> {
        val sections = mutableListOf<SectionText>()
        var buffer = StringBuilder()
        var startPage: Int? = null
        var endPage: Int? = null

        fun flushSection() {
            val text = buffer.toString().trim()
            if (text.isNotBlank()) {
                sections.add(SectionText(text, startPage, endPage))
            }
            buffer = StringBuilder()
            startPage = null
            endPage = null
        }

        pages.forEach { page ->
            val text = page.text
            if (text.isBlank()) return@forEach
            text.lines().forEach { line ->
                val parts = line.split(SectionEndMarker)
                parts.forEachIndexed { index, part ->
                    val cleaned = part.trim()
                    if (cleaned.isNotBlank()) {
                        if (startPage == null) {
                            startPage = page.pageNumber
                        }
                        endPage = page.pageNumber
                        if (buffer.isNotEmpty()) {
                            buffer.append('\n')
                        }
                        buffer.append(cleaned)
                    }
                    if (index < parts.lastIndex) {
                        flushSection()
                    }
                }
            }
        }

        if (buffer.isNotEmpty()) {
            flushSection()
        }
        return sections
    }

    private fun splitIntoChunks(
        text: String,
        maxChars: Int = 800,
        overlap: Int = 120
    ): List<String> {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < cleaned.length) {
            var end = (start + maxChars).coerceAtMost(cleaned.length)
            if (end < cleaned.length) {
                val lastBreak = cleaned.lastIndexOf('\n', end)
                if (lastBreak > start + maxChars / 2) {
                    end = lastBreak
                }
            }
            val chunk = cleaned.substring(start, end).trim()
            if (chunk.isNotEmpty()) {
                chunks.add(chunk)
            }
            if (end >= cleaned.length) break
            start = (end - overlap).coerceAtLeast(0)
        }
        return chunks
    }

    private fun vectorize(text: String, dimension: Int = 256): FloatArray {
        val vector = FloatArray(dimension)
        val tokens = tokenize(text)
        tokens.forEach { token ->
            val index = (token.hashCode() and 0x7fffffff) % dimension
            vector[index] += 1.0f
        }
        var normSum = 0f
        for (value in vector) {
            normSum += value * value
        }
        val norm = sqrt(normSum)
        if (norm > 0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
        return vector
    }

    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val matcher = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase())
        matcher.forEach { match ->
            val value = match.value
            if (value.any { it.code in 0x4E00..0x9FFF }) {
                value.forEach { ch ->
                    tokens.add(ch.toString())
                }
            } else {
                tokens.add(value)
            }
        }
        return tokens
    }

    private fun cosineSimilarity(queryVector: FloatArray, vector: List<Float>): Float {
        if (vector.isEmpty()) return 0f
        var dot = 0f
        var queryNorm = 0f
        var vectorNorm = 0f
        for (i in queryVector.indices) {
            val q = queryVector[i]
            val v = vector.getOrElse(i) { 0f }
            dot += q * v
            queryNorm += q * q
            vectorNorm += v * v
        }
        val denom = sqrt(queryNorm) * sqrt(vectorNorm)
        return if (denom > 0f) dot / denom else 0f
    }

    private fun saveIndex(docId: String, index: RagIndexFile) {
        indexFile(docId).writeText(json.encodeToString(index))
    }

    private fun loadIndex(docId: String): RagIndexFile? {
        val file = indexFile(docId)
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString<RagIndexFile>(file.readText())
        }.getOrNull()
    }

    private fun indexFile(docId: String): File {
        return File(indexDir, "rag_index_$docId.json")
    }

    private suspend fun requestOcrText(
        imageBase64: String,
        mimeType: String,
        partLabel: String
    ): String {
        val provider = configManager.getMultimodalVisionProvider()
        val config = loadVisionConfig(provider)
        if (!configValid(config, "图片模型")) return ""

        val maxOutputTokens = config.maxContextTokens.coerceAtLeast(512)
        val recommendedTokens = (maxOutputTokens * 0.7).toInt().coerceAtLeast(256)

        val service = AIService(configManager)
        service.setSystemPrompt(
            "你是一个OCR助手，请准确识别图片中的文字并保持段落结构。请在每个小节结束时单独输出一行" +
                "标记 ${SectionEndMarker}，除该用途外不要输出该标记。输出请控制在 $maxOutputTokens tokens 以内，" +
                "推荐不超过 $recommendedTokens tokens。"
        )
        val prompt = "请识别教材$partLabel 的文字内容，保留标题与段落。" +
            "当你判断一个小节结束时，请单独输出一行标记 ${SectionEndMarker}。" +
            "输出请控制在 $maxOutputTokens tokens 以内，推荐不超过 $recommendedTokens tokens。"
        val message = Message(
            role = MessageRole.User,
            content = prompt,
            imageBase64 = imageBase64,
            imageMimeType = mimeType
        )

        service.sendMessage(message, emptyList(), config, provider)
        val result = service.responseState
            .dropWhile { it is AIResponseState.Idle }
            .first { it is AIResponseState.Success || it is AIResponseState.Error }

        return when (result) {
            is AIResponseState.Success -> result.response.trim()
            is AIResponseState.Error -> {
                Logger.error("RagIndexManager", "OCR失败: ${result.message}")
                ""
            }
            else -> ""
        }
    }

    private fun renderPageToBitmap(page: PdfRenderer.Page, maxSize: Int = 1200): Bitmap {
        val width = page.width
        val height = page.height
        val scale = if (width > height) maxSize.toFloat() / width else maxSize.toFloat() / height
        val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)
        val rect = Rect(0, 0, scaledWidth, scaledHeight)
        page.render(bitmap, rect, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private suspend fun loadVisionConfig(provider: AIProvider): com.example.kaoyanassistant.core.APIConfig {
        return if (provider == AIProvider.Custom) {
            configManager.getMultimodalVisionCustomConfig()
        } else {
            configManager.getAPIConfigFlow(provider).first()
        }
    }

    private fun configValid(config: com.example.kaoyanassistant.core.APIConfig, label: String): Boolean {
        if (config.apiKey.isBlank() || config.apiUrl.isBlank()) {
            Logger.warning("RagIndexManager", "$label 未配置，跳过OCR")
            return false
        }
        return true
    }

    private suspend fun loadEmbeddingConfig(): EmbeddingConfig {
        return configManager.getEmbeddingConfig()
    }

    private fun embeddingConfigValid(config: EmbeddingConfig): Boolean {
        return config.apiKey.isNotBlank() && config.apiUrl.isNotBlank() && config.model.isNotBlank()
    }

    private fun readFileText(file: File): String {
        return runCatching {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                val builder = StringBuilder()
                var total = 0
                var truncated = false
                while (true) {
                    val read = reader.read(buffer)
                    if (read == -1) break
                    val remaining = MAX_TEXT_CHARS - total
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
                    Logger.warning("RagIndexManager", "文本内容过长，索引已截断: ${file.name}")
                }
                builder.toString()
            }
        }.getOrElse {
            Logger.warning("RagIndexManager", "读取文本失败: ${it.message}")
            ""
        }
    }
}
