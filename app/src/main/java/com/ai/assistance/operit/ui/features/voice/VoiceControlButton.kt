package com.ai.assistance.operit.ui.features.voice

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.voice.dialogue.DialogueManager

/**
 * A button for controlling voice interactions.
 * Changes appearance based on the current dialogue state.
 * 
 * @param dialogueState The current state of the dialogue system
 * @param onStartListening Callback when the user wants to start listening
 * @param onStopListening Callback when the user wants to stop listening
 * @param onStopSpeaking Callback when the user wants to stop the AI from speaking
 * @param modifier Optional modifier for the button
 */
@Composable
fun VoiceControlButton(
    dialogueState: DialogueManager.DialogueState,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onStopSpeaking: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Determine colors and icon based on state
    val (backgroundColor, borderColor, icon) = when (dialogueState) {
        DialogueManager.DialogueState.IDLE -> Triple(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.primary,
            Icons.Default.Mic
        )
        DialogueManager.DialogueState.LISTENING -> Triple(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary,
            Icons.Default.MicOff
        )
        DialogueManager.DialogueState.PROCESSING -> Triple(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            MaterialTheme.colorScheme.primary,
            Icons.Default.MicOff
        )
        DialogueManager.DialogueState.SPEAKING -> Triple(
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.tertiary,
            Icons.Default.Stop
        )
    }
    
    // Animation for the scale when active
    val scale by animateFloatAsState(
        targetValue = if (dialogueState == DialogueManager.DialogueState.IDLE) 1f else 1.1f,
        label = "scaleAnimation"
    )
    
    // Determine click action based on state
    val onClick = {
        when (dialogueState) {
            DialogueManager.DialogueState.IDLE -> onStartListening()
            DialogueManager.DialogueState.LISTENING -> onStopListening()
            DialogueManager.DialogueState.SPEAKING -> onStopSpeaking()
            else -> { /* Do nothing during processing */ }
        }
    }
    
    // Create the button
    Box(
        modifier = modifier
            .size(56.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(backgroundColor)
            .border(2.dp, borderColor, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(bounded = false, color = Color.White),
                onClick = onClick,
                enabled = dialogueState != DialogueManager.DialogueState.PROCESSING
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = when (dialogueState) {
                DialogueManager.DialogueState.IDLE -> "Start listening"
                DialogueManager.DialogueState.LISTENING -> "Stop listening"
                DialogueManager.DialogueState.PROCESSING -> "Processing speech"
                DialogueManager.DialogueState.SPEAKING -> "Stop speaking"
            },
            tint = Color.White
        )
    }
} 