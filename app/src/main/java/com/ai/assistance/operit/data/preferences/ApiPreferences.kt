package com.ai.assistance.operit.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ParameterCategory
import com.ai.assistance.operit.data.model.ParameterValueType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Define the DataStore at the module level
private val Context.apiDataStore: DataStore<Preferences> by
        preferencesDataStore(name = "api_settings")

class ApiPreferences(private val context: Context) {

    // Define our preferences keys
    companion object {
        val API_KEY = stringPreferencesKey("api_key")
        val API_ENDPOINT = stringPreferencesKey("api_endpoint")
        val MODEL_NAME = stringPreferencesKey("model_name")
        val SHOW_THINKING = booleanPreferencesKey("show_thinking")
        val MEMORY_OPTIMIZATION = booleanPreferencesKey("memory_optimization")
        val PREFERENCE_ANALYSIS_INPUT_TOKENS = intPreferencesKey("preference_analysis_input_tokens")
        val PREFERENCE_ANALYSIS_OUTPUT_TOKENS =
                intPreferencesKey("preference_analysis_output_tokens")
        val SHOW_FPS_COUNTER = booleanPreferencesKey("show_fps_counter")
        val ENABLE_AI_PLANNING = booleanPreferencesKey("enable_ai_planning")
        val AUTO_GRANT_ACCESSIBILITY = booleanPreferencesKey("auto_grant_accessibility")

        // Custom Prompt Settings
        val CUSTOM_INTRO_PROMPT = stringPreferencesKey("custom_intro_prompt")
        val CUSTOM_TONE_PROMPT = stringPreferencesKey("custom_tone_prompt")

        // DeepSeek Model Parameters - Keys for values
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val TOP_P = floatPreferencesKey("top_p")
        val TOP_K = intPreferencesKey("top_k")
        val PRESENCE_PENALTY = floatPreferencesKey("presence_penalty")
        val FREQUENCY_PENALTY = floatPreferencesKey("frequency_penalty")
        val REPETITION_PENALTY = floatPreferencesKey("repetition_penalty")

        // New: Keys for parameter enabled state
        val MAX_TOKENS_ENABLED = booleanPreferencesKey("max_tokens_enabled")
        val TEMPERATURE_ENABLED = booleanPreferencesKey("temperature_enabled")
        val TOP_P_ENABLED = booleanPreferencesKey("top_p_enabled")
        val TOP_K_ENABLED = booleanPreferencesKey("top_k_enabled")
        val PRESENCE_PENALTY_ENABLED = booleanPreferencesKey("presence_penalty_enabled")
        val FREQUENCY_PENALTY_ENABLED = booleanPreferencesKey("frequency_penalty_enabled")
        val REPETITION_PENALTY_ENABLED = booleanPreferencesKey("repetition_penalty_enabled")

        // Default values
        const val DEFAULT_API_ENDPOINT = "https://api.deepseek.com/v1/chat/completions"
        const val DEFAULT_MODEL_NAME = "deepseek-chat"
        const val DEFAULT_API_KEY = "sk-e565390c164c4cfa8820624ef47d68bf"
        const val DEFAULT_SHOW_THINKING = true
        const val DEFAULT_MEMORY_OPTIMIZATION = true
        const val DEFAULT_SHOW_FPS_COUNTER = false
        const val DEFAULT_ENABLE_AI_PLANNING = false
        const val DEFAULT_AUTO_GRANT_ACCESSIBILITY = false

        const val DEFAULT_GEMINI_API_ENDPOINT = "https://generativelanguage.googleapis.com/v1/models"
        const val DEFAULT_GEMINI_MODEL_NAME = "gemini-1.5-pro"

        // Default values for custom prompts
        const val DEFAULT_INTRO_PROMPT = "你是Operit，一个全能AI助手，旨在解决用户提出的任何任务。你有各种工具可以调用，以高效完成复杂的请求。"
        const val DEFAULT_TONE_PROMPT = "保持有帮助的语气，并清楚地传达限制。使用问题库根据用户的风格、偏好和过去的信息个性化响应。"

        // Default values for DeepSeek model parameters
        const val DEFAULT_MAX_TOKENS = 4096
        const val DEFAULT_TEMPERATURE = 1.0f
        const val DEFAULT_TOP_P = 1.0f
        const val DEFAULT_TOP_K = 0
        const val DEFAULT_PRESENCE_PENALTY = 0.0f
        const val DEFAULT_FREQUENCY_PENALTY = 0.0f
        const val DEFAULT_REPETITION_PENALTY = 1.0f

        // Default enabled state for parameters (all disabled by default)
        const val DEFAULT_PARAM_ENABLED = false

        // 自定义参数存储键
        val CUSTOM_PARAMETERS = stringPreferencesKey("custom_parameters")

        // 默认空的自定义参数列表
        const val DEFAULT_CUSTOM_PARAMETERS = "[]"

        // Add provider type preference key
        val PROVIDER_TYPE = stringPreferencesKey("provider_type")
        
        // Default provider is DeepSeek
        const val DEFAULT_PROVIDER_TYPE = "DEEPSEEK"
    }

    // Get API Key as Flow
    val apiKeyFlow: Flow<String> =
            context.apiDataStore.data.map { preferences -> preferences[API_KEY] ?: DEFAULT_API_KEY }

    // Get API Endpoint as Flow
    val apiEndpointFlow: Flow<String> =
            context.apiDataStore.data.map { preferences ->
                preferences[API_ENDPOINT] ?: DEFAULT_API_ENDPOINT
            }

    // Get Model Name as Flow
    val modelNameFlow: Flow<String> =
            context.apiDataStore.data.map { preferences ->
                preferences[MODEL_NAME] ?: DEFAULT_MODEL_NAME
            }

    // Get Show Thinking as Flow
    val showThinkingFlow: Flow<Boolean> =
            context.apiDataStore.data.map { preferences ->
                preferences[SHOW_THINKING] ?: DEFAULT_SHOW_THINKING
            }

    // DeepSeek Model Parameter Flows - Values
    val maxTokensFlow: Flow<Int> =
            context.apiDataStore.data.map { preferences ->
                preferences[MAX_TOKENS] ?: DEFAULT_MAX_TOKENS
            }

    val temperatureFlow: Flow<Float> =
            context.apiDataStore.data.map { preferences ->
                preferences[TEMPERATURE] ?: DEFAULT_TEMPERATURE
            }

    val topPFlow: Flow<Float> =
            context.apiDataStore.data.map { preferences -> preferences[TOP_P] ?: DEFAULT_TOP_P }

    val topKFlow: Flow<Int> =
            context.apiDataStore.data.map { preferences -> preferences[TOP_K] ?: DEFAULT_TOP_K }

    val presencePenaltyFlow: Flow<Float> =
            context.apiDataStore.data.map { preferences ->
                preferences[PRESENCE_PENALTY] ?: DEFAULT_PRESENCE_PENALTY
            }

    val frequencyPenaltyFlow: Flow<Float> =
            context.apiDataStore.data.map { preferences ->
                preferences[FREQUENCY_PENALTY] ?: DEFAULT_FREQUENCY_PENALTY
            }

    val repetitionPenaltyFlow: Flow<Float> =
            context.apiDataStore.data.map { preferences ->
                preferences[REPETITION_PENALTY] ?: DEFAULT_REPETITION_PENALTY
            }

    // DeepSeek Model Parameter Flows - Enabled state
    val maxTokensEnabledFlow: Flow<Boolean> =
            context.apiDataStore.data.map { preferences ->
                preferences[MAX_TOKENS_ENABLED] ?: DEFAULT_PARAM_ENABLED
            }

    val temperatureEnabledFlow: Flow<Boolean> =
            context.apiDataStore.data.map { preferences ->
                preferences[TEMPERATURE_ENABLED] ?: DEFAULT_PARAM_ENABLED
            }

    val topPEnabledFlow: Flow<Boolean> =
            context.apiDataStore.data.map { preferences ->
                preferences[TOP_P_ENABLED] ?: DEFAULT_PARAM_ENABLED
            }

    val topKEnabledFlow: Flow<Boolean> =
            context.apiDataStore.data.map { preferences ->
                preferences[TOP_K_ENABLED] ?: DEFAULT_PARAM_ENABLED
            }

    val presencePenaltyEnabledFlow: Flow<Boolean> =
            context.apiDataStore.data.map { preferences ->
                preferences[PRESENCE_PENALTY_ENABLED] ?: DEFAULT_PARAM_ENABLED
            }

    val frequencyPenaltyEnabledFlow: Flow<Boolean> =
            context.apiDataStore.data.map { preferences ->
                preferences[FREQUENCY_PENALTY_ENABLED] ?: DEFAULT_PARAM_ENABLED
            }

    val repetitionPenaltyEnabledFlow: Flow<Boolean> =
            context.apiDataStore.data.map { preferences ->
                preferences[REPETITION_PENALTY_ENABLED] ?: DEFAULT_PARAM_ENABLED
            }

    // Get Memory Optimization as Flow
    val memoryOptimizationFlow: Flow<Boolean> =
            context.apiDataStore.data.map { preferences ->
                preferences[MEMORY_OPTIMIZATION] ?: DEFAULT_MEMORY_OPTIMIZATION
            }

    // 获取偏好分析输入token计数
    val preferenceAnalysisInputTokensFlow: Flow<Int> =
            context.apiDataStore.data.map { preferences ->
                preferences[PREFERENCE_ANALYSIS_INPUT_TOKENS] ?: 0
            }

    // 获取偏好分析输出token计数
    val preferenceAnalysisOutputTokensFlow: Flow<Int> =
            context.apiDataStore.data.map { preferences ->
                preferences[PREFERENCE_ANALYSIS_OUTPUT_TOKENS] ?: 0
            }

    // Get FPS Counter Display setting as Flow
    val showFpsCounterFlow: Flow<Boolean> =
            context.apiDataStore.data.map { preferences ->
                preferences[SHOW_FPS_COUNTER] ?: DEFAULT_SHOW_FPS_COUNTER
            }

    // Get AI Planning setting as Flow
    val enableAiPlanningFlow: Flow<Boolean> =
            context.apiDataStore.data.map { preferences ->
                preferences[ENABLE_AI_PLANNING] ?: DEFAULT_ENABLE_AI_PLANNING
            }

    // Get Auto Grant Accessibility setting as Flow
    val autoGrantAccessibilityFlow: Flow<Boolean> =
            context.apiDataStore.data.map { preferences ->
                preferences[AUTO_GRANT_ACCESSIBILITY] ?: DEFAULT_AUTO_GRANT_ACCESSIBILITY
            }

    // Custom Prompt Flows
    val customIntroPromptFlow: Flow<String> =
            context.apiDataStore.data.map { preferences ->
                preferences[CUSTOM_INTRO_PROMPT] ?: DEFAULT_INTRO_PROMPT
            }

    val customTonePromptFlow: Flow<String> =
            context.apiDataStore.data.map { preferences ->
                preferences[CUSTOM_TONE_PROMPT] ?: DEFAULT_TONE_PROMPT
            }

    // Add provider type flow
    val providerTypeFlow: Flow<String> = 
        context.apiDataStore.data.map { preferences ->
            preferences[PROVIDER_TYPE] ?: DEFAULT_PROVIDER_TYPE
        }

    // Save API Key
    suspend fun saveApiKey(apiKey: String) {
        context.apiDataStore.edit { preferences -> preferences[API_KEY] = apiKey }
    }

    // Save API Endpoint
    suspend fun saveApiEndpoint(endpoint: String) {
        context.apiDataStore.edit { preferences -> preferences[API_ENDPOINT] = endpoint }
    }

    // Save Model Name
    suspend fun saveModelName(modelName: String) {
        context.apiDataStore.edit { preferences -> preferences[MODEL_NAME] = modelName }
    }

    // Save Show Thinking setting
    suspend fun saveShowThinking(showThinking: Boolean) {
        context.apiDataStore.edit { preferences -> preferences[SHOW_THINKING] = showThinking }
    }

    // Save DeepSeek Model Parameters - Value and enabled state
    suspend fun saveMaxTokens(maxTokens: Int, isEnabled: Boolean) {
        context.apiDataStore.edit { preferences ->
            preferences[MAX_TOKENS] = maxTokens
            preferences[MAX_TOKENS_ENABLED] = isEnabled
        }
    }

    suspend fun saveTemperature(temperature: Float, isEnabled: Boolean) {
        context.apiDataStore.edit { preferences ->
            preferences[TEMPERATURE] = temperature
            preferences[TEMPERATURE_ENABLED] = isEnabled
        }
    }

    suspend fun saveTopP(topP: Float, isEnabled: Boolean) {
        context.apiDataStore.edit { preferences ->
            preferences[TOP_P] = topP
            preferences[TOP_P_ENABLED] = isEnabled
        }
    }

    suspend fun saveTopK(topK: Int, isEnabled: Boolean) {
        context.apiDataStore.edit { preferences ->
            preferences[TOP_K] = topK
            preferences[TOP_K_ENABLED] = isEnabled
        }
    }

    suspend fun savePresencePenalty(presencePenalty: Float, isEnabled: Boolean) {
        context.apiDataStore.edit { preferences ->
            preferences[PRESENCE_PENALTY] = presencePenalty
            preferences[PRESENCE_PENALTY_ENABLED] = isEnabled
        }
    }

    suspend fun saveFrequencyPenalty(frequencyPenalty: Float, isEnabled: Boolean) {
        context.apiDataStore.edit { preferences ->
            preferences[FREQUENCY_PENALTY] = frequencyPenalty
            preferences[FREQUENCY_PENALTY_ENABLED] = isEnabled
        }
    }

    suspend fun saveRepetitionPenalty(repetitionPenalty: Float, isEnabled: Boolean) {
        context.apiDataStore.edit { preferences ->
            preferences[REPETITION_PENALTY] = repetitionPenalty
            preferences[REPETITION_PENALTY_ENABLED] = isEnabled
        }
    }

    // Save Memory Optimization setting
    suspend fun saveMemoryOptimization(memoryOptimization: Boolean) {
        context.apiDataStore.edit { preferences ->
            preferences[MEMORY_OPTIMIZATION] = memoryOptimization
        }
    }

    // Save FPS Counter Display setting
    suspend fun saveShowFpsCounter(showFpsCounter: Boolean) {
        context.apiDataStore.edit { preferences -> preferences[SHOW_FPS_COUNTER] = showFpsCounter }
    }

    // Save AI Planning setting
    suspend fun saveEnableAiPlanning(enablePlanning: Boolean) {
        context.apiDataStore.edit { preferences ->
            preferences[ENABLE_AI_PLANNING] = enablePlanning
        }
    }

    // Save Auto Grant Accessibility setting
    suspend fun saveAutoGrantAccessibility(autoGrantAccessibility: Boolean) {
        context.apiDataStore.edit { preferences ->
            preferences[AUTO_GRANT_ACCESSIBILITY] = autoGrantAccessibility
        }
    }

    // Save all settings at once
    suspend fun saveApiSettings(apiKey: String, endpoint: String, modelName: String) {
        context.apiDataStore.edit { preferences ->
            preferences[API_KEY] = apiKey
            preferences[API_ENDPOINT] = endpoint
            preferences[MODEL_NAME] = modelName
        }
    }

    // 添加保存显示和行为设置的方法，不会影响模型参数
    suspend fun saveDisplaySettings(
            showThinking: Boolean,
            memoryOptimization: Boolean,
            showFpsCounter: Boolean,
            autoGrantAccessibility: Boolean
    ) {
        context.apiDataStore.edit { preferences ->
            preferences[SHOW_THINKING] = showThinking
            preferences[MEMORY_OPTIMIZATION] = memoryOptimization
            preferences[SHOW_FPS_COUNTER] = showFpsCounter
            preferences[AUTO_GRANT_ACCESSIBILITY] = autoGrantAccessibility
        }
    }

    // Update the saveAllSettings method to include all model parameters with enabled state
    suspend fun saveAllSettings(
            apiKey: String,
            endpoint: String,
            modelName: String,
            showThinking: Boolean,
            memoryOptimization: Boolean,
            showFpsCounter: Boolean,
            enableAiPlanning: Boolean,
            autoGrantAccessibility: Boolean = DEFAULT_AUTO_GRANT_ACCESSIBILITY,
            modelParameters: List<ModelParameter<*>> = emptyList()
    ) {
        context.apiDataStore.edit { preferences ->
            preferences[API_KEY] = apiKey
            preferences[API_ENDPOINT] = endpoint
            preferences[MODEL_NAME] = modelName
            preferences[SHOW_THINKING] = showThinking
            preferences[MEMORY_OPTIMIZATION] = memoryOptimization
            preferences[SHOW_FPS_COUNTER] = showFpsCounter
            preferences[ENABLE_AI_PLANNING] = enableAiPlanning
            preferences[AUTO_GRANT_ACCESSIBILITY] = autoGrantAccessibility

            // Save all model parameters
            for (param in modelParameters) {
                when (param.id) {
                    "max_tokens" -> {
                        preferences[MAX_TOKENS] = (param.currentValue as Int)
                        preferences[MAX_TOKENS_ENABLED] = param.isEnabled
                    }
                    "temperature" -> {
                        preferences[TEMPERATURE] = (param.currentValue as Float)
                        preferences[TEMPERATURE_ENABLED] = param.isEnabled
                    }
                    "top_p" -> {
                        preferences[TOP_P] = (param.currentValue as Float)
                        preferences[TOP_P_ENABLED] = param.isEnabled
                    }
                    "top_k" -> {
                        preferences[TOP_K] = (param.currentValue as Int)
                        preferences[TOP_K_ENABLED] = param.isEnabled
                    }
                    "presence_penalty" -> {
                        preferences[PRESENCE_PENALTY] = (param.currentValue as Float)
                        preferences[PRESENCE_PENALTY_ENABLED] = param.isEnabled
                    }
                    "frequency_penalty" -> {
                        preferences[FREQUENCY_PENALTY] = (param.currentValue as Float)
                        preferences[FREQUENCY_PENALTY_ENABLED] = param.isEnabled
                    }
                    "repetition_penalty" -> {
                        preferences[REPETITION_PENALTY] = (param.currentValue as Float)
                        preferences[REPETITION_PENALTY_ENABLED] = param.isEnabled
                    }
                // Add more parameters as needed
                }
            }
        }
    }

    // Save model parameters only - now accepts a list of parameters
    suspend fun saveModelParameters(parameters: List<ModelParameter<*>>) {
        // 分离标准参数和自定义参数
        val standardParams = parameters.filter { !it.isCustom }
        val customParams = parameters.filter { it.isCustom }
        
        context.apiDataStore.edit { preferences ->
            // 保存标准参数
            for (param in standardParams) {
                when (param.id) {
                    "max_tokens" -> {
                        preferences[MAX_TOKENS] = (param.currentValue as Int)
                        preferences[MAX_TOKENS_ENABLED] = param.isEnabled
                    }
                    "temperature" -> {
                        preferences[TEMPERATURE] = (param.currentValue as Float)
                        preferences[TEMPERATURE_ENABLED] = param.isEnabled
                    }
                    "top_p" -> {
                        preferences[TOP_P] = (param.currentValue as Float)
                        preferences[TOP_P_ENABLED] = param.isEnabled
                    }
                    "top_k" -> {
                        preferences[TOP_K] = (param.currentValue as Int)
                        preferences[TOP_K_ENABLED] = param.isEnabled
                    }
                    "presence_penalty" -> {
                        preferences[PRESENCE_PENALTY] = (param.currentValue as Float)
                        preferences[PRESENCE_PENALTY_ENABLED] = param.isEnabled
                    }
                    "frequency_penalty" -> {
                        preferences[FREQUENCY_PENALTY] = (param.currentValue as Float)
                        preferences[FREQUENCY_PENALTY_ENABLED] = param.isEnabled
                    }
                    "repetition_penalty" -> {
                        preferences[REPETITION_PENALTY] = (param.currentValue as Float)
                        preferences[REPETITION_PENALTY_ENABLED] = param.isEnabled
                    }
                }
            }
            
            // 保存自定义参数为JSON字符串
            if (customParams.isNotEmpty()) {
                val customParamsList = customParams.map { param ->
                    when (param.valueType) {
                        ParameterValueType.INT -> {
                            CustomParameterData(
                                id = param.id,
                                name = param.name,
                                apiName = param.apiName,
                                description = param.description,
                                defaultValue = (param.defaultValue as Int).toString(),
                                currentValue = (param.currentValue as Int).toString(),
                                isEnabled = param.isEnabled,
                                valueType = param.valueType.name,
                                minValue = (param.minValue as? Int)?.toString(),
                                maxValue = (param.maxValue as? Int)?.toString(),
                                category = param.category.name
                            )
                        }
                        ParameterValueType.FLOAT -> {
                            CustomParameterData(
                                id = param.id,
                                name = param.name,
                                apiName = param.apiName,
                                description = param.description,
                                defaultValue = (param.defaultValue as Float).toString(),
                                currentValue = (param.currentValue as Float).toString(),
                                isEnabled = param.isEnabled,
                                valueType = param.valueType.name,
                                minValue = (param.minValue as? Float)?.toString(),
                                maxValue = (param.maxValue as? Float)?.toString(),
                                category = param.category.name
                            )
                        }
                        ParameterValueType.STRING -> {
                            CustomParameterData(
                                id = param.id,
                                name = param.name,
                                apiName = param.apiName,
                                description = param.description,
                                defaultValue = param.defaultValue as String,
                                currentValue = param.currentValue as String,
                                isEnabled = param.isEnabled,
                                valueType = param.valueType.name,
                                category = param.category.name
                            )
                        }
                        ParameterValueType.BOOLEAN -> {
                            CustomParameterData(
                                id = param.id,
                                name = param.name,
                                apiName = param.apiName,
                                description = param.description,
                                defaultValue = (param.defaultValue as Boolean).toString(),
                                currentValue = (param.currentValue as Boolean).toString(),
                                isEnabled = param.isEnabled,
                                valueType = param.valueType.name,
                                category = param.category.name
                            )
                        }
                    }
                }
                preferences[CUSTOM_PARAMETERS] = Json.encodeToString(customParamsList)
            } else {
                // 如果没有自定义参数，保存空列表
                preferences[CUSTOM_PARAMETERS] = DEFAULT_CUSTOM_PARAMETERS
            }
        }
    }

    // Get all model parameters as a list
    suspend fun getAllModelParameters(): List<ModelParameter<*>> {
        val preferences = context.apiDataStore.data.first()
        val parameters = mutableListOf<ModelParameter<*>>()

        // 添加标准参数
        // Max Tokens
        parameters.add(
                ModelParameter(
                        id = "max_tokens",
                        name = "最大生成Token数",
                        apiName = "max_tokens",
                        description = "控制AI每次最多生成的Token数量",
                        defaultValue = DEFAULT_MAX_TOKENS,
                        currentValue = preferences[MAX_TOKENS] ?: DEFAULT_MAX_TOKENS,
                        isEnabled = preferences[MAX_TOKENS_ENABLED] ?: DEFAULT_PARAM_ENABLED,
                        valueType = ParameterValueType.INT,
                        minValue = 1,
                        maxValue = 16000,
                        icon = "text_format",
                        category = ParameterCategory.GENERATION
                )
        )

        // Temperature
        parameters.add(
                ModelParameter(
                        id = "temperature",
                        name = "温度",
                        apiName = "temperature",
                        description = "控制输出的随机性。较低的值更确定性，较高的值更随机",
                        defaultValue = DEFAULT_TEMPERATURE,
                        currentValue = preferences[TEMPERATURE] ?: DEFAULT_TEMPERATURE,
                        isEnabled = preferences[TEMPERATURE_ENABLED] ?: DEFAULT_PARAM_ENABLED,
                        valueType = ParameterValueType.FLOAT,
                        minValue = 0.0f,
                        maxValue = 2.0f,
                        icon = "thermostat",
                        category = ParameterCategory.CREATIVITY
                )
        )

        // Top P
        parameters.add(
                ModelParameter(
                        id = "top_p",
                        name = "Top-P 采样",
                        apiName = "top_p",
                        description = "作为温度的替代方案，模型仅考虑概率最高的Top-P比例的token",
                        defaultValue = DEFAULT_TOP_P,
                        currentValue = preferences[TOP_P] ?: DEFAULT_TOP_P,
                        isEnabled = preferences[TOP_P_ENABLED] ?: DEFAULT_PARAM_ENABLED,
                        valueType = ParameterValueType.FLOAT,
                        minValue = 0.0f,
                        maxValue = 1.0f,
                        icon = "filter_alt",
                        category = ParameterCategory.CREATIVITY
                )
        )

        // Top K
        parameters.add(
                ModelParameter(
                        id = "top_k",
                        name = "Top-K 采样",
                        apiName = "top_k",
                        description = "模型仅考虑概率最高的K个token。0表示禁用",
                        defaultValue = DEFAULT_TOP_K,
                        currentValue = preferences[TOP_K] ?: DEFAULT_TOP_K,
                        isEnabled = preferences[TOP_K_ENABLED] ?: DEFAULT_PARAM_ENABLED,
                        valueType = ParameterValueType.INT,
                        minValue = 0,
                        maxValue = 100,
                        icon = "filter_list",
                        category = ParameterCategory.CREATIVITY
                )
        )

        // Presence Penalty
        parameters.add(
                ModelParameter(
                        id = "presence_penalty",
                        name = "存在惩罚",
                        apiName = "presence_penalty",
                        description = "增强模型谈论新主题的倾向。值越高，惩罚越大",
                        defaultValue = DEFAULT_PRESENCE_PENALTY,
                        currentValue = preferences[PRESENCE_PENALTY] ?: DEFAULT_PRESENCE_PENALTY,
                        isEnabled = preferences[PRESENCE_PENALTY_ENABLED] ?: DEFAULT_PARAM_ENABLED,
                        valueType = ParameterValueType.FLOAT,
                        minValue = -2.0f,
                        maxValue = 2.0f,
                        icon = "repeat",
                        category = ParameterCategory.REPETITION
                )
        )

        // Frequency Penalty
        parameters.add(
                ModelParameter(
                        id = "frequency_penalty",
                        name = "频率惩罚",
                        apiName = "frequency_penalty",
                        description = "减少模型重复同一词语的可能性。值越高，惩罚越大",
                        defaultValue = DEFAULT_FREQUENCY_PENALTY,
                        currentValue = preferences[FREQUENCY_PENALTY] ?: DEFAULT_FREQUENCY_PENALTY,
                        isEnabled = preferences[FREQUENCY_PENALTY_ENABLED] ?: DEFAULT_PARAM_ENABLED,
                        valueType = ParameterValueType.FLOAT,
                        minValue = -2.0f,
                        maxValue = 2.0f,
                        icon = "stacked_bar_chart",
                        category = ParameterCategory.REPETITION
                )
        )

        // Repetition Penalty
        parameters.add(
                ModelParameter(
                        id = "repetition_penalty",
                        name = "重复惩罚",
                        apiName = "repetition_penalty",
                        description = "进一步减少重复。1.0表示不惩罚，大于1.0会降低重复可能性",
                        defaultValue = DEFAULT_REPETITION_PENALTY,
                        currentValue = preferences[REPETITION_PENALTY]
                                        ?: DEFAULT_REPETITION_PENALTY,
                        isEnabled = preferences[REPETITION_PENALTY_ENABLED]
                                        ?: DEFAULT_PARAM_ENABLED,
                        valueType = ParameterValueType.FLOAT,
                        minValue = 0.0f,
                        maxValue = 2.0f,
                        icon = "filter_none",
                        category = ParameterCategory.REPETITION
                )
        )

        // 加载自定义参数
        val customParamsJson = preferences[CUSTOM_PARAMETERS] ?: DEFAULT_CUSTOM_PARAMETERS
        if (customParamsJson != DEFAULT_CUSTOM_PARAMETERS) {
            try {
                val customParamsList = Json.decodeFromString<List<CustomParameterData>>(customParamsJson)
                for (customParam in customParamsList) {
                    val param = when (ParameterValueType.valueOf(customParam.valueType)) {
                        ParameterValueType.INT -> {
                            ModelParameter(
                                id = customParam.id,
                                name = customParam.name,
                                apiName = customParam.apiName,
                                description = customParam.description,
                                defaultValue = customParam.defaultValue.toInt(),
                                currentValue = customParam.currentValue.toInt(),
                                isEnabled = customParam.isEnabled,
                                valueType = ParameterValueType.INT,
                                minValue = customParam.minValue?.toInt(),
                                maxValue = customParam.maxValue?.toInt(),
                                category = ParameterCategory.valueOf(customParam.category),
                                isCustom = true
                            )
                        }
                        ParameterValueType.FLOAT -> {
                            ModelParameter(
                                id = customParam.id,
                                name = customParam.name,
                                apiName = customParam.apiName,
                                description = customParam.description,
                                defaultValue = customParam.defaultValue.toFloat(),
                                currentValue = customParam.currentValue.toFloat(),
                                isEnabled = customParam.isEnabled,
                                valueType = ParameterValueType.FLOAT,
                                minValue = customParam.minValue?.toFloat(),
                                maxValue = customParam.maxValue?.toFloat(),
                                category = ParameterCategory.valueOf(customParam.category),
                                isCustom = true
                            )
                        }
                        ParameterValueType.STRING -> {
                            ModelParameter(
                                id = customParam.id,
                                name = customParam.name,
                                apiName = customParam.apiName,
                                description = customParam.description,
                                defaultValue = customParam.defaultValue,
                                currentValue = customParam.currentValue,
                                isEnabled = customParam.isEnabled,
                                valueType = ParameterValueType.STRING,
                                category = ParameterCategory.valueOf(customParam.category),
                                isCustom = true
                            )
                        }
                        ParameterValueType.BOOLEAN -> {
                            ModelParameter(
                                id = customParam.id,
                                name = customParam.name,
                                apiName = customParam.apiName,
                                description = customParam.description,
                                defaultValue = customParam.defaultValue.toBoolean(),
                                currentValue = customParam.currentValue.toBoolean(),
                                isEnabled = customParam.isEnabled,
                                valueType = ParameterValueType.BOOLEAN,
                                category = ParameterCategory.valueOf(customParam.category),
                                isCustom = true
                            )
                        }
                    }
                    parameters.add(param)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // 如果解析失败，忽略自定义参数
            }
        }

        return parameters
    }

    // 更新偏好分析token计数
    suspend fun updatePreferenceAnalysisTokens(inputTokens: Int, outputTokens: Int) {
        context.apiDataStore.edit { preferences ->
            // 累加而不是覆盖
            val currentInputTokens = preferences[PREFERENCE_ANALYSIS_INPUT_TOKENS] ?: 0
            val currentOutputTokens = preferences[PREFERENCE_ANALYSIS_OUTPUT_TOKENS] ?: 0

            preferences[PREFERENCE_ANALYSIS_INPUT_TOKENS] = currentInputTokens + inputTokens
            preferences[PREFERENCE_ANALYSIS_OUTPUT_TOKENS] = currentOutputTokens + outputTokens
        }
    }

    // Save custom prompts
    suspend fun saveCustomPrompts(introPrompt: String, tonePrompt: String) {
        context.apiDataStore.edit { preferences ->
            preferences[CUSTOM_INTRO_PROMPT] = introPrompt
            preferences[CUSTOM_TONE_PROMPT] = tonePrompt
        }
    }

    // Reset custom prompts to default values
    suspend fun resetCustomPrompts() {
        context.apiDataStore.edit { preferences ->
            preferences[CUSTOM_INTRO_PROMPT] = DEFAULT_INTRO_PROMPT
            preferences[CUSTOM_TONE_PROMPT] = DEFAULT_TONE_PROMPT
        }
    }

    // 重置偏好分析token计数
    suspend fun resetPreferenceAnalysisTokens() {
        context.apiDataStore.edit { preferences ->
            preferences[PREFERENCE_ANALYSIS_INPUT_TOKENS] = 0
            preferences[PREFERENCE_ANALYSIS_OUTPUT_TOKENS] = 0
        }
    }

    // Add a method to save provider type
    suspend fun saveProviderType(providerType: String) {
        context.apiDataStore.edit { preferences -> 
            preferences[PROVIDER_TYPE] = providerType 
        }
    }
}

/**
 * 用于序列化存储自定义参数的数据类
 */
@Serializable
data class CustomParameterData(
    val id: String,
    val name: String,
    val apiName: String,
    val description: String,
    val defaultValue: String,
    val currentValue: String,
    val isEnabled: Boolean = false,
    val valueType: String,
    val minValue: String? = null,
    val maxValue: String? = null,
    val category: String
)
