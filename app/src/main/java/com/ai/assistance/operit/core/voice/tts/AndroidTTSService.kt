package com.ai.assistance.operit.core.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of TTSService that uses Android's TextToSpeech engine.
 */
class AndroidTTSService(private val context: Context) : TTSService {
    
    companion object {
        private const val TAG = "AndroidTTSService"
    }
    
    // TextToSpeech engine
    private var tts: TextToSpeech? = null
    
    // Track active utterances
    private val activeUtterances = ConcurrentHashMap<String, Boolean>()
    
    // 流式TTS状态
    private var isStreaming = false
    private var streamingUtteranceId: String? = null
    private val streamingSentences = mutableListOf<String>()
    private var currentStreamingIndex = 0
    
    /**
     * Initialize the TTS engine.
     */
    override suspend fun initialize(): Boolean {
        return withContext(Dispatchers.IO) {
            suspendCoroutine { continuation ->
                try {
                    tts = TextToSpeech(context) { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            // Set language and other settings
                            val result = tts?.setLanguage(Locale.getDefault())
                            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                                Log.e(TAG, "Language not supported")
                                continuation.resume(false)
                            } else {
                                // Set utterance progress listener
                                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                    override fun onStart(utteranceId: String) {
                                        Log.d(TAG, "Speech started: $utteranceId")
                                    }
                                    
                                    override fun onDone(utteranceId: String) {
                                        Log.d(TAG, "Speech completed: $utteranceId")
                                        activeUtterances.remove(utteranceId)
                                        
                                        // 处理流式TTS的下一句
                                        if (isStreaming && utteranceId == streamingUtteranceId) {
                                            continueStreamingSpeech()
                                        }
                                    }
                                    
                                    @Deprecated("Deprecated in Java")
                                    override fun onError(utteranceId: String) {
                                        Log.e(TAG, "Speech error: $utteranceId")
                                        activeUtterances.remove(utteranceId)
                                    }
                                    
                                    override fun onError(utteranceId: String, errorCode: Int) {
                                        Log.e(TAG, "Speech error: $utteranceId, code: $errorCode")
                                        activeUtterances.remove(utteranceId)
                                    }
                                })
                                
                                // Set audio attributes
                                tts?.setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .build()
                                )
                                
                                continuation.resume(true)
                            }
                        } else {
                            Log.e(TAG, "TTS initialization failed: $status")
                            continuation.resume(false)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error initializing TTS", e)
                    continuation.resume(false)
                }
            }
        }
    }
    
    /**
     * Speak a text using the TTS engine.
     */
    override suspend fun speak(text: String, params: TTSParams): Boolean {
        if (tts == null || text.isBlank()) {
            return false
        }
        
        return withContext(Dispatchers.Main) {
            try {
                val utteranceId = UUID.randomUUID().toString()
                activeUtterances[utteranceId] = true
                
                // Apply parameters
                tts?.setPitch(params.pitch)
                tts?.setSpeechRate(params.rate)
                
                // Speak the text
                val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                result == TextToSpeech.SUCCESS
            } catch (e: Exception) {
                Log.e(TAG, "Error speaking text", e)
                false
            }
        }
    }
    
    /**
     * 开始流式语音播放。
     * 将文本分割成句子，立即开始播放第一句，后续句子在前一句播放完成后继续。
     * 
     * @param text 要播放的文本，可以是部分文本
     * @param params TTS参数
     * @return 是否成功开始播放
     */
    override suspend fun startStreamingSpeech(text: String, params: TTSParams): Boolean {
        if (tts == null || text.isBlank()) {
            return false
        }
        
        return withContext(Dispatchers.Main) {
            try {
                // 如果已经在流式播放中，停止当前播放
                if (isStreaming) {
                    stopStreaming()
                }
                
                // 将文本分割成句子
                val sentences = splitIntoSentences(text)
                if (sentences.isEmpty()) {
                    return@withContext false
                }
                
                // 设置流式播放状态
                isStreaming = true
                streamingUtteranceId = UUID.randomUUID().toString()
                streamingSentences.clear()
                streamingSentences.addAll(sentences)
                currentStreamingIndex = 0
                
                // 应用TTS参数
                tts?.setPitch(params.pitch)
                tts?.setSpeechRate(params.rate)
                
                // 开始播放第一句
                if (streamingSentences.isNotEmpty()) {
                    val firstSentence = streamingSentences[0]
                    activeUtterances[streamingUtteranceId!!] = true
                    
                    val result = tts?.speak(firstSentence, TextToSpeech.QUEUE_FLUSH, null, streamingUtteranceId)
                    currentStreamingIndex = 1
                    return@withContext result == TextToSpeech.SUCCESS
                }
                
                false
            } catch (e: Exception) {
                Log.e(TAG, "Error starting streaming speech", e)
                isStreaming = false
                false
            }
        }
    }
    
    /**
     * 添加更多文本到当前流式播放中。
     * 如果流式播放未开始，则启动新的流式播放。
     * 
     * @param additionalText 要添加的文本
     * @param params TTS参数
     * @return 是否成功添加
     */
    override suspend fun appendStreamingSpeech(additionalText: String, params: TTSParams): Boolean {
        if (tts == null || additionalText.isBlank()) {
            return false
        }
        
        return withContext(Dispatchers.Main) {
            try {
                // 如果未开始流式播放，则启动新的流式播放
                if (!isStreaming) {
                    return@withContext startStreamingSpeech(additionalText, params)
                }
                
                // 添加新的句子到流式播放队列
                val newSentences = splitIntoSentences(additionalText)
                synchronized(streamingSentences) {
                    streamingSentences.addAll(newSentences)
                }
                
                // 如果当前没有在播放，则开始播放
                if (!isSpeaking()) {
                    continueStreamingSpeech()
                }
                
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error appending to streaming speech", e)
                false
            }
        }
    }
    
    /**
     * 停止当前的流式播放。
     */
    private fun stopStreaming() {
        if (isStreaming) {
            tts?.stop()
            isStreaming = false
            streamingUtteranceId = null
            streamingSentences.clear()
            currentStreamingIndex = 0
        }
    }
    
    /**
     * 继续播放流式文本的下一句。
     * 由UtteranceProgressListener的onDone回调触发。
     */
    private fun continueStreamingSpeech() {
        if (!isStreaming || streamingUtteranceId == null) {
            return
        }
        
        synchronized(streamingSentences) {
            if (currentStreamingIndex < streamingSentences.size) {
                val nextSentence = streamingSentences[currentStreamingIndex]
                tts?.speak(nextSentence, TextToSpeech.QUEUE_ADD, null, streamingUtteranceId)
                currentStreamingIndex++
            } else {
                // 所有句子都已播放完成
                isStreaming = false
                streamingUtteranceId = null
            }
        }
    }
    
    /**
     * 将文本分割成句子以便流式播放。
     * 
     * @param text 要分割的文本
     * @return 分割后的句子列表
     */
    private fun splitIntoSentences(text: String): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }
        
        // 使用简单的正则表达式分割句子
        val sentenceEndings = Regex("[.!?。！？]")
        val sentences = mutableListOf<String>()
        
        // 分割文本
        var start = 0
        var matcher = sentenceEndings.find(text, start)
        
        while (matcher != null) {
            val end = matcher.range.last + 1
            if (end > start) {
                sentences.add(text.substring(start, end).trim())
            }
            start = end
            matcher = sentenceEndings.find(text, start)
        }
        
        // 处理剩余部分
        if (start < text.length) {
            sentences.add(text.substring(start).trim())
        }
        
        // 如果没有找到句子结束标记，将整个文本作为一个句子
        if (sentences.isEmpty() && text.isNotBlank()) {
            sentences.add(text.trim())
        }
        
        return sentences
    }
    
    /**
     * Stop the TTS engine from speaking.
     */
    override fun stop() {
        try {
            tts?.stop()
            activeUtterances.clear()
            
            // 停止流式播放
            stopStreaming()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }
    }
    
    /**
     * Check if the TTS engine is currently speaking.
     */
    override fun isSpeaking(): Boolean {
        return tts?.isSpeaking() == true || activeUtterances.isNotEmpty()
    }
    
    /**
     * Release resources used by the TTS engine.
     */
    override fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            activeUtterances.clear()
            
            // 重置流式播放状态
            isStreaming = false
            streamingUtteranceId = null
            streamingSentences.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS", e)
        }
    }
} 