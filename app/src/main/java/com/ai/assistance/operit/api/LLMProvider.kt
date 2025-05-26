package com.ai.assistance.operit.api

import com.ai.assistance.operit.data.model.ModelParameter

/**
 * Interface defining common operations for LLM providers
 * Allows the application to use different LLM providers interchangeably
 */
interface LLMProvider {
    /**
     * Get the current input token count
     */
    val inputTokenCount: Int
    
    /**
     * Get the current output token count
     */
    val outputTokenCount: Int
    
    /**
     * Reset the token counters
     */
    fun resetTokenCounts()
    
    /**
     * Cancel the current streaming request
     */
    fun cancelStreaming()
    
    /**
     * Send a message to the LLM provider
     * 
     * @param message The message content to send
     * @param onPartialResponse Callback for partial responses with main content and optional thinking content
     * @param chatHistory Previous conversation history as pairs of (role, content)
     * @param onComplete Callback when the response is complete
     * @param onConnectionStatus Optional callback for connection status updates
     * @param modelParameters List of model parameters to apply to the request
     */
    suspend fun sendMessage(
        message: String,
        onPartialResponse: (content: String, thinking: String?) -> Unit,
        chatHistory: List<Pair<String, String>> = emptyList(),
        onComplete: () -> Unit = {},
        onConnectionStatus: ((status: String) -> Unit)? = null,
        modelParameters: List<ModelParameter<*>> = emptyList()
    )
} 