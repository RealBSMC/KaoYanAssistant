package com.example.kaoyanassistant.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.kaoyanassistant.R
import com.example.kaoyanassistant.core.ConfigManager

class IndexDocumentWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val WorkTag = "rag_index_work"
        const val KeyDocId = "doc_id"
        const val KeyStage = "stage"
        const val KeyCurrent = "current"
        const val KeyTotal = "total"
        const val KeyMessage = "message"
        const val KeyProcessedTokens = "processed_tokens"
        const val KeyEstimatedTokens = "estimated_tokens"
        const val KeyChunkCount = "chunk_count"
        const val KeyError = "error"

        fun uniqueWorkName(docId: String): String = "rag_index_$docId"
    }

    override suspend fun doWork(): Result {
        val docId = inputData.getString(KeyDocId)
            ?: return Result.failure(workDataOf(KeyError to "缺少文档ID"))

        val configManager = ConfigManager.getInstance(applicationContext)
        val documentManager = DocumentManager.getInstance(applicationContext)
        val ragIndexManager = RagIndexManager(applicationContext, configManager)

        val document = documentManager.getDocument(docId)
            ?: return Result.failure(workDataOf(KeyError to "未找到待索引文档"))

        setForeground(createForegroundInfo("准备索引...", 0, 0))

        return runCatching {
            var errorMessage: String? = null
            val result = ragIndexManager.buildIndex(document) { progress ->
                val progressData = workDataOf(
                    KeyStage to progress.stage.name,
                    KeyCurrent to progress.current,
                    KeyTotal to progress.total,
                    KeyMessage to progress.message,
                    KeyProcessedTokens to progress.processedTokens,
                    KeyEstimatedTokens to progress.estimatedTokens
                )
                setProgressAsync(progressData)
                setForegroundAsync(createForegroundInfo(progress.message, progress.current, progress.total))
                if (progress.stage == RagStage.Error && errorMessage == null) {
                    errorMessage = progress.message.ifBlank { "索引失败，请检查设置" }
                }
            }
            if (errorMessage != null) {
                return Result.failure(workDataOf(KeyError to errorMessage))
            }
            Result.success(workDataOf(
                KeyChunkCount to result.chunkCount,
                KeyEstimatedTokens to result.estimatedTokens
            ))
        }.getOrElse { e ->
            Result.failure(workDataOf(KeyError to (e.message ?: "索引失败")))
        }
    }

    private fun createForegroundInfo(message: String, current: Int, total: Int): ForegroundInfo {
        val channelId = "rag_index_channel"
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(
                channelId,
                "文档索引任务",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val displayMessage = if (message.isNotBlank()) message else "正在处理..."
        val indeterminate = total <= 0
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("正在构建文档索引")
            .setContentText(displayMessage)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total.coerceAtLeast(0), current.coerceAtLeast(0), indeterminate)
            .build()

        return ForegroundInfo(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
