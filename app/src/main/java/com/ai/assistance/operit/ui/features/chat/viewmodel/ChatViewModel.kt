package com.ai.assistance.operit.ui.features.chat.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.assistance.operit.api.EnhancedAIService
import com.ai.assistance.operit.api.LLMProviderFactory
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.data.model.AttachmentInfo
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.PlanItem
import com.ai.assistance.operit.data.model.ToolExecutionProgress
import com.ai.assistance.operit.ui.features.chat.attachments.AttachmentManager
import com.ai.assistance.operit.ui.permissions.PermissionLevel
import com.ai.assistance.operit.ui.permissions.ToolPermissionSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.ai.assistance.operit.core.voice.recognition.SpeechRecognitionService
import com.ai.assistance.operit.core.voice.recognition.AndroidSpeechRecognitionService
import android.Manifest
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

class ChatViewModel(private val context: Context) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    // 服务收集器设置状态跟踪
    private var serviceCollectorSetupComplete = false

    // API服务
    private var enhancedAiService: EnhancedAIService? = null

    // 工具处理器
    private val toolHandler = AIToolHandler.getInstance(context)

    // 工具权限系统
    private val toolPermissionSystem = ToolPermissionSystem.getInstance(context)

    // 附件管理器
    private val attachmentManager = AttachmentManager(context, toolHandler)

    // 委托类
    private val uiStateDelegate = UiStateDelegate()
    private val tokenStatsDelegate =
            TokenStatisticsDelegate(
                    getEnhancedAiService = { enhancedAiService },
                    updateUiStatistics = { contextSize, inputTokens, outputTokens ->
                        uiStateDelegate.updateChatStatistics(contextSize, inputTokens, outputTokens)
                    }
            )
    private val apiConfigDelegate =
            ApiConfigDelegate(
                    context = context,
                    viewModelScope = viewModelScope,
                    onConfigChanged = { service ->
                        enhancedAiService = service
                        // API配置变更后，异步设置服务收集器
                        viewModelScope.launch {
                            // 重置服务收集器状态，因为服务实例已变更
                            serviceCollectorSetupComplete = false
                            Log.d(TAG, "API配置变更，重置服务收集器状态并重新设置")
                            setupServiceCollectors()
                        }
                    }
            )
    private val planItemsDelegate =
            PlanItemsDelegate(
                    viewModelScope = viewModelScope,
                    getEnhancedAiService = { enhancedAiService }
            )

    // Break circular dependency with lateinit
    private lateinit var chatHistoryDelegate: ChatHistoryDelegate
    lateinit var messageProcessingDelegate: MessageProcessingDelegate
    private lateinit var floatingWindowDelegate: FloatingWindowDelegate

    // Use lazy initialization for exposed properties to avoid circular reference issues
    // API配置相关
    val apiKey: StateFlow<String> by lazy { apiConfigDelegate.apiKey }
    val apiProviderType: StateFlow<LLMProviderFactory.ProviderType> by lazy { apiConfigDelegate.apiProviderType }
    val apiEndpoint: StateFlow<String> by lazy { apiConfigDelegate.apiEndpoint }
    val modelName: StateFlow<String> by lazy { apiConfigDelegate.modelName }
    val isConfigured: StateFlow<Boolean> by lazy { apiConfigDelegate.isConfigured }
    val showThinking: StateFlow<Boolean> by lazy { apiConfigDelegate.showThinking }
    val enableAiPlanning: StateFlow<Boolean> by lazy { apiConfigDelegate.enableAiPlanning }
    val memoryOptimization: StateFlow<Boolean> by lazy { apiConfigDelegate.memoryOptimization }

    // 聊天历史相关
    val chatHistory: StateFlow<List<ChatMessage>> by lazy { chatHistoryDelegate.chatHistory }
    val showChatHistorySelector: StateFlow<Boolean> by lazy {
        chatHistoryDelegate.showChatHistorySelector
    }
    val chatHistories: StateFlow<List<ChatHistory>> by lazy { chatHistoryDelegate.chatHistories }
    val currentChatId: StateFlow<String?> by lazy { chatHistoryDelegate.currentChatId }

    // 消息处理相关
    val userMessage: StateFlow<String> by lazy { messageProcessingDelegate.userMessage }
    val isLoading: StateFlow<Boolean> by lazy { messageProcessingDelegate.isLoading }
    val isProcessingInput: StateFlow<Boolean> by lazy {
        messageProcessingDelegate.isProcessingInput
    }
    val inputProcessingMessage: StateFlow<String> by lazy {
        messageProcessingDelegate.inputProcessingMessage
    }

    val batchedAiContent: StateFlow<String> by lazy {
        messageProcessingDelegate.batchedAiContent
    }

    // UI状态相关
    val errorMessage: StateFlow<String?> by lazy { uiStateDelegate.errorMessage }
    val popupMessage: StateFlow<String?> by lazy { uiStateDelegate.popupMessage }
    val toastEvent: StateFlow<String?> by lazy { uiStateDelegate.toastEvent }
    val toolProgress: StateFlow<ToolExecutionProgress> by lazy { uiStateDelegate.toolProgress }
    val masterPermissionLevel: StateFlow<PermissionLevel> by lazy {
        uiStateDelegate.masterPermissionLevel
    }

    // 聊天统计相关
    val contextWindowSize: StateFlow<Int> by lazy { uiStateDelegate.contextWindowSize }
    val inputTokenCount: StateFlow<Int> by lazy { uiStateDelegate.inputTokenCount }
    val outputTokenCount: StateFlow<Int> by lazy { uiStateDelegate.outputTokenCount }

    // 计划项相关
    val planItems: StateFlow<List<PlanItem>> by lazy { planItemsDelegate.planItems }

    // 悬浮窗相关
    val isFloatingMode: StateFlow<Boolean> by lazy { floatingWindowDelegate.isFloatingMode }

    // 附件相关
    val attachments: StateFlow<List<AttachmentInfo>> by lazy { attachmentManager.attachments }

    // 添加一个用于跟踪附件面板状态的变量
    private val _attachmentPanelState = MutableStateFlow(false)
    val attachmentPanelState: StateFlow<Boolean> = _attachmentPanelState
    
    // 语音识别相关
    private var speechRecognitionService: SpeechRecognitionService? = null
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening
    
    // 语音识别语言设置
    private val _currentVoiceLanguage = MutableStateFlow("")

    init {
        // Initialize delegates in correct order to avoid circular references
        initializeDelegates()

        // Setup additional components
        setupPermissionSystemCollection()
        setupAttachmentManagerToastCollection()
        checkIfShouldCreateNewChat()
        
        // Initialize speech recognition service
        initializeSpeechRecognition()
    }

    private fun initializeDelegates() {
        // First initialize chat history delegate
        chatHistoryDelegate =
                ChatHistoryDelegate(
                        context = context,
                        viewModelScope = viewModelScope,
                        onChatHistoryLoaded = { messages: List<ChatMessage> ->
                            // We'll update floating window messages after it's initialized
                            if (::floatingWindowDelegate.isInitialized) {
                                floatingWindowDelegate.updateFloatingWindowMessages(messages)
                            }
                        },
                        onTokenStatisticsLoaded = { inputTokens: Int, outputTokens: Int ->
                            tokenStatsDelegate.setTokenCounts(inputTokens, outputTokens)
                        },
                        resetPlanItems = { planItemsDelegate.clearPlanItems() },
                        getEnhancedAiService = { enhancedAiService },
                        ensureAiServiceAvailable = { ensureAiServiceAvailable() },
                        getTokenCounts = { tokenStatsDelegate.getCurrentTokenCounts() }
                )

        // Then initialize message processing delegate
        messageProcessingDelegate =
                MessageProcessingDelegate(
                        context = context,
                        viewModelScope = viewModelScope,
                        getEnhancedAiService = { enhancedAiService },
                        getShowThinking = { apiConfigDelegate.showThinking.value },
                        getChatHistory = { chatHistoryDelegate.chatHistory.value },
                        getMemory = { includePlanInfo ->
                            chatHistoryDelegate.getMemory(includePlanInfo)
                        },
                        addMessageToChat = { message ->
                            chatHistoryDelegate.addMessageToChat(message)
                        },
                        updateChatStatistics = {
                            val (inputTokens, outputTokens) =
                                    tokenStatsDelegate.updateChatStatistics()
                            chatHistoryDelegate.saveCurrentChat(inputTokens, outputTokens)
                        },
                        saveCurrentChat = {
                            val (inputTokens, outputTokens) =
                                    tokenStatsDelegate.getCurrentTokenCounts()
                            chatHistoryDelegate.saveCurrentChat(inputTokens, outputTokens)
                        },
                        showErrorMessage = { message -> uiStateDelegate.showErrorMessage(message) }
                )

        // Finally initialize floating window delegate
        floatingWindowDelegate =
                FloatingWindowDelegate(
                        context = context,
                        viewModelScope = viewModelScope,
                        onMessageReceived = { message ->
                            // 更新用户消息
                            messageProcessingDelegate.updateUserMessage(message)
                            // 发送消息时也要传递附件
                            sendUserMessage()
                        },
                        onAttachmentRequested = { request -> processAttachmentRequest(request) },
                        onAttachmentRemoveRequested = { filePath -> removeAttachment(filePath) }
                )
    }

    private fun setupPermissionSystemCollection() {
        viewModelScope.launch {
            toolPermissionSystem.masterSwitchFlow.collect { level ->
                uiStateDelegate.updateMasterPermissionLevel(level)
            }
        }
    }

    private fun setupAttachmentManagerToastCollection() {
        viewModelScope.launch {
            attachmentManager.toastEvent.collect { message -> uiStateDelegate.showToast(message) }
        }
    }

    private fun checkIfShouldCreateNewChat() {
        viewModelScope.launch {
            // 检查历史记录加载后是否需要创建新聊天
            if (chatHistoryDelegate.checkIfShouldCreateNewChat() && isConfigured.value) {
                chatHistoryDelegate.createNewChat()
            }
        }
    }

    /** 设置服务相关的流收集逻辑 */
    private fun setupServiceCollectors() {
        // 避免重复设置服务收集器
        if (serviceCollectorSetupComplete) {
            Log.d(TAG, "服务收集器已经设置完成，跳过重复设置")
            return
        }

        // 确保enhancedAiService不为null
        if (enhancedAiService == null) {
            Log.d(TAG, "EnhancedAIService尚未初始化，跳过服务收集器设置")
            return
        }

        // 设置工具进度收集
        viewModelScope.launch {
            try {
                enhancedAiService?.getToolProgressFlow()?.collect { progress ->
                    uiStateDelegate.updateToolProgress(progress)
                }
            } catch (e: Exception) {
                Log.e(TAG, "工具进度收集出错: ${e.message}", e)
                // 修改：使用错误弹窗显示工具进度收集错误
                uiStateDelegate.showErrorMessage("工具进度收集失败: ${e.message}")
            }
        }

        // 设置输入处理状态收集和计划项收集
        viewModelScope.launch {
            try {
                var inputProcessingSetupComplete = false
                var planItemsSetupComplete = false
                var retryCount = 0
                val maxRetries = 3

                while ((!inputProcessingSetupComplete || !planItemsSetupComplete) &&
                        retryCount < maxRetries) {

                    // 先设置输入处理状态收集
                    if (::messageProcessingDelegate.isInitialized && !inputProcessingSetupComplete
                    ) {
                        try {
                            Log.d(TAG, "设置输入处理状态收集，尝试 ${retryCount + 1}/${maxRetries}")
                            messageProcessingDelegate.setupInputProcessingStateCollection()
                            inputProcessingSetupComplete = true
                            Log.d(TAG, "输入处理状态收集设置成功")
                        } catch (e: Exception) {
                            Log.e(TAG, "设置输入处理状态收集时出错: ${e.message}", e)
                            // 修改：对于重要的初始化错误，使用错误弹窗而不是仅记录日志
                            if (retryCount == maxRetries - 1) {
                                uiStateDelegate.showErrorMessage("无法初始化消息处理: ${e.message}")
                            }
                        }
                    }

                    // 再设置计划项收集
                    if (!planItemsSetupComplete) {
                        try {
                            Log.d(TAG, "设置计划项收集，尝试 ${retryCount + 1}/${maxRetries}")
                            planItemsDelegate.setupPlanItemsCollection()
                            planItemsSetupComplete = true
                            Log.d(TAG, "计划项收集设置成功")
                        } catch (e: Exception) {
                            Log.e(TAG, "设置计划项收集时出错: ${e.message}", e)
                            // 修改：对于重要的初始化错误，使用错误弹窗而不是仅记录日志
                            if (retryCount == maxRetries - 1) {
                                uiStateDelegate.showErrorMessage("无法初始化计划项: ${e.message}")
                            }
                        }
                    }

                    // 如果都已完成，直接退出循环
                    if (inputProcessingSetupComplete && planItemsSetupComplete) {
                        break
                    }

                    // 如果还未完成设置，则等待一段时间后重试
                    retryCount++
                    if (retryCount < maxRetries) {
                        kotlinx.coroutines.delay(500L) // 延迟500毫秒后重试
                    }
                }

                // 记录最终设置状态
                if (!inputProcessingSetupComplete) {
                    Log.e(TAG, "无法设置输入处理状态收集，已达到最大重试次数")
                }
                if (!planItemsSetupComplete) {
                    Log.e(TAG, "无法设置计划项收集，已达到最大重试次数")
                }

                // 只要有一项设置成功，就标记整体服务收集器设置为已完成
                if (inputProcessingSetupComplete || planItemsSetupComplete) {
                    serviceCollectorSetupComplete = true
                    Log.d(TAG, "服务收集器设置已标记为完成")
                }
            } catch (e: Exception) {
                Log.e(TAG, "设置服务收集器时发生异常: ${e.message}", e)
            }
        }
    }

    // API配置相关方法
    fun updateApiKey(key: String) = apiConfigDelegate.updateApiKey(key)
    fun updateApiEndpoint(endpoint: String) = apiConfigDelegate.updateApiEndpoint(endpoint)
    fun updateModelName(model: String) = apiConfigDelegate.updateModelName(model)
    fun saveApiSettings() = apiConfigDelegate.saveApiSettings()
    fun useDefaultConfig() {
        if (apiConfigDelegate.useDefaultConfig()) {
            uiStateDelegate.showToast("使用默认配置继续")
        } else {
            // 修改：使用错误弹窗而不是Toast显示配置错误
            uiStateDelegate.showErrorMessage("默认配置不完整，请填写必要信息")
        }
    }
    fun toggleAiPlanning() {
        apiConfigDelegate.toggleAiPlanning()
        uiStateDelegate.showToast(if (enableAiPlanning.value) "AI计划模式已开启" else "AI计划模式已关闭")
    }
    fun toggleShowThinking() {
        apiConfigDelegate.toggleShowThinking()
        uiStateDelegate.showToast(if (showThinking.value) "思考过程显示已开启" else "思考过程显示已关闭")
    }
    fun toggleMemoryOptimization() {
        apiConfigDelegate.toggleMemoryOptimization()
        uiStateDelegate.showToast(if (memoryOptimization.value) "记忆优化已开启" else "记忆优化已关闭")
    }

    // 聊天历史相关方法
    fun createNewChat() {
        chatHistoryDelegate.createNewChat()
    }
    fun switchChat(chatId: String) = chatHistoryDelegate.switchChat(chatId)
    fun deleteChatHistory(chatId: String) = chatHistoryDelegate.deleteChatHistory(chatId)
    fun clearCurrentChat() {
        chatHistoryDelegate.clearCurrentChat()
        uiStateDelegate.showToast("聊天记录已清空")
    }
    fun toggleChatHistorySelector() = chatHistoryDelegate.toggleChatHistorySelector()
    fun showChatHistorySelector(show: Boolean) = chatHistoryDelegate.showChatHistorySelector(show)
    fun saveCurrentChat() {
        val (inputTokens, outputTokens) = tokenStatsDelegate.getCurrentTokenCounts()
        chatHistoryDelegate.saveCurrentChat(inputTokens, outputTokens)
    }

    // 添加消息编辑方法
    fun updateMessage(index: Int, editedMessage: ChatMessage) {
        viewModelScope.launch {
            try {
                // 获取当前聊天历史
                val currentHistory = chatHistoryDelegate.chatHistory.value.toMutableList()

                // 确保索引有效
                if (index < 0 || index >= currentHistory.size) {
                    uiStateDelegate.showErrorMessage("无效的消息索引")
                    return@launch
                }

                // 更新消息
                currentHistory[index] = editedMessage

                // 将更新后的历史记录保存到ChatHistoryDelegate
                chatHistoryDelegate.updateChatHistory(currentHistory)

                // 更新统计信息并保存
                val (inputTokens, outputTokens) = tokenStatsDelegate.updateChatStatistics()
                chatHistoryDelegate.saveCurrentChat(inputTokens, outputTokens)

                // 显示成功提示
                uiStateDelegate.showToast("消息已更新")
            } catch (e: Exception) {
                Log.e(TAG, "更新消息失败", e)
                uiStateDelegate.showErrorMessage("更新消息失败: ${e.message}")
            }
        }
    }

    /**
     * 回档到指定消息并重新发送
     * @param index 要回档到的消息索引
     * @param editedContent 编辑后的消息内容（如果有）
     */
    fun rewindAndResendMessage(index: Int, editedContent: String) {
        viewModelScope.launch {
            try {
                // 获取当前聊天历史
                val currentHistory = chatHistoryDelegate.chatHistory.value.toMutableList()

                // 确保索引有效
                if (index < 0 || index >= currentHistory.size) {
                    uiStateDelegate.showErrorMessage("无效的消息索引")
                    return@launch
                }

                // 获取目标消息
                val targetMessage = currentHistory[index]

                // 检查目标消息是否是用户消息，如果不是，选择前一条用户消息
                val finalIndex: Int
                val finalMessage: ChatMessage

                if (targetMessage.sender == "user") {
                    finalIndex = index
                    finalMessage = targetMessage.copy(content = editedContent)
                } else {
                    // 查找该消息前最近的用户消息
                    var userMessageIndex = index - 1
                    while (userMessageIndex >= 0 &&
                            currentHistory[userMessageIndex].sender != "user") {
                        userMessageIndex--
                    }

                    if (userMessageIndex < 0) {
                        uiStateDelegate.showErrorMessage("找不到有效的用户消息进行回档")
                        return@launch
                    }

                    finalIndex = userMessageIndex
                    finalMessage = currentHistory[userMessageIndex].copy(content = editedContent)
                }

                // 截取到指定消息的历史记录（包含该消息）
                val rewindHistory = currentHistory.subList(0, finalIndex)

                // 更新ChatHistoryDelegate中的历史记录
                chatHistoryDelegate.updateChatHistory(rewindHistory)

                // 显示重新发送的消息准备状态
                uiStateDelegate.showToast("正在准备重新发送消息")

                // 使用修改后的消息内容来发送
                messageProcessingDelegate.updateUserMessage(finalMessage.content)
                messageProcessingDelegate.sendUserMessage(emptyList())
            } catch (e: Exception) {
                Log.e(TAG, "回档并重新发送消息失败", e)
                uiStateDelegate.showErrorMessage("回档失败: ${e.message}")
            }
        }
    }

    // 消息处理相关方法
    fun updateUserMessage(message: String) = messageProcessingDelegate.updateUserMessage(message)

    fun sendUserMessage() {
        // 获取当前附件列表
        val currentAttachments = attachmentManager.attachments.value

        // 调用messageProcessingDelegate发送消息，并传递附件信息
        messageProcessingDelegate.sendUserMessage(currentAttachments)

        // 发送后清空附件列表
        if (currentAttachments.isNotEmpty()) {
            attachmentManager.clearAttachments()
            // 更新悬浮窗附件列表
            updateFloatingWindowAttachments()
        }

        // 重置附件面板状态 - 在发送消息后关闭附件面板
        resetAttachmentPanelState()
    }

    fun cancelCurrentMessage() {
        messageProcessingDelegate.cancelCurrentMessage()
        uiStateDelegate.showToast("已取消当前对话")
    }

    // UI状态相关方法
    fun showErrorMessage(message: String) = uiStateDelegate.showErrorMessage(message)
    fun clearError() = uiStateDelegate.clearError()
    fun popupMessage(message: String) = uiStateDelegate.showPopupMessage(message)
    fun clearPopupMessage() = uiStateDelegate.clearPopupMessage()
    fun showToast(message: String) = uiStateDelegate.showToast(message)
    fun clearToastEvent() = uiStateDelegate.clearToastEvent()

    // 悬浮窗相关方法
    fun toggleFloatingMode() {
        floatingWindowDelegate.toggleFloatingMode()
    }
    fun updateFloatingWindowMessages(messages: List<ChatMessage>) {
        floatingWindowDelegate.updateFloatingWindowMessages(messages)
    }
    fun updateFloatingWindowAttachments() {
        floatingWindowDelegate.updateFloatingWindowAttachments(attachments.value)
    }

    // 权限相关方法
    fun toggleMasterPermission() {
        viewModelScope.launch {
            val newLevel =
                    if (masterPermissionLevel.value == PermissionLevel.ASK) {
                        PermissionLevel.ALLOW
                    } else {
                        PermissionLevel.ASK
                    }
            toolPermissionSystem.saveMasterSwitch(newLevel)

            uiStateDelegate.showToast(
                    if (newLevel == PermissionLevel.ALLOW) {
                        "已开启自动批准，工具执行将不再询问"
                    } else {
                        "已恢复询问模式，工具执行将询问批准"
                    }
            )
        }
    }

    // 附件相关方法
    /** 处理从悬浮窗接收的附件请求 */
    private fun processAttachmentRequest(request: String) {
        viewModelScope.launch {
            try {
                // 显示附件请求处理进度
                messageProcessingDelegate.setInputProcessingState(true, "正在处理附件请求...")

                when {
                    request == "screen_capture" -> {
                        // 捕获屏幕内容
                        captureScreenContent()
                    }
                    request == "notifications_capture" -> {
                        // 捕获通知
                        captureNotifications()
                    }
                    request == "location_capture" -> {
                        // 捕获位置
                        captureLocation()
                    }
                    request == "problem_memory" -> {
                        // 查询问题记忆 - 使用当前消息作为查询
                        val userQuery = userMessage.value
                        if (userQuery.isNotBlank()) {
                            messageProcessingDelegate.setInputProcessingState(true, "正在搜索问题记忆...")
                            val result = attachmentManager.queryProblemMemory(userQuery)
                            attachProblemMemory(result.first, result.second)
                        } else {
                            // 修改：轻微错误使用 Toast，保持原样
                            uiStateDelegate.showToast("请先输入搜索问题的内容")
                            messageProcessingDelegate.setInputProcessingState(false, "")
                        }
                    }
                    else -> {
                        // 处理普通文件附件
                        handleAttachment(request)
                    }
                }

                // 在各子方法中都已经有设置进度条状态的代码，不需要在这里重复清除
            } catch (e: Exception) {
                Log.e(TAG, "Error processing attachment request", e)
                // 修改: 使用错误弹窗而不是 Toast 显示附件处理错误
                uiStateDelegate.showErrorMessage("处理附件失败: ${e.message}")
                // 确保出错时清除进度显示
                messageProcessingDelegate.setInputProcessingState(false, "")
            }
        }
    }

    /** Handles a file or image attachment selected by the user */
    fun handleAttachment(filePath: String) {
        viewModelScope.launch {
            try {
                // 显示附件处理进度
                messageProcessingDelegate.setInputProcessingState(true, "正在处理附件...")

                attachmentManager.handleAttachment(filePath)

                // 处理完附件后立即更新悬浮窗中的附件列表
                updateFloatingWindowAttachments()

                // 清除附件处理进度显示
                messageProcessingDelegate.setInputProcessingState(false, "")
            } catch (e: Exception) {
                Log.e(TAG, "处理附件失败", e)
                // 修改: 使用错误弹窗而不是 Toast 显示附件处理错误
                uiStateDelegate.showErrorMessage("处理附件失败: ${e.message}")
                // 发生错误时也需要清除进度显示
                messageProcessingDelegate.setInputProcessingState(false, "")
            }
        }
    }

    /** Removes an attachment by its file path */
    fun removeAttachment(filePath: String) {
        attachmentManager.removeAttachment(filePath)
        // 移除附件后立即更新悬浮窗中的附件列表
        updateFloatingWindowAttachments()
    }

    /** Inserts a reference to an attachment at the current cursor position in the user's message */
    fun insertAttachmentReference(attachment: AttachmentInfo) {
        val currentMessage = userMessage.value
        val attachmentRef = attachmentManager.createAttachmentReference(attachment)

        // Insert at the end of the current message
        updateUserMessage("$currentMessage $attachmentRef ")

        // Show a toast to confirm insertion
        uiStateDelegate.showToast("已插入附件引用: ${attachment.fileName}")
    }

    /** Captures the current screen content and attaches it to the message */
    fun captureScreenContent() {
        viewModelScope.launch {
            try {
                messageProcessingDelegate.updateUserMessage("")
                // 显示屏幕内容获取进度
                messageProcessingDelegate.setInputProcessingState(true, "正在获取屏幕内容...")
                uiStateDelegate.showToast("正在获取屏幕内容...")

                attachmentManager.captureScreenContent()

                // 完成后立即更新悬浮窗中的附件列表
                updateFloatingWindowAttachments()

                // 清除进度显示
                messageProcessingDelegate.setInputProcessingState(false, "")
            } catch (e: Exception) {
                Log.e(TAG, "截取屏幕内容失败", e)
                // 修改: 使用错误弹窗而不是 Toast 显示屏幕内容获取错误
                uiStateDelegate.showErrorMessage("截取屏幕内容失败: ${e.message}")
                // 发生错误时也需要清除进度显示
                messageProcessingDelegate.setInputProcessingState(false, "")
            }
        }
    }

    /** 获取设备当前通知数据并添加为附件 */
    fun captureNotifications() {
        viewModelScope.launch {
            try {
                messageProcessingDelegate.updateUserMessage("")
                // 显示通知获取进度
                messageProcessingDelegate.setInputProcessingState(true, "正在获取当前通知...")
                uiStateDelegate.showToast("正在获取当前通知...")

                attachmentManager.captureNotifications()

                // 完成后立即更新悬浮窗中的附件列表
                updateFloatingWindowAttachments()

                // 清除进度显示
                messageProcessingDelegate.setInputProcessingState(false, "")
            } catch (e: Exception) {
                Log.e(TAG, "获取通知数据失败", e)
                // 修改: 使用错误弹窗而不是 Toast 显示通知获取错误
                uiStateDelegate.showErrorMessage("获取通知数据失败: ${e.message}")
                // 发生错误时也需要清除进度显示
                messageProcessingDelegate.setInputProcessingState(false, "")
            }
        }
    }

    /** 获取设备当前位置数据并添加为附件 */
    fun captureLocation() {
        viewModelScope.launch {
            try {
                messageProcessingDelegate.updateUserMessage("")
                // 显示位置获取进度
                messageProcessingDelegate.setInputProcessingState(true, "正在获取位置信息...")
                uiStateDelegate.showToast("正在获取位置信息...")

                attachmentManager.captureLocation()

                // 完成后立即更新悬浮窗中的附件列表
                updateFloatingWindowAttachments()

                // 清除进度显示
                messageProcessingDelegate.setInputProcessingState(false, "")
            } catch (e: Exception) {
                Log.e(TAG, "获取位置数据失败", e)
                // 修改: 使用错误弹窗而不是 Toast 显示位置获取错误
                uiStateDelegate.showErrorMessage("获取位置数据失败: ${e.message}")
                // 发生错误时也需要清除进度显示
                messageProcessingDelegate.setInputProcessingState(false, "")
            }
        }
    }

    /** 添加问题记忆附件 */
    fun attachProblemMemory(content: String, filename: String) {
        viewModelScope.launch {
            try {
                messageProcessingDelegate.updateUserMessage("")
                // 显示问题记忆添加进度
                messageProcessingDelegate.setInputProcessingState(true, "正在添加问题记忆...")
                uiStateDelegate.showToast("正在添加问题记忆...")

                // 将实际处理委托给AttachmentManager
                attachmentManager.attachProblemMemory(content, filename)

                // 完成后立即更新悬浮窗中的附件列表
                updateFloatingWindowAttachments()

                // 清除进度显示
                messageProcessingDelegate.setInputProcessingState(false, "")
            } catch (e: Exception) {
                Log.e(TAG, "添加问题记忆失败", e)
                // 修改: 使用错误弹窗而不是 Toast 显示问题记忆添加错误
                uiStateDelegate.showErrorMessage("添加问题记忆失败: ${e.message}")
                // 发生错误时也需要清除进度显示
                messageProcessingDelegate.setInputProcessingState(false, "")
            }
        }
    }

    /** 搜索问题记忆 */
    fun searchProblemMemory() {
        // 此方法已被 attachProblemMemory 替代
        // 保留此方法以确保向后兼容性
        uiStateDelegate.showToast("请使用新的问题记忆功能")
    }

    /** 确保AI服务可用，如果当前实例为空则创建一个默认实例 */
    fun ensureAiServiceAvailable() {
        if (enhancedAiService == null) {
            viewModelScope.launch {
                try {
                    // 使用默认配置或保存的配置创建一个新实例
                    Log.d(TAG, "创建默认EnhancedAIService实例")
                    apiConfigDelegate.useDefaultConfig()

                    // 等待服务实例创建完成
                    var retryCount = 0
                    while (enhancedAiService == null && retryCount < 3) {
                        kotlinx.coroutines.delay(500)
                        retryCount++
                    }

                    if (enhancedAiService == null) {
                        Log.e(TAG, "无法创建EnhancedAIService实例")
                        // 修改: 使用错误弹窗而不是 Toast 显示服务初始化错误
                        uiStateDelegate.showErrorMessage("无法初始化AI服务，请检查网络和API设置")
                    } else {
                        Log.d(TAG, "成功创建EnhancedAIService实例")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "创建EnhancedAIService实例时出错", e)
                    // 修改: 使用错误弹窗而不是 Toast 显示服务初始化错误
                    uiStateDelegate.showErrorMessage("初始化AI服务失败: ${e.message}")
                }
            }
        }
    }

    /** 重置附件面板状态 - 在发送消息后关闭附件面板 */
    fun resetAttachmentPanelState() {
        _attachmentPanelState.value = false
    }

    /** 更新附件面板状态 */
    fun updateAttachmentPanelState(isOpen: Boolean) {
        _attachmentPanelState.value = isOpen
    }

    /** 初始化语音识别服务 */
    private fun initializeSpeechRecognition() {
        viewModelScope.launch {
            // 创建服务
            speechRecognitionService = AndroidSpeechRecognitionService(context)

            // 设置语音识别的默认语言
            val defaultLanguage = getSystemLanguage()
            setVoiceRecognitionLanguage(defaultLanguage)

            Log.d(TAG, "语音识别服务初始化成功")
            // 设置结果收集器
            setupSpeechRecognitionCollector()
        }
    }
    
    /** 
     * 从系统设置中获取当前语言 
     * @return 语言代码，例如 "zh-CN"
     */
    private fun getSystemLanguage(): String {
        val locale = Locale.getDefault()
        val language = locale.language
        val country = locale.country
        
        // 构建语言代码
        return if (country.isNotBlank()) {
            "$language-$country"
        } else {
            // 对于一些常见语言，提供默认国家代码
            when (language) {
                "zh" -> "zh-CN"
                "en" -> "en-US"
                "jp" -> "ja-JP"
                else -> language
            }
        }
    }
    
    /**
     * 设置语音识别使用的语言
     * @param languageCode 语言代码，例如 "zh-CN", "en-US" 等
     */
    fun setVoiceRecognitionLanguage(languageCode: String) {
        if (languageCode.isNotBlank()) {
            _currentVoiceLanguage.value = languageCode
            speechRecognitionService?.setLanguage(languageCode)
            Log.d(TAG, "语音识别语言已设置为: $languageCode")
        }
    }
    
    /** 设置语音识别结果收集器 */
    private fun setupSpeechRecognitionCollector() {
        speechRecognitionService?.let { service ->
            // 收集部分识别结果 - 实时更新用户输入框
            viewModelScope.launch {
                Log.d(TAG, "开始监听语音识别部分结果")
                service.getPartialResultsFlow().collect { partialText ->
                    // 防止收集到空的部分结果
                    if (partialText.isBlank()) {
                        return@collect
                    }

                    Log.d(TAG, "收到语音识别部分结果: '$partialText'")
                    // 只有当前正在监听时，才更新部分结果
                    if (_isListening.value) {
                        // 在主线程中更新用户消息
                        withContext(Dispatchers.Main) {
                            // 更新用户消息，显示实时识别结果
                            messageProcessingDelegate.updateUserMessage(partialText)
                        }
                    }
                }
            }
            
            // 收集最终识别结果
            viewModelScope.launch {
                Log.d(TAG, "开始监听语音")
                service.getFinalResultsFlow().collect { result ->
                    Log.d(TAG, "收到最终语音识别结果: $result (是否有错误: ${result.error != null})")

                    // 无论如何都停止监听状态，避免卡在监听状态
                    val wasListening = _isListening.value
                    _isListening.value = false

                    if (result.error == null) {
                        Log.d(TAG, "收到有效的语音识别最终结果: '${result.text}', 信心度: ${result.confidence}")

                        withContext(Dispatchers.Main) {
                            // 获取当前输入框内容
                            val currentText = messageProcessingDelegate.userMessage.value.trim()

                            // 确定如何组合新的识别结果
                            val newText = when {
                                // 如果当前没有文本，直接使用识别结果
                                currentText.isEmpty() -> result.text

                                // 如果当前文本已经包含识别结果(部分识别已经更新过)，则不重复添加
                                currentText == result.text -> currentText

                                // 如果当前文本是识别结果的一部分，则使用完整结果
                                result.text.contains(currentText) -> result.text

                                // 否则将新识别结果附加到当前文本后面
                                else -> "$currentText ${result.text}"
                            }

                            // 直接在主线程更新用户消息
                            messageProcessingDelegate.updateUserMessage(newText)
                        }
                    } else {
                        // 识别出错
                        Log.e(TAG, "语音识别错误: ${result.error.message} (Code: ${result.error.code})")
                        uiStateDelegate.showToast("未能识别您的语音，请重试")
                    }

                    // 通知服务停止监听，确保资源释放
                    if (wasListening) {
                        service.stopListening()
                        Log.d(TAG, "语音识别过程完成，停止监听")
                    }
                }
            }
            
            // 监听服务的听取状态，确保UI状态与服务状态同步
            viewModelScope.launch {
                Log.d(TAG, "开始监听语音识别状态变化")
                service.getListeningStateFlow().collect { isServiceListening ->
                    Log.d(TAG, "语音识别状态更新: $isServiceListening")
                    _isListening.value = isServiceListening
                }
            }
        } ?: run {
            Log.e(TAG, "语音识别服务为空，无法设置结果收集器")
        }
    }
    
    /** 请求开始语音识别 */
    fun startVoiceRecognition() {
        viewModelScope.launch {
            // 检查音频录制权限
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                Log.d(TAG, "缺少RECORD_AUDIO权限，语音识别无法工作")
                uiStateDelegate.showToast("需要麦克风权限才能使用语音识别功能")
                return@launch
            }

            // 确保服务已初始化
            if (speechRecognitionService == null) {
                Log.d(TAG, "语音识别服务为空，正在初始化...")
                initializeSpeechRecognition()
            }

            // 避免在已经在监听时重复启动
            if (_isListening.value) {
                Log.d(TAG, "语音识别已经在进行中，忽略重复启动请求")
                return@launch
            }

            // 如果当前语言未设置，则使用系统语言
            if (_currentVoiceLanguage.value.isBlank()) {
                setVoiceRecognitionLanguage(getSystemLanguage())
            }

            // 开始语音识别之前显示状态
            messageProcessingDelegate.setInputProcessingState(true, "正在启动语音识别...")

            // 启动语音识别
            Log.d(TAG, "请求开始语音识别，使用语言: ${_currentVoiceLanguage.value}")
            val success = speechRecognitionService?.startListening(false) ?: false

            if (success) {
                // 我们现在依赖于从service的状态流来更新_isListening
                uiStateDelegate.showToast("请开始说话...")
            }

            // 语音识别启动后不再显示处理状态
            messageProcessingDelegate.setInputProcessingState(false, "")
        }
    }
    
    /** 停止语音识别 */
    fun stopVoiceRecognition() {
        _isListening.value = false
        
        viewModelScope.launch {
            try {
                if (speechRecognitionService != null) {
                    val stopped = speechRecognitionService?.stopListening() ?: false
                    
                    // 对于某些设备，可能需要强制关闭和重建语音识别器
                    if (!stopped) {
                        speechRecognitionService?.shutdown()
                        speechRecognitionService = null
                        initializeSpeechRecognition() // 重新初始化以备下次使用
                    }
                }
            } catch (e: Exception) {
                speechRecognitionService?.shutdown()
                speechRecognitionService = null
                initializeSpeechRecognition() // 重新初始化以备下次使用
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 清理悬浮窗资源
        floatingWindowDelegate.cleanup()
        
        // 清理语音识别资源
        speechRecognitionService?.shutdown()
        speechRecognitionService = null
    }
}
