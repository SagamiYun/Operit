package com.ai.assistance.operit.api

import android.util.Log
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.preferences.ApiPreferences.Companion.DEFAULT_MODEL_NAME
import com.ai.assistance.operit.data.preferences.ApiPreferences.Companion.DEFAULT_API_ENDPOINT as DEF_API_ENDPOINT
import com.ai.assistance.operit.util.ChatUtils
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * AIService for DeepSeek API integration, implements the common LLMProvider interface
 */
class AIService(
        private val apiEndpoint: String,
        private val apiKey: String,
        private val modelName: String
) : LLMProvider {
    companion object {
        private const val TAG = "AIService"
        
        // Default DeepSeek models
        const val DEEPSEEK_CHAT = DEFAULT_MODEL_NAME
        const val DEEPSEEK_CODER = "deepseek-coder"
        
        // Default DeepSeek endpoint
        const val DEFAULT_API_ENDPOINT = DEF_API_ENDPOINT
    }
    
    private val client =
            OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // 当前活跃的Call对象，用于取消流式传输
    private var activeCall: Call? = null

    // 添加token计数器
    private var _inputTokenCount = 0
    private var _outputTokenCount = 0

    // 公开token计数
    override val inputTokenCount: Int
        get() = _inputTokenCount
    override val outputTokenCount: Int
        get() = _outputTokenCount

    // Token计数逻辑
    private fun estimateTokenCount(text: String): Int {
        // 简单估算：中文每个字约1.5个token，英文每4个字符约1个token
        val chineseCharCount = text.count { it.code in 0x4E00..0x9FFF }
        val otherCharCount = text.length - chineseCharCount

        return (chineseCharCount * 1.5 + otherCharCount * 0.25).toInt()
    }

    // 重置token计数
    override fun resetTokenCounts() {
        _inputTokenCount = 0
        _outputTokenCount = 0
    }

    // 工具函数：分块打印大型文本日志
    private fun logLargeString(tag: String, message: String, prefix: String = "") {
        // 设置单次日志输出的最大长度（Android日志上限约为4000字符）
        val maxLogSize = 3000

        // 如果消息长度超过限制，分块打印
        if (message.length > maxLogSize) {
            // 计算需要分多少块打印
            val chunkCount = message.length / maxLogSize + 1

            for (i in 0 until chunkCount) {
                val start = i * maxLogSize
                val end = minOf((i + 1) * maxLogSize, message.length)
                val chunkMessage = message.substring(start, end)

                // 打印带有编号的日志
                Log.d(tag, "$prefix Part ${i+1}/$chunkCount: $chunkMessage")
            }
        } else {
            // 消息长度在限制之内，直接打印
            Log.d(tag, "$prefix$message")
        }
    }

    // 取消当前流式传输
    override fun cancelStreaming() {
        activeCall?.let {
            if (!it.isCanceled()) {
                it.cancel()
                Log.d("AIService", "已取消当前流式传输")
            }
        }
        activeCall = null
    }

    // 解析服务器返回的内容，处理<think>标签
    private fun parseResponse(content: String): Pair<String, String?> {
        val thinkStartTag = "<think>"
        val thinkEndTag = "</think>"

        val startIndex = content.indexOf(thinkStartTag)
        val endIndex = content.lastIndexOf(thinkEndTag)

        return if (startIndex != -1 && endIndex != -1 && startIndex < endIndex) {
            // 提取思考内容和主要内容
            val thinkContent = content.substring(startIndex + thinkStartTag.length, endIndex).trim()
            val mainContent = content.substring(endIndex + thinkEndTag.length).trim()
            Pair(mainContent, thinkContent)
        } else if (startIndex != -1 && endIndex == -1) {
            Pair("", content.substring(startIndex + thinkStartTag.length).trim())
        } else {
            // 没有思考内容，返回原始内容
            Pair(content, null)
        }
    }

    // 创建请求体
    private fun createRequestBody(
            message: String,
            chatHistory: List<Pair<String, String>>,
            modelParameters: List<ModelParameter<*>> = emptyList()
    ): RequestBody {
        val jsonObject = JSONObject()
        jsonObject.put("model", modelName)
        jsonObject.put("stream", true) // 启用流式响应

        // 添加已启用的模型参数
        for (param in modelParameters) {
            if (param.isEnabled) {
                when (param.valueType) {
                    com.ai.assistance.operit.data.model.ParameterValueType.INT ->
                            jsonObject.put(param.apiName, param.currentValue as Int)
                    com.ai.assistance.operit.data.model.ParameterValueType.FLOAT ->
                            jsonObject.put(param.apiName, param.currentValue as Float)
                    com.ai.assistance.operit.data.model.ParameterValueType.STRING ->
                            jsonObject.put(param.apiName, param.currentValue as String)
                    com.ai.assistance.operit.data.model.ParameterValueType.BOOLEAN ->
                            jsonObject.put(param.apiName, param.currentValue as Boolean)
                }
                Log.d("AIService", "添加参数 ${param.apiName} = ${param.currentValue}")
            }
        }

        // 创建消息数组，包含聊天历史
        val messagesArray = JSONArray()

        // 添加聊天历史
        if (chatHistory.isNotEmpty()) {

            // 添加历史消息 - 使用工具函数统一转换角色格式，并确保没有连续的相同角色
            val standardizedHistory = ChatUtils.mapChatHistoryToStandardRoles(chatHistory)

            // 防止连续相同角色消息
            val filteredHistory = mutableListOf<Pair<String, String>>()
            var lastRole: String? = null

            for ((role, content) in standardizedHistory) {
                // 如果当前消息角色与上一个相同，跳过（除了system消息）
                if (role == lastRole && role != "system") {
                    Log.d("AIService", "跳过连续相同角色的消息: $role")
                    continue
                }

                filteredHistory.add(Pair(role, content))
                lastRole = role
            }

            // 将过滤后的历史添加到消息数组
            for ((role, content) in filteredHistory) {
                val historyMessage = JSONObject()
                historyMessage.put("role", role)
                historyMessage.put("content", content)
                messagesArray.put(historyMessage)

                // 计算输入token
                _inputTokenCount += estimateTokenCount(content)
            }
        }

        // 添加当前消息，确保与上一条消息角色不同
        val lastMessageRole =
                if (messagesArray.length() > 0) {
                    messagesArray.getJSONObject(messagesArray.length() - 1).getString("role")
                } else null

        if (lastMessageRole != "user") {
            val messageObject = JSONObject()
            messageObject.put("role", "user")
            messageObject.put("content", message)
            messagesArray.put(messageObject)

            // 计算当前消息的token
            _inputTokenCount += estimateTokenCount(message)
        } else {
            // 如果上一条消息也是用户，将当前消息与上一条合并
            Log.d("AIService", "合并连续的用户消息")
            val lastMessage = messagesArray.getJSONObject(messagesArray.length() - 1)
            if (lastMessage.getString("content") != message) {
                val combinedContent = lastMessage.getString("content") + "\n" + message
                lastMessage.put("content", combinedContent)

                // 重新计算合并后消息的token
                _inputTokenCount += estimateTokenCount(message)
            }
        }

        jsonObject.put("messages", messagesArray)

        // 使用分块日志函数记录完整的请求体
        logLargeString("AIService", jsonObject.toString(4), "请求体: ")
        return jsonObject.toString().toRequestBody(JSON)
    }

    // 创建请求
    private fun createRequest(requestBody: RequestBody): Request {
        return Request.Builder()
                .url(apiEndpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()
    }

    // 处理响应
    private suspend fun processResponse(
            response: Response,
            onPartialResponse: (content: String, thinking: String?) -> Unit,
            onComplete: () -> Unit
    ) {
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "No error details"
            throw IOException("API请求失败，状态码: ${response.code}，错误信息: $errorBody")
        }

        val responseBody = response.body ?: throw IOException("API响应为空")
        val reader = responseBody.charStream().buffered()
        var currentContent = StringBuilder()
        var currentThinking = StringBuilder()
        var isFirstResponse = true
        var wasCancelled = false // 添加取消标志

        try {
            reader.useLines { lines ->
                lines.forEach { line ->
                    // 如果call已被取消，提前退出
                    if (activeCall?.isCanceled() == true) {
                        Log.d("AIService", "流式传输已被取消，提前退出处理")
                        wasCancelled = true // 设置取消标志
                        return@forEach
                    }

                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()
                        if (data != "[DONE]") {
                            try {
                                val jsonResponse = JSONObject(data)
                                val choices = jsonResponse.getJSONArray("choices")

                                if (choices.length() > 0) {
                                    val choice = choices.getJSONObject(0)

                                    // 检查是否有reasoning_content（Deepseek API格式）
                                    var hasReasoningContent = false
                                    var reasoningContent = ""
                                    var content = ""

                                    // 处理delta格式（流式响应）
                                    val delta = choice.optJSONObject("delta")
                                    if (delta != null) {
                                        // 尝试获取常规内容
                                        content = delta.optString("content", "")

                                        // 尝试获取reasoning_content（思考内容）
                                        reasoningContent = delta.optString("reasoning_content", "")
                                        if (reasoningContent.isNotEmpty() &&
                                                        reasoningContent != "null"
                                        ) {
                                            hasReasoningContent = true
                                        }
                                    }
                                    // 处理message格式（非流式响应）
                                    else {
                                        val message = choice.optJSONObject("message")
                                        if (message != null) {
                                            content = message.optString("content", "")
                                            reasoningContent =
                                                    message.optString("reasoning_content", "")
                                            if (reasoningContent.isNotEmpty() &&
                                                            reasoningContent != "null"
                                            ) {
                                                hasReasoningContent = true
                                            }
                                        }
                                    }

                                    // 处理收到的内容
                                    if ((content.isNotEmpty() && content != "null") ||
                                                    hasReasoningContent
                                    ) {
                                        // 当收到第一个有效内容时，标记不再是首次响应
                                        if (isFirstResponse) {
                                            isFirstResponse = false
                                        }

                                        // 更新内容
                                        if (content.isNotEmpty() && content != "null") {
                                            currentContent.append(content)

                                            // 计算输出tokens
                                            _outputTokenCount += estimateTokenCount(content)
                                        }

                                        // 更新思考内容（仅当使用Deepseek API时）
                                        if (hasReasoningContent) {
                                            currentThinking.append(reasoningContent)
                                        }

                                        // 解析内容，检查<think>标签（原始方案）
                                        val (mainContent, thinkContent) =
                                                parseResponse(currentContent.toString())

                                        // 确定最终输出内容
                                        val finalContent =
                                                if (mainContent.isNotEmpty()) mainContent
                                                else currentContent.toString()

                                        // 确定思考内容，保持两种方式
                                        val finalThinking =
                                                when {
                                                    // 如果原始方案有思考内容，则使用原始方案的思考内容
                                                    thinkContent != null -> thinkContent

                                                    // 如果Deepseek API有思考内容，则使用Deepseek API的思考内容
                                                    currentThinking.isNotEmpty() ->
                                                            currentThinking.toString()

                                                    // 都没有则返回null
                                                    else -> null
                                                }

                                        onPartialResponse(finalContent, finalThinking)
                                    }
                                }
                            } catch (e: Exception) {
                                // 忽略解析错误，继续处理下一行
                                Log.d("AIService", "JSON解析错误: ${e.message}")
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // 捕获IO异常，可能是由于取消Call导致的
            if (activeCall?.isCanceled() == true) {
                Log.d("AIService", "流式传输已被取消，处理IO异常")
                wasCancelled = true // 设置取消标志
            } else {
                throw e
            }
        }

        // 只有在未被取消时才调用完成回调
        if (!wasCancelled && activeCall?.isCanceled() != true) {
            Log.d("AIService", "处理完成，调用完成回调")
            onComplete()
        } else {
            Log.d("AIService", "流被取消，跳过完成回调")
        }

        // 清理活跃Call引用
        activeCall = null
    }

    override suspend fun sendMessage(
            message: String,
            onPartialResponse: (content: String, thinking: String?) -> Unit,
            chatHistory: List<Pair<String, String>>,
            onComplete: () -> Unit,
            onConnectionStatus: ((status: String) -> Unit)?,
            modelParameters: List<ModelParameter<*>>
    ) =
            withContext(Dispatchers.IO) {
                // 重置token计数
                _inputTokenCount = 0
                _outputTokenCount = 0

                val maxRetries = 3
                var retryCount = 0
                var lastException: Exception? = null

                val standardizedHistory = ChatUtils.mapChatHistoryToStandardRoles(chatHistory)
                val requestBody = createRequestBody(message, standardizedHistory, modelParameters)
                val request = createRequest(requestBody)

                onConnectionStatus?.invoke("准备连接到DeepSeek AI服务...")
                while (retryCount < maxRetries) {
                    try {
                        // 创建Call对象并保存到activeCall中，以便可以取消
                        val call = client.newCall(request)
                        activeCall = call

                        onConnectionStatus?.invoke("正在建立连接...")
                        call.execute().use { response ->
                            onConnectionStatus?.invoke("连接成功，等待响应...")
                            processResponse(response, onPartialResponse, onComplete)
                        }

                        // 成功处理后，清空activeCall
                        activeCall = null

                        // 成功处理，返回
                        return@withContext
                    } catch (e: SocketTimeoutException) {
                        lastException = e
                        retryCount++
                        // 通知正在重试
                        onConnectionStatus?.invoke("连接超时，正在进行第 $retryCount 次重试...")
                        onPartialResponse("", "连接超时，正在进行第 $retryCount 次重试...")
                        // 指数退避重试
                        delay(1000L * retryCount)
                    } catch (e: UnknownHostException) {
                        onConnectionStatus?.invoke("无法连接到服务器，请检查网络")
                        throw IOException("无法连接到服务器，请检查网络连接或API地址是否正确")
                    } catch (e: Exception) {
                        onConnectionStatus?.invoke("连接失败: ${e.message}")
                        throw IOException(
                                "AI响应获取失败: ${e.message} ${e.stackTrace.joinToString("\n")}"
                        )
                    }
                }

                onConnectionStatus?.invoke("重试失败，请检查网络连接")
                // 所有重试都失败
                throw IOException("连接超时，已重试 $maxRetries 次: ${lastException?.message}")
            }
}
