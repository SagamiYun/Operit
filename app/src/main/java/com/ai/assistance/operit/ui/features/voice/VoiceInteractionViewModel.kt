package com.ai.assistance.operit.ui.features.voice

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.assistance.operit.core.voice.dialogue.DialogueManager
import com.ai.assistance.operit.data.model.voice.DialogueTurn
import com.ai.assistance.operit.data.model.voice.VoicePreferences
import com.ai.assistance.operit.data.preferences.voice.VoicePreferencesManager
import com.ai.assistance.operit.services.voice.VoiceInteractionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ViewModel that manages voice interactions and interfaces with the VoiceInteractionService.
 */
class VoiceInteractionViewModel(application: Application) : AndroidViewModel(application) {
    
    private val voicePreferencesManager = VoicePreferencesManager(application)
    
    // Service connection
    private var voiceService: VoiceInteractionService? = null
    private var serviceBound = false
    
    // UI state
    private val _dialogueState = MutableStateFlow(DialogueManager.DialogueState.IDLE)
    val dialogueState: StateFlow<DialogueManager.DialogueState> = _dialogueState.asStateFlow()
    
    private val _partialSpeechText = MutableStateFlow("")
    val partialSpeechText: StateFlow<String> = _partialSpeechText.asStateFlow()
    
    private val _dialogueHistory = MutableStateFlow<List<DialogueTurn>>(emptyList())
    val dialogueHistory: StateFlow<List<DialogueTurn>> = _dialogueHistory.asStateFlow()
    
    private val _voicePreferences = MutableStateFlow(VoicePreferences())
    val voicePreferences: StateFlow<VoicePreferences> = _voicePreferences.asStateFlow()
    
    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()
    
    companion object {
        private const val TAG = "VoiceInteractionVM"
    }
    
    // Service connection object
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as VoiceInteractionService.VoiceInteractionBinder
            voiceService = binder.getService()
            serviceBound = true
            _serviceConnected.value = true
            
            // Start observing dialogue state
            observeDialogueState()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            voiceService = null
            serviceBound = false
            _serviceConnected.value = false
        }
    }
    
    init {
        // Bind to the service
        bindVoiceService()
        
        // Observe voice preferences
        viewModelScope.launch {
            voicePreferencesManager.voicePreferencesFlow.collectLatest { preferences ->
                _voicePreferences.value = preferences
            }
        }
    }
    
    /**
     * Bind to the VoiceInteractionService.
     */
    private fun bindVoiceService() {
        val context = getApplication<Application>()
        val intent = Intent(context, VoiceInteractionService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        context.startService(intent)
    }
    
    /**
     * Observe dialogue state and other flows from the service.
     */
    private fun observeDialogueState() {
        val service = voiceService ?: return
        
        // Only observe once the dialogueManager is initialized
        if (!isInitialized(service)) {
            Log.d(TAG, "DialogueManager not initialized yet")
            return
        }
        
        viewModelScope.launch {
            service.dialogueState.collectLatest { state ->
                _dialogueState.value = state
            }
        }
        
        viewModelScope.launch {
            service.partialSpeechText.collectLatest { text ->
                _partialSpeechText.value = text
            }
        }
        
        viewModelScope.launch {
            service.dialogueHistory.collectLatest { history ->
                _dialogueHistory.value = history
            }
        }
    }
    
    /**
     * Check if the dialogueManager is initialized in the service.
     */
    private fun isInitialized(service: VoiceInteractionService): Boolean {
        return try {
            // This will throw an exception if dialogueManager is not initialized
            service.dialogueManager
            true
        } catch (e: UninitializedPropertyAccessException) {
            false
        }
    }
    
    /**
     * Start listening for user speech.
     */
    fun startListening() {
        Log.d(TAG, "startListening")
        voiceService?.startListening()
    }
    
    /**
     * Stop listening for user speech.
     */
    fun stopListening() {
        Log.d(TAG, "stopListening")
        voiceService?.stopListening()
    }
    
    /**
     * Stop the TTS engine from speaking.
     */
    fun stopSpeaking() {
        Log.d(TAG, "stopSpeaking")
        voiceService?.stopSpeaking()
    }
    
    /**
     * Speak a text using TTS.
     */
    fun speak(text: String) {
        Log.d(TAG, "speak: $text")
        viewModelScope.launch {
            voiceService?.speakResponse(text)
        }
    }
    
    /**
     * Update voice preferences.
     */
    fun updateVoicePreferences(preferences: VoicePreferences) {
        viewModelScope.launch {
            voicePreferencesManager.updateVoicePreferences(preferences)
        }
    }
    
    /**
     * Update TTS enabled state.
     */
    fun updateTtsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            voicePreferencesManager.updateTtsEnabled(enabled)
        }
    }
    
    /**
     * Update speech recognition enabled state.
     */
    fun updateSpeechRecognitionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            voicePreferencesManager.updateSpeechRecognitionEnabled(enabled)
        }
    }
    
    /**
     * Clear dialogue history.
     */
    fun clearDialogueHistory() {
        voiceService?.dialogueManager?.clearHistory()
    }
    
    override fun onCleared() {
        super.onCleared()
        
        // Unbind from the service when the ViewModel is cleared
        if (serviceBound) {
            getApplication<Application>().unbindService(serviceConnection)
            serviceBound = false
        }
    }
} 