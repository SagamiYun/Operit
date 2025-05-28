package com.ai.assistance.operit.core.voice.recognition

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for Speech Recognition services.
 * Defines the core functionality for converting speech to text in the application.
 */
interface SpeechRecognitionService {
    /**
     * Initialize the speech recognition engine.
     * @return true if initialization was successful, false otherwise
     */
    suspend fun initialize(): Boolean
    
    /**
     * Start listening for speech input.
     * @param continuous If true, recognition continues until explicitly stopped.
     *                   If false, recognition stops after detecting a pause in speech.
     * @return true if the operation was successful, false otherwise
     */
    suspend fun startListening(continuous: Boolean = false): Boolean
    
    /**
     * Stop listening for speech input.
     * @return true if the operation was successful, false otherwise
     */
    fun stopListening(): Boolean
    
    /**
     * Check if the recognition engine is currently listening.
     * @return true if listening, false otherwise
     */
    fun isListening(): Boolean
    
    /**
     * Get a flow of partial recognition results as they become available.
     * @return A flow of strings representing the partial recognition results.
     */
    fun getPartialResultsFlow(): Flow<String>
    
    /**
     * Get a flow of final recognition results.
     * @return A flow of SpeechRecognitionResult objects.
     */
    fun getFinalResultsFlow(): Flow<SpeechRecognitionResult>
    
    /**
     * Set the recognition language to be used.
     * Default implementation is empty to make this method optional for implementations.
     * @param languageCode The language code to use, e.g. "zh-CN", "en-US"
     */
    fun setLanguage(languageCode: String) { }
    
    /**
     * Get a flow of the current listening state.
     * @return A StateFlow of Boolean values representing whether the service is currently listening.
     */
    fun getListeningStateFlow(): StateFlow<Boolean>
    
    /**
     * Release resources used by the speech recognition engine.
     * Should be called when the speech recognition service is no longer needed.
     */
    fun shutdown()
}

/**
 * Result of a speech recognition operation.
 */
data class SpeechRecognitionResult(
    val text: String,
    val confidence: Float,
    val isFinal: Boolean,
    val alternatives: List<String> = emptyList(),
    val error: SpeechRecognitionError? = null
)

/**
 * Error information for speech recognition.
 */
data class SpeechRecognitionError(
    val code: Int,
    val message: String
) 