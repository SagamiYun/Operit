package com.ai.assistance.operit.api.library

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.api.LLMProvider
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.preferences.preferencesManager
import com.ai.assistance.operit.util.TextSegmenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 问题库管理类 - 提供分析对话内容并存储问题记录的功能 */
object ProblemLibrary {
    private const val TAG = "ProblemLibrary"
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var apiPreferences: ApiPreferences? = null

    // 添加初始化状态标志
    @Volatile private var isInitialized = false

    /** 分析结果数据类 */
    data class AnalysisResults(
            val problemSummary: String = "",
            val userPreferences: String = "",
            val solutionSummary: String = ""
    )

    /** 初始化问题库 */
    fun initialize(context: Context) {
        // 添加同步锁和初始化检查，确保只初始化一次
        synchronized(ProblemLibrary::class.java) {
            if (isInitialized) {
                // Log.d(TAG, "ProblemLibrary 已经初始化，跳过重复初始化")
                return
            }

            Log.d(TAG, "正在初始化 ProblemLibrary")
            apiPreferences = ApiPreferences(context.applicationContext)

            // 初始化分词器
            TextSegmenter.initialize(context.applicationContext)

            // 后台预热分词器
            coroutineScope.launch {
                try {
                    prewarmSegmenter()
                    Log.d(TAG, "分词器预热完成")
                } catch (e: Exception) {
                    Log.e(TAG, "分词器预热失败", e)
                }
            }

            isInitialized = true
            Log.d(TAG, "ProblemLibrary 初始化完成")
        }
    }

    /** 预热分词器，提前加载词典 */
    private suspend fun prewarmSegmenter() {
        withContext(Dispatchers.IO) {
            // 使用几个常见生活词汇预热分词器
            val testWords = listOf("如何制作美味的家常菜", "周末旅行好去处推荐", "健康生活小技巧分享", "家庭理财省钱妙招", "日常英语口语学习方法")

            testWords.forEach { word -> TextSegmenter.segment(word) }
        }
    }

    /** 保存问题到问题库（异步方式） */
    fun saveProblemAsync(
            context: Context,
            toolHandler: AIToolHandler,
            conversationHistory: List<Pair<String, String>>,
            content: String,
            aiService: LLMProvider
    ) {
        ensureInitialized(context)

        coroutineScope.launch {
            try {
                saveProblem(toolHandler, conversationHistory, content, aiService)
            } catch (e: Exception) {
                Log.e(TAG, "保存问题记录失败", e)
            }
        }
    }

    /** 确保已初始化 */
    private fun ensureInitialized(context: Context) {
        if (!isInitialized) {
            synchronized(ProblemLibrary::class.java) {
                if (!isInitialized) {
                    initialize(context)
                }
            }
        }
    }

    /** 保存问题记录（内部实现） */
    private suspend fun saveProblem(
            toolHandler: AIToolHandler,
            conversationHistory: List<Pair<String, String>>,
            content: String,
            aiService: LLMProvider
    ) {
        // 检查会话历史是否为空
        if (conversationHistory.isEmpty()) {
            Log.w(TAG, "会话历史为空，跳过保存问题记录")
            return
        }

        // 提取使用的工具
        val toolInvocations = toolHandler.extractToolInvocations(content)
        val tools = toolInvocations.map { it.tool.name }

        // 获取用户最后一条消息作为查询
        val query = conversationHistory.lastOrNull { it.first == "user" }?.second ?: ""
        if (query.isEmpty()) {
            Log.w(TAG, "未找到用户查询消息，使用空字符串")
        }

        // 生成问题分析
        val analysisResults =
                try {
                    generateAnalysis(aiService, query, content, conversationHistory)
                } catch (e: Exception) {
                    Log.e(TAG, "生成分析失败", e)
                    AnalysisResults()
                }

        // 更新用户偏好
        if (analysisResults.userPreferences.isNotEmpty()) {
            try {
                withContext(Dispatchers.IO) {
                    // 解析生成的偏好文本，尝试更新各个分类
                    updateUserPreferencesFromAnalysis(analysisResults.userPreferences)
                    Log.d(TAG, "用户偏好已更新")
                }
            } catch (e: Exception) {
                Log.e(TAG, "更新用户偏好失败", e)
            }
        }

        // 创建问题记录
        val record =
                ProblemLibraryTool.ProblemRecord(
                        uuid = java.util.UUID.randomUUID().toString(),
                        query = query,
                        solution =
                                if (analysisResults.solutionSummary.isNotEmpty())
                                        analysisResults.solutionSummary
                                else content.take(300),
                        tools = tools,
                        summary = analysisResults.problemSummary
                )

        // 保存问题记录到 ProblemLibraryTool
        try {
            val problemLibraryTool = toolHandler.getProblemLibraryTool()
            if (problemLibraryTool != null) {
                problemLibraryTool.saveProblemRecord(record)
                Log.d(TAG, "问题记录已保存: ${record.uuid}")
            } else {
                Log.e(TAG, "保存问题记录失败: ProblemLibraryTool 未初始化")
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存问题记录失败", e)
        }
    }

    /** 生成分析结果 */
    private suspend fun generateAnalysis(
            aiService: LLMProvider,
            query: String,
            solution: String,
            conversationHistory: List<Pair<String, String>>
    ): AnalysisResults {
        try {
            // 获取当前的用户偏好
            val currentPreferences =
                    withContext(Dispatchers.IO) {
                        var preferences = ""
                        preferencesManager.getUserPreferencesFlow().take(1).collect { profile ->
                            preferences = buildPreferencesText(profile)
                        }
                        preferences
                    }

            val systemPrompt =
                    """
                你是一个专业的问题分析专家。你的任务是：
                1. 根据用户的问题和解决方案，生成一个简洁的问题摘要
                2. 分析用户的对话历史，增量更新用户偏好信息
                3. 对解决方案进行全面归纳总结，保留关键信息和上下文
                
                你需要返回一个固定格式的JSON对象，包含三个字段：
                {
                  "problem_summary": "问题摘要内容",
                  "user_preferences": {
                    "age": 保持不变用"<UNCHANGED>"，有新发现则更新为数字,
                    "gender": 保持不变用"<UNCHANGED>"，有新发现则更新为具体值,
                    "personality": 保持不变用"<UNCHANGED>"，有新发现则更新为具体值,
                    "identity": 保持不变用"<UNCHANGED>"，有新发现则更新为具体值,
                    "occupation": 保持不变用"<UNCHANGED>"，有新发现则更新为具体值,
                    "aiStyle": 保持不变用"<UNCHANGED>"，有新发现则更新为具体值
                  },
                  "solution_summary": "解决方案摘要内容"
                }
                
                问题摘要：清晰描述问题的核心和背景，不超过150字，客观记录技术术语和问题类型。
                【重要提示】必须从解决方案中提取并包含关键技术词汇、函数名、类名、工具名称和专业术语，
                以确保后续基于关键词的搜索能够找到这条记录。必须在摘要末尾添加以下格式的内容:
                "关键词: [从解决方案中提取的10-15个最重要的技术词汇、方法名和核心概念，用逗号分隔]"
                
                用户偏好：【特别重要】用结构化JSON格式表示，在现有偏好的基础上进行小幅增量更新，不要完全重写。
                现有用户偏好："${currentPreferences}"
                对于没有新发现的字段，使用"<UNCHANGED>"特殊标记表示保持不变。
                只有当确定发现与现有偏好不同的新信息时才进行更新。
                
                解决方案摘要：全面提炼解决方案的核心步骤和关键点，不超过600字，结构化呈现。
                必须包含：
                1. 用户身份信息（如有）
                2. 用户特定喜好和偏好（如有）
                3. 对话中的关键注意点和警告
                4. 核心解决步骤和方法
                5. 技术术语和专业指导
                6. 解决方案中的亮点句子和独特表述，使用原文中的原始表达
                7. 【特别重要】直接引用解决方案中最关键的1-2个代码片段或独特表述，使用引号标注
                
                只返回格式正确的JSON对象，不要添加任何其他内容。
            """.trimIndent()

            // 构建分析消息
            val analysisMessage = buildAnalysisMessage(query, solution, conversationHistory)

            // AIService会自动计算和累计token，不需要手动预估

            // 准备消息
            val messages = listOf(Pair("system", systemPrompt), Pair("user", analysisMessage))

            // 收集结果
            val result = StringBuilder()
            var outputTokens = 0

            // 调用AI服务
            withContext(Dispatchers.IO) {
                aiService.sendMessage(
                        message = analysisMessage,
                        onPartialResponse = { content, _ ->
                            result.clear()
                            result.append(content)
                            outputTokens = aiService.outputTokenCount
                        },
                        chatHistory = messages,
                        onComplete = {}
                )
            }

            // 更新token统计
            apiPreferences?.updatePreferenceAnalysisTokens(
                    aiService.inputTokenCount,
                    aiService.outputTokenCount
            )

            // 解析结果
            return parseAnalysisResult(result.toString())
        } catch (e: Exception) {
            Log.e(TAG, "生成分析失败", e)
            return AnalysisResults()
        }
    }

    /** 构建分析消息 */
    private fun buildAnalysisMessage(
            query: String,
            solution: String,
            conversationHistory: List<Pair<String, String>>
    ): String {
        val messageBuilder = StringBuilder()

        // 添加问题和解决方案
        messageBuilder.appendLine("问题:")
        messageBuilder.appendLine(query)
        messageBuilder.appendLine()
        messageBuilder.appendLine("解决方案:")
        messageBuilder.appendLine(solution.take(3000)) // 增加取值长度，获取更多解决方案细节
        messageBuilder.appendLine()

        // 添加更完整的对话历史（最多15条，每条限制300字符）
        val recentHistory = conversationHistory.takeLast(15)
        if (recentHistory.isNotEmpty()) {
            messageBuilder.appendLine("历史记录:")
            recentHistory.forEachIndexed { index, (role, content) ->
                messageBuilder.appendLine("#${index + 1} $role: ${content.take(300)}")
            }
        }

        return messageBuilder.toString()
    }

    /** 解析分析结果 */
    private fun parseAnalysisResult(jsonString: String): AnalysisResults {
        return try {
            // 清理非JSON前缀
            val cleanJson =
                    jsonString.trim().let {
                        val startIndex = it.indexOf("{")
                        val endIndex = it.lastIndexOf("}")
                        if (startIndex in 0..<endIndex) {
                            it.substring(startIndex, endIndex + 1)
                        } else {
                            it
                        }
                    }

            val json = JSONObject(cleanJson)

            // 提取用户偏好信息，将结构化数据转换为字符串
            val userPreferences =
                    if (json.has("user_preferences") && json.get("user_preferences") is JSONObject
                    ) {
                        val preferencesObj = json.getJSONObject("user_preferences")
                        val preferenceParts = mutableListOf<String>()

                        // 处理每个偏好类别
                        if (preferencesObj.has("age") && preferencesObj.get("age") != "<UNCHANGED>"
                        ) {
                            val age = preferencesObj.get("age")
                            preferenceParts.add("出生年份: $age")
                        }

                        if (preferencesObj.has("gender") &&
                                        preferencesObj.get("gender") != "<UNCHANGED>"
                        ) {
                            val gender = preferencesObj.getString("gender")
                            if (gender.isNotEmpty()) {
                                preferenceParts.add("性别: $gender")
                            }
                        }

                        if (preferencesObj.has("personality") &&
                                        preferencesObj.get("personality") != "<UNCHANGED>"
                        ) {
                            val personality = preferencesObj.getString("personality")
                            if (personality.isNotEmpty()) {
                                preferenceParts.add("性格特点: $personality")
                            }
                        }

                        if (preferencesObj.has("identity") &&
                                        preferencesObj.get("identity") != "<UNCHANGED>"
                        ) {
                            val identity = preferencesObj.getString("identity")
                            if (identity.isNotEmpty()) {
                                preferenceParts.add("身份认同: $identity")
                            }
                        }

                        if (preferencesObj.has("occupation") &&
                                        preferencesObj.get("occupation") != "<UNCHANGED>"
                        ) {
                            val occupation = preferencesObj.getString("occupation")
                            if (occupation.isNotEmpty()) {
                                preferenceParts.add("职业: $occupation")
                            }
                        }

                        if (preferencesObj.has("aiStyle") &&
                                        preferencesObj.get("aiStyle") != "<UNCHANGED>"
                        ) {
                            val aiStyle = preferencesObj.getString("aiStyle")
                            if (aiStyle.isNotEmpty()) {
                                preferenceParts.add("期待的AI风格: $aiStyle")
                            }
                        }

                        preferenceParts.joinToString("; ")
                    } else {
                        // 兼容旧格式
                        json.optString("user_preferences", "")
                    }

            AnalysisResults(
                    problemSummary = json.optString("problem_summary", "").take(500),
                    userPreferences = userPreferences.take(200),
                    solutionSummary = json.optString("solution_summary", "").take(1000)
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析分析结果失败", e)
            AnalysisResults(problemSummary = jsonString.take(200))
        }
    }

    /** 将用户偏好配置转换为文本描述 */
    private fun buildPreferencesText(
            profile: com.ai.assistance.operit.data.model.PreferenceProfile
    ): String {
        val parts = mutableListOf<String>()

        if (profile.gender.isNotEmpty()) {
            parts.add("性别: ${profile.gender}")
        }

        if (profile.birthDate > 0) {
            // Convert timestamp to formatted date string
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            parts.add("出生日期: ${dateFormat.format(java.util.Date(profile.birthDate))}")

            // Also calculate and include age
            val today = java.util.Calendar.getInstance()
            val birthCal =
                    java.util.Calendar.getInstance().apply { timeInMillis = profile.birthDate }
            var age = today.get(java.util.Calendar.YEAR) - birthCal.get(java.util.Calendar.YEAR)
            // Adjust age if birthday hasn't occurred yet this year
            if (today.get(java.util.Calendar.MONTH) < birthCal.get(java.util.Calendar.MONTH) ||
                            (today.get(java.util.Calendar.MONTH) ==
                                    birthCal.get(java.util.Calendar.MONTH) &&
                                    today.get(java.util.Calendar.DAY_OF_MONTH) <
                                            birthCal.get(java.util.Calendar.DAY_OF_MONTH))
            ) {
                age--
            }
            parts.add("年龄: ${age}岁")
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

    /**
     * 从分析结果文本中解析并更新用户偏好
     *
     * 分析文本可能是结构化格式，例如： "性别: 男; 出生年份: 1990; 性格特点: 耐心、细致; 职业: 软件工程师"
     *
     * 只有在分析出来的字段才会被更新，未包含的字段将保持不变
     */
    private suspend fun updateUserPreferencesFromAnalysis(preferencesText: String) {
        if (preferencesText.isEmpty()) {
            return
        }

        // 提取各项信息
        val birthDateMatch = """(出生日期|出生年月日)[:：\s]+([\d-]+)""".toRegex().find(preferencesText)
        val birthYearMatch = """(出生年份|年龄)[:：\s]+(\d+)""".toRegex().find(preferencesText)
        val genderMatch = """性别[:：\s]+([\u4e00-\u9fa5]+)""".toRegex().find(preferencesText)
        val personalityMatch =
                """性格(特点)?[:：\s]+([\u4e00-\u9fa5、，,]+)""".toRegex().find(preferencesText)
        val identityMatch =
                """身份(认同)?[:：\s]+([\u4e00-\u9fa5、，,]+)""".toRegex().find(preferencesText)
        val occupationMatch = """职业[:：\s]+([\u4e00-\u9fa5、，,]+)""".toRegex().find(preferencesText)
        val aiStyleMatch =
                """(AI风格|期待的AI风格|偏好的AI风格)[:：\s]+([\u4e00-\u9fa5、，,]+)"""
                        .toRegex()
                        .find(preferencesText)

        // 转换出生日期或年龄为birthDate时间戳
        var birthDateTimestamp: Long? = null
        if (birthDateMatch != null) {
            // 尝试解析完整日期格式 (yyyy-MM-dd)
            try {
                val dateFormat =
                        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                val date = dateFormat.parse(birthDateMatch.groupValues[2])
                if (date != null) {
                    birthDateTimestamp = date.time
                }
            } catch (e: Exception) {
                Log.e(TAG, "解析出生日期失败: ${e.message}")
            }
        } else if (birthYearMatch != null) {
            // 只有年份，设置为当年1月1日
            try {
                val year = birthYearMatch.groupValues[2].toInt()
                val calendar = java.util.Calendar.getInstance()
                calendar.set(year, java.util.Calendar.JANUARY, 1, 0, 0, 0)
                calendar.set(java.util.Calendar.MILLISECOND, 0)
                birthDateTimestamp = calendar.timeInMillis
            } catch (e: Exception) {
                Log.e(TAG, "解析出生年份失败: ${e.message}")
            }
        }

        // 只更新分析出的字段，其他字段保持不变
        preferencesManager.updateProfileCategory(
                birthDate = birthDateTimestamp,
                gender = genderMatch?.groupValues?.getOrNull(1),
                personality = personalityMatch?.groupValues?.getOrNull(2),
                identity = identityMatch?.groupValues?.getOrNull(2),
                occupation = occupationMatch?.groupValues?.getOrNull(1),
                aiStyle = aiStyleMatch?.groupValues?.getOrNull(2)
        )

        // 记录更新了哪些字段
        val updatedFields = mutableListOf<String>()
        if (birthDateTimestamp != null) updatedFields.add("出生日期")
        if (genderMatch != null) updatedFields.add("性别")
        if (personalityMatch != null) updatedFields.add("性格特点")
        if (identityMatch != null) updatedFields.add("身份认同")
        if (occupationMatch != null) updatedFields.add("职业")
        if (aiStyleMatch != null) updatedFields.add("AI风格偏好")

        if (updatedFields.isNotEmpty()) {
            Log.d(TAG, "已更新用户偏好字段: ${updatedFields.joinToString(", ")}")
        } else {
            Log.d(TAG, "未从文本中提取到新的用户偏好信息")
        }
    }
}
