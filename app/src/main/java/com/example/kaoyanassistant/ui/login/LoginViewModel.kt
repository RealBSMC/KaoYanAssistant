package com.example.kaoyanassistant.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kaoyanassistant.core.UserInfo
import com.example.kaoyanassistant.core.UserManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 登录界面状态
 */
data class LoginUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val error: String? = null,
    val isRegisterMode: Boolean = false,
    val loginSuccess: Boolean = false
)

/**
 * 登录ViewModel
 */
class LoginViewModel(
    private val userManager: UserManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        // 检查登录状态
        viewModelScope.launch {
            userManager.isLoggedInFlow.collect { isLoggedIn ->
                _uiState.update { it.copy(isLoggedIn = isLoggedIn) }
            }
        }
    }

    /**
     * 登录
     */
    fun login(username: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = userManager.login(username, password)
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false, loginSuccess = true) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
            )
        }
    }

    /**
     * 注册
     */
    fun register(username: String, password: String, confirmPassword: String, nickname: String) {
        if (password != confirmPassword) {
            _uiState.update { it.copy(error = "两次输入的密码不一致") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = userManager.register(username, password, nickname)
            result.fold(
                onSuccess = {
                    // 注册成功后自动登录
                    val loginResult = userManager.login(username, password)
                    loginResult.fold(
                        onSuccess = {
                            _uiState.update { it.copy(isLoading = false, loginSuccess = true) }
                        },
                        onFailure = { e ->
                            _uiState.update { it.copy(isLoading = false, error = e.message) }
                        }
                    )
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
            )
        }
    }

    /**
     * 切换登录/注册模式
     */
    fun toggleMode() {
        _uiState.update { it.copy(isRegisterMode = !it.isRegisterMode, error = null) }
    }

    /**
     * 清除错误
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    class Factory(
        private val userManager: UserManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LoginViewModel(userManager) as T
        }
    }
}
