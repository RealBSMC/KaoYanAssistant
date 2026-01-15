package com.example.kaoyanassistant.core

import android.content.Context
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * 用户信息
 */
@Serializable
data class UserInfo(
    val username: String,
    val nickname: String = "",
    val targetSchool: String = "",      // 目标院校
    val targetMajor: String = "",       // 目标专业
    val examYear: Int = 0,              // 考研年份
    val currentEducation: String = "",  // 当前学历
    val currentSchool: String = "",     // 本科院校
    val createTime: Long = System.currentTimeMillis()
)

/**
 * 用户管理器 - 处理登录、注册和用户信息
 */
class UserManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: UserManager? = null

        private val USERS_KEY = stringPreferencesKey("users_data")
        private val CURRENT_USER_KEY = stringPreferencesKey("current_user")
        private val IS_LOGGED_IN_KEY = booleanPreferencesKey("is_logged_in")

        fun getInstance(context: Context): UserManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val dataStore = context.dataStore

    /**
     * 是否已登录
     */
    val isLoggedInFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[IS_LOGGED_IN_KEY] ?: false
    }

    /**
     * 当前用户信息
     */
    val currentUserFlow: Flow<UserInfo?> = dataStore.data.map { preferences ->
        val username = preferences[CURRENT_USER_KEY] ?: return@map null
        val usersJson = preferences[USERS_KEY] ?: return@map null
        try {
            val users = json.decodeFromString<Map<String, StoredUser>>(usersJson)
            users[username]?.userInfo
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 注册新用户
     */
    suspend fun register(username: String, password: String, nickname: String = ""): Result<Unit> {
        if (username.isBlank() || password.isBlank()) {
            return Result.failure(Exception("用户名和密码不能为空"))
        }
        if (username.length < 3) {
            return Result.failure(Exception("用户名至少3个字符"))
        }
        if (password.length < 6) {
            return Result.failure(Exception("密码至少6个字符"))
        }

        return try {
            dataStore.edit { preferences ->
                val usersJson = preferences[USERS_KEY]
                val users = if (usersJson != null) {
                    json.decodeFromString<MutableMap<String, StoredUser>>(usersJson)
                } else {
                    mutableMapOf()
                }

                if (users.containsKey(username)) {
                    throw Exception("用户名已存在")
                }

                val hashedPassword = hashPassword(password)
                val userInfo = UserInfo(
                    username = username,
                    nickname = nickname.ifBlank { username }
                )
                users[username] = StoredUser(hashedPassword, userInfo)

                preferences[USERS_KEY] = json.encodeToString(users)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 登录
     */
    suspend fun login(username: String, password: String): Result<UserInfo> {
        if (username.isBlank() || password.isBlank()) {
            return Result.failure(Exception("用户名和密码不能为空"))
        }

        return try {
            val preferences = dataStore.data.first()
            val usersJson = preferences[USERS_KEY]
                ?: return Result.failure(Exception("用户不存在"))

            val users = json.decodeFromString<Map<String, StoredUser>>(usersJson)
            val storedUser = users[username]
                ?: return Result.failure(Exception("用户不存在"))

            val hashedPassword = hashPassword(password)
            if (storedUser.passwordHash != hashedPassword) {
                return Result.failure(Exception("密码错误"))
            }

            dataStore.edit { prefs ->
                prefs[CURRENT_USER_KEY] = username
                prefs[IS_LOGGED_IN_KEY] = true
            }

            Result.success(storedUser.userInfo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 登出
     */
    suspend fun logout() {
        dataStore.edit { preferences ->
            preferences[IS_LOGGED_IN_KEY] = false
            preferences.remove(CURRENT_USER_KEY)
        }
    }

    /**
     * 更新用户信息
     */
    suspend fun updateUserInfo(userInfo: UserInfo): Result<Unit> {
        return try {
            dataStore.edit { preferences ->
                val currentUser = preferences[CURRENT_USER_KEY]
                    ?: throw Exception("未登录")

                val usersJson = preferences[USERS_KEY]
                    ?: throw Exception("用户数据不存在")

                val users = json.decodeFromString<MutableMap<String, StoredUser>>(usersJson)
                val storedUser = users[currentUser]
                    ?: throw Exception("用户不存在")

                users[currentUser] = storedUser.copy(userInfo = userInfo)
                preferences[USERS_KEY] = json.encodeToString(users)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取当前用户
     */
    suspend fun getCurrentUser(): UserInfo? {
        return currentUserFlow.first()
    }

    /**
     * 检查是否已登录
     */
    suspend fun isLoggedIn(): Boolean {
        return isLoggedInFlow.first()
    }

    /**
     * 密码哈希
     */
    private fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 存储的用户数据
     */
    @Serializable
    private data class StoredUser(
        val passwordHash: String,
        val userInfo: UserInfo
    )
}
