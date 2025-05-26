package com.ai.assistance.operit.core.tools

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.api.enhance.ConversationMarkupManager
import com.ai.assistance.operit.api.library.ProblemLibraryTool
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolExecutionProgress
import com.ai.assistance.operit.data.model.ToolExecutionState
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.model.ToolValidationResult
import com.ai.assistance.operit.ui.permissions.ToolCategory
import com.ai.assistance.operit.ui.permissions.ToolPermissionSystem
import java.util.regex.Pattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Handles the extraction and execution of AI tools from responses Supports real-time streaming
 * extraction and execution of tools
 */
class AIToolHandler private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AIToolHandler"

        @Volatile private var INSTANCE: AIToolHandler? = null

        fun getInstance(context: Context): AIToolHandler {
            return INSTANCE
                    ?: synchronized(this) {
                        INSTANCE ?: AIToolHandler(context.applicationContext).also { INSTANCE = it }
                    }
        }

        // Updated and more robust regex patterns for tool extraction with improved
        // robustness
        private val TOOL_PATTERN =
                Pattern.compile(
                        "<tool\\s+name=\\s*\"([^\"]+)\"(?:\\s+description=\\s*\"([^\"]+)\")?[^>]*>([\\s\\S]*?)</tool>",
                        Pattern.DOTALL
                )

        private val TOOL_NAME_PATTERN = Pattern.compile("name=\\s*\"([^\"]+)\"", Pattern.DOTALL)

        private val TOOL_PARAM_PATTERN =
                Pattern.compile(
                        "<param\\s+name=\\s*\"([^\"]+)\">([\\s\\S]*?)</param>",
                        Pattern.DOTALL
                )

        // Alternative pattern to match common edge cases with different spacing
        private val ALTERNATIVE_TOOL_PATTERN =
                Pattern.compile(
                        "<tool[\\s\\n]+name\\s*=\\s*[\"']([^\"']+)[\"'](?:[\\s\\n]+description\\s*=\\s*[\"']([^\"']+)[\"'])?[^>]*>([\\s\\S]*?)</tool>",
                        Pattern.DOTALL
                )
                
        // 识别简化的工具调用格式，如<toolname="list_files">/sdcard/
        private val SIMPLIFIED_TOOL_PATTERN =
                Pattern.compile(
                        "<toolname=\"([^\"]+)\">([\\s\\S]*?)(?:<|\n|\r|$)",
                        Pattern.DOTALL
                )
    }

    // Tool execution state
    private val _toolProgress =
            MutableStateFlow(ToolExecutionProgress(state = ToolExecutionState.IDLE))
    val toolProgress: StateFlow<ToolExecutionProgress> = _toolProgress.asStateFlow()

    // Available tools registry
    private val availableTools = mutableMapOf<String, ToolExecutor>()

    // Tool permission system
    private val toolPermissionSystem = ToolPermissionSystem.getInstance(context)

    // 问题库工具实例（在工具注册时设置）
    private var problemLibraryTool: ProblemLibraryTool? = null

    /**
     * 设置问题库工具实例
     * @param tool 问题库工具实例
     */
    fun setProblemLibraryTool(tool: ProblemLibraryTool) {
        this.problemLibraryTool = tool
    }

    /**
     * 获取问题库工具实例
     * @return 问题库工具实例，如果没有则返回null
     */
    fun getProblemLibraryTool(): ProblemLibraryTool? {
        // 如果还没有设置过问题库工具，则自动创建一个
        if (problemLibraryTool == null) {
            synchronized(this) {
                if (problemLibraryTool == null) {
                    problemLibraryTool = ProblemLibraryTool.getInstance(context)
                    Log.d(TAG, "自动创建并设置了ProblemLibraryTool实例")
                }
            }
        }
        return problemLibraryTool
    }

    /** Get the tool permission system for UI use */
    fun getToolPermissionSystem(): ToolPermissionSystem {
        return toolPermissionSystem
    }

    /** Force refresh permission request state Can be called if permission dialog is not showing */
    fun refreshPermissionState(): Boolean {
        return toolPermissionSystem.refreshPermissionRequestState()
    }

    // 工具注册的唯一方法 - 提供完整信息的注册
    fun registerTool(
            name: String,
            category: ToolCategory,
            dangerCheck: ((AITool) -> Boolean)? = null,
            descriptionGenerator: ((AITool) -> String)? = null,
            executor: ToolExecutor
    ) {
        // 注册工具的类别
        val wrappedExecutor =
                object : ToolExecutor {
                    override fun invoke(tool: AITool): ToolResult {
                        // 创建一个带类别的工具实例（如果需要）
                        val toolWithCategory =
                                if (tool.category == null) {
                                    tool.copy(category = category)
                                } else {
                                    tool
                                }
                        return executor.invoke(toolWithCategory)
                    }

                    override fun validateParameters(tool: AITool): ToolValidationResult {
                        return executor.validateParameters(tool)
                    }

                    override fun getCategory(): ToolCategory {
                        return category
                    }
                }

        availableTools[name] = wrappedExecutor

        // 注册危险操作检查（如果提供）
        if (dangerCheck != null) {
            toolPermissionSystem.registerDangerousOperation(name, dangerCheck)
        }

        // 注册描述生成器（如果提供）
        if (descriptionGenerator != null) {
            toolPermissionSystem.registerOperationDescription(name, descriptionGenerator)
        }
    }

    // 添加重载方法接受函数式接口作为executor的便捷写法
    fun registerTool(
            name: String,
            category: ToolCategory,
            dangerCheck: ((AITool) -> Boolean)? = null,
            descriptionGenerator: ((AITool) -> String)? = null,
            executor: (AITool) -> ToolResult
    ) {
        registerTool(
                name = name,
                category = category,
                dangerCheck = dangerCheck,
                descriptionGenerator = descriptionGenerator,
                executor = ToolExecutor { tool -> executor(tool) }
        )
    }

    // Register all default tools
    fun registerDefaultTools() {
        // Initialize the permission system with default rules
        toolPermissionSystem.initializeDefaultRules()

        registerAllTools(this, context)
    }

    // Package manager instance (lazy initialized)
    private var packageManagerInstance: PackageManager? = null

    /** Gets or creates the package manager instance */
    fun getOrCreatePackageManager(): PackageManager {
        return packageManagerInstance
                ?: run {
                    packageManagerInstance = PackageManager.getInstance(context, this)
                    packageManagerInstance!!
                }
    }

    /**
     * Process the AI response to extract and execute tools
     * @param response The AI response text
     * @return The processed response with tool results inserted
     */
    suspend fun processResponse(response: String): String {
        _toolProgress.value =
                ToolExecutionProgress(
                        state = ToolExecutionState.EXTRACTING,
                        message = "Extracting tools from response..."
                )

        try {
            // Extract tool invocations
            val toolInvocations = extractToolInvocations(response)

            if (toolInvocations.isEmpty()) {
                _toolProgress.value =
                        ToolExecutionProgress(
                                state = ToolExecutionState.COMPLETED,
                                message = "No tools to execute"
                        )
                return response
            }

            var processedResponse = response

            // Execute each tool and replace the invocation with the result
            toolInvocations.forEachIndexed { index, invocation ->
                _toolProgress.value =
                        ToolExecutionProgress(
                                state = ToolExecutionState.EXECUTING,
                                tool = invocation.tool,
                                progress = index.toFloat() / toolInvocations.size,
                                message = "Executing tool: ${invocation.tool.name}..."
                        )

                // Find the executor for this tool
                val executor = availableTools[invocation.tool.name]

                if (executor == null) {
                    // Create a proper ToolResult for unavailable tool
                    val toolNotAvailableResult =
                            ToolResult(
                                    toolName = invocation.tool.name,
                                    success = false,
                                    result = StringResultData(""),
                                    error = "Tool '${invocation.tool.name}' is not available"
                            )

                    // Format using ConversationMarkupManager
                    val errorResult =
                            ConversationMarkupManager.formatToolResultForMessage(
                                    toolNotAvailableResult
                            )

                    processedResponse =
                            replaceToolInvocation(processedResponse, invocation, errorResult)

                    // Update tool progress state
                    _toolProgress.value =
                            ToolExecutionProgress(
                                    state = ToolExecutionState.FAILED,
                                    tool = invocation.tool,
                                    message = "Tool not available: ${invocation.tool.name}",
                                    result = toolNotAvailableResult
                            )
                } else {
                    try {
                        // Add permission check
                        Log.d(TAG, "Starting permission check for tool: ${invocation.tool.name}")

                        // Initialize permission variables
                        var permissionDenied = false

                        try {
                            // Perform permission check
                            val hasPermission =
                                    kotlinx.coroutines.runBlocking {
                                        toolPermissionSystem.checkToolPermission(invocation.tool)
                                    }
                            Log.d(
                                    TAG,
                                    "Permission check result: ${invocation.tool.name}, result: $hasPermission"
                            )

                            if (!hasPermission) {
                                // User denied permission
                                val permissionDeniedResult =
                                        ToolResult(
                                                toolName = invocation.tool.name,
                                                success = false,
                                                result = StringResultData(""),
                                                error =
                                                        "Permission denied: Operation '${invocation.tool.name}' was not authorized"
                                        )

                                // Format using
                                // ConversationMarkupManager
                                val errorResult =
                                        ConversationMarkupManager.formatToolResultForMessage(
                                                permissionDeniedResult
                                        )

                                Log.d(
                                        TAG,
                                        "Permission denied: ${invocation.tool.name}, replacing invocation"
                                )

                                processedResponse =
                                        replaceToolInvocation(
                                                processedResponse,
                                                invocation,
                                                errorResult
                                        )

                                _toolProgress.value =
                                        ToolExecutionProgress(
                                                state = ToolExecutionState.FAILED,
                                                tool = invocation.tool,
                                                message =
                                                        "Permission denied for tool: ${invocation.tool.name}",
                                                result = permissionDeniedResult
                                        )

                                // Mark permission as denied due to
                                // error
                                permissionDenied = true
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error during permission check", e)

                            // Create a proper ToolResult for the
                            // permission check error
                            val permissionCheckErrorResult =
                                    ToolResult(
                                            toolName = invocation.tool.name,
                                            success = false,
                                            result = StringResultData(""),
                                            error = "Permission check failed: ${e.message}"
                                    )

                            // Format using ConversationMarkupManager
                            val errorResult =
                                    ConversationMarkupManager.formatToolResultForMessage(
                                            permissionCheckErrorResult
                                    )

                            processedResponse =
                                    replaceToolInvocation(
                                            processedResponse,
                                            invocation,
                                            errorResult
                                    )

                            _toolProgress.value =
                                    ToolExecutionProgress(
                                            state = ToolExecutionState.FAILED,
                                            tool = invocation.tool,
                                            message = "Permission check failed: ${e.message}",
                                            result = permissionCheckErrorResult
                                    )

                            // Mark permission as denied due to error
                            permissionDenied = true
                        }

                        // If permission not denied, execute the tool
                        if (!permissionDenied) {
                            // Execute the tool
                            val result = executor.invoke(invocation.tool)

                            // Replace the tool invocation with the
                            // result
                            processedResponse =
                                    replaceToolInvocation(
                                            processedResponse,
                                            invocation,
                                            result.result.toString()
                                    )

                            _toolProgress.value =
                                    ToolExecutionProgress(
                                            state = ToolExecutionState.COMPLETED,
                                            tool = invocation.tool,
                                            progress = (index + 1).toFloat() / toolInvocations.size,
                                            message = "Tool executed: ${invocation.tool.name}",
                                            result = result
                                    )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error executing tool: ${invocation.tool.name}", e)

                        // Create a proper ToolResult for the tool execution
                        // error
                        val toolExecutionErrorResult =
                                ToolResult(
                                        toolName = invocation.tool.name,
                                        success = false,
                                        result = StringResultData(""),
                                        error = "Error executing tool: ${e.message}"
                                )

                        // Format using ConversationMarkupManager
                        val errorResult =
                                ConversationMarkupManager.formatToolResultForMessage(
                                        toolExecutionErrorResult
                                )

                        processedResponse =
                                replaceToolInvocation(processedResponse, invocation, errorResult)

                        _toolProgress.value =
                                ToolExecutionProgress(
                                        state = ToolExecutionState.FAILED,
                                        tool = invocation.tool,
                                        message = "Tool execution failed: ${e.message}",
                                        result = toolExecutionErrorResult
                                )
                    }
                }
            }

            _toolProgress.value =
                    ToolExecutionProgress(
                            state = ToolExecutionState.COMPLETED,
                            progress = 1.0f,
                            message = "All tools executed successfully"
                    )

            return processedResponse
        } catch (e: Exception) {
            Log.e(TAG, "Error processing tools", e)

            _toolProgress.value =
                    ToolExecutionProgress(
                            state = ToolExecutionState.FAILED,
                            message = "Failed to process tools: ${e.message}"
                    )

            return "$response\nError processing tools: ${e.message}"
        }
    }

    /** Replace a tool invocation in the response with its result */
    private fun replaceToolInvocation(
            response: String,
            invocation: ToolInvocation,
            result: String
    ): String {
        val before = response.substring(0, invocation.responseLocation.first)
        val after = response.substring(invocation.responseLocation.last + 1)

        return "$before\n**Tool Result [${invocation.tool.name}]:** \n$result\n$after"
    }

    /**
     * Unescapes XML special characters
     * @param input The XML escaped string
     * @return Unescaped string
     */
    private fun unescapeXml(input: String): String {
        return input.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
    }

    /** Reset the tool execution state */
    fun reset() {
        _toolProgress.value = ToolExecutionProgress(state = ToolExecutionState.IDLE)
    }

    /**
     * Get a registered tool executor by name
     * @param toolName The name of the tool
     * @return The tool executor or null if not found
     */
    fun getToolExecutor(toolName: String): ToolExecutor? {
        return availableTools[toolName]
    }

    /**
     * Extract tool invocations from the AI response Public method to be used by EnhancedAIService
     */
    fun extractToolInvocations(response: String): List<ToolInvocation> {
        // Add more comprehensive logging for debugging
        Log.d(TAG, "Extracting tool invocations from response of length: ${response.length}")

        // First try with regular tool patterns
        val invocations = extractToolInvocationsInternal(response)
        
        // If no regular invocations found, try with simplified format
        if (invocations.isEmpty()) {
            val simplifiedInvocations = extractSimplifiedToolInvocations(response)
            if (simplifiedInvocations.isNotEmpty()) {
                Log.d(TAG, "Found ${simplifiedInvocations.size} simplified tool invocations: ${simplifiedInvocations.map { it.tool.name }}")
                return simplifiedInvocations
            }
        }

        Log.d(
                TAG,
                "Found ${invocations.size} tool invocations: ${invocations.map { it.tool.name }}"
        )

        return invocations
    }

    /**
     * Internal implementation of tool invocation extraction Enhanced to better support streaming
     * responses and handle edge cases
     */
    private fun extractToolInvocationsInternal(response: String): List<ToolInvocation> {
        val invocations = mutableListOf<ToolInvocation>()

        // First try with the primary pattern
        val matcher = TOOL_PATTERN.matcher(response)
        var matchFound = false

        while (matcher.find()) {
            matchFound = true
            val rawText = matcher.group(0) ?: continue
            val toolName = matcher.group(1) ?: continue
            val toolContent =
                    matcher.group(3) ?: continue // Updated to use group 3 since group 2 is now
            // the optional
            // description
            val start = matcher.start()
            val end = matcher.end()

            // Create a unique ID for this tool invocation based on its position and
            // content
            val invocationId = "$toolName:$start:$end"

            Log.d(TAG, "Found tool invocation primary pattern: $toolName at position $start-$end")

            // Extract parameters
            val parameters = mutableListOf<ToolParameter>()
            val paramMatcher = TOOL_PARAM_PATTERN.matcher(toolContent)

            while (paramMatcher.find()) {
                val paramName = paramMatcher.group(1) ?: continue
                val paramValue = paramMatcher.group(2) ?: continue

                // Unescape XML entities in the parameter value
                val unescapedValue = unescapeXml(paramValue)

                parameters.add(ToolParameter(paramName, unescapedValue))
                Log.d(TAG, "  Parameter: $paramName = $unescapedValue (original: $paramValue)")
            }

            val tool = AITool(name = toolName, parameters = parameters)

            invocations.add(
                    ToolInvocation(tool = tool, rawText = rawText, responseLocation = start..end)
            )
        }

        // If no matches found with primary pattern, try alternative pattern
        if (!matchFound) {
            Log.d(TAG, "No matches with primary pattern, trying alternative pattern")
            val altMatcher = ALTERNATIVE_TOOL_PATTERN.matcher(response)

            while (altMatcher.find()) {
                val rawText = altMatcher.group(0) ?: continue
                val toolName = altMatcher.group(1) ?: continue
                val toolContent = altMatcher.group(3) ?: continue
                val start = altMatcher.start()
                val end = altMatcher.end()

                // Create a unique ID for this tool invocation based on its position
                // and content
                val invocationId = "$toolName:$start:$end"

                Log.d(
                        TAG,
                        "Found tool invocation with alternative pattern: $toolName at position $start-$end"
                )

                // Extract parameters (using more flexible parameter pattern)
                val parameters = mutableListOf<ToolParameter>()
                val paramContent = toolContent.trim()
                val paramLines = paramContent.split(Regex("\\s*<param\\s+"))

                for (line in paramLines) {
                    if (line.isBlank() || !line.contains("name=")) continue

                    val nameMatch = Regex("name\\s*=\\s*[\"']([^\"']+)[\"']").find(line)
                    val name = nameMatch?.groupValues?.get(1) ?: continue

                    val valueMatch = Regex(">([\\s\\S]*?)</param>").find(line)
                    val value = valueMatch?.groupValues?.get(1)?.trim() ?: continue

                    // Unescape XML entities in the parameter value
                    val unescapedValue = unescapeXml(value)

                    parameters.add(ToolParameter(name, unescapedValue))
                    Log.d(TAG, "  Parameter (alt): $name = $unescapedValue (original: $value)")
                }

                val tool = AITool(name = toolName, parameters = parameters)

                invocations.add(
                        ToolInvocation(
                                tool = tool,
                                rawText = rawText,
                                responseLocation = start..end
                        )
                )
            }
        }

        return invocations
    }

    /**
     * 提取简化格式的工具调用
     * 处理类似 <toolname="list_files">/sdcard/ 这样的格式
     */
    private fun extractSimplifiedToolInvocations(response: String): List<ToolInvocation> {
        val invocations = mutableListOf<ToolInvocation>()
        val matcher = SIMPLIFIED_TOOL_PATTERN.matcher(response)
        
        while (matcher.find()) {
            try {
                val rawText = matcher.group(0) ?: continue
                val toolName = matcher.group(1) ?: continue
                val toolValue = matcher.group(2)?.trim() ?: ""
                val start = matcher.start()
                val end = matcher.end()
                
                Log.d(TAG, "Found simplified tool invocation: $toolName with value: $toolValue at position $start-$end")
                
                // 构建参数列表
                val parameters = when {
                    // 如果工具名称包含list_files并且有路径值
                    toolName == "list_files" && toolValue.isNotEmpty() -> {
                        listOf(ToolParameter("path", toolValue))
                    }
                    // 如果工具名称包含find_files或search_files并且有查询值
                    toolName.contains("find_files") || toolName.contains("search_files") -> {
                        if (":" in toolValue) {
                            val parts = toolValue.split(":", limit = 2)
                            listOf(
                                ToolParameter("path", parts[0].trim()),
                                ToolParameter("pattern", parts[1].trim())
                            )
                        } else {
                            listOf(ToolParameter("query", toolValue))
                        }
                    }
                    // 如果工具名称包含read_file并且有文件路径
                    toolName.contains("read_file") -> {
                        listOf(ToolParameter("path", toolValue))
                    }
                    // 如果工具名称包含write_file并且有内容
                    toolName.contains("write_file") -> {
                        if (":" in toolValue) {
                            val parts = toolValue.split(":", limit = 2)
                            listOf(
                                ToolParameter("path", parts[0].trim()),
                                ToolParameter("content", parts[1].trim())
                            )
                        } else {
                            listOf(ToolParameter("path", toolValue))
                        }
                    }
                    // 如果工具名称包含calculate并且有表达式
                    toolName.contains("calculate") -> {
                        listOf(ToolParameter("expression", toolValue))
                    }
                    // 其他情况，使用通用参数
                    else -> {
                        listOf(ToolParameter("value", toolValue))
                    }
                }
                
                val tool = AITool(name = toolName, parameters = parameters)
                invocations.add(
                    ToolInvocation(
                        tool = tool,
                        rawText = rawText,
                        responseLocation = start..end
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing simplified tool invocation", e)
            }
        }
        
        return invocations
    }

    /** Executes a tool directly */
    fun executeTool(tool: AITool): ToolResult {
        val executor = availableTools[tool.name]

        if (executor == null) {
            return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Tool not found: ${tool.name}"
            )
        }

        // Validate parameters
        val validationResult = executor.validateParameters(tool)
        if (!validationResult.valid) {
            return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = validationResult.errorMessage
            )
        }

        // Execute the tool
        return executor.invoke(tool)
    }
}

/** Interface for tool executors */
fun interface ToolExecutor {
    operator fun invoke(tool: AITool): ToolResult

    /**
     * Validates the parameters of a tool before execution Default implementation always returns
     * valid
     */
    fun validateParameters(tool: AITool): ToolValidationResult {
        return ToolValidationResult(valid = true)
    }

    /** Get the tool's category, default to UI_AUTOMATION as highest security level */
    fun getCategory(): ToolCategory {
        return ToolCategory.UI_AUTOMATION
    }
}
