package com.ai.assistance.operit.core.voice.recognition

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Implementation of SpeechRecognitionService using Android's SpeechRecognizer API.
 */
class AndroidSpeechRecognitionService(
    private val context: Context
) : SpeechRecognitionService {
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isInitialized = false
    private var isListening = false
    private var isContinuousListening = false
    
    private val partialResultsFlow = MutableSharedFlow<String>(replay = 0)
    private val finalResultsFlow = MutableSharedFlow<SpeechRecognitionResult>(replay = 1)
    private val _listeningStateFlow = MutableStateFlow(false)
    private val listeningStateFlow: StateFlow<Boolean> = _listeningStateFlow
    
    // 添加语言支持
    private var recognitionLanguage: String = ""
    
    companion object {
        private const val TAG = "AndroidSpeechRecognizer"
        
        // 支持的语言列表
        val SUPPORTED_LANGUAGES = mapOf(
            "en" to "en-US",   // 英语 (美国)
            "zh" to "zh-CN",   // 中文 (简体)
            "ja" to "ja-JP",   // 日语
            "ko" to "ko-KR",   // 韩语
            "fr" to "fr-FR",   // 法语
            "de" to "de-DE",   // 德语
            "es" to "es-ES",   // 西班牙语
            "it" to "it-IT",   // 意大利语
            "ru" to "ru-RU",   // 俄语
            "pt" to "pt-BR"    // 葡萄牙语
        )
    }
    
    /**
     * 设置语音识别的语言
     * @param languageCode 语言代码，例如"zh-CN"、"en-US"等
     */
    fun setRecognitionLanguage(languageCode: String) {
        if (languageCode.isNotBlank()) {
            recognitionLanguage = languageCode
            Log.d(TAG, "语音识别语言设置为: $languageCode")
        }
    }
    
    /**
     * 实现 SpeechRecognitionService 接口的 setLanguage 方法
     */
    override fun setLanguage(languageCode: String) {
        setRecognitionLanguage(languageCode)
    }
    
    /**
     * 获取当前设备的语言设置
     * @return 语言代码
     */
    private fun getCurrentLanguage(): String {
        // 如果已经设置了语言，则使用已设置的语言
        if (recognitionLanguage.isNotBlank()) {
            return recognitionLanguage
        }
        
        // 否则，从系统设置中获取语言
        val locale = Locale.getDefault()
        val language = locale.language
        
        // 尝试匹配更具体的语言设置
        return SUPPORTED_LANGUAGES[language] ?: "$language-${locale.country}"
    }
    
    /**
     * Initialize the speech recognition engine.
     * @return true if initialization was successful, false otherwise
     */
    override suspend fun initialize(): Boolean = withContext(Dispatchers.Main) {
        if (isInitialized && speechRecognizer != null) {
            Log.d(TAG, "Speech recognizer already initialized")
            return@withContext true
        }
        
        return@withContext try {
            val isAvailable = SpeechRecognizer.isRecognitionAvailable(context)
            Log.d(TAG, "Speech recognition available: $isAvailable")
            
            if (isAvailable) {
                try {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    val listener = createRecognitionListener()
                    speechRecognizer?.setRecognitionListener(listener)
                    isInitialized = true
                    Log.d(TAG, "Speech recognizer initialized successfully")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating speech recognizer: ${e.message}")
                    false
                }
            } else {
                Log.d(TAG, "Speech recognition is not available on this device")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking speech recognition availability: ${e.message}")
            false
        }
    }
    
    /**
     * Create the recognition listener for handling speech events.
     */
    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
                isListening = true
                _listeningStateFlow.value = true
            }
            
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Beginning of speech")
            }
            
            override fun onRmsChanged(rmsdB: Float) {
                // RMS (audio level) changed - could be used for UI feedback
            }
            
            override fun onBufferReceived(buffer: ByteArray?) {
                // Buffer received - raw audio data
            }
            
            override fun onEndOfSpeech() {
                Log.d(TAG, "End of speech")
                if (!isContinuousListening) {
                    isListening = false
                    _listeningStateFlow.value = false
                } else {
                    // Restart listening if in continuous mode
                    startListeningInternal()
                }
            }
            
            override fun onError(error: Int) {
                val errorMessage = getErrorMessage(error)
                Log.d(TAG, "Error in speech recognition: $errorMessage")
                
                finalResultsFlow.tryEmit(
                    SpeechRecognitionResult(
                        text = "",
                        confidence = 0f,
                        isFinal = true,
                        error = SpeechRecognitionError(error, errorMessage)
                    )
                )
                
                isListening = false
                _listeningStateFlow.value = false
                
                // Restart listening if in continuous mode and it's a recoverable error
                if (isContinuousListening && isRecoverableError(error)) {
                    startListeningInternal()
                }
            }
            
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidenceScores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                
                if (matches != null && matches.isNotEmpty()) {
                    val primaryResult = matches[0].trim()
                    val confidence = confidenceScores?.getOrNull(0) ?: 1.0f
                    
                    // 确保结果不为空
                    if (primaryResult.isNotBlank()) {
                        Log.d(TAG, "准备发送最终语音识别结果: '$primaryResult'")
                        
                        try {
                            val result = SpeechRecognitionResult(
                                text = primaryResult,
                                confidence = confidence,
                                isFinal = true,
                                alternatives = matches.drop(1)
                            )
                            
                            // 使用 emit 代替 tryEmit，确保结果被处理
                            kotlinx.coroutines.runBlocking {
                                finalResultsFlow.emit(result)
                                Log.d(TAG, "已发送最终语音识别结果")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "发送语音识别结果时出错", e)
                        }
                    } else {
                        Log.d(TAG, "识别结果为空，不处理")
                        // 即使为空结果也发送一个特殊标记，让 ViewModel 知道识别结束但没有结果
                        kotlinx.coroutines.runBlocking {
                            finalResultsFlow.emit(
                                SpeechRecognitionResult(
                                    text = "",
                                    confidence = 0f,
                                    isFinal = true,
                                    error = SpeechRecognitionError(SpeechRecognizer.ERROR_NO_MATCH, "No speech detected")
                                )
                            )
                        }
                    }
                } else {
                    Log.d(TAG, "未获取到识别结果")
                    // 发送一个空结果错误
                    kotlinx.coroutines.runBlocking {
                        finalResultsFlow.emit(
                            SpeechRecognitionResult(
                                text = "",
                                confidence = 0f,
                                isFinal = true,
                                error = SpeechRecognitionError(SpeechRecognizer.ERROR_NO_MATCH, "No recognition results")
                            )
                        )
                    }
                }
                
                if (isContinuousListening) {
                    startListeningInternal()
                } else {
                    isListening = false
                    _listeningStateFlow.value = false
                }
            }
            
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                
                if (matches != null && matches.isNotEmpty()) {
                    val primaryResult = matches[0].trim() // 去除空白字符
                    
                    // 只有在部分结果不为空的情况下才发送
                    if (primaryResult.isNotBlank()) {
                        Log.d(TAG, "Partial result: '$primaryResult'")
                        partialResultsFlow.tryEmit(primaryResult)
                    } else {
                        Log.d(TAG, "忽略空的部分识别结果")
                    }
                }
            }
            
            override fun onEvent(eventType: Int, params: Bundle?) {
                // Additional events
            }
        }
    }
    
    /**
     * Start listening for speech input.
     * @param continuous If true, recognition continues until explicitly stopped.
     *                   If false, recognition stops after detecting a pause in speech.
     * @return true if the operation was successful, false otherwise
     */
    override suspend fun startListening(continuous: Boolean): Boolean = withContext(Dispatchers.Main) {
        if (!isInitialized) {
            if (!initialize()) {
                return@withContext false
            }
        }
        
        if (isListening) {
            stopListening()
        }
        
        isContinuousListening = continuous
        val result = startListeningInternal()
        isListening = result
        return@withContext result
    }
    
    /**
     * Internal method to start the speech recognizer.
     * @return true if the operation was successful, false otherwise
     */
    private fun startListeningInternal(): Boolean {
        return try {
            val currentLanguage = getCurrentLanguage()
            Log.d(TAG, "开始语音识别，使用语言: $currentLanguage")
            
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            
            speechRecognizer?.startListening(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition: ${e.message}")
            false
        }
    }
    
    /**
     * Stop listening for speech input.
     * @return true if the operation was successful, false otherwise
     */
    override fun stopListening(): Boolean {
        if (!isInitialized || speechRecognizer == null) {
            Log.d(TAG, "Stop listening failed - service not initialized or recognizer is null")
            return false
        }
        
        return try {
            Log.d(TAG, "Stopping speech recognition")
            isContinuousListening = false
            isListening = false
            _listeningStateFlow.value = false
            
            // 确保在主线程调用停止方法，因为 SpeechRecognizer 需要在主线程操作
            Handler(Looper.getMainLooper()).post {
                try {
                    speechRecognizer?.stopListening()
                    Log.d(TAG, "Speech recognizer stop listening command sent")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in main thread stop listening: ${e.message}")
                }
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech recognition: ${e.message}")
            // 即使发生异常，也要确保状态被重置
            isListening = false
            _listeningStateFlow.value = false
            false
        }
    }
    
    /**
     * Check if the recognition engine is currently listening.
     * @return true if listening, false otherwise
     */
    override fun isListening(): Boolean {
        return isListening
    }
    
    /**
     * Get a flow of partial recognition results as they become available.
     * @return A flow of strings representing the partial recognition results.
     */
    override fun getPartialResultsFlow(): Flow<String> {
        return partialResultsFlow
    }
    
    /**
     * Get a flow of final recognition results.
     * @return A flow of SpeechRecognitionResult objects.
     */
    override fun getFinalResultsFlow(): Flow<SpeechRecognitionResult> {
        return finalResultsFlow
    }
    
    /**
     * Release the resources used by the speech recognition engine.
     */
    override fun shutdown() {
        try {
            Log.d(TAG, "关闭语音识别服务")
            
            // 确保停止任何可能正在进行的语音识别
            if (isListening) {
                Log.d(TAG, "在关闭前先停止正在进行的语音识别")
                stopListening()
            }
            
            // 在主线程中释放资源
            Handler(Looper.getMainLooper()).post {
                try {
                    Log.d(TAG, "在主线程中释放语音识别资源")
                    speechRecognizer?.setRecognitionListener(null)
                    speechRecognizer?.destroy()
                    Log.d(TAG, "语音识别资源已释放")
                } catch (e: Exception) {
                    Log.e(TAG, "在主线程中释放语音识别资源时出错: ${e.message}")
                }
                
                // 确保状态重置
                speechRecognizer = null
            }
            
            // 立即重置所有状态变量，不等待主线程
            isInitialized = false
            isListening = false
            isContinuousListening = false
            _listeningStateFlow.value = false
            
            Log.d(TAG, "语音识别服务关闭完成")
        } catch (e: Exception) {
            Log.e(TAG, "关闭语音识别服务时出错: ${e.message}")
            
            // 即使出错，也确保资源被标记为释放，防止再次访问已销毁的对象
            speechRecognizer = null
            isInitialized = false
            isListening = false
            _listeningStateFlow.value = false
        }
    }
    
    /**
     * Convert error codes to descriptive messages.
     * @param errorCode The error code from SpeechRecognizer
     * @return A descriptive error message
     */
    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
            SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
            SpeechRecognizer.ERROR_NETWORK -> "网络错误"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
            SpeechRecognizer.ERROR_NO_MATCH -> "未匹配到语音"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙"
            SpeechRecognizer.ERROR_SERVER -> "服务器错误"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音输入"
            else -> "未知错误: $errorCode"
        }
    }
    
    /**
     * Check if an error is recoverable for continuous listening mode.
     * @param errorCode The error code from SpeechRecognizer
     * @return true if the error is recoverable, false otherwise
     */
    private fun isRecoverableError(errorCode: Int): Boolean {
        return when (errorCode) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> true
            else -> false
        }
    }
    
    override fun getListeningStateFlow(): StateFlow<Boolean> = listeningStateFlow
} 