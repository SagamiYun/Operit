package com.ai.assistance.operit.core.voice.dialogue

import android.util.Log
import com.ai.assistance.operit.core.voice.recognition.SpeechRecognitionResult
import com.ai.assistance.operit.core.voice.recognition.SpeechRecognitionService
import com.ai.assistance.operit.core.voice.tts.TTSParams
import com.ai.assistance.operit.core.voice.tts.TTSService
import com.ai.assistance.operit.data.model.voice.DialogueTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manager for dialogue interactions using TTS and speech recognition.
 */
class DialogueManager(
    private val ttsService: TTSService,
    private val speechRecognitionService: SpeechRecognitionService,
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
) {
    // Dialogue states
    enum class DialogueState {
        IDLE,       // System is inactive
        LISTENING,  // System is listening for user input
        PROCESSING, // System is processing user input
        SPEAKING    // System is speaking a response
    }
    
    // Current dialogue state
    private val _dialogueState = MutableStateFlow(DialogueState.IDLE)
    val dialogueState: StateFlow<DialogueState> = _dialogueState.asStateFlow()
    
    // Current partial speech text
    private val _partialSpeechText = MutableStateFlow("")
    val partialSpeechText: StateFlow<String> = _partialSpeechText.asStateFlow()
    
    // Dialogue history
    private val _dialogueHistory = MutableStateFlow<List<DialogueTurn>>(emptyList())
    val dialogueHistory: StateFlow<List<DialogueTurn>> = _dialogueHistory.asStateFlow()
    
    // Collection jobs for speech recognition
    private var partialResultsJob: Job? = null
    private var finalResultsJob: Job? = null
    
    // Callback for final speech recognition results
    private var onSpeechResultListener: ((String) -> Unit)? = null
    
    companion object {
        private const val TAG = "DialogueManager"
    }
    
    /**
     * Initialize the dialogue system.
     * @return true if initialization was successful, false otherwise
     */
    suspend fun initialize(): Boolean {
        Log.d(TAG, "Initializing dialogue system")
        
        val ttsInitialized = ttsService.initialize()
        val speechInitialized = speechRecognitionService.initialize()
        
        if (ttsInitialized && speechInitialized) {
            setupSpeechRecognitionListeners()
            _dialogueState.value = DialogueState.IDLE
            return true
        }
        
        return false
    }
    
    /**
     * Set up listeners for speech recognition results.
     */
    private fun setupSpeechRecognitionListeners() {
        partialResultsJob?.cancel()
        finalResultsJob?.cancel()
        
        partialResultsJob = appScope.launch {
            speechRecognitionService.getPartialResultsFlow().collect { partialText ->
                _partialSpeechText.value = partialText
            }
        }
        
        finalResultsJob = appScope.launch {
            speechRecognitionService.getFinalResultsFlow().collect { result ->
                if (result.error == null && result.text.isNotBlank()) {
                    handleSpeechRecognitionResult(result)
                } else if (result.error != null) {
                    Log.e(TAG, "Speech recognition error: ${result.error.message}")
                    _dialogueState.value = DialogueState.IDLE
                }
            }
        }
    }
    
    /**
     * Handle speech recognition results.
     * @param result The speech recognition result
     */
    private fun handleSpeechRecognitionResult(result: SpeechRecognitionResult) {
        val recognizedText = result.text
        Log.d(TAG, "Recognized text: $recognizedText")
        
        // Only process if we're in LISTENING state and the text isn't empty
        if (_dialogueState.value == DialogueState.LISTENING && recognizedText.isNotBlank()) {
            _dialogueState.value = DialogueState.PROCESSING
            
            // Add the user's message to the dialogue history
            addUserTurnToHistory(recognizedText)
            
            // Reset the partial text
            _partialSpeechText.value = ""
            
            // Notify listener of the final result
            onSpeechResultListener?.invoke(recognizedText)
        }
    }
    
    /**
     * Start listening for user speech input.
     * @param continuous Whether to continuously listen or stop after silence
     * @param onResult Callback for when a final result is recognized
     */
    suspend fun startListening(
        continuous: Boolean = false,
        onResult: (String) -> Unit
    ): Boolean {
        if (_dialogueState.value == DialogueState.SPEAKING) {
            // Stop speaking if we're currently speaking
            ttsService.stop()
        }
        
        onSpeechResultListener = onResult
        _partialSpeechText.value = ""
        _dialogueState.value = DialogueState.LISTENING
        
        return withContext(Dispatchers.Main) {
            val result = speechRecognitionService.startListening(continuous)
            if (!result) {
                _dialogueState.value = DialogueState.IDLE
            }
            result
        }
    }
    
    /**
     * Stop listening for user speech input.
     */
    fun stopListening() {
        if (_dialogueState.value == DialogueState.LISTENING) {
            speechRecognitionService.stopListening()
            _dialogueState.value = DialogueState.IDLE
            _partialSpeechText.value = ""
        }
    }
    
    /**
     * Speak a text response from the AI.
     * @param text The text to speak
     * @param params TTS parameters
     * @param addToHistory Whether to add this response to the dialogue history
     */
    suspend fun speak(
        text: String,
        params: TTSParams = TTSParams(),
        addToHistory: Boolean = true
    ): Boolean {
        if (text.isBlank()) {
            return false
        }
        
        // Stop any ongoing speech
        ttsService.stop()
        
        // Update the state
        _dialogueState.value = DialogueState.SPEAKING
        
        // Add the AI's response to the dialogue history if requested
        if (addToHistory) {
            addAITurnToHistory(text)
        }
        
        // Speak the text
        val result = ttsService.speak(text, params)
        
        // Update the state when done speaking
        if (result) {
            // Wait until speaking is done before updating state
            appScope.launch {
                while (ttsService.isSpeaking()) {
                    withContext(Dispatchers.IO) {
                        Thread.sleep(100)
                    }
                }
                _dialogueState.value = DialogueState.IDLE
            }
        } else {
            _dialogueState.value = DialogueState.IDLE
        }
        
        return result
    }
    
    /**
     * Stop any ongoing speech.
     */
    fun stopSpeaking() {
        if (_dialogueState.value == DialogueState.SPEAKING) {
            ttsService.stop()
            _dialogueState.value = DialogueState.IDLE
        }
    }
    
    /**
     * Add a user turn to the dialogue history.
     * @param text The user's message text
     */
    private fun addUserTurnToHistory(text: String) {
        val turn = DialogueTurn(
            isUser = true,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        
        _dialogueHistory.value = _dialogueHistory.value + turn
    }
    
    /**
     * Add an AI turn to the dialogue history.
     * @param text The AI's message text
     */
    private fun addAITurnToHistory(text: String) {
        val turn = DialogueTurn(
            isUser = false,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        
        _dialogueHistory.value = _dialogueHistory.value + turn
    }
    
    /**
     * Check if the dialogue system is active (speaking or listening).
     * @return true if active, false otherwise
     */
    fun isActive(): Boolean {
        return _dialogueState.value != DialogueState.IDLE
    }
    
    /**
     * Clear the dialogue history.
     */
    fun clearHistory() {
        _dialogueHistory.value = emptyList()
    }
    
    /**
     * Release resources used by the dialogue manager.
     * Should be called when the dialogue manager is no longer needed.
     */
    fun shutdown() {
        partialResultsJob?.cancel()
        finalResultsJob?.cancel()
        ttsService.shutdown()
        speechRecognitionService.shutdown()
        _dialogueState.value = DialogueState.IDLE
    }
} 