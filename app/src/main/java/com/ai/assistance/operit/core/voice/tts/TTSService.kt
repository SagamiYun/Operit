package com.ai.assistance.operit.core.voice.tts

/**
 * Interface for Text-to-Speech services.
 * Defines the core functionality for converting text to speech in the application.
 */
interface TTSService {
    /**
     * Initialize the TTS engine.
     * @return true if initialization was successful, false otherwise
     */
    suspend fun initialize(): Boolean
    
    /**
     * Convert text to speech and play it.
     * @param text The text to be spoken
     * @param params Optional parameters for speech (pitch, rate, etc.)
     * @return true if the operation was successful, false otherwise
     */
    suspend fun speak(text: String, params: TTSParams = TTSParams()): Boolean
    
    /**
     * Pause the current speech.
     * @return true if the operation was successful, false otherwise
     */
    fun pause(): Boolean
    
    /**
     * Resume paused speech.
     * @return true if the operation was successful, false otherwise
     */
    fun resume(): Boolean
    
    /**
     * Stop the current speech.
     * @return true if the operation was successful, false otherwise
     */
    fun stop(): Boolean
    
    /**
     * Check if the TTS engine is currently speaking.
     * @return true if speaking, false otherwise
     */
    fun isSpeaking(): Boolean
    
    /**
     * Release resources used by the TTS engine.
     * Should be called when the TTS service is no longer needed.
     */
    fun shutdown()
}

/**
 * Parameters for TTS speech customization.
 */
data class TTSParams(
    val pitch: Float = 1.0f,
    val rate: Float = 1.0f,
    val volume: Float = 1.0f,
    val language: String? = null,
    val voice: String? = null
) 