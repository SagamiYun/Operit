package com.ai.assistance.operit.data.model.voice

/**
 * Represents a single turn in a dialogue conversation.
 * A turn can be either from the user or from the AI.
 *
 * @property isUser Whether this turn is from the user (true) or the AI (false)
 * @property text The text content of the turn
 * @property timestamp The timestamp when the turn was created (in milliseconds since epoch)
 * @property audioUri Optional URI to the audio recording of this turn (if available)
 */
data class DialogueTurn(
    val isUser: Boolean,
    val text: String,
    val timestamp: Long,
    val audioUri: String? = null
) {
    /**
     * Get a unique identifier for this turn based on timestamp and speaker.
     */
    val id: String
        get() = "${if (isUser) "user" else "ai"}_${timestamp}"
} 