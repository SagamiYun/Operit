package com.ai.assistance.operit.ui.features.chat.viewmodel

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.api.EnhancedAIService
import com.ai.assistance.operit.api.LLMProviderFactory
import com.ai.assistance.operit.data.preferences.ApiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 委托类，负责管理API配置相关功能 */
class ApiConfigDelegate(
        private val context: Context,
        private val viewModelScope: CoroutineScope,
        private val onConfigChanged: (EnhancedAIService) -> Unit
) {
    companion object {
        private const val TAG = "ApiConfigDelegate"
        private const val SERVICE_INIT_DEBOUNCE_MS = 300L // 服务初始化防抖时间
    }

    // Preferences
    private val apiPreferences = ApiPreferences(context)

    // State flows
    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _apiProviderType = MutableStateFlow(LLMProviderFactory.ProviderType.DEEPSEEK)
    val apiProviderType: StateFlow<LLMProviderFactory.ProviderType> =_apiProviderType.asStateFlow()

    private val _apiEndpoint = MutableStateFlow(ApiPreferences.DEFAULT_API_ENDPOINT)
    val apiEndpoint: StateFlow<String> = _apiEndpoint.asStateFlow()

    private val _modelName = MutableStateFlow(ApiPreferences.DEFAULT_MODEL_NAME)
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    private val _isConfigured = MutableStateFlow(false)
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    private val _showThinking = MutableStateFlow(ApiPreferences.DEFAULT_SHOW_THINKING)
    val showThinking: StateFlow<Boolean> = _showThinking.asStateFlow()

    private val _enableAiPlanning = MutableStateFlow(ApiPreferences.DEFAULT_ENABLE_AI_PLANNING)
    val enableAiPlanning: StateFlow<Boolean> = _enableAiPlanning.asStateFlow()

    private val _memoryOptimization = MutableStateFlow(ApiPreferences.DEFAULT_MEMORY_OPTIMIZATION)
    val memoryOptimization: StateFlow<Boolean> = _memoryOptimization.asStateFlow()

    // 防抖相关变量
    private var serviceInitJob: Job? = null
    private var configUpdateCount = 0

    init {
        // Load settings from preferences
        initializeSettingsCollection()
    }

    private fun initializeSettingsCollection() {
        // Load API key
        viewModelScope.launch {
            apiPreferences.apiKeyFlow.collect { key ->
                _apiKey.value = key
                checkAndInitializeService()
            }
        }

        // Load API Provider
        viewModelScope.launch {
            apiPreferences.providerTypeFlow.collect { key ->
                _apiProviderType.value = LLMProviderFactory.ProviderType.valueOf(key)
                checkAndInitializeService()
            }
        }

        // Load API endpoint
        viewModelScope.launch {
            apiPreferences.apiEndpointFlow.collect { endpoint ->
                _apiEndpoint.value = endpoint
                checkAndInitializeService()
            }
        }

        // Load model name
        viewModelScope.launch {
            apiPreferences.modelNameFlow.collect { model ->
                _modelName.value = model
                checkAndInitializeService()
            }
        }

        // Collect show thinking preference
        viewModelScope.launch {
            apiPreferences.showThinkingFlow.collect { showThinkingValue ->
                _showThinking.value = showThinkingValue
            }
        }

        // Collect AI planning setting
        viewModelScope.launch {
            apiPreferences.enableAiPlanningFlow.collect { enableAiPlanningValue ->
                _enableAiPlanning.value = enableAiPlanningValue
            }
        }

        // Collect memory optimization preference
        viewModelScope.launch {
            apiPreferences.memoryOptimizationFlow.collect { enabled ->
                _memoryOptimization.value = enabled
            }
        }
    }

    /** 检查API配置是否完整，如果是，则初始化AI服务 */
    private fun checkAndInitializeService() {
        val key = _apiKey.value
        val endpoint = _apiEndpoint.value
        val model = _modelName.value
        val providerType = _apiProviderType.value

        if (key.isNotBlank() &&
            endpoint.isNotBlank() &&
            model.isNotBlank() &&
            providerType.toString().isNotBlank())
        {
            // 取消任何之前的初始化任务
            serviceInitJob?.cancel()
            configUpdateCount++
            val currentUpdateCount = configUpdateCount

            // 添加防抖机制，延迟初始化服务
            serviceInitJob = viewModelScope.launch {
                delay(SERVICE_INIT_DEBOUNCE_MS)
                // 检查是否是最新的更新请求
                if (currentUpdateCount == configUpdateCount) {
                    Log.d(TAG, "初始化EnhancedAIService (apiKey=${key.take(5)}..., endpoint=$endpoint, model=$model)")
                    // 创建新的AI服务实例
                    val enhancedAiService = EnhancedAIService(endpoint, key, model,
                        providerType, context)

                    // 通知ViewModel配置已更改
                    onConfigChanged(enhancedAiService)

                    // 更新已配置状态
                    _isConfigured.value = true
                } else {
                    Log.d(TAG, "跳过过期的服务初始化请求")
                }
            }
        }
    }

    /** 更新API密钥 */
    fun updateApiKey(key: String) {
        _apiKey.value = key
    }

    /** 更新API端点 */
    fun updateApiEndpoint(endpoint: String) {
        _apiEndpoint.value = endpoint
    }

    /** 更新模型名称 */
    fun updateModelName(model: String) {
        _modelName.value = model
    }

    /** 保存API设置 */
    fun saveApiSettings() {
        viewModelScope.launch {
            apiPreferences.saveApiSettings(_apiKey.value, _apiEndpoint.value, _modelName.value)

            // 直接调用初始化服务 - 无需防抖机制，因为这是用户主动操作
            Log.d(TAG, "保存API设置并初始化服务")
            val enhancedAiService =
                    EnhancedAIService(_apiEndpoint.value, _apiKey.value, _modelName.value,
                        _apiProviderType.value, context)

            // 通知ViewModel配置已更改
            onConfigChanged(enhancedAiService)

            // 更新已配置状态
            _isConfigured.value = true
        }
    }

    /**
     * 使用默认配置继续，不保存用户输入
     * @return 是否成功使用默认配置
     */
    fun useDefaultConfig(): Boolean {
        // 检查是否有可用的默认配置
        if (_apiEndpoint.value.isNotBlank() &&
            _apiKey.value.isNotBlank() &&
            _modelName.value.isNotBlank() &&
            _apiProviderType.value.toString().isNotBlank())
        {
            // 直接标记为已配置
            _isConfigured.value = true

            // 初始化AI服务
            Log.d(TAG, "使用默认配置初始化服务")
            val enhancedAiService =
                    EnhancedAIService(_apiEndpoint.value, _apiKey.value, _modelName.value,
                        _apiProviderType.value, context)

            // 通知ViewModel配置已更改
            onConfigChanged(enhancedAiService)

            return true
        }
        return false
    }

    /** 切换AI计划功能 */
    fun toggleAiPlanning() {
        viewModelScope.launch {
            val newValue = !_enableAiPlanning.value
            apiPreferences.saveEnableAiPlanning(newValue)
            _enableAiPlanning.value = newValue
        }
    }

    /** 切换显示思考过程 */
    fun toggleShowThinking() {
        viewModelScope.launch {
            val newValue = !_showThinking.value
            apiPreferences.saveShowThinking(newValue)
            _showThinking.value = newValue
        }
    }

    /** 切换内存优化 */
    fun toggleMemoryOptimization() {
        viewModelScope.launch {
            val newValue = !_memoryOptimization.value
            apiPreferences.saveMemoryOptimization(newValue)
            _memoryOptimization.value = newValue
        }
    }
}
