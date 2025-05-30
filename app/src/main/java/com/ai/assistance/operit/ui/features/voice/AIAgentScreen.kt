package com.ai.assistance.operit.ui.features.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VoiceChat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.core.voice.dialogue.DialogueManager

/**
 * A screen for voice interactions with the AI assistant.
 * Implements a modern, visually appealing interface with full-screen experience.
 *
 * @param onClose Callback for when the user closes the AIAgent screen
 * @param onNavigateToSettings Callback to navigate to voice settings
 * @param modifier Optional modifier
 * @param viewModel The AIAgentViewModel that manages voice interaction state
 */
@Composable
fun AIAgentScreen(
    onClose: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AIAgentViewModel = viewModel()
) {
    // Collect states from the ViewModel
    val dialogueState by viewModel.dialogueState.collectAsState()
    val partialSpeechText by viewModel.partialSpeechText.collectAsState()
    val dialogueHistory by viewModel.dialogueHistory.collectAsState()
    val serviceConnected by viewModel.serviceConnected.collectAsState()
    val amplitude by viewModel.visualizationAmplitude.collectAsState()
    val isListening by viewModel.isListening.collectAsState()

    // Determine if the waveform should be visible
    val showWaveform = dialogueState != DialogueManager.DialogueState.IDLE || dialogueHistory.isNotEmpty()

    // Modern gradient background colors - dark blue gradient with burgundy top
    val gradientColors = listOf(
        Color(0xFF912B3A), // Dark burgundy/red at top
        Color(0xFF112554), // Dark blue in middle
        Color(0xFF1A365D)  // Slightly lighter blue at bottom
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = gradientColors
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.VoiceChat,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            
            Text(
                text = "Agent",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                color = Color.White
            )
        }

        // Dialogue history and partial speech text area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 60.dp, bottom = 140.dp) // Padding for header and controls
                .padding(horizontal = 16.dp)
        ) {
            if (dialogueHistory.isNotEmpty() || partialSpeechText.isNotEmpty()) {
                VoiceTranscriptionView(
                    dialogueHistory = dialogueHistory,
                    partialSpeechText = partialSpeechText,
                    dialogueState = dialogueState,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (!serviceConnected) {
                // Show connecting message if service is not connected
                Text(
                    text = "Connecting to voice service...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(32.dp)
                        .align(Alignment.Center)
                )
            } else {
                // Show initial instruction
                Text(
                    text = "Tap the microphone to start speaking",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(32.dp)
                        .align(Alignment.Center)
                )
            }
        }

        // Bottom controls area with wave visualization
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            // Add the waveform visualization
            if (showWaveform) {
                VoiceWaveform(
                    amplitude = amplitude,
                    isActive = isListening,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                )
            }
            
            // Bottom control bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp, start = 24.dp, end = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Camera button (placeholder, no functionality)
                IconButton(
                    onClick = { /* No functionality */ },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF333333))
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Camera",
                        tint = Color.White
                    )
                }
                
                // Voice control button (main button)
                VoiceControlButton(
                    dialogueState = dialogueState,
                    onStartListening = { viewModel.startListening() },
                    onStopListening = { viewModel.stopListening() },
                    onStopSpeaking = { viewModel.stopSpeaking() },
                    modifier = Modifier.size(64.dp)
                )
                
                // Close button
                IconButton(
                    onClick = {
//                        viewModel.clearDialogueHistory()
//                        viewModel.shutdownDialogueManager()
                        onClose()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF333333))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }

            // Status indicator - shows current state
            if (dialogueState != DialogueManager.DialogueState.IDLE && serviceConnected) {
                val statusText = when (dialogueState) {
                    DialogueManager.DialogueState.LISTENING -> "Listening..."
                    DialogueManager.DialogueState.PROCESSING -> "Processing..."
                    DialogueManager.DialogueState.SPEAKING -> "Speaking..."
                    else -> ""
                }

                if (statusText.isNotEmpty()) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(bottom = 8.dp)
                    )
                }
            }
        }
    }
} 