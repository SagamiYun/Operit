package com.ai.assistance.operit.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallTopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.ui.common.NavItem
import com.ai.assistance.operit.ui.common.displays.FpsCounter
import com.ai.assistance.operit.ui.features.about.screens.AboutScreen
import com.ai.assistance.operit.ui.features.chat.screens.AIChatScreen
import com.ai.assistance.operit.ui.features.demo.screens.ShizukuDemoScreen
import com.ai.assistance.operit.ui.features.help.screens.HelpScreen
import com.ai.assistance.operit.ui.features.packages.screens.PackageManagerScreen
import com.ai.assistance.operit.ui.features.problems.screens.ProblemLibraryScreen
import com.ai.assistance.operit.ui.features.settings.screens.ModelParametersSettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.ModelPromptsSettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.SettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.ThemeSettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.ToolPermissionSettingsScreen
import com.ai.assistance.operit.ui.features.settings.screens.UserPreferencesGuideScreen
import com.ai.assistance.operit.ui.features.settings.screens.UserPreferencesSettingsScreen
import com.ai.assistance.operit.ui.features.token.TokenConfigWebViewScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.AppPermissionsToolScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.FileManagerToolScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.FormatConverterToolScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.ShellExecutorToolScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.TerminalAutoConfigToolScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.TerminalToolScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.ToolboxScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.UIDebuggerToolScreen
import com.ai.assistance.operit.ui.features.toolbox.screens.ffmpegtoolbox.FFmpegToolboxScreen
import com.ai.assistance.operit.ui.features.voice.AIAgentScreen
import com.ai.assistance.operit.ui.main.screens.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent(
        currentScreen: Screen,
        selectedItem: NavItem,
        useTabletLayout: Boolean,
        isTabletSidebarExpanded: Boolean,
        isLoading: Boolean,
        navController: NavController,
        scope: CoroutineScope,
        drawerState: androidx.compose.material3.DrawerState,
        showFpsCounter: Boolean,
        onScreenChange: (Screen) -> Unit,
        onNavItemChange: (NavItem) -> Unit,
        onToggleSidebar: () -> Unit,
        navigateToTokenConfig: () -> Unit,
        onLoading: (Boolean) -> Unit = {},
        onError: (String) -> Unit = {}
) {
    // Get background image state
    val context = LocalContext.current
    val preferencesManager = UserPreferencesManager(context)
    val useBackgroundImage =
            preferencesManager.useBackgroundImage.collectAsState(initial = false).value
    val backgroundImageUri =
            preferencesManager.backgroundImageUri.collectAsState(initial = null).value
    val hasBackgroundImage = useBackgroundImage && backgroundImageUri != null

    // 获取聊天历史管理器
    val chatHistoryManager = ChatHistoryManager(context)
    val chatHistories =
            chatHistoryManager.chatHistoriesFlow.collectAsState(initial = emptyList()).value
    val currentChatId = chatHistoryManager.currentChatIdFlow.collectAsState(initial = null).value

    // 当前聊天标题
    val currentChatTitle =
            if (currentChatId != null) {
                chatHistories.find { it.id == currentChatId }?.title ?: ""
            } else {
                ""
            }

    // 为AIAgentScreen提供完全全屏体验，没有顶部栏
    if (currentScreen is Screen.AIAgent) {
        AIAgentScreen(
            onClose = {
                onScreenChange(Screen.AiChat)
                onNavItemChange(NavItem.AiChat)
            },
            onNavigateToSettings = {
                onScreenChange(Screen.Settings)
                onNavItemChange(NavItem.Settings)
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // 可选: 显示帧率计数器
        if (showFpsCounter) {
            Box(modifier = Modifier.fillMaxSize()) {
                FpsCounter(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 16.dp, end = 16.dp)
                )
            }
        }
    } else {
        // 内容区域仅包含顶部应用栏和主内容，不再使用Scaffold
        Column(
                modifier =
                        Modifier.fillMaxSize()
                                .background(Color.Transparent) // Explicitly set transparent background
        ) {
            // 单一工具栏 - 使用小型化的设计
            SmallTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                    when (currentScreen) {
                                        is Screen.ToolPermission ->
                                                stringResource(id = R.string.tool_permissions)
                                        is Screen.UserPreferencesGuide ->
                                                stringResource(id = R.string.user_preferences_guide)
                                        is Screen.UserPreferencesSettings ->
                                                stringResource(id = R.string.user_preferences_settings)
                                        is Screen.ModelParametersSettings -> "模型参数设置"
                                        is Screen.ModelPromptsSettings -> "模型提示词设置"
                                        is Screen.ThemeSettings -> "主题设置"
                                        is Screen.FormatConverter -> "万能格式转换"
                                        is Screen.FileManager -> "文件管理器"
                                        is Screen.Terminal -> "命令终端"
                                        is Screen.TerminalAutoConfig -> "终端自动配置"
                                        is Screen.AppPermissions -> "应用权限管理"
                                        is Screen.UIDebugger -> "UI调试工具"
                                        is Screen.FFmpegToolbox -> "FFmpeg工具箱"
                                        is Screen.FFmpegVideoConverter -> "FFmpeg视频转换"
                                        is Screen.FFmpegCustomCommand -> "FFmpeg自定义命令"
                                        is Screen.FFmpegVideoCompression -> "FFmpeg视频压缩"
                                        is Screen.FFmpegVideoTrimmer -> "FFmpeg视频裁剪"
                                        is Screen.FFmpegAudioExtractor -> "FFmpeg音频提取"
                                        is Screen.FFmpegVideoMerger -> "FFmpeg视频合并"
                                        is Screen.FFmpegWatermark -> "FFmpeg水印添加"
                                        is Screen.FFmpegGifMaker -> "FFmpeg GIF制作"
                                        is Screen.ShellExecutor -> "命令执行器"
                                        else -> stringResource(id = selectedItem.titleResId)
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onPrimary
                            )

                            // 显示当前聊天标题（仅在AI对话页面)
                            if (currentScreen is Screen.AiChat && currentChatTitle.isNotBlank()) {
                                Text(
                                        text = "- $currentChatTitle",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        // 所有情况下都显示导航按钮
                        IconButton(
                                onClick = {
                                    when (currentScreen) {
                                        is Screen.ThemeSettings,
                                        is Screen.ToolPermission,
                                        is Screen.UserPreferencesGuide,
                                        is Screen.UserPreferencesSettings,
                                        is Screen.ModelParametersSettings,
                                        is Screen.ModelPromptsSettings -> {
                                            // Return to settings screen
                                            onScreenChange(Screen.Settings)
                                            onNavItemChange(NavItem.Settings)
                                        }
                                        is Screen.FormatConverter,
                                        is Screen.FileManager,
                                        is Screen.Terminal,
                                        is Screen.TerminalAutoConfig,
                                        is Screen.AppPermissions,
                                        is Screen.UIDebugger,
                                        is Screen.ShellExecutor,
                                        is Screen.FFmpegToolbox,
                                        is Screen.FFmpegVideoConverter,
                                        is Screen.FFmpegVideoCompression,
                                        is Screen.FFmpegVideoTrimmer,
                                        is Screen.FFmpegAudioExtractor,
                                        is Screen.FFmpegVideoMerger,
                                        is Screen.FFmpegWatermark,
                                        is Screen.FFmpegGifMaker,
                                        is Screen.FFmpegCustomCommand -> {
                                            // 如果在工具箱二级页面，返回到工具箱页面
                                            onScreenChange(Screen.Toolbox)
                                        }
                                        else -> {
                                            // 平板模式下切换侧边栏展开/收起状态
                                            if (useTabletLayout) {
                                                onToggleSidebar()
                                            } else {
                                                // 手机模式下打开抽屉
                                                scope.launch { drawerState.open() }
                                            }
                                        }
                                    }
                                }
                        ) {
                            Icon(
                                    if (currentScreen is Screen.ToolPermission ||
                                                    currentScreen is Screen.UserPreferencesGuide ||
                                                    currentScreen is Screen.UserPreferencesSettings ||
                                                    currentScreen is Screen.ModelParametersSettings ||
                                                    currentScreen is Screen.ThemeSettings ||
                                                    currentScreen is Screen.ModelPromptsSettings ||
                                                    currentScreen is Screen.FormatConverter ||
                                                    currentScreen is Screen.FileManager ||
                                                    currentScreen is Screen.Terminal ||
                                                    currentScreen is Screen.TerminalAutoConfig ||
                                                    currentScreen is Screen.AppPermissions ||
                                                    currentScreen is Screen.UIDebugger ||
                                                    currentScreen is Screen.FFmpegToolbox ||
                                                    currentScreen is Screen.FFmpegVideoConverter ||
                                                    currentScreen is Screen.FFmpegVideoCompression ||
                                                    currentScreen is Screen.FFmpegVideoTrimmer ||
                                                    currentScreen is Screen.FFmpegAudioExtractor ||
                                                    currentScreen is Screen.FFmpegVideoMerger ||
                                                    currentScreen is Screen.FFmpegWatermark ||
                                                    currentScreen is Screen.FFmpegGifMaker ||
                                                    currentScreen is Screen.FFmpegCustomCommand ||
                                                    currentScreen is Screen.ShellExecutor
                                    )
                                            Icons.Default.ArrowBack
                                    else if (useTabletLayout)
                                    // 平板模式下使用开关图标表示收起/展开
                                    if (isTabletSidebarExpanded) Icons.Filled.ChevronLeft
                                            else Icons.Default.Menu
                                    else Icons.Default.Menu,
                                    contentDescription =
                                            when (currentScreen) {
                                                is Screen.ThemeSettings -> "返回设置"
                                                is Screen.ToolPermission,
                                                is Screen.UserPreferencesGuide,
                                                is Screen.UserPreferencesSettings,
                                                is Screen.ModelParametersSettings,
                                                is Screen.ModelPromptsSettings ->
                                                        stringResource(id = R.string.nav_settings)
                                                is Screen.FormatConverter,
                                                is Screen.FileManager,
                                                is Screen.Terminal,
                                                is Screen.TerminalAutoConfig,
                                                is Screen.AppPermissions,
                                                is Screen.UIDebugger,
                                                is Screen.FFmpegToolbox,
                                                is Screen.FFmpegVideoConverter,
                                                is Screen.FFmpegVideoCompression,
                                                is Screen.FFmpegVideoTrimmer,
                                                is Screen.FFmpegAudioExtractor,
                                                is Screen.FFmpegVideoMerger,
                                                is Screen.FFmpegWatermark,
                                                is Screen.FFmpegGifMaker,
                                                is Screen.FFmpegCustomCommand,
                                                is Screen.ShellExecutor -> "返回工具箱"
                                                else ->
                                                        if (useTabletLayout)
                                                                if (isTabletSidebarExpanded) "收起侧边栏"
                                                                else "展开侧边栏"
                                                        else stringResource(id = R.string.menu)
                                            },
                                    tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    },
                    colors =
                            TopAppBarDefaults.smallTopAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                            )
            )

            // 主内容区域
            Surface(
                    modifier = Modifier.fillMaxSize(),
                    color =
                            if (hasBackgroundImage) Color.Transparent
                            else MaterialTheme.colorScheme.background
            ) {
                if (isLoading) {
                    // 加载中状态
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                    modifier = Modifier.size(48.dp),
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                            text = "...",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                    text = "正在加载...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                } else {
                    // 主要内容
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (currentScreen) {
                            is Screen.ToolPermission -> {
                                // 工具权限设置页面
                                ToolPermissionSettingsScreen(
                                        navigateBack = {
                                            onScreenChange(Screen.Settings)
                                            onNavItemChange(NavItem.Settings)
                                        }
                                )
                            }
                            is Screen.UserPreferencesGuide -> {
                                // 用户偏好引导页面
                                val screen = currentScreen as Screen.UserPreferencesGuide
                                UserPreferencesGuideScreen(
                                        profileName = screen.profileName,
                                        profileId = screen.profileId,
                                        onComplete = {
                                            onScreenChange(Screen.Settings)
                                            onNavItemChange(NavItem.Settings)
                                        },
                                        navigateToPermissions = {
                                            onScreenChange(Screen.ShizukuCommands)
                                            onNavItemChange(NavItem.ShizukuCommands) // 直接跳转到权限授予界面
                                        }
                                )
                            }
                            is Screen.UserPreferencesSettings -> {
                                UserPreferencesSettingsScreen(
                                        onNavigateBack = { onScreenChange(Screen.Settings) },
                                        onNavigateToGuide = { profileName, profileId ->
                                            // 导航到引导页并传递配置信息
                                            val guide = Screen.UserPreferencesGuide
                                            guide.profileName = profileName
                                            guide.profileId = profileId
                                            onScreenChange(guide)
                                        }
                                )
                            }
                            is Screen.FormatConverter -> {
                                // 格式转换工具屏幕
                                FormatConverterToolScreen(navController = navController)
                            }
                            is Screen.FileManager -> {
                                // 文件管理器屏幕
                                FileManagerToolScreen(navController = navController)
                            }
                            is Screen.Terminal -> {
                                // 终端工具屏幕
                                TerminalToolScreen(navController = navController)
                            }
                            is Screen.TerminalAutoConfig -> {
                                // 终端自动配置屏幕
                                TerminalAutoConfigToolScreen(navController = navController)
                            }
                            is Screen.AppPermissions -> {
                                // 应用权限管理屏幕
                                AppPermissionsToolScreen(navController = navController)
                            }
                            is Screen.UIDebugger -> {
                                // UI调试工具屏幕
                                UIDebuggerToolScreen(navController = navController)
                            }
                            is Screen.FFmpegToolbox -> {
                                // FFmpeg工具箱屏幕
                                FFmpegToolboxScreen(navController = navController)
                            }
                            is Screen.FFmpegVideoConverter -> {
                                // 已移除FFmpegVideoConverterScreen，但保留导航case避免编译错误
                                // 直接重定向到FFmpegToolbox页面
                                onScreenChange(Screen.FFmpegToolbox)
                                FFmpegToolboxScreen(navController = navController)
                            }
                            is Screen.FFmpegCustomCommand -> {
                                // 已移除FFmpegCustomCommandScreen，但保留导航case避免编译错误
                                // 直接重定向到FFmpegToolbox页面
                                onScreenChange(Screen.FFmpegToolbox)
                                FFmpegToolboxScreen(navController = navController)
                            }
                            is Screen.FFmpegVideoCompression -> {
                                // FFmpeg视频压缩屏幕
                                // 实现FFmpeg视频压缩屏幕的逻辑
                                onScreenChange(Screen.FFmpegToolbox)
                                FFmpegToolboxScreen(navController = navController)
                            }
                            is Screen.FFmpegVideoTrimmer -> {
                                // FFmpeg视频裁剪屏幕
                                // 实现FFmpeg视频裁剪的逻辑
                                onScreenChange(Screen.FFmpegToolbox)
                                FFmpegToolboxScreen(navController = navController)
                            }
                            is Screen.FFmpegAudioExtractor -> {
                                // FFmpeg音频提取屏幕
                                // 实现FFmpeg音频提取的逻辑
                                onScreenChange(Screen.FFmpegToolbox)
                                FFmpegToolboxScreen(navController = navController)
                            }
                            is Screen.FFmpegVideoMerger -> {
                                // FFmpeg视频合并屏幕
                                // 实现FFmpeg视频合并的逻辑
                                onScreenChange(Screen.FFmpegToolbox)
                                FFmpegToolboxScreen(navController = navController)
                            }
                            is Screen.FFmpegWatermark -> {
                                // FFmpeg水印添加屏幕
                                // 实现FFmpeg水印添加的逻辑
                                onScreenChange(Screen.FFmpegToolbox)
                                FFmpegToolboxScreen(navController = navController)
                            }
                            is Screen.FFmpegGifMaker -> {
                                // FFmpeg GIF制作屏幕
                                // 实现FFmpeg GIF制作的逻辑
                                onScreenChange(Screen.FFmpegToolbox)
                                FFmpegToolboxScreen(navController = navController)
                            }
                            is Screen.ShellExecutor -> {
                                // 命令执行器屏幕
                                ShellExecutorToolScreen(navController = navController)
                            }
                            is Screen.AiChat ->
                                    AIChatScreen(
                                            padding = PaddingValues(0.dp),
                                            viewModel = null,
                                            isFloatingMode = false,
                                            hasBackgroundImage = hasBackgroundImage,
                                            onNavigateToTokenConfig = navigateToTokenConfig,
                                            onNavigateToSettings = {
                                                onScreenChange(Screen.Settings)
                                                onNavItemChange(NavItem.Settings)
                                            },
                                            onNavigateToAIAgent = {
                                                onScreenChange(Screen.AIAgent)
                                            },
                                            onLoading = onLoading,
                                            onError = onError
                                    )
                            is Screen.ShizukuCommands -> ShizukuDemoScreen()
                            is Screen.Toolbox -> {
                                // 工具箱页面
                                ToolboxScreen(
                                        navController = navController,
                                        onFormatConverterSelected = {
                                            onScreenChange(Screen.FormatConverter)
                                        },
                                        onFileManagerSelected = { onScreenChange(Screen.FileManager) },
                                        onTerminalSelected = { onScreenChange(Screen.Terminal) },
                                        onTerminalAutoConfigSelected = {
                                            onScreenChange(Screen.TerminalAutoConfig)
                                        },
                                        onAppPermissionsSelected = {
                                            onScreenChange(Screen.AppPermissions)
                                        },
                                        onUIDebuggerSelected = { onScreenChange(Screen.UIDebugger) },
                                        onFFmpegToolboxSelected = {
                                            onScreenChange(Screen.FFmpegToolbox)
                                        },
                                        onShellExecutorSelected = { 
                                            onScreenChange(Screen.ShellExecutor)
                                        }
                                )
                            }
                            is Screen.Settings ->
                                    SettingsScreen(
                                            navigateToToolPermissions = {
                                                onScreenChange(Screen.ToolPermission)
                                            },
                                            onNavigateToUserPreferences = {
                                                onScreenChange(Screen.UserPreferencesSettings)
                                            },
                                            navigateToModelParameters = {
                                                onScreenChange(Screen.ModelParametersSettings)
                                            },
                                            navigateToThemeSettings = {
                                                onScreenChange(Screen.ThemeSettings)
                                            },
                                            navigateToModelPrompts = {
                                                onScreenChange(Screen.ModelPromptsSettings)
                                            }
                                    )
                            is Screen.ModelParametersSettings -> ModelParametersSettingsScreen()
                            is Screen.ModelPromptsSettings ->
                                    ModelPromptsSettingsScreen(
                                            onBackPressed = {
                                                onScreenChange(Screen.Settings)
                                                onNavItemChange(NavItem.Settings)
                                            }
                                    )
                            is Screen.ThemeSettings -> ThemeSettingsScreen()
                            is Screen.Packages -> PackageManagerScreen()
                            is Screen.ProblemLibrary -> {
                                // 问题库页面
                                ProblemLibraryScreen()
                            }
                            is Screen.About -> AboutScreen()
                            is Screen.Help ->
                                    HelpScreen(
                                            onBackPressed = {
                                                onScreenChange(Screen.AiChat)
                                                onNavItemChange(NavItem.AiChat)
                                            }
                                    )
                            is Screen.TokenConfig -> {
                                // 显示TokenConfigWebViewScreen
                                TokenConfigWebViewScreen(
                                        onNavigateBack = {
                                            onScreenChange(Screen.AiChat)
                                            onNavItemChange(NavItem.AiChat)
                                        }
                                )
                            }
                            // AIAgent屏幕已经在外部条件中处理
                            else -> {
                                // 这里不应该有任何处理，因为所有类型都已经处理过了
                            }
                        }

                        // 帧率计数器 - 放在右上角
                        if (showFpsCounter) {
                            FpsCounter(
                                    modifier =
                                            Modifier.align(Alignment.TopEnd)
                                                    .padding(top = 80.dp, end = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
