package com.example.kaoyanassistant.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * AI服务商类型
 * 对应Qt版本的AIProvider枚举
 */
enum class AIProvider {
    OpenAI,
    Claude,
    DeepSeek,
    Qwen,      // 通义千问
    Custom     // 自定义API
}

/**
 * API配置数据类
 * 对应Qt版本的APIConfig结构体
 */
@Serializable
data class APIConfig(
    val apiUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val enabled: Boolean = false,
    val maxContextTokens: Int = 8000,
    val keepRecentMessages: Int = 10
)

/**
 * 配置管理器 - 管理API配置和应用设置
 * 使用DataStore进行持久化存储
 * 对应Qt版本的ConfigManager类
 */
class ConfigManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: ConfigManager? = null

        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kaoyan_settings")

        // Preference Keys
        private val CURRENT_PROVIDER = stringPreferencesKey("current_provider")
        private val DOCUMENT_STORAGE_PATH = stringPreferencesKey("document_storage_path")

        // API配置Keys（按服务商存储）
        private fun apiConfigKey(provider: AIProvider) = stringPreferencesKey("api_config_${provider.name}")
        private val CUSTOM_API_CONFIGS = stringPreferencesKey("custom_api_configs")

        fun getInstance(context: Context): ConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConfigManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // 默认API配置
    private val defaultConfigs = mapOf(
        AIProvider.OpenAI to APIConfig(
            apiUrl = "https://api.openai.com/v1/chat/completions",
            model = "gpt-4"
        ),
        AIProvider.Claude to APIConfig(
            apiUrl = "https://api.anthropic.com/v1/messages",
            model = "claude-3-opus-20240229"
        ),
        AIProvider.DeepSeek to APIConfig(
            apiUrl = "https://api.deepseek.com/v1/chat/completions",
            model = "deepseek-chat"
        ),
        AIProvider.Qwen to APIConfig(
            apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation",
            model = "qwen-turbo"
        ),
        AIProvider.Custom to APIConfig()
    )

    /**
     * 获取当前选择的AI服务商
     */
    val currentProviderFlow: Flow<AIProvider> = context.dataStore.data.map { preferences ->
        val providerName = preferences[CURRENT_PROVIDER] ?: AIProvider.DeepSeek.name
        try {
            AIProvider.valueOf(providerName)
        } catch (e: IllegalArgumentException) {
            AIProvider.DeepSeek
        }
    }

    /**
     * 设置当前AI服务商
     */
    suspend fun setCurrentProvider(provider: AIProvider) {
        context.dataStore.edit { preferences ->
            preferences[CURRENT_PROVIDER] = provider.name
        }
    }

    /**
     * 获取指定服务商的API配置
     */
    fun getAPIConfigFlow(provider: AIProvider): Flow<APIConfig> {
        return context.dataStore.data.map { preferences ->
            val configJson = preferences[apiConfigKey(provider)]
            if (configJson != null) {
                try {
                    json.decodeFromString<APIConfig>(configJson)
                } catch (e: Exception) {
                    defaultConfigs[provider] ?: APIConfig()
                }
            } else {
                defaultConfigs[provider] ?: APIConfig()
            }
        }
    }

    /**
     * 获取当前服务商的API配置
     */
    val currentAPIConfigFlow: Flow<APIConfig> = context.dataStore.data.map { preferences ->
        val providerName = preferences[CURRENT_PROVIDER] ?: AIProvider.DeepSeek.name
        val provider = try {
            AIProvider.valueOf(providerName)
        } catch (e: IllegalArgumentException) {
            AIProvider.DeepSeek
        }

        val configJson = preferences[apiConfigKey(provider)]
        if (configJson != null) {
            try {
                json.decodeFromString<APIConfig>(configJson)
            } catch (e: Exception) {
                defaultConfigs[provider] ?: APIConfig()
            }
        } else {
            defaultConfigs[provider] ?: APIConfig()
        }
    }

    /**
     * 设置API配置
     */
    suspend fun setAPIConfig(provider: AIProvider, config: APIConfig) {
        context.dataStore.edit { preferences ->
            preferences[apiConfigKey(provider)] = json.encodeToString(config)
        }
    }

    /**
     * 获取自定义API配置列表
     */
    val customAPIConfigsFlow: Flow<Map<String, APIConfig>> = context.dataStore.data.map { preferences ->
        val configsJson = preferences[CUSTOM_API_CONFIGS]
        if (configsJson != null) {
            try {
                json.decodeFromString<Map<String, APIConfig>>(configsJson)
            } catch (e: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }
    }

    /**
     * 添加自定义API配置
     */
    suspend fun setCustomAPIConfig(name: String, config: APIConfig) {
        context.dataStore.edit { preferences ->
            val currentConfigs = preferences[CUSTOM_API_CONFIGS]?.let {
                try {
                    json.decodeFromString<Map<String, APIConfig>>(it).toMutableMap()
                } catch (e: Exception) {
                    mutableMapOf()
                }
            } ?: mutableMapOf()

            currentConfigs[name] = config
            preferences[CUSTOM_API_CONFIGS] = json.encodeToString(currentConfigs)
        }
    }

    /**
     * 移除自定义API配置
     */
    suspend fun removeCustomAPIConfig(name: String) {
        context.dataStore.edit { preferences ->
            val currentConfigs = preferences[CUSTOM_API_CONFIGS]?.let {
                try {
                    json.decodeFromString<Map<String, APIConfig>>(it).toMutableMap()
                } catch (e: Exception) {
                    mutableMapOf()
                }
            } ?: mutableMapOf()

            currentConfigs.remove(name)
            preferences[CUSTOM_API_CONFIGS] = json.encodeToString(currentConfigs)
        }
    }

    /**
     * 获取文档存储路径
     */
    val documentStoragePathFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DOCUMENT_STORAGE_PATH] ?: context.filesDir.absolutePath + "/documents"
    }

    /**
     * 设置文档存储路径
     */
    suspend fun setDocumentStoragePath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[DOCUMENT_STORAGE_PATH] = path
        }
    }

    /**
     * 同步获取当前API配置（用于非协程环境）
     */
    suspend fun getCurrentAPIConfig(): APIConfig {
        return currentAPIConfigFlow.first()
    }

    /**
     * 同步获取当前服务商
     */
    suspend fun getCurrentProvider(): AIProvider {
        return currentProviderFlow.first()
    }
}
