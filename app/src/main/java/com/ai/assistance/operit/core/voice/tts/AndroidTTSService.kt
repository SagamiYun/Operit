package com.ai.assistance.operit.core.voice.tts

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Implementation of TTSService using Android's TextToSpeech API.
 */
class AndroidTTSService(
    private val context: Context
) : TTSService {
    
    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false
    private var currentUtteranceId: String? = null
    
    companion object {
        private const val TAG = "AndroidTTSService"
    }
    
    /**
     * Initialize the TTS engine.
     * @return true if initialization was successful, false otherwise
     */
    override suspend fun initialize(): Boolean = withContext(Dispatchers.Main) {
        if (isInitialized && textToSpeech != null) {
            return@withContext true
        }
        
        return@withContext suspendCancellableCoroutine { continuation ->
            textToSpeech = TextToSpeech(context) { status ->
                isInitialized = status == TextToSpeech.SUCCESS
                
                if (isInitialized) {
                    // Set default language to Chinese
                    val result = textToSpeech?.setLanguage(Locale.CHINESE)
                    
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        // If Chinese is not available, try English
                        textToSpeech?.setLanguage(Locale.US)
                    }
                    
                    // Set the utterance progress listener
                    setUtteranceProgressListener()
                }
                
                continuation.resume(isInitialized)
            }
            
            continuation.invokeOnCancellation {
                shutdown()
            }
        }
    }
    
    /**
     * Set up the utterance progress listener to track speech events.
     */
    private fun setUtteranceProgressListener() {
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "Speech started: $utteranceId")
            }
            
            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "Speech completed: $utteranceId")
            }
            
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "Speech error: $utteranceId")
            }
            
            // Required override for newer Android versions
            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "Speech error with code: $utteranceId, $errorCode")
            }
        })
    }
    
    /**
     * Convert text to speech and play it.
     * @param text The text to be spoken
     * @param params Optional parameters for speech (pitch, rate, etc.)
     * @return true if the operation was successful, false otherwise
     */
    override suspend fun speak(text: String, params: TTSParams): Boolean = withContext(Dispatchers.Main) {
        if (!isInitialized || textToSpeech == null) {
            if (!initialize()) {
                return@withContext false
            }
        }
        
        // Apply speech parameters
        textToSpeech?.setPitch(params.pitch)
        textToSpeech?.setSpeechRate(params.rate)
        
        // Apply language if specified
        if (params.language != null) {
            try {
                val locale = if (params.language.contains("-")) {
                    val parts = params.language.split("-")
                    Locale(parts[0], parts[1])
                } else {
                    Locale(params.language)
                }
                textToSpeech?.setLanguage(locale)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting language: ${e.message}")
            }
        }
        
        // Apply voice if specified and supported by API level
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && params.voice != null) {
            textToSpeech?.voices?.find { it.name == params.voice }?.let {
                textToSpeech?.setVoice(it)
            }
        }
        
        currentUtteranceId = UUID.randomUUID().toString()
        
        // Queue the text to be spoken
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            textToSpeech?.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                currentUtteranceId
            ) ?: TextToSpeech.ERROR
        } else {
            @Suppress("DEPRECATION")
            val params = HashMap<String, String>().apply {
                put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, currentUtteranceId!!)
            }
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params) ?: TextToSpeech.ERROR
        }
        
        return@withContext result == TextToSpeech.SUCCESS
    }
    
    /**
     * Pause the current speech.
     * @return true if the operation was successful, false otherwise
     */
    override fun pause(): Boolean {
        if (!isInitialized || textToSpeech == null) {
            return false
        }
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val result = textToSpeech?.stop()
            result == TextToSpeech.SUCCESS
        } else {
            false
        }
    }
    
    /**
     * Resume paused speech. Note: Android's TextToSpeech doesn't directly support resuming.
     * @return true if the operation was successful, false otherwise
     */
    override fun resume(): Boolean {
        // Android TTS doesn't natively support resuming paused speech
        // This would need to be implemented with custom logic to track what was being spoken
        return false
    }
    
    /**
     * Stop the current speech.
     * @return true if the operation was successful, false otherwise
     */
    override fun stop(): Boolean {
        if (!isInitialized || textToSpeech == null) {
            return false
        }
        
        val result = textToSpeech?.stop()
        return result == TextToSpeech.SUCCESS
    }
    
    /**
     * Check if the TTS engine is currently speaking.
     * @return true if speaking, false otherwise
     */
    override fun isSpeaking(): Boolean {
        if (!isInitialized || textToSpeech == null) {
            return false
        }
        
        return textToSpeech!!.isSpeaking
    }
    
    /**
     * Release resources used by the TTS engine.
     * Should be called when the TTS service is no longer needed.
     */
    override fun shutdown() {
        if (textToSpeech != null) {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
        }
        isInitialized = false
    }
} 