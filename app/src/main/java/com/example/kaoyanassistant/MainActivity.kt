package com.example.kaoyanassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.kaoyanassistant.ui.chat.ChatScreen
import com.example.kaoyanassistant.ui.chat.ChatViewModel
import com.example.kaoyanassistant.ui.document.DocumentScreen
import com.example.kaoyanassistant.ui.document.DocumentViewModel
import com.example.kaoyanassistant.ui.login.LoginScreen
import com.example.kaoyanassistant.ui.login.LoginViewModel
import com.example.kaoyanassistant.ui.school.SchoolSelectionScreen
import com.example.kaoyanassistant.ui.school.SchoolSelectionViewModel
import com.example.kaoyanassistant.ui.settings.SettingsScreen
import com.example.kaoyanassistant.ui.settings.SettingsViewModel
import com.example.kaoyanassistant.ui.studyplan.StudyPlanScreen
import com.example.kaoyanassistant.ui.studyplan.StudyPlanViewModel
import com.example.kaoyanassistant.ui.theme.KaoyanAssistantTheme

/**
 * 导航路由
 */
sealed class Screen(val route: String, val title: String, val icon: @Composable () -> Unit) {
    object Login : Screen("login", "登录", { Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null) })
    object Chat : Screen("chat", "AI助手", { Icon(Icons.Default.SmartToy, contentDescription = null) })
    object SchoolSelection : Screen("school_selection", "择校", { Icon(Icons.Default.School, contentDescription = null) })
    object StudyPlan : Screen("study_plan", "计划", { Icon(Icons.Default.CalendarMonth, contentDescription = null) })
    object Documents : Screen("documents", "资料", { Icon(Icons.Default.Description, contentDescription = null) })
    object Settings : Screen("settings", "设置", { Icon(Icons.Default.Settings, contentDescription = null) })
}

// 底部导航栏显示的页面
val bottomNavItems = listOf(
    Screen.Chat,
    Screen.SchoolSelection,
    Screen.StudyPlan,
    Screen.Documents,
    Screen.Settings
)

/**
 * 主Activity
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as KaoyanApplication

        setContent {
            KaoyanAssistantTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation(app)
                }
            }
        }
    }
}

/**
 * 主导航
 */
@Composable
fun MainNavigation(app: KaoyanApplication) {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current

    // 登录状态
    val isLoggedIn by app.userManager.isLoggedInFlow.collectAsState(initial = false)
    var isCheckingLogin by remember { mutableStateOf(true) }

    // 检查登录状态
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100) // 短暂延迟确保状态加载
        isCheckingLogin = false
    }

    // 创建ViewModels
    val loginViewModel: LoginViewModel = viewModel(
        factory = LoginViewModel.Factory(app.userManager)
    )
    val chatViewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory(app.configManager, app.documentManager, context)
    )
    val documentViewModel: DocumentViewModel = viewModel(
        factory = DocumentViewModel.Factory(app.documentManager)
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(app.configManager, app.userManager)
    )
    val schoolSelectionViewModel: SchoolSelectionViewModel = viewModel(
        factory = SchoolSelectionViewModel.Factory(app.configManager, app.userManager)
    )
    val studyPlanViewModel: StudyPlanViewModel = viewModel(
        factory = StudyPlanViewModel.Factory(app.studyPlanManager, app.userManager, app.configManager)
    )

    // 当前路由
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // 是否显示底部导航栏
    val showBottomBar = isLoggedIn && currentRoute in bottomNavItems.map { it.route }

    // 加载中显示
    if (isCheckingLogin) {
        Box(modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator(
                modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
            )
        }
        return
    }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                NavigationBar {
                    bottomNavItems.forEach { screen ->
                        val selected = navBackStackEntry?.destination?.hierarchy?.any {
                            it.route == screen.route
                        } == true

                        NavigationBarItem(
                            icon = screen.icon,
                            label = { Text(screen.title) },
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = if (isLoggedIn) Screen.Chat.route else Screen.Login.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            // 登录界面
            composable(Screen.Login.route) {
                LoginScreen(
                    viewModel = loginViewModel,
                    onLoginSuccess = {
                        navController.navigate(Screen.Chat.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    }
                )
            }

            // 聊天界面
            composable(Screen.Chat.route) {
                ChatScreen(
                    viewModel = chatViewModel,
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onNavigateToDocuments = {
                        navController.navigate(Screen.Documents.route)
                    }
                )
            }

            // 择校界面
            composable(Screen.SchoolSelection.route) {
                SchoolSelectionScreen(
                    viewModel = schoolSelectionViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            // 学习计划界面
            composable(Screen.StudyPlan.route) {
                StudyPlanScreen(
                    viewModel = studyPlanViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            // 文档管理界面
            composable(Screen.Documents.route) {
                DocumentScreen(
                    viewModel = documentViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onDocumentsSelected = { selectedIds ->
                        chatViewModel.setSelectedDocuments(selectedIds)
                    }
                )
            }

            // 设置界面
            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onLogout = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}
