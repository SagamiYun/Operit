package com.ai.assistance.operit.ui.features.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.voice.dialogue.DialogueManager
import com.ai.assistance.operit.data.model.voice.DialogueTurn

/**
 * Displays the transcription of voice inputs and outputs.
 * Shows both the dialogue history and the current partial speech text.
 * 
 * @param dialogueHistory List of dialogue turns
 * @param partialSpeechText Current partial speech recognition text
 * @param dialogueState Current state of the dialogue system
 * @param modifier Optional modifier
 */
@Composable
fun VoiceTranscriptionView(
    dialogueHistory: List<DialogueTurn>,
    partialSpeechText: String,
    dialogueState: DialogueManager.DialogueState,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    
    // Scroll to bottom when new items are added
    LaunchedEffect(dialogueHistory.size) {
        if (dialogueHistory.isNotEmpty()) {
            listState.animateScrollToItem(dialogueHistory.size - 1)
        }
    }
    
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Dialogue history
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(dialogueHistory) { turn ->
                    DialogueTurnItem(turn = turn)
                }
            }
            
            // Partial speech text (when listening)
            AnimatedVisibility(
                visible = dialogueState == DialogueManager.DialogueState.LISTENING && partialSpeechText.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = partialSpeechText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Displays a single dialogue turn.
 * 
 * @param turn The dialogue turn to display
 * @param modifier Optional modifier
 */
@Composable
fun DialogueTurnItem(
    turn: DialogueTurn,
    modifier: Modifier = Modifier
) {
    val alignment = if (turn.isUser) Alignment.CenterEnd else Alignment.CenterStart
    val backgroundColor = if (turn.isUser) 
        MaterialTheme.colorScheme.primaryContainer 
    else 
        MaterialTheme.colorScheme.secondaryContainer
    val textColor = if (turn.isUser) 
        MaterialTheme.colorScheme.onPrimaryContainer 
    else 
        MaterialTheme.colorScheme.onSecondaryContainer
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (turn.isUser) 16.dp else 4.dp,
                        bottomEnd = if (turn.isUser) 4.dp else 16.dp
                    )
                )
                .background(backgroundColor)
                .padding(12.dp)
        ) {
            Text(
                text = turn.text,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
                fontWeight = FontWeight.Normal
            )
        }
    }
} 