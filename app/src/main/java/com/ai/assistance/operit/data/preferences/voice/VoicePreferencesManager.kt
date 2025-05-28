package com.ai.assistance.operit.data.preferences.voice

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.data.model.voice.VoicePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Manages voice-related user preferences using DataStore.
 */
class VoicePreferencesManager(private val context: Context) {
    
    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "voice_preferences")
        
        // Preference keys
        private val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        private val SPEECH_RECOGNITION_ENABLED = booleanPreferencesKey("speech_recognition_enabled")
        private val TTS_VOICE = stringPreferencesKey("tts_voice")
        private val TTS_LANGUAGE = stringPreferencesKey("tts_language")
        private val TTS_PITCH = floatPreferencesKey("tts_pitch")
        private val TTS_RATE = floatPreferencesKey("tts_rate")
        private val TTS_VOLUME = floatPreferencesKey("tts_volume")
        private val SPEECH_LANGUAGE = stringPreferencesKey("speech_language")
        private val CONTINUOUS_LISTENING = booleanPreferencesKey("continuous_listening")
        private val USE_WAKE_WORD = booleanPreferencesKey("use_wake_word")
        private val WAKE_WORD = stringPreferencesKey("wake_word")
    }
    
    /**
     * Get the current voice preferences as a Flow.
     */
    val voicePreferencesFlow: Flow<VoicePreferences> = context.dataStore.data
        .map { preferences ->
            VoicePreferences(
                isTtsEnabled = preferences[TTS_ENABLED] ?: true,
                isSpeechRecognitionEnabled = preferences[SPEECH_RECOGNITION_ENABLED] ?: true,
                ttsVoice = preferences[TTS_VOICE],
                ttsLanguage = preferences[TTS_LANGUAGE] ?: "zh-CN",
                ttsPitch = preferences[TTS_PITCH] ?: 1.0f,
                ttsRate = preferences[TTS_RATE] ?: 1.0f,
                ttsVolume = preferences[TTS_VOLUME] ?: 1.0f,
                speechLanguage = preferences[SPEECH_LANGUAGE] ?: "zh-CN",
                continuousListening = preferences[CONTINUOUS_LISTENING] ?: false,
                useWakeWord = preferences[USE_WAKE_WORD] ?: false,
                wakeWord = preferences[WAKE_WORD] ?: "Operit"
            )
        }
    
    /**
     * Update TTS enabled state.
     */
    suspend fun updateTtsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[TTS_ENABLED] = enabled
        }
    }
    
    /**
     * Update speech recognition enabled state.
     */
    suspend fun updateSpeechRecognitionEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SPEECH_RECOGNITION_ENABLED] = enabled
        }
    }
    
    /**
     * Update TTS voice.
     */
    suspend fun updateTtsVoice(voice: String?) {
        context.dataStore.edit { preferences ->
            if (voice != null) {
                preferences[TTS_VOICE] = voice
            } else {
                preferences.remove(TTS_VOICE)
            }
        }
    }
    
    /**
     * Update TTS language.
     */
    suspend fun updateTtsLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[TTS_LANGUAGE] = language
        }
    }
    
    /**
     * Update TTS pitch.
     */
    suspend fun updateTtsPitch(pitch: Float) {
        context.dataStore.edit { preferences ->
            preferences[TTS_PITCH] = pitch
        }
    }
    
    /**
     * Update TTS rate.
     */
    suspend fun updateTtsRate(rate: Float) {
        context.dataStore.edit { preferences ->
            preferences[TTS_RATE] = rate
        }
    }
    
    /**
     * Update TTS volume.
     */
    suspend fun updateTtsVolume(volume: Float) {
        context.dataStore.edit { preferences ->
            preferences[TTS_VOLUME] = volume
        }
    }
    
    /**
     * Update speech recognition language.
     */
    suspend fun updateSpeechLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[SPEECH_LANGUAGE] = language
        }
    }
    
    /**
     * Update continuous listening mode.
     */
    suspend fun updateContinuousListening(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[CONTINUOUS_LISTENING] = enabled
        }
    }
    
    /**
     * Update wake word usage.
     */
    suspend fun updateUseWakeWord(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[USE_WAKE_WORD] = enabled
        }
    }
    
    /**
     * Update wake word.
     */
    suspend fun updateWakeWord(wakeWord: String) {
        context.dataStore.edit { preferences ->
            preferences[WAKE_WORD] = wakeWord
        }
    }
    
    /**
     * Update all voice preferences at once.
     */
    suspend fun updateVoicePreferences(preferences: VoicePreferences) {
        context.dataStore.edit { prefs ->
            prefs[TTS_ENABLED] = preferences.isTtsEnabled
            prefs[SPEECH_RECOGNITION_ENABLED] = preferences.isSpeechRecognitionEnabled
            if (preferences.ttsVoice != null) {
                prefs[TTS_VOICE] = preferences.ttsVoice
            } else {
                prefs.remove(TTS_VOICE)
            }
            prefs[TTS_LANGUAGE] = preferences.ttsLanguage
            prefs[TTS_PITCH] = preferences.ttsPitch
            prefs[TTS_RATE] = preferences.ttsRate
            prefs[TTS_VOLUME] = preferences.ttsVolume
            prefs[SPEECH_LANGUAGE] = preferences.speechLanguage
            prefs[CONTINUOUS_LISTENING] = preferences.continuousListening
            prefs[USE_WAKE_WORD] = preferences.useWakeWord
            prefs[WAKE_WORD] = preferences.wakeWord
        }
    }
} 