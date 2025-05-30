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
     * Speak a text using the TTS engine.
     * @param text The text to speak
     * @param params Parameters for the TTS engine
     * @return true if the operation was successful, false otherwise
     */
    suspend fun speak(text: String, params: TTSParams = TTSParams()): Boolean
    
    /**
     * 开始流式语音播放。
     * 将文本分割成句子，立即开始播放第一句，后续句子在前一句播放完成后继续。
     * 
     * @param text 要播放的文本，可以是部分文本
     * @param params TTS参数
     * @return 是否成功开始播放
     */
    suspend fun startStreamingSpeech(text: String, params: TTSParams = TTSParams()): Boolean
    
    /**
     * 添加更多文本到当前流式播放中。
     * 如果流式播放未开始，则启动新的流式播放。
     * 
     * @param additionalText 要添加的文本
     * @param params TTS参数
     * @return 是否成功添加
     */
    suspend fun appendStreamingSpeech(additionalText: String, params: TTSParams = TTSParams()): Boolean
    
    /**
     * Stop the TTS engine from speaking.
     */
    fun stop()
    
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

    fun pause()
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