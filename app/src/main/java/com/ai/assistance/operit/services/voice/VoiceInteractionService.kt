package com.ai.assistance.operit.services.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import com.ai.assistance.operit.core.voice.dialogue.DialogueManager
import com.ai.assistance.operit.core.voice.recognition.AndroidSpeechRecognitionService
import com.ai.assistance.operit.core.voice.recognition.SpeechRecognitionService
import com.ai.assistance.operit.core.voice.tts.AndroidTTSService
import com.ai.assistance.operit.core.voice.tts.TTSParams
import com.ai.assistance.operit.core.voice.tts.TTSService
import com.ai.assistance.operit.data.model.voice.VoicePreferences
import com.ai.assistance.operit.data.preferences.voice.VoicePreferencesManager
import com.ai.assistance.operit.services.ServiceLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Background service for managing voice interactions.
 * Handles TTS, speech recognition, and dialogue management.
 */
class VoiceInteractionService : Service() {
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val binder = VoiceInteractionBinder()
    
    private lateinit var ttsService: TTSService
    private lateinit var speechRecognitionService: SpeechRecognitionService
    lateinit var dialogueManager: DialogueManager
    private lateinit var voicePreferencesManager: VoicePreferencesManager
    
    private val lifecycleOwner = ServiceLifecycleOwner()
    
    // Service state
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    
    private val _currentVoicePreferences = MutableStateFlow(VoicePreferences())
    val currentVoicePreferences: StateFlow<VoicePreferences> = _currentVoicePreferences.asStateFlow()
    
    // States for the DialogueManager
    val dialogueState: StateFlow<DialogueManager.DialogueState> get() = dialogueManager.dialogueState
    val partialSpeechText: StateFlow<String> get() = dialogueManager.partialSpeechText
    val dialogueHistory: StateFlow<List<com.ai.assistance.operit.data.model.voice.DialogueTurn>> get() = dialogueManager.dialogueHistory
    
    companion object {
        private const val TAG = "VoiceInteractionSvc"
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "voice_interaction_channel"
        
        // Intent actions
        const val ACTION_START_LISTENING = "com.ai.assistance.operit.action.START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.ai.assistance.operit.action.STOP_LISTENING"
        const val ACTION_STOP_SPEAKING = "com.ai.assistance.operit.action.STOP_SPEAKING"
        const val ACTION_STOP_SERVICE = "com.ai.assistance.operit.action.STOP_SERVICE"
    }
    
    /**
     * Binder for clients to access the service.
     */
    inner class VoiceInteractionBinder : Binder() {
        fun getService(): VoiceInteractionService = this@VoiceInteractionService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Voice interaction service created")

        // Signal lifecycle events
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // Initialize services
        ttsService = AndroidTTSService(this)
        speechRecognitionService = AndroidSpeechRecognitionService(this)
        dialogueManager = DialogueManager(ttsService, speechRecognitionService, serviceScope)
        voicePreferencesManager = VoicePreferencesManager(this)

        // Create notification channel
        createNotificationChannel()

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification())

        // Initialize the dialogue system
        initializeDialogueSystem()

        // Observe voice preferences
        observeVoicePreferences()
    }
    
    private fun initializeDialogueSystem() {
        serviceScope.launch {
            val result = dialogueManager.initialize()
            _isInitialized.value = result
            
            if (result) {
                Log.d(TAG, "Dialogue system initialized successfully")
            } else {
                Log.e(TAG, "Failed to initialize dialogue system")
            }
        }
    }
    
    private fun observeVoicePreferences() {
        serviceScope.launch {
            voicePreferencesManager.voicePreferencesFlow.collectLatest { preferences ->
                _currentVoicePreferences.value = preferences
                Log.d(TAG, "Voice preferences updated: $preferences")
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        // Signal lifecycle events
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        
        intent?.let { handleIntent(it) }
        
        // Restart if the service is killed
        return START_STICKY
    }
    
    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            ACTION_START_LISTENING -> startListening()
            ACTION_STOP_LISTENING -> stopListening()
            ACTION_STOP_SPEAKING -> stopSpeaking()
            ACTION_STOP_SERVICE -> stopSelf()
        }
    }
    
    /**
     * Start listening for user speech.
     */
    fun startListening() {
        if (!_isInitialized.value) {
            Log.e(TAG, "Cannot start listening - dialogue system not initialized")
            return
        }
        
        serviceScope.launch {
            val preferences = _currentVoicePreferences.value
            val continuous = preferences.continuousListening
            
            dialogueManager.startListening(continuous) { text ->
                // This callback is called when a final speech result is recognized
                Log.d(TAG, "Speech recognized: $text")
                
                // Here you would process the recognized text, perhaps sending it
                // to your AI processing module and getting a response
                val response = "I heard you say: $text"
                
                // Speak the response
                serviceScope.launch {
                    speakResponse(response)
                }
            }
        }
    }
    
    /**
     * Stop listening for user speech.
     */
    fun stopListening() {
        dialogueManager.stopListening()
    }
    
    /**
     * Speak a response using TTS.
     */
    suspend fun speakResponse(text: String) {
        if (!_isInitialized.value) {
            Log.e(TAG, "Cannot speak - dialogue system not initialized")
            return
        }
        
        val preferences = _currentVoicePreferences.value
        if (!preferences.isTtsEnabled) {
            Log.d(TAG, "TTS is disabled in preferences")
            return
        }
        
        val params = TTSParams(
            pitch = preferences.ttsPitch,
            rate = preferences.ttsRate,
            volume = preferences.ttsVolume,
            language = preferences.ttsLanguage,
            voice = preferences.ttsVoice
        )
        
        dialogueManager.speak(text, params)
    }
    
    /**
     * Stop the TTS engine from speaking.
     */
    fun stopSpeaking() {
        dialogueManager.stopSpeaking()
    }
    
    /**
     * Check if the dialogue system is active (speaking or listening).
     */
    fun isDialogueActive(): Boolean {
        return dialogueManager.isActive()
    }
    
    /**
     * Create the notification channel for the service.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Interaction Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service for voice interactions"
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Create a notification for the foreground service.
     */
    private fun createNotification(): Notification {
        // Create intent for stopping the service
        val stopIntent = Intent(this, VoiceInteractionService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Create intent for starting listening
        val listenIntent = Intent(this, VoiceInteractionService::class.java).apply {
            action = ACTION_START_LISTENING
        }
        val listenPendingIntent = PendingIntent.getService(
            this, 1, listenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build the notification
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Assistant")
            .setContentText("Voice interaction service is running")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .addAction(android.R.drawable.ic_menu_call, "Listen", listenPendingIntent)
            .build()
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Voice interaction service destroyed")
        
        // Signal lifecycle event
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        
        // Clean up resources
        dialogueManager.shutdown()
        serviceScope.cancel()
        
        super.onDestroy()
    }
} 