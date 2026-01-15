package com.example.kaoyanassistant.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kaoyanassistant.core.AIProvider
import com.example.kaoyanassistant.core.APIConfig
import com.example.kaoyanassistant.core.ConfigManager
import com.example.kaoyanassistant.core.UserInfo
import com.example.kaoyanassistant.core.UserManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 设置界面UI状态
 */
data class SettingsUiState(
    val currentProvider: AIProvider = AIProvider.DeepSeek,
    val apiConfigs: Map<AIProvider, APIConfig> = emptyMap(),
    val customConfigs: Map<String, APIConfig> = emptyMap(),
    val currentUser: UserInfo? = null,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val error: String? = null
)

/**
 * 设置界面ViewModel
 */
class SettingsViewModel(
    private val configManager: ConfigManager,
    private val userManager: UserManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // 加载当前配置
        viewModelScope.launch {
            configManager.currentProviderFlow.collect { provider ->
                _uiState.update { it.copy(currentProvider = provider) }
            }
        }

        // 加载用户信息
        userManager?.let { manager ->
            viewModelScope.launch {
                manager.currentUserFlow.collect { user ->
                    _uiState.update { it.copy(currentUser = user) }
                }
            }
        }

        // 加载所有API配置
        loadAllConfigs()
    }

    /**
     * 加载所有API配置
     */
    private fun loadAllConfigs() {
        viewModelScope.launch {
            val configs = mutableMapOf<AIProvider, APIConfig>()
            AIProvider.entries.forEach { provider ->
                configManager.getAPIConfigFlow(provider).first().let { config ->
                    configs[provider] = config
                }
            }
            _uiState.update { it.copy(apiConfigs = configs) }
        }

        viewModelScope.launch {
            configManager.customAPIConfigsFlow.collect { customConfigs ->
                _uiState.update { it.copy(customConfigs = customConfigs) }
            }
        }
    }

    /**
     * 设置当前服务商
     */
    fun setCurrentProvider(provider: AIProvider) {
        viewModelScope.launch {
            configManager.setCurrentProvider(provider)
        }
    }

    /**
     * 更新API配置
     */
    fun updateAPIConfig(provider: AIProvider, config: APIConfig) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                configManager.setAPIConfig(provider, config)
                _uiState.update { currentState ->
                    val newConfigs = currentState.apiConfigs.toMutableMap()
                    newConfigs[provider] = config
                    currentState.copy(
                        apiConfigs = newConfigs,
                        isSaving = false,
                        saveSuccess = true
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isSaving = false) }
            }
        }
    }

    /**
     * 添加自定义API配置
     */
    fun addCustomConfig(name: String, config: APIConfig) {
        viewModelScope.launch {
            configManager.setCustomAPIConfig(name, config)
        }
    }

    /**
     * 删除自定义API配置
     */
    fun removeCustomConfig(name: String) {
        viewModelScope.launch {
            configManager.removeCustomAPIConfig(name)
        }
    }

    /**
     * 更新用户信息
     */
    fun updateUserInfo(userInfo: UserInfo) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            userManager?.updateUserInfo(userInfo)?.fold(
                onSuccess = {
                    _uiState.update { it.copy(isSaving = false, saveSuccess = true) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isSaving = false, error = e.message) }
                }
            )
        }
    }

    /**
     * 登出
     */
    fun logout() {
        viewModelScope.launch {
            userManager?.logout()
        }
    }

    /**
     * 清除保存成功状态
     */
    fun clearSaveSuccess() {
        _uiState.update { it.copy(saveSuccess = false) }
    }

    /**
     * 清除错误
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * 获取服务商显示名称
     */
    fun getProviderDisplayName(provider: AIProvider): String {
        return when (provider) {
            AIProvider.OpenAI -> "OpenAI"
            AIProvider.Claude -> "Claude"
            AIProvider.DeepSeek -> "DeepSeek"
            AIProvider.Qwen -> "通义千问"
            AIProvider.Custom -> "自定义"
        }
    }

    class Factory(
        private val configManager: ConfigManager,
        private val userManager: UserManager? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(configManager, userManager) as T
        }
    }
}
