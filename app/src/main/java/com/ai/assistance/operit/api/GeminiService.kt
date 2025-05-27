package com.ai.assistance.operit.api

import android.util.Log
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.preferences.ApiPreferences.Companion.DEFAULT_GEMINI_API_ENDPOINT
import com.ai.assistance.operit.data.preferences.ApiPreferences.Companion.DEFAULT_GEMINI_MODEL_NAME
import com.ai.assistance.operit.util.ChatUtils
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.common.shared.Content
import com.google.ai.client.generativeai.common.shared.TextPart
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import org.jetbrains.kotlinx.mcp.Role
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * GeminiService - Google Gemini API integration for Operit
 * Follows similar interface as AIService for compatibility
 * Uses the official Google Generative AI SDK for Kotlin (v0.9.0)
 */
class GeminiService(
    private val apiEndpoint: String,
    private val apiKey: String,
    private val modelName: String
) : LLMProvider {
    companion object {
        private const val TAG = "GeminiService"
        
        // Default Gemini model identifiers
        const val GEMINI_1_5_PRO = DEFAULT_GEMINI_MODEL_NAME
        const val GEMINI_1_5_FLASH = "gemini-1.5-flash"
        const val GEMINI_1_0_PRO = "gemini-1.0-pro"
        const val GEMINI_2_5_FLASH_05_20 = "gemini-2.5-flash-preview-05-20"
        const val GEMINI_2_5_PRO_05_06 = "gemini-2.5-pro-preview-05-06"
        
        // Default Gemini endpoint
        const val DEFAULT_API_ENDPOINT = DEFAULT_GEMINI_API_ENDPOINT
    }
    
    // 定义自定义的GenerativeAIException类
    class GenerativeAIException(message: String, cause: Throwable? = null) : Exception(message, cause)
    
    // TextStreamPart 简单实现
    data class TextStreamPart(val text: String)

    // Token counters
    private var _inputTokenCount = 0
    private var _outputTokenCount = 0
    
    // Chat history for streaming continuations
    @OptIn(ExperimentalSerializationApi::class)
    private var chatHistory = mutableListOf<Content>()
    
    // Flag to track cancellation
    private var isCancelled = false

    // Public token count access
    override val inputTokenCount: Int
        get() = _inputTokenCount
    override val outputTokenCount: Int
        get() = _outputTokenCount
    
    // Token count estimation
    private fun estimateTokenCount(text: String): Int {
        // Simple estimation: about 1.5 tokens per Chinese character, 0.25 tokens per other character
        val chineseCharCount = text.count { it.code in 0x4E00..0x9FFF }
        val otherCharCount = text.length - chineseCharCount

        return (chineseCharCount * 1.5 + otherCharCount * 0.25).toInt()
    }

    // Reset token counts
    override fun resetTokenCounts() {
        _inputTokenCount = 0
        _outputTokenCount = 0
    }
    
    // Log large strings in chunks
    private fun logLargeString(tag: String, message: String, prefix: String = "") {
        val maxLogSize = 3000

        if (message.length > maxLogSize) {
            val chunkCount = message.length / maxLogSize + 1
            for (i in 0 until chunkCount) {
                val start = i * maxLogSize
                val end = minOf((i + 1) * maxLogSize, message.length)
                val chunkMessage = message.substring(start, end)
                Log.d(tag, "$prefix Part ${i+1}/$chunkCount: $chunkMessage")
            }
        } else {
            Log.d(tag, "$prefix$message")
        }
    }

    // Cancel streaming
    override fun cancelStreaming() {
        isCancelled = true
        Log.d(TAG, "Canceled current streaming request")
    }
    
    // Parse response for thinking content
    private fun parseResponse(content: String): Pair<String, String?> {
        val thinkStartTag = "<think>"
        val thinkEndTag = "</think>"

        val startIndex = content.indexOf(thinkStartTag)
        val endIndex = content.lastIndexOf(thinkEndTag)

        return if (startIndex != -1 && endIndex != -1 && startIndex < endIndex) {
            val thinkContent = content.substring(startIndex + thinkStartTag.length, endIndex).trim()
            val mainContent = content.substring(endIndex + thinkEndTag.length).trim()
            Pair(mainContent, thinkContent)
        } else if (startIndex != -1 && endIndex == -1) {
            Pair("", content.substring(startIndex + thinkStartTag.length).trim())
        } else {
            Pair(content, null)
        }
    }
    
    // Create GenerativeModel instance with proper configuration
    private fun createGenerativeModel(modelParameters: List<ModelParameter<*>>): GenerativeModel {
        // Create generation config based on model parameters
        val genConfig = generationConfig {
            // Apply model parameters
        for (param in modelParameters) {
            if (param.isEnabled) {
                    when (param.apiName) {
                        "temperature" -> temperature = (param.currentValue as Float)
                        "topP" -> topP = (param.currentValue as Float)
                        "topK" -> topK = (param.currentValue as Int)
                        "maxOutputTokens" -> maxOutputTokens = param.currentValue as Int
                        "candidateCount" -> candidateCount = param.currentValue as Int
                        // stopSequences需要特殊处理
                        "stopSequences" -> {
                            val stopSeq = param.currentValue
                            if (stopSeq is List<*>) {
                                val stringList = stopSeq.filterIsInstance<String>()
                                stopSequences = stringList
                            }
                        }
                        // Add other parameters as needed
                    }
                    Log.d(TAG, "Added parameter ${param.apiName} = ${param.currentValue}")
                }
            }
        }
        
        // Create safety settings (with moderate defaults)
        val safetySettings = listOf(
            SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.MEDIUM_AND_ABOVE),
            SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.MEDIUM_AND_ABOVE),
            SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.MEDIUM_AND_ABOVE),
            SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.MEDIUM_AND_ABOVE)
        )
        
        // Create GenerativeModel with config
        return GenerativeModel(
            modelName = modelName,
            apiKey = apiKey,
            generationConfig = genConfig,
            safetySettings = safetySettings
        )
    }
    
    // Convert standard chat history to Gemini Content format
    @OptIn(ExperimentalSerializationApi::class)
    private fun convertChatHistory(standardizedHistory: List<Pair<String, String>>): List<Content> {
        val contents = mutableListOf<Content>()
            
            // Filter out consecutive identical roles (except system)
            val filteredHistory = mutableListOf<Pair<String, String>>()
            var lastRole: String? = null
            
        for ((role, text) in standardizedHistory) {
                if (role == lastRole && role != "system") {
                    Log.d(TAG, "Skipping consecutive message with same role: $role")
                    continue
                }
                
            filteredHistory.add(Pair(role, text))
                lastRole = role
            }
            
        // Convert filtered history to Gemini Content format
        for ((role, text) in filteredHistory) {
            // 特殊处理工具结果消息 - 保持XML格式完整
            if (role == "tool" && text.contains("<tool_result>")) {
                Log.d(TAG, "处理工具结果消息: ${text.take(100)}...")
                
                // 以用户消息的形式传递完整的工具结果XML
                // 这样Gemini可以看到完整的XML结构，而不会丢失信息
                val content = Content(Role.user.toString(), listOf(TextPart(text)))
                contents.add(content)
                
                // 计算输入tokens
                _inputTokenCount += estimateTokenCount(text)
                continue
            }
            
            // Map standard roles to Gemini roles
            val geminiRole = when (role) {
                "user" -> Role.user
                "assistant" -> Role.assistant
                "system" -> Role.system 
                "tool" -> Role.user // 工具消息映射为用户角色
                else -> Role.user // 默认为用户
            }
            
            // 创建消息内容，保留原始格式
            val content = Content(geminiRole.toString(), listOf(TextPart(text)))
            contents.add(content)
            
            // 计算输入tokens
            _inputTokenCount += estimateTokenCount(text)
        }
        
        return contents
    }
    
    // Create user message content
    @OptIn(ExperimentalSerializationApi::class)
    private fun createUserContent(message: String): Content {
        _inputTokenCount += estimateTokenCount(message)
        return Content(Role.user.toString(), listOf(TextPart(message)))
    }
    
    // Process stream part to extract text content
    private fun processTextStreamPart(part: TextStreamPart): String {
        return part.text.trim()
    }
    
    // Process content stream with better error handling
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun processContentStream(
        model: GenerativeModel,
        contents: List<Content>,
        onPartialResponse: (content: String, thinking: String?) -> Unit
    ): String {
        val responseBuilder = StringBuilder()
        var currentThinking: StringBuilder? = null
        var lastSentResponse = ""
        
        try {
            // 使用字符串提示而不是Content列表
            // 将内容合并为单个提示字符串
            val combinedPrompt = buildPromptFromContents(contents)
            
            // 使用字符串作为提示
            val streamFlow: Flow<GenerateContentResponse> = model.generateContentStream(combinedPrompt)
            
            streamFlow
                .flowOn(Dispatchers.IO)
                .catch { e ->
                    // Handle errors in the flow
                    when (e) {
                        is CancellationException -> {
                            Log.d(TAG, "Stream cancelled")
                        }
                        else -> {
                            val errorMessage = "Error in Gemini response stream: ${e.message}"
                            Log.e(TAG, errorMessage, e)
                            throw IOException(errorMessage)
                        }
                    }
                }
                .collect { response ->
                    if (isCancelled) {
                        return@collect
                    }
                    
                    // 直接从GenerateContentResponse中获取文本
                    val chunk = response.text?.trim() ?: ""
                    if (chunk.isNotEmpty()) {
                        // 检查是否有新增内容
                        if (chunk.length > 0) {
                            // 更新完整响应
                            responseBuilder.append(chunk)
                            
                            // 计算输出tokens
                            _outputTokenCount += estimateTokenCount(chunk)
                            
                            // 解析响应，查找思考内容和其他标签
                            val fullResponse = responseBuilder.toString()
                            
                            // 检查是否包含任何特殊标签，以确保在内容变化时触发回调
                            val containsSpecialTags = fullResponse.contains("<toolname=") || 
                                                     fullResponse.contains("<tool name=") ||
                                                     fullResponse.contains("<status") ||
                                                     fullResponse.contains("<plan_item") ||
                                                     fullResponse.contains("<plan_update")
                            
                            // 处理计划项和状态标签
                            val hasPlanItems = processPlanItems(fullResponse)
                            val hasStatusTags = processStatusTags(fullResponse)
                            
                            // 如果检测到计划项或状态标签，确保触发回调
                            val shouldTriggerCallback = containsSpecialTags || hasPlanItems || hasStatusTags
                            
                            // 使用标签提取思考内容
                            val thinkStartTag = "<think>"
                            val thinkEndTag = "</think>"
                            val thinkStartIndex = fullResponse.indexOf(thinkStartTag)
                            
                            if (thinkStartIndex != -1) {
                                // 有思考内容标签
                                val thinkEndIndex = fullResponse.lastIndexOf(thinkEndTag)
                                
                                if (thinkEndIndex != -1 && thinkEndIndex > thinkStartIndex) {
                                    // 完整的思考内容
                                    val thinkContent = fullResponse.substring(
                                        thinkStartIndex + thinkStartTag.length,
                                        thinkEndIndex
                                    ).trim()
                                    
                                    val mainContent = fullResponse.substring(thinkEndIndex + thinkEndTag.length).trim()
                                    
                                    // 当内容有变化或包含特殊标签时才回调
                                    if (mainContent != lastSentResponse || shouldTriggerCallback) {
                                        lastSentResponse = mainContent
                                        onPartialResponse(mainContent, thinkContent)
                                    }
                                } else {
                                    // 思考内容尚未结束
                                    val thinkContent = fullResponse.substring(
                                        thinkStartIndex + thinkStartTag.length
                                    ).trim()
                                    
                                    // 设置思考内容，但主内容为空
                                    currentThinking = StringBuilder(thinkContent)
                                    onPartialResponse("", thinkContent)
                                }
                            } else {
                                // 没有思考内容标签，直接返回完整响应
                                // 当内容有变化或包含特殊标签时才回调
                                if (fullResponse != lastSentResponse || shouldTriggerCallback) {
                                    lastSentResponse = fullResponse
                                    onPartialResponse(fullResponse, null)
                                }
                            }
                        }
                    }
                }
            
            // 处理完成后，最后再检查一次标签
            val finalResponse = responseBuilder.toString()
            processPlanItems(finalResponse)
            processStatusTags(finalResponse)
            
            return finalResponse
        } catch (e: Exception) {
            if (e is IOException) throw e
            throw IOException("Failed to process content stream: ${e.message}")
        }
    }
    
    // 将Content列表转换为单个提示字符串
    @OptIn(ExperimentalSerializationApi::class)
    private fun buildPromptFromContents(contents: List<Content>): String {
        val prompt = StringBuilder()
        
        // 添加简短的系统说明，指导Gemini识别和使用XML标签
        prompt.append("""
            系统指令: 你是一个支持工具调用的AI助手。使用<tool_call>标签调用工具，处理<tool_result>标签中的工具执行结果。
            请保持XML标签的完整性。
        """.trimIndent())
        prompt.append("\n\n")
        
        // 直接添加所有消息内容，保持XML标记完整
        for (content in contents) {
            val role = content.role
            val text = content.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
            
            // 简单映射角色，保留原始消息内容
            when (role) {
                Role.user.toString() -> prompt.append("User: $text\n\n")
                Role.assistant.toString() -> prompt.append("Assistant: $text\n\n")
                Role.system.toString() -> prompt.append("System: $text\n\n")
                else -> prompt.append("$role: $text\n\n")
            }
        }
        
        return prompt.toString().trim()
    }
    
    // 更直接地处理包含工具调用或结果的消息，尊重原始XML格式
    @OptIn(ExperimentalSerializationApi::class)
    private fun handleMessageWithToolResults(message: String): Content {
        // 检查消息是否包含工具结果或工具调用，保持原始XML结构
        if (message.contains("<tool_result>") || message.contains("<tool_call>")) {
            Log.d(TAG, "检测到包含工具结果或调用的消息，保留完整XML格式")
            
            // 直接传递原始XML格式，保持标签的完整性
            _inputTokenCount += estimateTokenCount(message)
            return Content(Role.user.toString(), listOf(TextPart(message)))
        }
        
        // 默认处理
        _inputTokenCount += estimateTokenCount(message)
        return Content(Role.user.toString(), listOf(TextPart(message)))
    }
    
    // Send message to Gemini API
    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun sendMessage(
        message: String,
        onPartialResponse: (content: String, thinking: String?) -> Unit,
        chatHistory: List<Pair<String, String>>,
        onComplete: () -> Unit,
        onConnectionStatus: ((status: String) -> Unit)?,
        modelParameters: List<ModelParameter<*>>
    ) = withContext(Dispatchers.IO) {
        // Reset token counters and cancellation flag
        resetTokenCounts()
        isCancelled = false
        
        val maxRetries = 3
        var retryCount = 0
        var lastException: Exception? = null
        
        onConnectionStatus?.invoke("Preparing to connect to Gemini API...")
        
        // 标准化聊天历史角色
        val standardizedHistory = ChatUtils.mapChatHistoryToStandardRoles(chatHistory)
        
        // 转换为Gemini内容格式
        val historyContents = convertChatHistory(standardizedHistory)
        
        // 创建用户消息内容 - 使用简化的处理方式，保留XML格式
        val userContent = handleMessageWithToolResults(message)
        
        while (retryCount < maxRetries && !isCancelled) {
            try {
                // 创建带参数的GenerativeModel
                val model = createGenerativeModel(modelParameters)
                
                onConnectionStatus?.invoke("Establishing connection...")
                
                // 准备聊天的所有内容
                val allContents = historyContents + userContent
                this@GeminiService.chatHistory = allContents.toMutableList()
                
                // 记录发送到Gemini的所有内容，用于调试
                Log.d(TAG, "发送到Gemini的消息数量: ${allContents.size}")
                allContents.forEachIndexed { index, content ->
                    val text = content.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
                    Log.d(TAG, "消息 #$index - 角色: ${content.role}, 内容: ${text.take(100)}...")
                }
                
                // 开始流式生成
                onConnectionStatus?.invoke("Connection successful, waiting for response...")
                
                // 处理内容流，提高错误处理能力
                val finalResponse = processContentStream(model, allContents, onPartialResponse)
                
                // 仅在未取消时调用完成回调
                if (!isCancelled) {
                    Log.d(TAG, "Processing complete, calling completion callback")
                    
                    // 添加模型响应到聊天历史，以备将来调用
                    val modelResponse = Content(Role.assistant.toString(), listOf(TextPart(finalResponse)))
                    this@GeminiService.chatHistory.add(modelResponse)
                    
                    onComplete()
                } else {
                    Log.d(TAG, "Stream was cancelled, skipping completion callback")
                }
                
                // 成功完成
                return@withContext
                
            } catch (e: SocketTimeoutException) {
                lastException = e
                retryCount++
                onConnectionStatus?.invoke("Connection timeout, retrying (${retryCount})...")
                onPartialResponse("", "Connection timeout, retry attempt ${retryCount}...")
                // 指数退避
                delay(1000L * retryCount)
            } catch (e: UnknownHostException) {
                onConnectionStatus?.invoke("Could not connect to server, check network")
                throw IOException("Could not connect to server. Please check network connection or API address")
            } catch (e: Exception) {
                val errorMessage = when (e) {
                    is GenerativeAIException -> "Gemini API error: ${e.message}"
                    is IOException -> "IO error: ${e.message}"
                    else -> "Unexpected error: ${e.message}"
                }
                Log.e(TAG, errorMessage, e)
                onConnectionStatus?.invoke(errorMessage)
                throw IOException(errorMessage)
            }
        }
        
        // If we get here, all retries failed or request was cancelled
        if (isCancelled) {
            Log.d(TAG, "Operation cancelled by user")
            throw IOException("Operation cancelled by user")
        } else {
        onConnectionStatus?.invoke("Retry failed, please check network connection")
        throw IOException("Connection timeout after $maxRetries retries: ${lastException?.message}")
    }
} 

    /**
     * 处理响应中的计划项标签
     * 识别并处理<plan_item>和<plan_update>标签
     */
    private fun processPlanItems(response: String): Boolean {
        // 检查是否包含计划相关标签
        val hasPlanItems = response.contains("<plan_item") || response.contains("<plan_update")
        
        if (hasPlanItems) {
            Log.d(TAG, "检测到响应中包含计划项标签")
            
            // 计划项正则表达式
            val planItemPattern = "<plan_item\\s+(?:.*?)?id=\"([^\"]+)\"(?:.*?)status=\"([^\"]+)\"(?:.*?)>([^<]+)</plan_item>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val planUpdatePattern = "<plan_update\\s+(?:.*?)?(?:id=\"([^\"]+)\"(?:.*?)status=\"([^\"]+)\"|status=\"([^\"]+)\"(?:.*?)id=\"([^\"]+)\")(?:.*?)(?:/>|></plan_update>|>(?:[^<]*)</plan_update>)".toRegex(RegexOption.DOT_MATCHES_ALL)
            
            // 查找所有计划项
            val planItems = planItemPattern.findAll(response)
            planItems.forEach { matchResult ->
                val id = matchResult.groupValues[1]
                val status = matchResult.groupValues[2]
                val content = matchResult.groupValues[3]
                Log.d(TAG, "找到计划项: ID=$id, 状态=$status, 内容=$content")
            }
            
            // 查找所有计划更新
            val planUpdates = planUpdatePattern.findAll(response)
            planUpdates.forEach { matchResult ->
                // 处理两种可能的捕获组排列
                val id = matchResult.groupValues[1].takeIf { it.isNotEmpty() } ?: matchResult.groupValues[4]
                val status = matchResult.groupValues[2].takeIf { it.isNotEmpty() } ?: matchResult.groupValues[3]
                Log.d(TAG, "找到计划更新: ID=$id, 新状态=$status")
            }
            
            return true
        }
        
        return false
    }
    
    /**
     * 处理响应中的状态标签
     * 识别并处理<status>标签
     */
    private fun processStatusTags(response: String): Boolean {
        // 检查是否包含状态标签
        val hasStatusTags = response.contains("<status")
        
        if (hasStatusTags) {
            Log.d(TAG, "检测到响应中包含状态标签")
            
            // 状态标签正则表达式
            val statusPattern = "<status\\s+type=\"([^\"]+)\"(?:\\s+tool=\"([^\"]+)\")?(?:\\s+uuid=\"([^\"]+)\")?(?:\\s+success=\"([^\"]+)\")?(?:\\s+title=\"([^\"]+)\")?(?:\\s+subtitle=\"([^\"]+)\")?>([\\s\\S]*?)</status>".toRegex()
            
            // 查找所有状态标签
            val statusTags = statusPattern.findAll(response)
            statusTags.forEach { matchResult ->
                val type = matchResult.groupValues[1]
                val tool = matchResult.groupValues[2]
                val content = matchResult.groupValues[7]
                
                when (type) {
                    "wait_for_user_need" -> {
                        Log.d(TAG, "找到等待用户输入状态: $content")
                    }
                    "executing_tool" -> {
                        Log.d(TAG, "找到执行工具状态: 工具=$tool, 内容=$content")
                    }
                    "error" -> {
                        val title = matchResult.groupValues[5]
                        val subtitle = matchResult.groupValues[6]
                        Log.d(TAG, "找到错误状态: 标题=$title, 子标题=$subtitle, 内容=$content")
                    }
                }
            }
            
            return true
        }
        
        return false
    }
}
