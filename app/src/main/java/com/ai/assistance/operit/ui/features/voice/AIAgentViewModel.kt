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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job

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

        // Check if this is a duplicate message
        if (userText == _lastProcessedMessage.value) {
            Log.d(TAG, "Duplicate message detected, ignoring: $userText")
            _processingAIRequest.value = false
            return
        }

        // Update user message in ChatViewModel
        chatViewModel.messageProcessingDelegate.updateUserMessage(userText)

        // Track that we've sent this message to prevent duplicates
        _lastProcessedMessage.value = userText

        // 添加时间戳防止取到错误的resp
        val sendTimeMillis = System.currentTimeMillis()

        // 用于跟踪流式响应中已经朗读的内容
        var lastSpokenContent = ""

        // 创建一个单独的流式响应处理job
        var streamingResponseJob: Job? = null

        // Use a single-collect approach to avoid creating multiple observers
        // that might cause multiple responses to be processed
        viewModelScope.launch {
            try {
                // 监听处理完成事件，重置处理标志
                chatViewModel.messageProcessingDelegate.isLoading.collectLatest { isLoading ->
                    if (!isLoading && _processingAIRequest.value) {
                        // 当加载完成且我们标记了处理中状态，进行重置
                        Log.d(TAG, "AI message processing complete, resetting state")
                        _processingAIRequest.value = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing loading state: ${e.message}", e)
                _processingAIRequest.value = false
            }
        }

        // 监控并处理流式响应
        viewModelScope.launch {
            try {
                chatViewModel.chatHistory.collectLatest { messages ->
                    val latestMessage = messages.lastOrNull { it.sender == "ai" }
                    val latestUserMessage = messages.lastOrNull { it.sender == "user" }

                    // 确保我们处理的是回应当前用户消息的AI响应
                    if (messages.isNotEmpty() &&
                        messages.lastOrNull()?.sender == "ai" &&
                        latestUserMessage?.timestamp == sendTimeMillis) {

                        latestMessage?.let { aiMessage ->
                            val currentContent = aiMessage.content

                            // 只有当内容有更新时才处理
                            if (currentContent != _lastAIResponse.value) {
                                // 更新当前响应UI
                                _currentResponse.value = currentContent

                                // 计算新增的内容
                                val newContent = if (currentContent.length > lastSpokenContent.length) {
                                    currentContent.substring(lastSpokenContent.length)
                                } else {
                                    // 如果内容变短了（极少发生），重新从头开始
                                    currentContent
                                }

                                // 如果有新内容，进行TTS播放
                                if (newContent.isNotEmpty()) {
                                    // 取消之前的流式播放job（如果存在）
                                    streamingResponseJob?.cancel()

                                    // 启动新的job来播放这段新内容
                                    streamingResponseJob = viewModelScope.launch {
                                        // 如果我们不在speaking状态，需要进行首次播放
                                        if (_dialogueState.value != DialogueManager.DialogueState.SPEAKING) {
                                            // 过滤整个内容并开始播放
                                            speakFilterResp(currentContent)
                                        } else {
                                            // 如果已经在speaking，则添加新的内容到队列
                                            // 过滤新内容然后添加到TTS队列
                                            voiceService?.appendToSpeak(filterTextForTTS(newContent))
                                        }

                                        // 更新已播放内容的指针
                                        lastSpokenContent = currentContent
                                    }
                                }

                                // 记录最后处理的AI响应
                                _lastAIResponse.value = currentContent
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting chat history: ${e.message}", e)
                _processingAIRequest.value = false
            }
        }

        chatViewModel.messageProcessingDelegate.sendUserMessage(sendTimeMillis)

        // AI请求处理完成的标志会在handleResponseComplete中重置
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

        // 对原始文本应用过滤处理
        val filteredText = filterTextForTTS(text)

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
    
    override fun onCleared() {
        super.onCleared()
        
        // Unbind from the service when the ViewModel is cleared
        if (serviceBound) {
            getApplication<Application>().unbindService(serviceConnection)
            serviceBound = false
        }
    }
} 