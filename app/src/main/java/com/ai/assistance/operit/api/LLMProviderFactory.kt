package com.ai.assistance.operit.api

/**
 * Factory for creating LLM provider instances
 * Provides a clean abstraction for switching between different providers
 */
object LLMProviderFactory {
    /**
     * Enum defining supported LLM provider types
     */
    enum class ProviderType {
        DEEPSEEK,
        GEMINI
    }
    
    /**
     * Create an LLM provider instance based on the specified provider type
     * 
     * @param type The provider type
     * @param apiEndpoint The API endpoint URL
     * @param apiKey The API key for authentication
     * @param modelName The model name to use
     * @return A new LLMProvider instance
     */
    fun createProvider(
        type: ProviderType,
        apiEndpoint: String,
        apiKey: String,
        modelName: String
    ): LLMProvider {
        return when (type) {
            ProviderType.DEEPSEEK -> AIService(apiEndpoint, apiKey, modelName)
            ProviderType.GEMINI -> GeminiService(apiEndpoint, apiKey, modelName)
        }
    }
    
    /**
     * Get the appropriate API endpoint based on provider type
     * 
     * @param type The provider type
     * @return The default API endpoint for the provider
     */
    fun getDefaultEndpoint(type: ProviderType): String {
        return when (type) {
            ProviderType.DEEPSEEK -> AIService.DEFAULT_API_ENDPOINT
            ProviderType.GEMINI -> GeminiService.DEFAULT_API_ENDPOINT
        }
    }
    
    /**
     * Get a list of available models for the specified provider
     * 
     * @param type The provider type
     * @return List of model names available for the provider
     */
    fun getAvailableModels(type: ProviderType): List<String> {
        return when (type) {
            ProviderType.DEEPSEEK -> listOf(
                AIService.DEEPSEEK_CHAT,
                AIService.DEEPSEEK_CODER
            )
            ProviderType.GEMINI -> listOf(
                GeminiService.GEMINI_1_5_PRO,
                GeminiService.GEMINI_1_5_FLASH,
                GeminiService.GEMINI_1_0_PRO,
                GeminiService.GEMINI_2_5_FLASH_05_20,
                GeminiService.GEMINI_2_5_PRO_05_06,
            )
        }
    }
    
    /**
     * Get the default model for the specified provider
     * 
     * @param type The provider type
     * @return The default model name for the provider
     */
    fun getDefaultModel(type: ProviderType): String {
        return when (type) {
            ProviderType.DEEPSEEK -> AIService.DEEPSEEK_CHAT
            ProviderType.GEMINI -> GeminiService.GEMINI_1_5_PRO
        }
    }
} 