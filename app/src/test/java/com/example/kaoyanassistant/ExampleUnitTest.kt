package com.example.kaoyanassistant

import org.junit.Test
import org.junit.Assert.*
import com.example.kaoyanassistant.core.ContextManager
import com.example.kaoyanassistant.services.Message
import com.example.kaoyanassistant.services.MessageRole

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun contextManager_tokenEstimation() {
        val contextManager = ContextManager()

        // 测试中文token估算
        val chineseText = "这是一段中文测试文本"
        val chineseTokens = contextManager.estimateTokens(chineseText)
        assertTrue(chineseTokens > 0)

        // 测试英文token估算
        val englishText = "This is an English test text"
        val englishTokens = contextManager.estimateTokens(englishText)
        assertTrue(englishTokens > 0)
    }

    @Test
    fun contextManager_compressionStrategy() {
        val contextManager = ContextManager()
        contextManager.setMaxTokens(1000)
        contextManager.setKeepRecentCount(3)

        val messages = listOf(
            Message(MessageRole.System, "系统提示"),
            Message(MessageRole.User, "问题1"),
            Message(MessageRole.Assistant, "回答1"),
            Message(MessageRole.User, "问题2"),
            Message(MessageRole.Assistant, "回答2"),
            Message(MessageRole.User, "问题3"),
            Message(MessageRole.Assistant, "回答3")
        )

        val compressed = contextManager.compressContext(messages)

        // 应该保留系统消息和最近3条非系统消息
        assertTrue(compressed.any { it.role == MessageRole.System })
        assertTrue(compressed.size <= 4) // 1个系统消息 + 3个最近消息
    }
}
