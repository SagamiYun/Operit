package com.ai.assistance.operit.api

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.api.enhance.ConversationMarkupManager
import com.ai.assistance.operit.api.enhance.ConversationRoundManager
import com.ai.assistance.operit.api.enhance.InputProcessor
import com.ai.assistance.operit.api.enhance.ToolExecutionManager
import com.ai.assistance.operit.api.library.ProblemLibrary
import com.ai.assistance.operit.core.config.SystemPromptConfig
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.data.model.PlanItem
import com.ai.assistance.operit.data.model.ToolExecutionProgress
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.preferences.preferencesManager
import com.ai.assistance.operit.ui.common.displays.MessageContentParser
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Enhanced AI service that provides advanced conversational capabilities by integrating various
 * components like tool execution, conversation management, user preferences, and problem library.
 */
class EnhancedAIService(
        apiEndpoint: String,
        apiKey: String,
        modelName: String,
        private val providerType: LLMProviderFactory.ProviderType,
        private val context: Context
) {
    companion object {
        private const val TAG = "EnhancedAIService"
    }

    // Use composition for the base LLM provider service
    private val llmProvider: LLMProvider = LLMProviderFactory.createProvider(
        providerType,
        apiEndpoint,
        apiKey,
        modelName
    )

    // Tool handler for executing tools
    private val toolHandler = AIToolHandler.getInstance(context)

    // 初始化问题库
    init {
        ProblemLibrary.initialize(context)
    }

    // State flows for UI updates
    private val _inputProcessingState =
            MutableStateFlow<InputProcessingState>(InputProcessingState.Idle)
    val inputProcessingState = _inputProcessingState.asStateFlow()

    // Plan items tracking
    private val _planItems = MutableStateFlow<List<PlanItem>>(emptyList())
    val planItems = _planItems.asStateFlow()

    // Conversation management
    private val streamBuffer = StringBuilder()
    private val roundManager = ConversationRoundManager()
    private val isConversationActive = AtomicBoolean(false)

    // Api Preferences for settings
    private val apiPreferences = ApiPreferences(context)

    // Coroutine management
    private val toolProcessingScope = CoroutineScope(Dispatchers.IO)
    private val toolExecutionJobs = ConcurrentHashMap<String, Job>()
    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private val conversationMutex = Mutex()

    // Callbacks
    private var currentResponseCallback: ((content: String, thinking: String?) -> Unit)? = null
    private var currentCompleteCallback: (() -> Unit)? = null

    // Package manager for handling tool packages
    private val packageManager = PackageManager.getInstance(context, toolHandler)

    init {
        toolHandler.registerDefaultTools()
    }

    /** Get the tool progress flow for UI updates */
    fun getToolProgressFlow(): StateFlow<ToolExecutionProgress> {
        return toolHandler.toolProgress
    }

    /** Process user input with a delay for UI feedback */
    suspend fun processUserInput(input: String): String {
        _inputProcessingState.value = InputProcessingState.Processing("Processing input...")
        return InputProcessor.processUserInput(input)
    }

    /** Extract and update plan items from content */
    private fun extractPlanItems(content: String) {
        // Only extract plan items if planning is enabled
        toolProcessingScope.launch {
            try {
                val planningEnabled = apiPreferences.enableAiPlanningFlow.first()
                if (planningEnabled) {
                    Log.d(TAG, "计划模式已启用，开始提取计划项")

                    // 获取当前的计划项
                    val existingItems = _planItems.value

                    // 始终传入现有的计划项，让PlanItemParser处理合并和更新
                    val updatedPlanItems =
                            ConversationMarkupManager.extractPlanItems(content, existingItems)

                    if (updatedPlanItems.isNotEmpty()) {
                        // 检查计划项是否有变化
                        if (updatedPlanItems != existingItems) {
                            Log.d(TAG, "计划项有变化，更新状态流")
                            _planItems.value = updatedPlanItems
                        }
                    } else if (_planItems.value.isNotEmpty()) {
                        // 即使没有提取到新的计划项，也保留现有的计划项
                        Log.d(TAG, "保留现有计划项")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "提取计划项时发生错误: ${e.message}")
                // 错误时也保留现有计划项，避免因为异常导致UI丢失显示
            }
        }
    }

    /** Clear all plan items */
    fun clearPlanItems() {
        toolProcessingScope.launch {
            try {
                Log.d(TAG, "清除所有计划项")
                _planItems.value = emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "清除计划项时发生错误", e)
            }
        }
    }

    /**
     * Re-extract plan items from the entire chat history This helps recover plan items in case they
     * were lost due to UI state issues
     */
    private fun reExtractPlanItems(chatHistory: List<Pair<String, String>>) {
        if (chatHistory.isEmpty()) {
            Log.d(TAG, "空聊天历史，跳过计划项重新提取")
            return
        }

        toolProcessingScope.launch {
            try {
                val planningEnabled = apiPreferences.enableAiPlanningFlow.first()
                if (!planningEnabled) {
                    Log.d(TAG, "计划模式未启用，跳过计划项重新提取")
                    return@launch
                }

                // 如果当前已有计划项，不进行重新提取
                if (_planItems.value.isNotEmpty()) {
                    Log.d(
                            TAG,
                            "当前已有计划项，无需重新提取: ${_planItems.value.map { "${it.id}: ${it.status}" }}"
                    )
                    return@launch
                }

                Log.d(TAG, "开始从历史聊天中重新提取计划项")
                // 获取所有助手消息内容
                val assistantMessages =
                        chatHistory.filter { it.first == "assistant" }.map { it.second }

                // 合并提取所有计划项的逻辑
                var accumulatedItems = _planItems.value

                for (content in assistantMessages) {
                    // 使用新的带有现有计划项参数的方法
                    accumulatedItems =
                            ConversationMarkupManager.extractPlanItems(content, accumulatedItems)
                }

                // 只有在找到计划项时才更新状态流
                if (accumulatedItems.isNotEmpty() && accumulatedItems != _planItems.value) {
                    Log.d(
                            TAG,
                            "从历史聊天中重新提取到计划项: ${accumulatedItems.map { "${it.id}: ${it.status}" }}"
                    )
                    _planItems.value = accumulatedItems
                    Log.d(TAG, "已更新计划项状态流")
                } else if (accumulatedItems.isEmpty()) {
                    Log.d(TAG, "未从历史聊天中提取到任何计划项")
                }
            } catch (e: Exception) {
                Log.e(TAG, "重新提取计划项时发生错误: ${e.message}", e)
            }
        }
    }

    /** Send a message to the AI service */
    suspend fun sendMessage(
            message: String,
            onPartialResponse: (content: String, thinking: String?) -> Unit,
            chatHistory: List<Pair<String, String>> = emptyList(),
            onComplete: () -> Unit = {}
    ) {
        try {
            // Mark conversation as active
            isConversationActive.set(true)

            // Save callbacks for later use
            currentResponseCallback = onPartialResponse
            currentCompleteCallback = onComplete

            // Process the input message for any conversation markup (e.g., for AI planning)
            val processedInput = InputProcessor.processUserInput(message)

            // Update state to show we're processing
            _inputProcessingState.value = InputProcessingState.Processing("Processing message...")

            // Prepare conversation history with system prompt
            val preparedHistory = prepareConversationHistory(chatHistory, processedInput)

            // Update UI state to connecting
            _inputProcessingState.value =
                    InputProcessingState.Connecting("Connecting to AI service...")

            Log.d(TAG, "Sending message to AI service with ${preparedHistory.size} history items")

            // Get all model parameters from preferences (with enabled state)
            val modelParameters = runBlocking { apiPreferences.getAllModelParameters() }
            Log.d(
                    TAG,
                    "Retrieved ${modelParameters.size} model parameters, enabled: ${modelParameters.count { it.isEnabled }}"
            )

            withContext(Dispatchers.IO) {
                llmProvider.sendMessage(
                        message = processedInput,
                        onPartialResponse = { content, thinking ->
                            // First response received, update to receiving state
                            if (streamBuffer.isEmpty()) {
                                _inputProcessingState.value =
                                        InputProcessingState.Receiving("Receiving AI response...")
                            }

                            processContent(
                                    content,
                                    thinking,
                                    onPartialResponse,
                                    preparedHistory,
                                    isFollowUp = false
                            )
                        },
                        chatHistory = preparedHistory,
                        onComplete = { handleStreamingComplete(onComplete) },
                        onConnectionStatus = { status ->
                            // Update connection status in UI
                            when {
                                status.contains("准备连接") || status.contains("正在建立连接") ->
                                        _inputProcessingState.value =
                                                InputProcessingState.Connecting(status)
                                status.contains("连接成功") ->
                                        _inputProcessingState.value =
                                                InputProcessingState.Receiving(status)
                                status.contains("超时") || status.contains("失败") ->
                                        _inputProcessingState.value =
                                                InputProcessingState.Processing(status)
                            }
                        },
                        modelParameters = modelParameters
                )
            }
        } catch (e: Exception) {
            // Handle any exceptions
            Log.e(TAG, "Error sending message: ${e.message}", e)
            _inputProcessingState.value =
                    InputProcessingState.Error(message = "Error: ${e.message}")
            onComplete() // Ensure completion callback is called even on error
        }
    }

    /** Prepare the conversation history with system prompt */
    private suspend fun prepareConversationHistory(
            chatHistory: List<Pair<String, String>>,
            processedInput: String
    ): MutableList<Pair<String, String>> {
        conversationMutex.withLock {
            conversationHistory.clear()

            // Add system prompt if not already present
            if (!chatHistory.any { it.first == "system" }) {
                val activeProfile = preferencesManager.getUserPreferencesFlow().first()
                val preferencesText = buildPreferencesText(activeProfile)

                // Check if planning is enabled
                val planningEnabled = apiPreferences.enableAiPlanningFlow.first()

                // Get custom prompts if available
                val customIntroPrompt = apiPreferences.customIntroPromptFlow.first()
                val customTonePrompt = apiPreferences.customTonePromptFlow.first()

                val systemPrompt =
                        if (customIntroPrompt.isNotEmpty() || customTonePrompt.isNotEmpty()) {
                            // Use custom prompts if they are set
                            SystemPromptConfig.getSystemPromptWithCustomPrompts(
                                    packageManager,
                                    planningEnabled,
                                    customIntroPrompt,
                                    customTonePrompt
                            )
                        } else {
                            // Use default system prompt
                            SystemPromptConfig.getSystemPrompt(packageManager, planningEnabled)
                        }

                if (preferencesText.isNotEmpty()) {
                    conversationHistory.add(
                            0,
                            Pair(
                                    "system",
                                    "$systemPrompt\n\nUser preference description: $preferencesText"
                            )
                    )
                } else {
                    conversationHistory.add(0, Pair("system", systemPrompt))
                }
            }

            // Process each message in chat history
            for (message in chatHistory) {
                val role = message.first
                val content = message.second

                // If it's an assistant message, check for tool results
                if (role == "assistant") {
                    val toolResults = extractToolResults(content)
                    if (toolResults.isNotEmpty()) {
                        // Process the message with tool results
                        processChatMessageWithTools(content, toolResults)
                    } else {
                        // Add the message as is
                        conversationHistory.add(message)
                    }
                } else {
                    // Add user or system messages as is
                    conversationHistory.add(message)
                }
            }
        }
        return conversationHistory
    }

    /**
     * Extract tool results from message content using both xmlToolResultPattern and
     * xmlStatusPattern
     */
    private fun extractToolResults(content: String): List<Triple<String, String, IntRange>> {
        val results = mutableListOf<Triple<String, String, IntRange>>()

        // Extract using tool_result pattern
        val xmlToolResultPattern = MessageContentParser.Companion.xmlToolResultPattern
        xmlToolResultPattern.findAll(content).forEach { match ->
            val toolName = match.groupValues[1]
            val toolResult = match.groupValues[0]
            results.add(Triple(toolName, toolResult, match.range))
        }

        // Also extract using status pattern for result type
        val xmlStatusPattern = MessageContentParser.Companion.xmlStatusPattern
        xmlStatusPattern.findAll(content).forEach { match ->
            val toolName = match.groupValues[2]
            val toolResult = match.groupValues[0]

            // Include status of type "result" or "executing" as tool result
            if (toolName.isNotEmpty()) {
                results.add(Triple(toolName, toolResult, match.range))
            }
        }

        return results
    }

    /**
     * Process a chat message that contains tool results, splitting it into assistant message
     * segments and tool result segments
     */
    private suspend fun processChatMessageWithTools(
            content: String,
            toolResults: List<Triple<String, String, IntRange>>
    ) {
        var lastEnd = 0
        val sortedResults = toolResults.sortedBy { it.third.first }

        // Check memory optimization setting once for all tool results
        val memoryOptimizationEnabled = apiPreferences.memoryOptimizationFlow.first()

        for (result in sortedResults) {
            val toolName = result.first
            var toolResult = result.second
            val range = result.third

            // Add assistant message before the tool result (if any)
            if (range.first > lastEnd) {
                val assistantContent = content.substring(lastEnd, range.first)
                if (assistantContent.isNotBlank()) {
                    conversationHistory.add(Pair("assistant", assistantContent))
                }
            }

            // Apply memory optimization for long tool results if enabled
            if (memoryOptimizationEnabled && toolResult.length > 1000) {
                // Optimize tool result by extracting the most important parts
                toolResult = optimizeToolResult(toolName, toolResult)
                Log.d(
                        TAG,
                        "Memory optimization applied to tool result for $toolName, reduced length from ${result.second.length} to ${toolResult.length}"
                )
            }

            if (conversationHistory.last().first == "tool") {
                conversationHistory[conversationHistory.size - 1] = Pair("tool", toolResult)
            } else {
                conversationHistory.add(Pair("tool", toolResult))
            }

            // Update lastEnd
            lastEnd = range.last + 1
        }

        // Add any remaining assistant content after the last tool result
        if (lastEnd < content.length) {
            val assistantContent = content.substring(lastEnd)
            if (assistantContent.isNotBlank()) {
                conversationHistory.add(Pair("assistant", assistantContent))
            }
        }
    }

    /**
     * Optimize tool result by selecting the most important parts This helps with memory management
     * for long tool outputs
     */
    private fun optimizeToolResult(toolName: String, toolResult: String): String {
        // For excessive long tool results, keep only essential parts
        if (toolName == "use_package") {
            return toolResult
        }
        if (toolResult.length <= 1000) return toolResult

        // Extract content within XML tags
        val tagContent =
                Regex("<[^>]*>(.*?)</[^>]*>", RegexOption.DOT_MATCHES_ALL)
                        .find(toolResult)
                        ?.groupValues
                        ?.getOrNull(1)

        val sb = StringBuilder()

        // Add prefix based on tool name
        sb.append("<tool_result name=\"$toolName\">")

        // If xml content was extracted, use it, otherwise use the first 300 and last 300 chars
        if (!tagContent.isNullOrEmpty()) {
            // For XML content, take up to 800 chars
            val maxContentLength = 800
            val content =
                    if (tagContent.length > maxContentLength) {
                        tagContent.substring(0, 400) +
                                "\n... [content truncated for memory optimization] ...\n" +
                                tagContent.substring(tagContent.length - 400)
                    } else {
                        tagContent
                    }
            sb.append(content)
        } else {
            // For non-XML content, take important parts from beginning and end
            sb.append(toolResult.substring(0, 400))
            sb.append("\n... [content truncated for memory optimization] ...\n")
            sb.append(toolResult.substring(toolResult.length - 400))
        }

        // Add closing tag
        sb.append("</tool_result>")

        return sb.toString()
    }

    /** Build a formatted preferences text string from a PreferenceProfile */
    private fun buildPreferencesText(
            profile: com.ai.assistance.operit.data.model.PreferenceProfile
    ): String {
        val parts = mutableListOf<String>()

        if (profile.gender.isNotEmpty()) {
            parts.add("性别: ${profile.gender}")
        }

        if (profile.birthDate > 0) {
            // Convert timestamp to age and format as text
            val today = Calendar.getInstance()
            val birthCal = Calendar.getInstance().apply { timeInMillis = profile.birthDate }
            var age = today.get(Calendar.YEAR) - birthCal.get(Calendar.YEAR)
            // Adjust age if birthday hasn't occurred yet this year
            if (today.get(Calendar.MONTH) < birthCal.get(Calendar.MONTH) ||
                            (today.get(Calendar.MONTH) == birthCal.get(Calendar.MONTH) &&
                                    today.get(Calendar.DAY_OF_MONTH) <
                                            birthCal.get(Calendar.DAY_OF_MONTH))
            ) {
                age--
            }
            parts.add("年龄: ${age}岁")

            // Also add birth date for more precise information
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            parts.add("出生日期: ${dateFormat.format(java.util.Date(profile.birthDate))}")
        }

        if (profile.personality.isNotEmpty()) {
            parts.add("性格特点: ${profile.personality}")
        }

        if (profile.identity.isNotEmpty()) {
            parts.add("身份认同: ${profile.identity}")
        }

        if (profile.occupation.isNotEmpty()) {
            parts.add("职业: ${profile.occupation}")
        }

        if (profile.aiStyle.isNotEmpty()) {
            parts.add("期待的AI风格: ${profile.aiStyle}")
        }

        return parts.joinToString("; ")
    }

    /** Cancel the current conversation */
    fun cancelConversation() {
        // Set conversation inactive
        isConversationActive.set(false)

        // Cancel underlying AIService streaming
        llmProvider.cancelStreaming()

        // Cancel all tool executions
        cancelAllToolExecutions()

        // Clean up current conversation content
        roundManager.clearContent()
        Log.d(TAG, "Conversation canceled - content pool cleared")

        // Reset input processing state
        _inputProcessingState.value = InputProcessingState.Idle

        // Clear callback references
        currentResponseCallback = null
        currentCompleteCallback = null

        Log.d(TAG, "Conversation cancellation complete - all state reset except plan items")
    }

    /** Process content received from the AI service */
    private fun processContent(
            content: String,
            thinking: String?,
            onPartialResponse: (content: String, thinking: String?) -> Unit,
            chatHistory: List<Pair<String, String>>,
            isFollowUp: Boolean
    ) {
        // First check if conversation is canceled
        if (!isConversationActive.get()) {
            Log.d(TAG, "Conversation canceled, skipping content processing")
            return
        }

        try {
            // If this is a follow-up after tool execution, use a new content round
            if (isFollowUp) {
                // Use completely new content processing
                streamBuffer.replace(0, streamBuffer.length, content)
                val displayContent = roundManager.updateContent(content)

                // 不再提取引用
                // extractPlanItems只在流结束时处理

                onPartialResponse(displayContent, thinking)
                return
            }

            // Regular message processing
            streamBuffer.replace(0, streamBuffer.length, content)

            // Update content in round manager
            val displayContent = roundManager.updateContent(content)

            // 不再提取引用
            // extractPlanItems只在流结束时处理

            // Check again if conversation is active
            if (!isConversationActive.get()) {
                Log.d(TAG, "Conversation canceled during processing, skipping callback")
                return
            }

            // Execute callback if not null
            onPartialResponse(displayContent, thinking)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing content", e)
            // Don't interrupt the flow even if there's an error
        }
    }

    /** Handle the completion of streaming response */
    private fun handleStreamingComplete(onComplete: () -> Unit) {
        toolProcessingScope.launch {
            try {
                // If conversation is no longer active, return immediately
                if (!isConversationActive.get()) {
                    Log.d(TAG, "Conversation canceled, skipping streaming complete handling")
                    onComplete()
                    return@launch
                }

                // Get response content
                val content = streamBuffer.toString().trim()

                // If content is empty, finish immediately
                if (content.isEmpty()) {
                    Log.d(TAG, "Response content is empty, skipping processing")
                    onComplete()
                    return@launch
                }

                val displayContent = roundManager.getDisplayContent()
                val responseCallback =
                        currentResponseCallback
                                ?: run {
                                    Log.d(TAG, "Callback cleared, skipping processing")
                                    onComplete()
                                    return@launch
                                }

                // 不再提取引用，只提取计划项
                Log.d(TAG, "流传输完成，处理计划项")
                extractPlanItems(content)

                // Handle task completion marker
                if (ConversationMarkupManager.containsTaskCompletion(content)) {
                    handleTaskCompletion(displayContent, null, responseCallback)
                    onComplete()
                    return@launch
                }

                // Handle wait for user need marker
                if (ConversationMarkupManager.containsWaitForUserNeed(content)) {
                    handleWaitForUserNeed(displayContent, null, responseCallback)
                    onComplete()
                    return@launch
                }

                // Check again if conversation is active
                if (!isConversationActive.get()) {
                    Log.d(
                            TAG,
                            "Conversation canceled after extracting content, stopping further processing"
                    )
                    onComplete()
                    return@launch
                }

                // Add current assistant message to conversation history
                try {
                    conversationMutex.withLock {
                        conversationHistory.add(
                                Pair("assistant", roundManager.getCurrentRoundContent())
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding assistant message to history", e)
                    onComplete()
                    return@launch
                }

                // Check again if conversation is active
                if (!isConversationActive.get()) {
                    Log.d(TAG, "Conversation canceled after processing history")
                    onComplete()
                    return@launch
                }

                // Main flow: Detect and process tool invocations
                Log.d(TAG, "Starting tool invocation detection")

                // Try to detect tool invocations
                val toolInvocations = toolHandler.extractToolInvocations(content)

                if (toolInvocations.isNotEmpty()) {
                    handleToolInvocation(
                            toolInvocations,
                            displayContent,
                            responseCallback,
                            onComplete
                    )
                    return@launch
                }

                // 修改默认行为：如果没有特殊标记或工具调用，默认等待用户输入
                // 而不是直接标记为完成
                Log.d(TAG, "未检测到特殊标记或工具调用，默认使用等待用户输入模式")

                // 创建等待用户输入的内容
                val userNeedContent =
                        ConversationMarkupManager.createWaitForUserNeedContent(displayContent)

                // 处理为等待用户输入模式
                handleWaitForUserNeed(userNeedContent, null, responseCallback)

                // 完成回调
                onComplete()
            } catch (e: Exception) {
                // Catch any exceptions in the processing flow
                Log.e(TAG, "Error handling streaming complete", e)
                _inputProcessingState.value = InputProcessingState.Idle
                onComplete() // Ensure completion callback is called even if error occurs
            }
        }
    }

    /** Handle tool invocation processing */
    private suspend fun handleToolInvocation(
            toolInvocations: List<ToolInvocation>,
            displayContent: String,
            responseCallback: (content: String, thinking: String?) -> Unit,
            onComplete: () -> Unit
    ) {
        Log.d(TAG, "Found tool invocation in completed content, starting processing")

        // Only process the first tool invocation, show warning if there are multiple
        val invocation = toolInvocations.first()

        if (toolInvocations.size > 1) {
            Log.w(
                    TAG,
                    "Found multiple tool invocations (${toolInvocations.size}), but only processing the first: ${invocation.tool.name}"
            )

            val updatedDisplayContent =
                    roundManager.appendContent(
                            ConversationMarkupManager.createMultipleToolsWarning(
                                    invocation.tool.name
                            )
                    )
            responseCallback(updatedDisplayContent, null)
        }

        // Start executing the tool
        _inputProcessingState.value =
                InputProcessingState.Processing("Executing tool: ${invocation.tool.name}")

        // Update UI to show tool execution status
        val updatedDisplayContent =
                roundManager.appendContent(
                        ConversationMarkupManager.createExecutingToolStatus(invocation.tool.name)
                )
        responseCallback(updatedDisplayContent, null)

        // Get tool executor and execute
        val executor = toolHandler.getToolExecutor(invocation.tool.name)
        val result: ToolResult

        if (executor == null) {
            // Tool not available handling
            Log.w(TAG, "Tool not available: ${invocation.tool.name}")

            val errorDisplayContent =
                roundManager.appendContent(
                    ConversationMarkupManager.createToolNotAvailableError(
                        invocation.tool.name
                    )
                )

            responseCallback(errorDisplayContent, null)

            // Create error result
            result =
                ToolResult(
                    toolName = invocation.tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Tool '${invocation.tool.name}' not available"
                )
            
            // 检查我们是否需要启动新的会话轮次
            // 但不会尝试调用相同的工具
//            roundManager.startNewRound()

        } else {
            // Check permissions before execution
            val (hasPermission, errorResult) =
                ToolExecutionManager.checkToolPermission(toolHandler, invocation)

            // If permission denied, add result and exit function
            if (!hasPermission) {
                // Add both error status and a tool result status to ensure the UI shows the error
                // properly
                val errorDisplayContent =
                    roundManager.appendContent(
                        ConversationMarkupManager.createErrorStatus(
                            "Permission denied",
                            "Operation '${invocation.tool.name}' was not authorized"
                        )
                    )

                // Also add a proper tool result status with error=true to ensure it shows up
                // correctly in the UI
                val toolResultStatusContent =
                    roundManager.appendContent(
                        ConversationMarkupManager.createToolResultStatus(
                            invocation.tool.name,
                            success = false,
                            resultText =
                            "Permission denied: Operation '${invocation.tool.name}' was not authorized"
                        )
                    )

                // Update UI with the error content
                responseCallback(toolResultStatusContent, null)

                // Process error result and exit
                if (errorResult != null) {
                    processToolResult(errorResult, onComplete)
                }
                return
            }

            // Execute the tool
            result = ToolExecutionManager.executeToolSafely(invocation, executor)

            // Display tool execution result
            val toolResultString =
                if (result.success) result.result.toString() else "${result.error}"
            val resultDisplayContent =
                roundManager.appendContent(
                    ConversationMarkupManager.createToolResultStatus(
                        invocation.tool.name,
                        result.success,
                        toolResultString
                    )
                )
            responseCallback(resultDisplayContent, null)
        }

        // Process the tool result
        processToolResult(result, onComplete)
    }

    /** Handle task completion logic */
    private fun handleTaskCompletion(
            content: String,
            thinking: String?,
            onPartialResponse: (content: String, thinking: String?) -> Unit
    ) {
        // Mark conversation as complete
        isConversationActive.set(false)

        // Clean up task completion marker
        val cleanedContent = ConversationMarkupManager.createTaskCompletionContent(content)

        val displayContent = roundManager.getDisplayContent()

        // 清除内容池
        roundManager.clearContent()

        // Ensure input processing state is updated to completed
        _inputProcessingState.value = InputProcessingState.Completed

        // Update UI
        onPartialResponse(cleanedContent, thinking)

        // Save problem in the background
        toolProcessingScope.launch {
            // Call completion callback
            currentCompleteCallback?.invoke()

            // 保存问题记录到库
            toolProcessingScope.launch {
                ProblemLibrary.saveProblemAsync(
                        context,
                        toolHandler,
                        conversationHistory,
                        displayContent,
                        llmProvider
                )
            }
        }
    }

    /** Handle wait for user need logic - similar to task completion but without problem summary */
    private fun handleWaitForUserNeed(
            content: String,
            thinking: String?,
            onPartialResponse: (content: String, thinking: String?) -> Unit
    ) {
        // Mark conversation as complete
        isConversationActive.set(false)

        // Clean up wait for user need marker
        val cleanedContent = ConversationMarkupManager.createWaitForUserNeedContent(content)

        val displayContent = roundManager.getDisplayContent()

        // 清除内容池
        roundManager.clearContent()

        // Ensure input processing state is updated to completed
        _inputProcessingState.value = InputProcessingState.Completed

        // Update UI
        onPartialResponse(cleanedContent, thinking)

        // Update conversation history without triggering problem analysis
        toolProcessingScope.launch {
            // Call completion callback
            currentCompleteCallback?.invoke()

            Log.d(TAG, "Wait for user need - skipping problem library analysis")
        }
    }

    /** Mark conversation as completed */
    private fun markConversationCompleted() {
        isConversationActive.set(false)
        roundManager.clearContent()
        _inputProcessingState.value = InputProcessingState.Completed
        Log.d(TAG, "Conversation marked as completed - content pool cleared")
    }

    /** Cancel all tool executions */
    private fun cancelAllToolExecutions() {
        toolProcessingScope.coroutineContext.cancelChildren()
    }

    /** Process tool execution result and continue conversation */
    private suspend fun processToolResult(result: ToolResult, onComplete: () -> Unit) {
        // Add transition state
        _inputProcessingState.value =
                InputProcessingState.Processing(
                        "Tool execution completed, preparing further processing..."
                )

        // Check if conversation is still active
        if (!isConversationActive.get()) {
            Log.d(TAG, "Conversation no longer active, not processing tool result")
            onComplete()
            return
        }

        // Tool result processing and subsequent AI request
        val toolResultMessage = ConversationMarkupManager.formatToolResultForMessage(result)

        // Add tool result to conversation history
        conversationMutex.withLock { conversationHistory.add(Pair("tool", toolResultMessage)) }

        // Get current conversation history
        val currentChatHistory = conversationMutex.withLock { conversationHistory }

        // Save callback to local variable
        val responseCallback = currentResponseCallback

        if (responseCallback != null) {
            responseCallback(roundManager.appendContent(toolResultMessage), null)
        }
        // Start new round - ensure tool execution response will be shown in a new message
        roundManager.startNewRound()
        streamBuffer.clear() // Clear buffer to ensure a new message will be created
        Log.d(TAG, "Starting new round for AI response to tool result")

        // Clearly show we're preparing to send tool result to AI
        _inputProcessingState.value =
                InputProcessingState.Processing("Preparing to process tool execution result...")

        // Add short delay to make state change more visible
        delay(300)

        // Get all model parameters from preferences (with enabled state)
        val modelParameters = runBlocking { apiPreferences.getAllModelParameters() }
        Log.d(
                TAG,
                "Retrieved ${modelParameters.size} model parameters for tool response, enabled: ${modelParameters.count { it.isEnabled }}"
        )

        // Direct request AI response in current flow, keeping in complete main loop
        withContext(Dispatchers.IO) {
            llmProvider.sendMessage(
                    message = toolResultMessage,
                    onPartialResponse = { content, thinking ->
                        // Only handle display, not any tool logic
                        // Use new processing approach to ensure this is a new message
                        if (responseCallback != null) {
                            processContent(
                                    content,
                                    thinking,
                                    responseCallback,
                                    currentChatHistory,
                                    isFollowUp = true
                            )
                        }
                    },
                    chatHistory = currentChatHistory,
                    onComplete = {
                        handleStreamingComplete {
                            if (!isConversationActive.get()) {
                                currentCompleteCallback?.invoke()
                            }
                        }
                    },
                    onConnectionStatus = { status ->
                        // Update connection status for post-tool execution request
                        when {
                            status.contains("准备连接") || status.contains("正在建立连接") ->
                                    _inputProcessingState.value =
                                            InputProcessingState.Connecting(
                                                    status + " (after tool execution)"
                                            )
                            status.contains("连接成功") ->
                                    _inputProcessingState.value =
                                            InputProcessingState.Receiving(
                                                    status + " (after tool execution)"
                                            )
                            status.contains("超时") || status.contains("失败") ->
                                    _inputProcessingState.value =
                                            InputProcessingState.Processing(
                                                    status + " (after tool execution)"
                                            )
                        }
                    },
                    modelParameters = modelParameters
            )
        }
    }

    // Expose base AIService for token counting
    fun getBaseAIService(): LLMProvider = llmProvider

    /**
     * Get the current input token count from the last API call
     * @return The number of input tokens used in the most recent request
     */
    fun getCurrentInputTokenCount(): Int {
        return llmProvider.inputTokenCount
    }

    /**
     * Get the current output token count from the last API call
     * @return The number of output tokens generated in the most recent response
     */
    fun getCurrentOutputTokenCount(): Int {
        return llmProvider.outputTokenCount
    }

    /** Reset token counters to zero Use this when starting a new conversation */
    fun resetTokenCounters() {
        llmProvider.resetTokenCounts()
    }

    /**
     * Extract plan items from chat history without sending a message This is used by ChatViewModel
     * to restore plan items when switching chats or on app startup
     */
    fun extractPlansFromHistory(chatHistory: List<Pair<String, String>>) {
        if (chatHistory.isEmpty()) {
            Log.d(TAG, "extractPlansFromHistory: 空聊天历史，跳过计划项提取")
            return
        }

        Log.d(TAG, "extractPlansFromHistory: 从 ${chatHistory.size} 条聊天记录中提取计划项")

        // 确保计划项状态流为空，以便重新填充
        _planItems.value = emptyList()

        // 提取所有助手消息
        val assistantMessages = chatHistory.filter { it.first == "assistant" }.map { it.second }
        Log.d(TAG, "extractPlansFromHistory: 找到 ${assistantMessages.size} 条助手消息")

        // 从每条助手消息中提取计划项
        var accumulatedItems = emptyList<PlanItem>()

        toolProcessingScope.launch {
            try {
                val planningEnabled = apiPreferences.enableAiPlanningFlow.first()
                if (!planningEnabled) {
                    Log.d(TAG, "extractPlansFromHistory: 计划模式未启用，跳过提取")
                    return@launch
                }

                // 按时间顺序处理每条消息
                for (content in assistantMessages) {
                    // 使用带有现有计划项参数的方法提取计划项
                    accumulatedItems =
                            ConversationMarkupManager.extractPlanItems(content, accumulatedItems)
                }

                // 只有找到计划项时才更新状态流
                if (accumulatedItems.isNotEmpty()) {
                    Log.d(
                            TAG,
                            "extractPlansFromHistory: 从历史聊天中提取到计划项: ${accumulatedItems.map { "${it.id}: ${it.status}" }}"
                    )
                    _planItems.value = accumulatedItems
                    Log.d(TAG, "extractPlansFromHistory: 已更新计划项状态流")
                } else {
                    Log.d(TAG, "extractPlansFromHistory: 未从历史聊天中提取到任何计划项")
                }
            } catch (e: Exception) {
                Log.e(TAG, "extractPlansFromHistory: 提取计划项时发生错误: ${e.message}", e)
            }
        }
    }

    /**
     * 生成对话总结
     * @param messages 要总结的消息列表
     * @return 生成的总结文本
     */
    suspend fun generateSummary(messages: List<Pair<String, String>>): String {
        return generateSummary(messages, null)
    }

    /**
     * 生成对话总结，并且包含上一次的总结内容
     * @param messages 要总结的消息列表
     * @param previousSummary 上一次的总结内容，可以为null
     * @return 生成的总结文本
     */
    suspend fun generateSummary(
            messages: List<Pair<String, String>>,
            previousSummary: String?
    ): String {
        try {
            // 使用更结构化、更详细的提示词
            var systemPrompt =
                    """
            请对以下对话内容进行简洁但全面的总结。遵循以下格式：
            
            1. 以"对话摘要"作为标题
            2. 用1-2个简短段落概述主要内容和交互
            3. 明确列出对理解后续对话至关重要的关键信息点（如用户提到的具体问题、需求、限制条件等）
            4. 特别标注用户明确表达的意图或情感，如有
            5. 避免使用复杂的标题结构和Markdown格式，使用简单段落
            
            总结应该尽量保留对后续对话有价值的上下文信息，但不要包含无关细节。内容应该简洁明了，便于AI快速理解历史对话的要点。
            """

            // 如果存在上一次的摘要，将其添加到系统提示中
            if (previousSummary != null && previousSummary.isNotBlank()) {
                systemPrompt +=
                        """
                
                以下是之前对话的摘要，请参考它来生成新的总结，确保新总结融合之前的要点，并包含新的信息：
                
                ${previousSummary.trim()}
                """
                Log.d(TAG, "添加上一条摘要内容到系统提示")
            }

            val finalMessages = listOf(Pair("system", systemPrompt)) + messages

            // Get all model parameters from preferences (with enabled state)
            val modelParameters = runBlocking { apiPreferences.getAllModelParameters() }

            // 使用aiService发送直接请求
            var summaryContent = ""

            llmProvider.sendMessage(
                    message = "请按照要求总结对话内容",
                    onPartialResponse = { content, _ -> summaryContent = content },
                    chatHistory = finalMessages,
                    onComplete = {},
                    onConnectionStatus = {},
                    modelParameters = modelParameters
            )

            // 如果没有内容，等待短暂时间让内容填充
            if (summaryContent.isBlank()) {
                delay(2000) // 等待2秒，给AI一些时间生成总结
            }

            // 获取本次总结生成的token统计
            val inputTokens = llmProvider.inputTokenCount
            val outputTokens = llmProvider.outputTokenCount

            // 将总结token计数添加到用户偏好分析的token统计中
            try {
                Log.d(TAG, "总结生成使用了输入token: $inputTokens, 输出token: $outputTokens")
                apiPreferences.updatePreferenceAnalysisTokens(inputTokens, outputTokens)
                Log.d(TAG, "已将总结token统计添加到用户偏好分析token计数中")
            } catch (e: Exception) {
                Log.e(TAG, "更新token统计失败", e)
            }

            return summaryContent.trim().takeIf { it.isNotBlank() } ?: "对话摘要：未能生成有效摘要。"
        } catch (e: Exception) {
            Log.e(TAG, "生成总结时出错", e)
            return "对话摘要：生成摘要时出错，但对话仍在继续。"
        }
    }
}
