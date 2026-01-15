package com.example.kaoyanassistant.core

import com.example.kaoyanassistant.services.Message
import com.example.kaoyanassistant.services.MessageRole

/**
 * 上下文压缩策略
 * 对应Qt版本的CompressionStrategy枚举
 */
enum class CompressionStrategy {
    RemoveOldest,       // 移除最早的消息
    SummarizeOld,       // 总结旧消息（需要AI调用）
    KeepSystemAndRecent // 保留系统提示和最近N条消息
}

/**
 * 上下文管理器 - 管理对话上下文和token计算
 * 支持上下文压缩和限制管理
 * 对应Qt版本的ContextManager类
 */
class ContextManager {

    private var maxTokens: Int = 8000
    private var warningThreshold: Double = 0.8
    private var compressionStrategy: CompressionStrategy = CompressionStrategy.KeepSystemAndRecent
    private var keepRecentCount: Int = 10

    /**
     * Token估算
     * 简单估算：中文约1.5字符/token，英文约4字符/token
     */
    fun estimateTokens(text: String): Int {
        var chineseCount = 0
        var otherCount = 0

        for (char in text) {
            if (char.code in 0x4E00..0x9FFF ||
                char.code in 0x3400..0x4DBF ||
                char.code in 0x20000..0x2A6DF) {
                chineseCount++
            } else {
                otherCount++
            }
        }

        // 中文约1.5字符/token，英文约4字符/token
        return (chineseCount / 1.5 + otherCount / 4.0).toInt()
    }

    /**
     * 估算消息列表的总token数
     */
    fun estimateTokens(messages: List<Message>): Int {
        return messages.sumOf { estimateTokens(it.content) }
    }

    /**
     * 设置最大token数
     */
    fun setMaxTokens(max: Int) {
        maxTokens = max
    }

    fun getMaxTokens(): Int = maxTokens

    /**
     * 设置警告阈值（0.0-1.0）
     */
    fun setWarningThreshold(threshold: Double) {
        warningThreshold = threshold.coerceIn(0.0, 1.0)
    }

    fun getWarningThreshold(): Double = warningThreshold

    /**
     * 设置压缩策略
     */
    fun setCompressionStrategy(strategy: CompressionStrategy) {
        compressionStrategy = strategy
    }

    fun getCompressionStrategy(): CompressionStrategy = compressionStrategy

    /**
     * 设置保留最近消息数量
     */
    fun setKeepRecentCount(count: Int) {
        keepRecentCount = count
    }

    fun getKeepRecentCount(): Int = keepRecentCount

    /**
     * 检查上下文是否超出限制
     */
    fun isContextOverLimit(messages: List<Message>, newMessage: String): Boolean {
        val currentTokens = estimateTokens(messages) + estimateTokens(newMessage)
        return currentTokens > maxTokens
    }

    /**
     * 检查上下文是否接近限制
     */
    fun isContextNearLimit(messages: List<Message>, newMessage: String): Boolean {
        val currentTokens = estimateTokens(messages) + estimateTokens(newMessage)
        return currentTokens > maxTokens * warningThreshold
    }

    /**
     * 获取当前使用率
     */
    fun getUsageRatio(messages: List<Message>): Double {
        val currentTokens = estimateTokens(messages)
        return currentTokens.toDouble() / maxTokens
    }

    /**
     * 获取当前token数
     */
    fun getCurrentTokens(messages: List<Message>): Int {
        return estimateTokens(messages)
    }

    /**
     * 压缩上下文
     */
    fun compressContext(messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return messages

        return when (compressionStrategy) {
            CompressionStrategy.RemoveOldest -> {
                // 移除最早的消息，保留系统消息
                val systemMessages = messages.filter { it.role == MessageRole.System }
                val nonSystemMessages = messages.filter { it.role != MessageRole.System }

                val result = systemMessages.toMutableList()
                var currentTokens = estimateTokens(result)

                // 从最新的消息开始添加
                for (message in nonSystemMessages.reversed()) {
                    val messageTokens = estimateTokens(message.content)
                    if (currentTokens + messageTokens <= maxTokens * 0.7) {
                        result.add(message)
                        currentTokens += messageTokens
                    }
                }

                // 恢复原始顺序
                systemMessages + result.filter { it.role != MessageRole.System }.reversed()
            }

            CompressionStrategy.KeepSystemAndRecent -> {
                // 保留系统提示和最近N条消息
                val systemMessages = messages.filter { it.role == MessageRole.System }
                val nonSystemMessages = messages.filter { it.role != MessageRole.System }

                val recentMessages = nonSystemMessages.takeLast(keepRecentCount)
                systemMessages + recentMessages
            }

            CompressionStrategy.SummarizeOld -> {
                // 总结旧消息需要AI调用，这里先返回KeepSystemAndRecent的结果
                // 实际总结功能需要在调用层实现
                val systemMessages = messages.filter { it.role == MessageRole.System }
                val nonSystemMessages = messages.filter { it.role != MessageRole.System }

                val recentMessages = nonSystemMessages.takeLast(keepRecentCount)
                systemMessages + recentMessages
            }
        }
    }

    /**
     * 生成压缩摘要的提示（用于AI总结）
     */
    fun generateSummaryPrompt(messages: List<Message>): String {
        val nonSystemMessages = messages.filter { it.role != MessageRole.System }
        val oldMessages = nonSystemMessages.dropLast(keepRecentCount)

        if (oldMessages.isEmpty()) return ""

        val conversationText = oldMessages.joinToString("\n") { message ->
            val roleStr = when (message.role) {
                MessageRole.User -> "用户"
                MessageRole.Assistant -> "助手"
                MessageRole.System -> "系统"
            }
            "$roleStr: ${message.content}"
        }

        return """
            请将以下对话内容总结为简洁的摘要，保留关键信息和上下文：

            $conversationText

            请用一段话总结上述对话的主要内容和结论。
        """.trimIndent()
    }
}
