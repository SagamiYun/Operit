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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.voice.dialogue.DialogueManager
import com.ai.assistance.operit.data.model.voice.DialogueTurn

/**
 * Displays the transcription of voice inputs and outputs.
 * Shows both the dialogue history and the current partial speech text.
 * Styled to match the "Live" interface design.
 * 
 * @param dialogueHistory List of dialogue turns
 * @param partialSpeechText Current partial speech recognition text
 * @param dialogueState Current state of the dialogue system
 * @param currentResponse Current AI response text (not yet in dialogue history)
 * @param modifier Optional modifier
 */
@Composable
fun VoiceTranscriptionView(
    dialogueHistory: List<DialogueTurn>,
    partialSpeechText: String,
    dialogueState: DialogueManager.DialogueState,
    currentResponse: String = "",
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    
    // Scroll to bottom when new items are added
    LaunchedEffect(dialogueHistory.size, currentResponse) {
        if (dialogueHistory.isNotEmpty() || currentResponse.isNotEmpty()) {
            listState.animateScrollToItem(dialogueHistory.size)
        }
    }
    
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A1A1A).copy(alpha = 0.7f))
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
                
                // 当前AI响应（如果有）
                if (currentResponse.isNotEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = 16.dp,
                                            topEnd = 16.dp,
                                            bottomStart = 4.dp,
                                            bottomEnd = 16.dp
                                        )
                                    )
                                    .background(Color(0xFF2563EB))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = currentResponse,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White,
                                    fontWeight = FontWeight.Normal
                                )
                            }
                        }
                    }
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
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF2563EB).copy(alpha = 0.7f),
                                    Color(0xFF3B82F6).copy(alpha = 0.7f)
                                )
                            )
                        )
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = partialSpeechText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Displays a single dialogue turn with modern styling.
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
    
    // Different styling for user vs AI messages
    val backgroundColor = if (turn.isUser) 
        Color(0xFF0D9488) // Teal for user
    else 
        Color(0xFF2563EB) // Blue for AI
    
    val bubbleShape = if (turn.isUser) {
        RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 16.dp,
            bottomEnd = 4.dp
        )
    } else {
        RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 4.dp,
            bottomEnd = 16.dp
        )
    }
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .clip(bubbleShape)
                .background(backgroundColor)
                .padding(12.dp)
        ) {
            Text(
                text = turn.text,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = FontWeight.Normal
            )
        }
    }
} 