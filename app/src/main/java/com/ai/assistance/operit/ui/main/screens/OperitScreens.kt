package com.ai.assistance.operit.ui.main.screens

// Hierarchical screen representation to replace multiple boolean flags
sealed class Screen {
    // Main screens (primary)
    data object AiChat : Screen()
    data object ProblemLibrary : Screen()
    data object Packages : Screen()
    data object Toolbox : Screen()
    data object ShizukuCommands : Screen()
    data object Settings : Screen()
    data object Help : Screen()
    data object About : Screen()
    data object TokenConfig : Screen() // New screen for token configuration
    data object AIAgent : Screen() // New screen for AI Agent voice dialogue mode

    // Secondary screens
    data object ToolPermission : Screen()
    data object UserPreferencesGuide : Screen() {
        var profileName: String = ""
        var profileId: String = ""
    }
    data object UserPreferencesSettings : Screen()
    data object ModelParametersSettings : Screen()
    data object ModelPromptsSettings : Screen() // Add new ModelPromptsSettings screen
    data object ThemeSettings : Screen() // Add new ThemeSettings screen

    // Toolbox secondary screens
    data object FormatConverter : Screen()
    data object FileManager : Screen()
    data object Terminal : Screen()
    data object TerminalAutoConfig : Screen()
    data object AppPermissions : Screen()
    data object UIDebugger : Screen() // 添加新的UI调试工具屏幕
    data object ShellExecutor : Screen() // 添加命令执行器屏幕

    // FFmpeg Toolbox screens
    data object FFmpegToolbox : Screen()
    data object FFmpegVideoConverter : Screen()
    data object FFmpegVideoCompression : Screen()
    data object FFmpegVideoTrimmer : Screen()
    data object FFmpegAudioExtractor : Screen()
    data object FFmpegVideoMerger : Screen()
    data object FFmpegWatermark : Screen()
    data object FFmpegGifMaker : Screen()
    data object FFmpegCustomCommand : Screen()
}
