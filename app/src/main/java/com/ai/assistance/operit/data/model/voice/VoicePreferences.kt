package com.ai.assistance.operit.data.model.voice

/**
 * Stores user preferences for voice interactions.
 *
 * @property isTtsEnabled Whether text-to-speech is enabled
 * @property isSpeechRecognitionEnabled Whether speech recognition is enabled
 * @property ttsVoice The preferred TTS voice ID
 * @property ttsLanguage The preferred TTS language code (e.g., "zh-CN", "en-US")
 * @property ttsPitch The TTS pitch setting (default is 1.0)
 * @property ttsRate The TTS speech rate setting (default is 1.0)
 * @property ttsVolume The TTS volume setting (default is 1.0)
 * @property speechLanguage The preferred speech recognition language
 * @property continuousListening Whether continuous listening mode is enabled
 * @property useWakeWord Whether to use a wake word to activate voice input
 * @property wakeWord The custom wake word to activate voice input (if enabled)
 */
data class VoicePreferences(
    val isTtsEnabled: Boolean = true,
    val isSpeechRecognitionEnabled: Boolean = true,
    val ttsVoice: String? = null,
    val ttsLanguage: String = "zh-CN",
    val ttsPitch: Float = 1.0f,
    val ttsRate: Float = 1.0f,
    val ttsVolume: Float = 1.0f,
    val speechLanguage: String = "zh-CN",
    val continuousListening: Boolean = false,
    val useWakeWord: Boolean = false,
    val wakeWord: String = "Operit"
) 