package com.example.kaoyanassistant

import android.app.Application
import com.example.kaoyanassistant.core.ConfigManager
import com.example.kaoyanassistant.core.UserManager
import com.example.kaoyanassistant.core.StudyPlanManager
import com.example.kaoyanassistant.services.DocumentManager
import com.example.kaoyanassistant.utils.Logger
import java.io.File

/**
 * 应用程序入口
 * 初始化全局单例和配置
 */
class KaoyanApplication : Application() {

    lateinit var configManager: ConfigManager
        private set

    lateinit var documentManager: DocumentManager
        private set

    lateinit var userManager: UserManager
        private set

    lateinit var studyPlanManager: StudyPlanManager
        private set

    override fun onCreate() {
        super.onCreate()

        // 初始化日志系统
        val logDir = File(filesDir, "logs")
        Logger.init(logDir, enableFile = true)
        Logger.info("Application", "考研AI助手启动")

        // 初始化配置管理器
        configManager = ConfigManager.getInstance(this)

        // 初始化文档管理器
        documentManager = DocumentManager.getInstance(this)

        // 初始化用户管理器
        userManager = UserManager.getInstance(this)

        // 初始化学习计划管理器
        studyPlanManager = StudyPlanManager.getInstance(this)

        // 清理旧日志
        Logger.cleanOldLogs(logDir)
    }
}
