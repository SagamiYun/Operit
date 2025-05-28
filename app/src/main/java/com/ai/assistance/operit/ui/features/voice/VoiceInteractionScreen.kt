package com.ai.assistance.operit.ui.features.voice

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Main screen for voice interactions.
 * Displays the voice control button, transcription view, and status information.
 * 
 * @param onNavigateToSettings Callback to navigate to the voice settings screen
 * @param modifier Optional modifier
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceInteractionScreen(
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VoiceInteractionViewModel = viewModel()
) {
    // Collect states
    val dialogueState by viewModel.dialogueState.collectAsState()
    val partialSpeechText by viewModel.partialSpeechText.collectAsState()
    val dialogueHistory by viewModel.dialogueHistory.collectAsState()
    val serviceConnected by viewModel.serviceConnected.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice Assistant") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                ),
                actions = {
                    // Clear history button
                    IconButton(onClick = { viewModel.clearDialogueHistory() }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear history",
                            tint = Color.White
                        )
                    }
                    
                    // Settings button
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.White
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Main content area - Voice transcription
            if (dialogueHistory.isNotEmpty()) {
                VoiceTranscriptionView(
                    dialogueHistory = dialogueHistory,
                    partialSpeechText = partialSpeechText,
                    dialogueState = dialogueState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.Center)
                )
            } else {
                // Empty state message
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (serviceConnected) 
                            "Tap the microphone button to start speaking" 
                        else 
                            "Connecting to voice service...",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    if (!serviceConnected) {
                        Text(
                            text = "Please wait while the voice service initializes",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Voice control button at the bottom
            FloatingActionButton(
                onClick = {
                    when (dialogueState) {
                        com.ai.assistance.operit.core.voice.dialogue.DialogueManager.DialogueState.IDLE -> 
                            viewModel.startListening()
                        com.ai.assistance.operit.core.voice.dialogue.DialogueManager.DialogueState.LISTENING -> 
                            viewModel.stopListening()
                        com.ai.assistance.operit.core.voice.dialogue.DialogueManager.DialogueState.SPEAKING -> 
                            viewModel.stopSpeaking()
                        else -> { /* Processing - do nothing */ }
                    }
                },
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.BottomCenter),
                containerColor = when (dialogueState) {
                    com.ai.assistance.operit.core.voice.dialogue.DialogueManager.DialogueState.IDLE -> 
                        MaterialTheme.colorScheme.primary
                    com.ai.assistance.operit.core.voice.dialogue.DialogueManager.DialogueState.LISTENING -> 
                        MaterialTheme.colorScheme.error
                    com.ai.assistance.operit.core.voice.dialogue.DialogueManager.DialogueState.PROCESSING -> 
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    com.ai.assistance.operit.core.voice.dialogue.DialogueManager.DialogueState.SPEAKING -> 
                        MaterialTheme.colorScheme.tertiary
                },
                contentColor = Color.White
            ) {
                when (dialogueState) {
                    com.ai.assistance.operit.core.voice.dialogue.DialogueManager.DialogueState.IDLE -> 
                        Icon(Icons.Default.Mic, "Start listening")
                    com.ai.assistance.operit.core.voice.dialogue.DialogueManager.DialogueState.LISTENING -> 
                        Icon(Icons.Default.MicOff, "Stop listening")
                    com.ai.assistance.operit.core.voice.dialogue.DialogueManager.DialogueState.PROCESSING -> 
                        Icon(Icons.Default.MicOff, "Processing")
                    com.ai.assistance.operit.core.voice.dialogue.DialogueManager.DialogueState.SPEAKING -> 
                        Icon(Icons.Default.Stop, "Stop speaking")
                }
            }
        }
    }
} 