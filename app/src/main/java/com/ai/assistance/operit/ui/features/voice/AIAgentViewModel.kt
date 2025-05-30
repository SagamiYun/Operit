package com.ai.assistance.operit.ui.features.voice

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.assistance.operit.core.voice.dialogue.DialogueManager
import com.ai.assistance.operit.data.model.voice.DialogueTurn
import com.ai.assistance.operit.data.model.voice.VoicePreferences
import com.ai.assistance.operit.data.preferences.voice.VoicePreferencesManager
import com.ai.assistance.operit.services.voice.VoiceInteractionService
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ViewModel for the AIAgent screen that manages voice interactions with the AI.
 * Integrates with the DialogueManager, TTSService, SpeechRecognitionService, and ChatViewModel.
 */
class AIAgentViewModel(application: Application) : AndroidViewModel(application) {

    private val voicePreferencesManager = VoicePreferencesManager(application)

    // Access the ChatViewModel through a property delegate
    private val chatViewModel = ChatViewModel(application)

    // Service connection
    private var voiceService: VoiceInteractionService? = null
    private var serviceBound = false

    // UI state
    private val _dialogueState = MutableStateFlow(DialogueManager.DialogueState.IDLE)
    val dialogueState: StateFlow<DialogueManager.DialogueState> = _dialogueState.asStateFlow()

    private val _partialSpeechText = MutableStateFlow("")
    val partialSpeechText: StateFlow<String> = _partialSpeechText.asStateFlow()

    private val _dialogueHistory = MutableStateFlow<List<DialogueTurn>>(emptyList())
    val dialogueHistory: StateFlow<List<DialogueTurn>> = _dialogueHistory.asStateFlow()

    private val _voicePreferences = MutableStateFlow(VoicePreferences())
    val voicePreferences: StateFlow<VoicePreferences> = _voicePreferences.asStateFlow()

    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

    // Waveform visualization state
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _visualizationAmplitude = MutableStateFlow(0f)
    val visualizationAmplitude: StateFlow<Float> = _visualizationAmplitude.asStateFlow()

    // For handling AI responses
    private val _currentResponse = MutableStateFlow("")
    val currentResponse: StateFlow<String> = _currentResponse.asStateFlow()

    private val _processingAIRequest = MutableStateFlow(false)
    val processingAIRequest: StateFlow<Boolean> = _processingAIRequest.asStateFlow()

    private val _lastProcessedMessage = MutableStateFlow("")
    private val _lastAIResponse = MutableStateFlow("")

    // 跟踪是否是正在等待响应完成
    private val _waitingForResponseCompletion = MutableStateFlow(false)

    companion object {
        private const val TAG = "AIAgentViewModel"
    }

    // Service connection object
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as VoiceInteractionService.VoiceInteractionBinder
            voiceService = binder.getService()
            serviceBound = true
            _serviceConnected.value = true

            // Start observing dialogue state
            observeDialogueState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            voiceService = null
            serviceBound = false
            _serviceConnected.value = false
        }
    }

    init {
        // Bind to the service
        bindVoiceService()

        // Observe voice preferences
        viewModelScope.launch {
            voicePreferencesManager.voicePreferencesFlow.collectLatest { preferences ->
                _voicePreferences.value = preferences
            }
        }
    }

    /**
     * Bind to the VoiceInteractionService.
     */
    private fun bindVoiceService() {
        val context = getApplication<Application>()
        val intent = Intent(context, VoiceInteractionService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        context.startService(intent)
    }

    /**
     * Observe dialogue state and other flows from the service.
     */
    private fun observeDialogueState() {
        val service = voiceService ?: return

        // Only observe once the dialogueManager is initialized
        if (!isInitialized(service)) {
            Log.d(TAG, "DialogueManager not initialized yet")
            return
        }

        viewModelScope.launch {
            service.dialogueState.collectLatest { state ->
                _dialogueState.value = state
                // Update listening state for waveform visualization
                _isListening.value = state == DialogueManager.DialogueState.LISTENING
            }
        }

        viewModelScope.launch {
            service.partialSpeechText.collectLatest { text ->
                _partialSpeechText.value = text
            }
        }

        viewModelScope.launch {
            service.dialogueHistory.collectLatest { history ->
                _dialogueHistory.value = history
            }
        }

        viewModelScope.launch {
            service.audioAmplitude.collectLatest { amplitude ->
                _visualizationAmplitude.value = amplitude
            }
        }

        // Register speech result handler to send messages to ChatViewModel
        service.setSpeechResultHandler { userText ->
            handleUserSpeechInput(userText)
        }
    }

    /**
     * Handle user speech input by sending it to the ChatViewModel.
     */
    private fun handleUserSpeechInput(userText: String) {
        if (userText.isBlank()) return

        // Prevent processing if already handling a request
        if (_processingAIRequest.value) {
            Log.d(TAG, "Already processing a request, ignoring new input: $userText")
            return
        }

        // Set flag to prevent duplicate processing
        _processingAIRequest.value = true
        _waitingForResponseCompletion.value = true

        // Check if this is a duplicate message
        if (userText == _lastProcessedMessage.value) {
            Log.d(TAG, "Duplicate message detected, ignoring: $userText")
            _processingAIRequest.value = false
            _waitingForResponseCompletion.value = false
            return
        }

        // Update user message in ChatViewModel
        chatViewModel.messageProcessingDelegate.updateUserMessage(userText)

        // Track that we've sent this message to prevent duplicates
        _lastProcessedMessage.value = userText

        // 添加时间戳防止取到错误的resp
        val sendTimeMillis = System.currentTimeMillis()

//        // 监控AI响应以更新UI，但不立即朗读
//        viewModelScope.launch {
//            try {
//                chatViewModel.chatHistory.collectLatest { messages ->
//                    val latestMessage = messages.lastOrNull { it.sender == "ai" }
//                    val latestUserMessage = messages.lastOrNull { it.sender == "user" }
//
//                    // 确保我们处理的是回应当前用户消息的AI响应
//                    if (messages.isNotEmpty() &&
//                        messages.lastOrNull()?.sender == "ai" &&
//                        latestUserMessage?.timestamp == sendTimeMillis) {
//
//                        latestMessage?.let { aiMessage ->
//                            val currentContent = aiMessage.content
//
//                            // 更新当前响应UI
//                            _currentResponse.value = currentContent
//
//                            // 保存最终的AI响应内容（会不断更新，直到响应完成）
//                            _finalAIResponse.value = currentContent
//
//                            // 记录最后处理的AI响应
//                            _lastAIResponse.value = currentContent
//                        }
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error collecting chat history: ${e.message}", e)
//                _processingAIRequest.value = false
//                _waitingForResponseCompletion.value = false
//            }
//        }

        // 发送用户消息到ChatViewModel
        chatViewModel.messageProcessingDelegate.sendUserMessage(sendTimeMillis)

        // 监听处理完成事件
        viewModelScope.launch {
            try {
                chatViewModel.messageProcessingDelegate.isLoading.collectLatest { isLoading ->
                    var cacheText: String = ""
                    if (!isLoading && _waitingForResponseCompletion.value) {
                        chatViewModel.chatHistory.collectLatest { messages ->
                            val latestUserMessage = messages.lastOrNull { it.sender == "user" }
                            val lastOrNull = messages.lastOrNull()
                            // 确保我们处理的是回应当前用户消息的AI响应
                            if (messages.isNotEmpty() &&
                                lastOrNull?.sender == "ai" &&
                                latestUserMessage?.timestamp == sendTimeMillis &&
                                lastOrNull.content != cacheText) {

                                speakFilterResp(lastOrNull.content)
                                cacheText = lastOrNull.content;

                                // AI响应加载完成，可以朗读最终结果
                                _waitingForResponseCompletion.value = false

                                // 重置处理标志
                                _processingAIRequest.value = false
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing loading state: ${e.message}", e)
                _processingAIRequest.value = false
                _waitingForResponseCompletion.value = false
            }
        }
    }

    /**
     * 从完整的AI回复中提取出最终的用户可读回复部分
     * 过滤掉工具调用、思考过程等技术细节
     */
    private fun extractFinalResponse(fullContent: String): String {
        if (fullContent.isBlank()) return ""

        try {
            var content = fullContent
            
            // 存储检测到的非技术性文本，用于最后组合
            val humanReadableParts = mutableListOf<String>()
            
            // 工具调用标签模式，更严格的匹配完整的工具调用块
            val fullToolPattern = Regex("<tool\\s+name=\"[^\"]+\"[^>]*>[\\s\\S]*?</tool>")
            content = content.replace(fullToolPattern, "")
            
            // 单行自闭合工具标签
            val selfClosingToolPattern = Regex("<tool[^>]*/>")
            content = content.replace(selfClosingToolPattern, "")
            
            // 参数标签 - 更精确匹配
            val paramPattern = Regex("<param\\s+name=\"[^\"]+\">[\\s\\S]*?</param>")
            content = content.replace(paramPattern, "")
            
            // 状态标签 - 完整匹配所有类型
            val allStatusTags = listOf(
                // 执行中状态 - 完整匹配
                Regex("<status\\s+type=\"executing\"[^>]*>[\\s\\S]*?</status>"),
                // 结果状态 - 完整匹配，可能包含成功信息
                Regex("<status\\s+type=\"result\"[^>]*>[\\s\\S]*?</status>"),
                // 错误状态
                Regex("<status\\s+type=\"error\"[^>]*>[\\s\\S]*?</status>"),
                // 警告状态
                Regex("<status\\s+type=\"warning\"[^>]*>[\\s\\S]*?</status>"),
                // 完成状态
                Regex("<status\\s+type=\"complete\"[^>]*>[\\s\\S]*?</status>"),
                // 思考状态
                Regex("<status\\s+type=\"thinking\"[^>]*>[\\s\\S]*?</status>"),
                // 等待用户状态
                Regex("<status\\s+type=\"wait_for_user_need\"[^>]*>[\\s\\S]*?</status>"),
                // 任何其他状态标签 - 通用匹配
                Regex("<status[^>]*>[\\s\\S]*?</status>"),
                // 自闭合状态标签
                Regex("<status[^>]*/>")
            )
            
            for (pattern in allStatusTags) {
                content = content.replace(pattern, "")
            }
            
            // 工具结果标签 - 完整匹配，确保嵌套内容也被处理
            val toolResultPattern = Regex("<tool_result\\s+name=\"[^\"]+\"\\s+status=\"[^\"]+\">[\\s\\S]*?</tool_result>")
            content = content.replace(toolResultPattern, "")
            
            // 内容标签 - 完整匹配
            val contentTagPattern = Regex("<content>[\\s\\S]*?</content>")
            content = content.replace(contentTagPattern, "")
            
            // 清除其他可能的XML标签
            val otherTagsPattern = Regex("</?[a-zA-Z][^>]*>")
            content = content.replace(otherTagsPattern, "")
            
            // 删除工具执行成功的特定格式文本
            val successPattern = Regex("(?:成功启动应用|Successfully launched app|成功执行)[^\\n。]*")
            content = content.replace(successPattern, "")
            
            // 删除工具不可用的特定格式文本
            val toolNotAvailablePattern = Regex("(?:[Tt]he tool|[Tt]ool)\\s+[`'\"]?[\\w:]+[`'\"]?\\s+is not available[^\\n]*")
            content = content.replace(toolNotAvailablePattern, "")
            
            // 处理使用包的相关信息
            val packageInfoPattern = Regex("(?:[Uu]sing package|use_package):[^\\n]*")
            content = content.replace(packageInfoPattern, "")
            
            // 处理工具类型信息
            val toolTypePattern = Regex("(?:time|daily_life):[\\w:]+[^\\n]*")
            content = content.replace(toolTypePattern, "")
            
            // 处理可用工具信息
            val availableToolsPattern = Regex("Available tools[^\\n]*")
            content = content.replace(availableToolsPattern, "")
            
            // 处理描述信息
            val descriptionPattern = Regex("Description:[^\\n]*")
            content = content.replace(descriptionPattern, "")
            
            // 处理时间信息
            val timePattern = Regex("Use Time:[^\\n]*")
            content = content.replace(timePattern, "")
            
            // 处理任务完成信息
            val taskCompletedPattern = Regex("Task completed")
            content = content.replace(taskCompletedPattern, "")
            
            // 清理多余空白
            content = content
                .replace(Regex("\\n{3,}"), "\n\n") // 将3个以上连续换行减为2个
                .replace(Regex("^\\s+"), "") // 清理开头空白
                .replace(Regex("\\s+$"), "") // 清理结尾空白
                .replace(Regex("[ \\t]+\\n"), "\n") // 清理行尾空白
                .replace(Regex("\\n[ \\t]+"), "\n") // 清理行首空白
                .trim()
            
            // 检查是否有任何有意义的内容剩下
            if (content.isBlank()) {
                // 尝试从原始内容中提取最后的人类可读内容
                // 通常工具调用完成后会有一条结论性语句
                val sentences = fullContent
                    .replace(Regex("<[^>]*>"), "\n") // 将所有标签替换为换行
                    .split(Regex("[.。!！?？\n]+")) // 按句子分隔符和换行符分割
                    .filter { it.trim().length > 5 } // 过滤掉太短的句子
                    .filter { !it.contains(Regex("成功[^。]*:|Successfully|is not available|[Aa]vailable tools|[Dd]escription:|[Uu]se [Tt]ime:|[Tt]ask completed")) } // 过滤技术内容
                
                // 返回最后一个句子，通常是总结
                val lastSentence = sentences.lastOrNull { it.trim().isNotEmpty() }
                if (lastSentence != null) {
                    return lastSentence.trim()
                }
                
                // 如果无法提取句子，尝试匹配最后的中文内容
                val chineseContent = extractChineseContent(fullContent)
                if (chineseContent.isNotBlank()) {
                    return chineseContent
                }
                
                // 兜底：返回净化后的原始内容
                return fullContent.replace(Regex("<[^>]*>"), "").trim()
            }
            
            return content
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting final response: ${e.message}", e)
            // 兜底：如果解析失败，直接移除所有尖括号标签并返回
            return fullContent.replace(Regex("<[^>]*>"), "").trim()
        }
    }
    
    /**
     * 从文本中提取中文内容
     */
    private fun extractChineseContent(text: String): String {
        // TODO
        // 尝试提取完整的中文段落
//        val chineseParagraph = Regex("[一-龥，。！？；：、（）""''【】—…《》·]+[。！？]").findAll(text)
//            .map { it.value }
//            .joinToString(" ")
//
//        if (chineseParagraph.isNotBlank()) {
//            return chineseParagraph
//        }
//
//        // 如果没有完整段落，尝试提取任何中文内容
//        val anyChineseContent = Regex("[一-龥，。！？；：、（）""''【】—…《》·]+").findAll(text)
//            .map { it.value }
//            .joinToString(" ")
            
        return text
    }

    /**
     * Check if the dialogueManager is initialized in the service.
     */
    private fun isInitialized(service: VoiceInteractionService): Boolean {
        return try {
            // This will throw an exception if dialogueManager is not initialized
            service.dialogueManager
            true
        } catch (e: UninitializedPropertyAccessException) {
            false
        }
    }

    /**
     * Start listening for user speech.
     */
    fun startListening() {
        Log.d(TAG, "startListening")
        voiceService?.startListening(continuous = _voicePreferences.value.continuousListening) { userText ->
            // This callback will be handled by the service's speech result handler
        }
    }

    /**
     * Stop listening for user speech.
     */
    fun stopListening() {
        Log.d(TAG, "stopListening")
        voiceService?.stopListening()
    }

    /**
     * Stop the TTS engine from speaking.
     */
    fun stopSpeaking() {
        Log.d(TAG, "stopSpeaking")
        voiceService?.stopSpeaking()
    }

    /**
     * Speak a response using TTS.
     */
    suspend fun speakResponse(text: String) {
        voiceService?.speakResponse(text)
    }

    /**
     * Pause the TTS engine from speaking.
     */
    suspend fun pauseSpeaking() {
        voiceService?.pauseSpeaking()
    }

    /**
     * 使用过滤器处理AI响应文本，使其更适合TTS朗读。
     * 处理标签、代码块、特殊符号和标点符号等。
     */
    suspend fun speakFilterResp(text: String) {
        if (text.isBlank()) return

        // 对原始文本应用过滤处理过滤工具标签等
        val filteredText = filterTextForTTS(extractFinalResponse(text))

        // 使用过滤后的文本进行TTS
        voiceService?.speakResponse(filteredText)
    }

    /**
     * 对文本进行过滤处理，使其更适合TTS朗读。
     * 1. 移除代码块标记
     * 2. 移除HTML/Markdown标签
     * 3. 处理特殊符号和表情
     * 4. 替换连续标点符号
     * 5. 格式化数字朗读
     */
    private fun filterTextForTTS(text: String): String {
        var result = text

        // 处理代码块 - 移除```标记，添加适当的提示
        val codeBlockRegex = Regex("```(?:(?:\\w+)?\\n)?(.*?)```", RegexOption.DOT_MATCHES_ALL)
        result = codeBlockRegex.replace(result) { matchResult ->
            val codeContent = matchResult.groupValues[1].trim()
            if (codeContent.isNotBlank()) {
                "这里是代码片段。"
            } else {
                ""
            }
        }

        // 处理Markdown强调标记（加粗、斜体）
        // 处理加粗 **text** 或 __text__
        result = result.replace(Regex("\\*\\*([^*]+)\\*\\*|__([^_]+)__")) { matchResult ->
            matchResult.groupValues[1].ifEmpty { matchResult.groupValues[2] }
        }

        // 处理斜体 *text* 或 _text_
        result = result.replace(Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)|(?<!_)_([^_]+)_(?!_)")) { matchResult ->
            matchResult.groupValues[1].ifEmpty { matchResult.groupValues[2] }
        }

        // 处理删除线 ~~text~~
        result = result.replace(Regex("~~([^~]+)~~")) { matchResult ->
            matchResult.groupValues[1]
        }

        // 移除HTML标签
        val htmlTagRegex = Regex("<[^>]+>")
        result = htmlTagRegex.replace(result, "")

        // 移除Markdown链接标记，保留链接文本
        val markdownLinkRegex = Regex("\\[([^\\]]+)\\]\\([^)]+\\)")
        result = markdownLinkRegex.replace(result) { matchResult ->
            matchResult.groupValues[1]
        }

        // 替换Markdown标题标记
        val headingRegex = Regex("^#{1,6}\\s+(.+)$", RegexOption.MULTILINE)
        result = headingRegex.replace(result) { matchResult ->
            matchResult.groupValues[1]
        }

        // 处理列表项 - 更智能地处理列表项，根据缩进级别添加适当前缀
        val listItemRegex = Regex("^(\\s*)([\\-\\*])\\s+(.+)$", RegexOption.MULTILINE)
        result = listItemRegex.replace(result) { matchResult ->
            val indent = matchResult.groupValues[1]
            val content = matchResult.groupValues[3]
            val indentLevel = indent.length / 2
            val prefix = when {
                indentLevel > 0 -> "子项，"
                else -> "，"
            }
            "$prefix$content"
        }

        // 处理数字列表 - 将 "1. " 替换为 "第一，" 等
        val numberListRegex = Regex("^(\\s*)(\\d+)\\.\\s+(.+)$", RegexOption.MULTILINE)
        result = numberListRegex.replace(result) { matchResult ->
            val indent = matchResult.groupValues[1]
            val number = matchResult.groupValues[2].toInt()
            val content = matchResult.groupValues[3]
            val indentLevel = indent.length / 2
            val prefix = when {
                indentLevel > 0 -> "子项"
                else -> ""
            }

            val numberText = when (number) {
                1 -> "${prefix}第一，"
                2 -> "${prefix}第二，"
                3 -> "${prefix}第三，"
                4 -> "${prefix}第四，"
                5 -> "${prefix}第五，"
                6 -> "${prefix}第六，"
                7 -> "${prefix}第七，"
                8 -> "${prefix}第八，"
                9 -> "${prefix}第九，"
                10 -> "${prefix}第十，"
                else -> "${prefix}第$number，"
            }
            "$numberText$content"
        }

        // 处理表格 - 简单地将表格转换为文本说明
        if (result.contains("|") && result.contains("---")) {
            val tableRegex = Regex("\\|[^|]+\\|[\\s\\S]*?\\|[-|]+\\|[\\s\\S]*?(?=\\n\\n|$)")
            result = tableRegex.replace(result) {
                "这里有一个表格，请在界面上查看。"
            }
        }

        // 替换常见的特殊字符
        result = result
            .replace("*", "星号")
            .replace("#", "井号")
            .replace("@", "at")
            .replace("&", "和")
            .replace("|", "或")
            .replace("%", "百分之")
            .replace("^", "上标")
            .replace("~", "波浪线")
            .replace("$", "美元")
            .replace("\\", "反斜杠")
            .replace("=>", "指向")
            .replace("->", "箭头")
            .replace("<-", "左箭头")

        // 处理括号，朗读时加入适当的语气停顿
        result = result.replace(Regex("\\(([^)]+)\\)")) { matchResult ->
            "，${matchResult.groupValues[1]}，"
        }

        result = result.replace(Regex("\\[([^\\]]+)\\]")) { matchResult ->
            "，${matchResult.groupValues[1]}，"
        }

        // 将连续的多个英文句点替换为单个句点
        result = result.replace(Regex("\\.{2,}"), "。")

        // 将连续的问号或感叹号替换为单个
        result = result.replace(Regex("\\?{2,}"), "？")
        result = result.replace(Regex("!{2,}"), "！")

        // 优化逗号处理
        result = result.replace(", ", "，")
            .replace(",", "，")

        result = result.replace(Regex("'([^']+)'")) { matchResult ->
            "，${matchResult.groupValues[1]}，"
        }

        result = result.replace(Regex("\"([^\"]+)\"")) { matchResult ->
            "，${matchResult.groupValues[1]}，"
        }

        // 在句子之间添加适当的停顿（中文句号、问号、感叹号后加逗号）
        result = result.replace("。", "。，")
            .replace("！", "！，")
            .replace("？", "？，")

        // 将多个连续空格替换为单个空格
        result = result.replace(Regex("\\s{2,}"), " ")

        // 处理表情符号
        result = result.replace(":)", "微笑")
            .replace(":(", "悲伤")
            .replace(":D", "开心")
            .replace(";)", "眨眼")
            .replace(":P", "吐舌")
            .replace(":O", "惊讶")
            .replace(":-)", "微笑")
            .replace(":-(", "悲伤")
            .replace(":-D", "开心")
            .replace(";-)", "眨眼")
            .replace(":-P", "吐舌")
            .replace(":-O", "惊讶")

        // 清理不必要的多余逗号
        result = result.replace(Regex("，+"), "，")
            .replace(Regex("^，"), "") // 开头的逗号
            .replace(Regex("，$"), "") // 结尾的逗号


        return result
    }


    /**
     * Clear dialogue history.
     */
    fun clearDialogueHistory() {
        voiceService?.dialogueManager?.clearHistory()
    }

    fun shutdownDialogueManager() {
        voiceService?.dialogueManager?.shutdown()
    }
    
    override fun onCleared() {
        super.onCleared()
        
        // Unbind from the service when the ViewModel is cleared
        if (serviceBound) {
            getApplication<Application>().unbindService(serviceConnection)
            serviceBound = false
        }
    }
} 