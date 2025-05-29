package com.ai.assistance.operit.ui.features.voice

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * A component that visualizes voice input as a waveform with a gradient effect.
 * Styled to match the "Live" interface shown in the reference image.
 * 
 * @param amplitude Current amplitude of the audio (0.0 to 1.0)
 * @param isActive Whether the waveform is active (should animate)
 * @param modifier Optional modifier
 * @param gradientColors Colors for the gradient background
 */
@Composable
fun VoiceWaveform(
    amplitude: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    gradientColors: List<Color> = listOf(
        Color(0xFF60A5FA), // Light blue
        Color(0xFF2563EB), // Medium blue
        Color(0xFF1E3A8A), // Dark blue
        Color(0xFF0F1A2E)  // Very dark blue/black
    )
) {
    // Animate the amplitude for smoother transitions
    val animatedAmplitude by animateFloatAsState(
        targetValue = if (isActive) amplitude.coerceIn(0.1f, 1f) else 0.05f,
        animationSpec = tween(durationMillis = 300),
        label = "amplitudeAnimation"
    )
    
    Box(
        modifier = modifier
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF112554), // Dark blue matching AIAgentScreen middle color
                        Color(0xFF1A365D)  // Slightly lighter blue matching AIAgentScreen bottom color
                    )
                )
            )
    ) {
        // Draw the waveform
        Canvas(
            modifier = Modifier
                .fillMaxSize()
        ) {
            val width = size.width
            val height = size.height
            val centerY = height * 0.7f // Position wave higher in the box
            
            // Create the wave path
            val wavePath = Path().apply {
                // Start at the bottom left
                moveTo(0f, height)
                lineTo(0f, centerY)
                
                // Number of waves across the width
                val waveCount = 3
                
                // Draw a sine wave
                for (i in 0..width.toInt()) {
                    val x = i.toFloat()
                    
                    // Calculate amplitude-modulated sine wave with time-based animation
                    val frequency = (2 * Math.PI * waveCount) / width
                    val phase = System.currentTimeMillis() % 2000 / 2000f * 2 * Math.PI
                    val y = centerY + sin(x * frequency + phase) * (50.dp.toPx() * animatedAmplitude)
                    
                    lineTo(x, y.toFloat())
                }
                
                // Complete the path to the bottom right
                lineTo(width, centerY)
                lineTo(width, height)
                close()
            }
            
            // Draw the filled wave with gradient
            drawPath(
                path = wavePath,
                brush = Brush.verticalGradient(
                    colors = gradientColors,
                    startY = centerY - 50.dp.toPx(),
                    endY = height
                )
            )
            
            // Add a second wave with offset for more complex effect
            if (isActive && animatedAmplitude > 0.2f) {
                val secondWavePath = Path().apply {
                    // Start at the bottom left
                    moveTo(0f, height)
                    lineTo(0f, centerY + 10.dp.toPx())
                    
                    // Different wave frequency
                    val waveCount = 5
                    
                    // Draw a sine wave with different phase
                    for (i in 0..width.toInt()) {
                        val x = i.toFloat()
                        
                        val frequency = (2 * Math.PI * waveCount) / width
                        val phase = System.currentTimeMillis() % 1500 / 1500f * 2 * Math.PI
                        val y = centerY + 10.dp.toPx() + 
                            sin(x * frequency + phase + Math.PI / 2) * (30.dp.toPx() * animatedAmplitude)
                        
                        lineTo(x, y.toFloat())
                    }
                    
                    // Complete the path
                    lineTo(width, centerY + 10.dp.toPx())
                    lineTo(width, height)
                    close()
                }
                
                // Draw with semi-transparent gradient
                drawPath(
                    path = secondWavePath,
                    brush = Brush.verticalGradient(
                        colors = gradientColors.map { it.copy(alpha = 0.7f) },
                        startY = centerY - 30.dp.toPx(),
                        endY = height
                    )
                )
            }
        }
    }
} 