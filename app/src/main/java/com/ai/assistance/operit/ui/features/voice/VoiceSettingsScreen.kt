package com.ai.assistance.operit.ui.features.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.data.model.voice.VoicePreferences

/**
 * Screen for configuring voice preferences.
 * 
 * @param onNavigateBack Callback to navigate back to the previous screen
 * @param modifier Optional modifier
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VoiceInteractionViewModel = viewModel()
) {
    // Collect current preferences
    val preferences by viewModel.voicePreferences.collectAsState()
    
    // Local state for preferences (to avoid UI updates for every change)
    var isTtsEnabled by remember { mutableStateOf(preferences.isTtsEnabled) }
    var isSpeechRecognitionEnabled by remember { mutableStateOf(preferences.isSpeechRecognitionEnabled) }
    var ttsPitch by remember { mutableFloatStateOf(preferences.ttsPitch) }
    var ttsRate by remember { mutableFloatStateOf(preferences.ttsRate) }
    var ttsVolume by remember { mutableFloatStateOf(preferences.ttsVolume) }
    var continuousListening by remember { mutableStateOf(preferences.continuousListening) }
    var useWakeWord by remember { mutableStateOf(preferences.useWakeWord) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Navigate back",
                            tint = Color.White
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // TTS Section
            Text(
                text = "Text-to-Speech",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            // TTS Toggle
            SettingsSwitchItem(
                title = "Enable Text-to-Speech",
                checked = isTtsEnabled,
                onCheckedChange = { value ->
                    isTtsEnabled = value
                    viewModel.updateTtsEnabled(value)
                }
            )
            
            if (isTtsEnabled) {
                // TTS Pitch
                SettingsSliderItem(
                    title = "Voice Pitch",
                    value = ttsPitch,
                    valueRange = 0.5f..2.0f,
                    onValueChange = { ttsPitch = it },
                    onValueChangeFinished = {
                        val newPreferences = preferences.copy(ttsPitch = ttsPitch)
                        viewModel.updateVoicePreferences(newPreferences)
                    },
                    valueDisplay = "%.1f".format(ttsPitch)
                )
                
                // TTS Rate
                SettingsSliderItem(
                    title = "Speech Rate",
                    value = ttsRate,
                    valueRange = 0.5f..2.0f,
                    onValueChange = { ttsRate = it },
                    onValueChangeFinished = {
                        val newPreferences = preferences.copy(ttsRate = ttsRate)
                        viewModel.updateVoicePreferences(newPreferences)
                    },
                    valueDisplay = "%.1f".format(ttsRate)
                )
                
                // TTS Volume
                SettingsSliderItem(
                    title = "Volume",
                    value = ttsVolume,
                    valueRange = 0.0f..1.0f,
                    onValueChange = { ttsVolume = it },
                    onValueChangeFinished = {
                        val newPreferences = preferences.copy(ttsVolume = ttsVolume)
                        viewModel.updateVoicePreferences(newPreferences)
                    },
                    valueDisplay = "%.1f".format(ttsVolume)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Speech Recognition Section
            Text(
                text = "Speech Recognition",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            // Speech Recognition Toggle
            SettingsSwitchItem(
                title = "Enable Speech Recognition",
                checked = isSpeechRecognitionEnabled,
                onCheckedChange = { value ->
                    isSpeechRecognitionEnabled = value
                    viewModel.updateSpeechRecognitionEnabled(value)
                }
            )
            
            if (isSpeechRecognitionEnabled) {
                // Continuous Listening
                SettingsSwitchItem(
                    title = "Continuous Listening",
                    subtitle = "Keep listening after detecting speech",
                    checked = continuousListening,
                    onCheckedChange = { value ->
                        continuousListening = value
                        val newPreferences = preferences.copy(continuousListening = value)
                        viewModel.updateVoicePreferences(newPreferences)
                    }
                )
                
                // Wake Word
                SettingsSwitchItem(
                    title = "Use Wake Word",
                    subtitle = "Activate voice input with a specific word",
                    checked = useWakeWord,
                    onCheckedChange = { value ->
                        useWakeWord = value
                        val newPreferences = preferences.copy(useWakeWord = value)
                        viewModel.updateVoicePreferences(newPreferences)
                    }
                )
            }
        }
    }
}

/**
 * A settings item with a switch toggle.
 */
@Composable
fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * A settings item with a slider for adjusting numeric values.
 */
@Composable
fun SettingsSliderItem(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueDisplay: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueDisplay,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
} 