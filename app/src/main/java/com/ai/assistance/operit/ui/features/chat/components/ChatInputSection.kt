package com.ai.assistance.operit.ui.features.chat.components

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.data.model.AttachmentInfo
import com.ai.assistance.operit.ui.common.animations.SimpleAnimatedVisibility

@Composable
fun ChatInputSection(
        userMessage: String,
        onUserMessageChange: (String) -> Unit,
        onSendMessage: () -> Unit,
        onCancelMessage: () -> Unit,
        isLoading: Boolean,
        isProcessingInput: Boolean = false,
        inputProcessingMessage: String = "",
        allowTextInputWhileProcessing: Boolean = false,
        onAttachmentRequest: (String) -> Unit = {},
        attachments: List<AttachmentInfo> = emptyList(),
        onRemoveAttachment: (String) -> Unit = {},
        onInsertAttachment: (AttachmentInfo) -> Unit = {},
        onAttachScreenContent: () -> Unit = {},
        onAttachNotifications: () -> Unit = {},
        onAttachLocation: () -> Unit = {},
        onAttachProblemMemory: (String, String) -> Unit = { _, _ -> },
        hasBackgroundImage: Boolean = false,
        modifier: Modifier = Modifier,
        externalAttachmentPanelState: Boolean? = null,
        onAttachmentPanelStateChange: ((Boolean) -> Unit)? = null,
        onVoiceRecognitionRequest: () -> Unit = {},
        isListening: Boolean = false
) {
        val modernTextStyle = TextStyle(fontSize = 13.sp, lineHeight = 16.sp)

        val isProcessing = isLoading || isProcessingInput

        // 控制附件面板的展开状态 - 使用外部状态或本地状态
        val (showAttachmentPanel, setShowAttachmentPanel) =
                androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(
                                externalAttachmentPanelState ?: false
                        )
                }

        // 当外部状态变化时更新本地状态
        androidx.compose.runtime.LaunchedEffect(externalAttachmentPanelState) {
                externalAttachmentPanelState?.let { setShowAttachmentPanel(it) }
        }

        // 当本地状态改变时通知外部
        androidx.compose.runtime.LaunchedEffect(showAttachmentPanel) {
                onAttachmentPanelStateChange?.invoke(showAttachmentPanel)
        }

        Surface(
                color =
                        if (hasBackgroundImage)
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        else MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = modifier.shadow(4.dp)
        ) {
                Column {
                        // Input processing indicator
                        SimpleAnimatedVisibility(visible = isProcessingInput) {
                                val progressColor =
                                        when {
                                                inputProcessingMessage.contains("工具执行后") ->
                                                        MaterialTheme.colorScheme.tertiary.copy(
                                                                alpha = 0.8f
                                                        )
                                                inputProcessingMessage.contains("Connecting") ||
                                                        inputProcessingMessage.contains("连接") ->
                                                        MaterialTheme.colorScheme.tertiary
                                                inputProcessingMessage.contains("Receiving") ||
                                                        inputProcessingMessage.contains("响应") ->
                                                        MaterialTheme.colorScheme.secondary
                                                else -> MaterialTheme.colorScheme.primary
                                        }

                                val progressValue =
                                        if (inputProcessingMessage.contains("准备")) 0.3f
                                        else if (inputProcessingMessage.contains("连接")) 0.6f else 1f

                                SimpleLinearProgressIndicator(
                                        progress = progressValue,
                                        modifier = Modifier.fillMaxWidth(),
                                        color = progressColor
                                )

                                if (inputProcessingMessage.isNotBlank()) {
                                        Row(
                                                modifier =
                                                        Modifier.fillMaxWidth()
                                                                .padding(
                                                                        horizontal = 16.dp,
                                                                        vertical = 4.dp
                                                                ),
                                                verticalAlignment = Alignment.CenterVertically
                                        ) {
                                                Text(
                                                        text = inputProcessingMessage,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color =
                                                                MaterialTheme.colorScheme.onSurface
                                                                        .copy(alpha = 0.8f),
                                                        modifier = Modifier.weight(1f)
                                                )
                                        }
                                }
                        }

                        // Attachment chips row - only show if there are attachments
                        if (attachments.isNotEmpty()) {
                                LazyRow(
                                        modifier =
                                                Modifier.fillMaxWidth()
                                                        .padding(
                                                                horizontal = 16.dp,
                                                                vertical = 4.dp
                                                        ),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                        items(attachments) { attachment ->
                                                AttachmentChip(
                                                        attachmentInfo = attachment,
                                                        onRemove = {
                                                                onRemoveAttachment(
                                                                        attachment.filePath
                                                                )
                                                        },
                                                        onInsert = {
                                                                onInsertAttachment(attachment)
                                                        }
                                                )
                                        }
                                }
                        }

                        Row(
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .padding(horizontal = 16.dp)
                                                .padding(top = 8.dp, bottom = 8.dp)
                                                .wrapContentHeight(),
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                                // Input field (保持原有高度)
                                OutlinedTextField(
                                        value = userMessage,
                                        onValueChange = onUserMessageChange,
                                        placeholder = {
                                                Text("请输入您的问题...", style = modernTextStyle)
                                        },
                                        modifier = Modifier.weight(1f).heightIn(min = 38.dp),
                                        textStyle = modernTextStyle,
                                        maxLines = 5,
                                        minLines = 1,
                                        singleLine = false,
                                        keyboardOptions =
                                                KeyboardOptions(imeAction = ImeAction.Default),
                                        keyboardActions = KeyboardActions(),
                                        colors =
                                                OutlinedTextFieldDefaults.colors(
                                                        focusedBorderColor =
                                                                MaterialTheme.colorScheme.primary,
                                                        unfocusedBorderColor =
                                                                MaterialTheme.colorScheme.outline
                                                ),
                                        shape = RoundedCornerShape(16.dp),
                                        enabled = !isProcessing || allowTextInputWhileProcessing
                                )

                                // Spacing between text field and buttons
                                Spacer(modifier = Modifier.width(8.dp))

                                // Voice recognition button
                                val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isListening) 
                                                MaterialTheme.colorScheme.primary
                                            else 
                                                MaterialTheme.colorScheme.primaryContainer,
                                            CircleShape
                                        )
                                        .clickable(
                                            enabled = !isProcessing,
                                            onClick = { 
                                                Log.d("ChatInputSection", "语音按钮被点击，当前isListening: $isListening") 
                                                // 清除焦点，避免键盘干扰
                                                focusManager.clearFocus()
                                                
                                                // 调用语音识别回调
                                                onVoiceRecognitionRequest()
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Remember the previous listening state to animate transitions
                                    val previousListening = remember { mutableStateOf(isListening) }
                                    val animatedColor by animateColorAsState(
                                        targetValue = if (isListening) 
                                                       MaterialTheme.colorScheme.onPrimary
                                                     else 
                                                       MaterialTheme.colorScheme.onPrimaryContainer,
                                        animationSpec = tween(300),
                                        label = "micIconColor"
                                    )
                                    
                                    // Detect state changes for additional feedback
                                    LaunchedEffect(isListening) {
                                        if (previousListening.value && !isListening) {
                                            // Transitioning from listening to not listening
                                            // Could add haptic feedback here if desired
                                        }
                                        previousListening.value = isListening
                                    }
                                    
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = if (isListening) "停止语音输入" else "语音输入",
                                        tint = animatedColor
                                    )
                                    
                                    // Add pulsing animation when listening
                                    if (isListening) {
                                        val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(
                                            label = "micPulse"
                                        )
                                        val scale by infiniteTransition.animateFloat(
                                            initialValue = 1f,
                                            targetValue = 1.2f,
                                            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                                animation = androidx.compose.animation.core.tween(700),
                                                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                                            ),
                                            label = "pulseAnimation"
                                        )
                                        
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .scale(scale)
                                                .border(
                                                    width = 2.dp,
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                                    shape = CircleShape
                                                )
                                        )
                                    }
                                }

                                // Spacing between buttons
                                Spacer(modifier = Modifier.width(8.dp))

                                // Attachment button (+ 按钮) - 确保圆形
                                Box(
                                        modifier =
                                                Modifier.size(42.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                                if (showAttachmentPanel)
                                                                        MaterialTheme.colorScheme
                                                                                .primary
                                                                else
                                                                        MaterialTheme.colorScheme
                                                                                .surfaceVariant
                                                        )
                                                        .clickable(
                                                                enabled = !isProcessing,
                                                                onClick = {
                                                                        setShowAttachmentPanel(
                                                                                !showAttachmentPanel
                                                                        )
                                                                }
                                                        ),
                                        contentAlignment = Alignment.Center
                                ) {
                                        Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = "添加附件",
                                                tint =
                                                        if (showAttachmentPanel)
                                                                MaterialTheme.colorScheme.onPrimary
                                                        else
                                                                MaterialTheme.colorScheme
                                                                        .onSurfaceVariant,
                                                modifier = Modifier.size(22.dp)
                                        )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Send button (发送按钮) - 确保圆形
                                Box(
                                        modifier =
                                                Modifier.size(42.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                                when {
                                                                        isProcessing ->
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .error
                                                                        userMessage.isNotBlank() ||
                                                                                attachments
                                                                                        .isNotEmpty() ->
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .primary
                                                                        else ->
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .primary
                                                                                        .copy(
                                                                                                alpha =
                                                                                                        0.5f
                                                                                        )
                                                                }
                                                        )
                                                        .clickable(
                                                                enabled =
                                                                        if (isProcessing) true
                                                                        else
                                                                                userMessage
                                                                                        .isNotBlank() ||
                                                                                        attachments
                                                                                                .isNotEmpty(),
                                                                onClick = {
                                                                        if (isProcessing) {
                                                                                onCancelMessage()
                                                                        } else {
                                                                                onSendMessage()
                                                                                // 发送消息后关闭附件面板
                                                                                setShowAttachmentPanel(
                                                                                        false
                                                                                )
                                                                        }
                                                                }
                                                        ),
                                        contentAlignment = Alignment.Center
                                ) {
                                        Icon(
                                                imageVector =
                                                        if (isProcessing) Icons.Default.Close
                                                        else Icons.Default.Send,
                                                contentDescription =
                                                        if (isProcessing) "取消" else "发送",
                                                tint =
                                                        if (isProcessing)
                                                                MaterialTheme.colorScheme.onError
                                                        else MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(22.dp)
                                        )
                                }
                        }

                        // 附件选择面板 - 移动到输入框下方
                        AttachmentSelectorPanel(
                                visible = showAttachmentPanel,
                                onAttachImage = { filePath ->
                                        // 传递文件路径给外部处理函数
                                        onAttachmentRequest(filePath)
                                },
                                onAttachFile = { filePath ->
                                        // 传递文件路径给外部处理函数
                                        onAttachmentRequest(filePath)
                                },
                                onAttachScreenContent = onAttachScreenContent,
                                onAttachNotifications = onAttachNotifications,
                                onAttachLocation = onAttachLocation,
                                onAttachProblemMemory = onAttachProblemMemory,
                                userQuery = userMessage,
                                onDismiss = { setShowAttachmentPanel(false) }
                        )
                }
        }
}

@Composable
fun AttachmentChip(attachmentInfo: AttachmentInfo, onRemove: () -> Unit, onInsert: () -> Unit) {
        val isImage = attachmentInfo.mimeType.startsWith("image/")
        val icon: ImageVector = if (isImage) Icons.Default.Image else Icons.Default.Description

        Surface(
                modifier =
                        Modifier.height(26.dp)
                                .border(
                                        width = 1.dp,
                                        color =
                                                MaterialTheme.colorScheme.outline.copy(
                                                        alpha = 0.5f
                                                ),
                                        shape = RoundedCornerShape(13.dp)
                                ),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ) {
                Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                        Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        Text(
                                text = attachmentInfo.fileName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 80.dp)
                        )

                        Spacer(modifier = Modifier.width(2.dp))

                        IconButton(onClick = onInsert, modifier = Modifier.size(14.dp)) {
                                Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "插入附件",
                                        modifier = Modifier.size(10.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                )
                        }

                        Spacer(modifier = Modifier.width(2.dp))

                        IconButton(onClick = onRemove, modifier = Modifier.size(14.dp)) {
                                Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "删除附件",
                                        modifier = Modifier.size(10.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                        }
                }
        }
}
