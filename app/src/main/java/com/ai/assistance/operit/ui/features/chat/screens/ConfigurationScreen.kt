package com.ai.assistance.operit.ui.features.chat.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.api.LLMProviderFactory
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.preferences.FreeUsagePreferences
import com.ai.assistance.operit.ui.features.chat.components.config.*
import com.ai.assistance.operit.util.ModelEndPointFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 简洁风格的AI助手配置界面 */
@Composable
fun ConfigurationScreen(
    apiEndpoint: String,
    apiProviderType: LLMProviderFactory.ProviderType,
    apiKey: String,
    modelName: String,
    onApiEndpointChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelNameChange: (String) -> Unit,
    onSaveConfig: () -> Unit,
    onError: (String) -> Unit,
    coroutineScope: CoroutineScope,
    onUseDefault: () -> Unit = {},
    isUsingDefault: Boolean = false,
    onNavigateToChat: () -> Unit = {},
    onNavigateToTokenConfig: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    // 获取Context
    val context = LocalContext.current

    // 初始化免费使用偏好
    val freeUsagePreferences = remember { FreeUsagePreferences(context) }
    val remainingUsages by freeUsagePreferences.remainingUsagesFlow.collectAsState()
    val maxDailyUsage = freeUsagePreferences.getMaxDailyUsage()

    // 状态管理
    var apiKeyInput by remember { mutableStateOf(if (isUsingDefault) "" else apiKey) }
    var showTokenInfoDialog by remember { mutableStateOf(false) }
    var showFreeUsageDialog by remember { mutableStateOf(false) }

    // 使用默认密钥的状态检测
    val isUsingDefaultApiKey by
            remember(apiKeyInput) {
                derivedStateOf {
                    apiKeyInput == ApiPreferences.DEFAULT_API_KEY ||
                            (apiKeyInput.isBlank() && isUsingDefault)
                }
            }

    // 检测用户是否输入了自己的token
    val hasEnteredToken = apiKeyInput.isNotBlank() && apiKeyInput != ApiPreferences.DEFAULT_API_KEY

    // 导航处理
    LaunchedEffect(isUsingDefault) {
        if (!isUsingDefault) {
            onNavigateToChat()
        }
    }

    // 密钥信息对话框
    if (showTokenInfoDialog) {
        TokenInfoDialog(
                onDismiss = { showTokenInfoDialog = false },
                onConfirm = {
                    showTokenInfoDialog = false
                    onNavigateToTokenConfig()
                }
        )
    }

    // 免费使用确认对话框
    if (showFreeUsageDialog) {
        FreeUsageConfirmDialog(
                onDismiss = { showFreeUsageDialog = false },
                onConfirm = {
                    showFreeUsageDialog = false

                    // Record usage
                    if (freeUsagePreferences.canUseFreeTier()) {
                        freeUsagePreferences.recordUsage()

                        // Apply free API settings
                        apiKeyInput = ""

                        onApiKeyChange(ApiPreferences.DEFAULT_API_KEY)
                        onApiEndpointChange(ApiPreferences.DEFAULT_API_ENDPOINT)
                        onModelNameChange(ApiPreferences.DEFAULT_MODEL_NAME)

                        onUseDefault()
                    } else {
                        onError("今日免费次数已用完")
                    }
                },
                remainingUsages = remainingUsages,
                maxDailyUsage = maxDailyUsage
        )
    }

    // 主界面 - 简洁设计
    Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
    ) {
        Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
        ) {
            // 标题和说明
            Text(
                    text = "Operit AI 助手",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 20.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                    text = "我们默认使用DeepSeek API，如需更换请点击下方自定义选项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // API密钥输入框 - 简洁设计
            OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("API密钥", fontSize = 12.sp) },
                    placeholder = { Text("DeepSeek API密钥", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = "API密钥",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    colors =
                            OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor =
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                            ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 主按钮 - 根据输入状态动态变化
            Button(
                    onClick = {
                        if (hasEnteredToken) {
                            // 如果用户已输入token，直接保存配置
                            onApiKeyChange(apiKeyInput)
                            onApiEndpointChange(ApiPreferences.DEFAULT_API_ENDPOINT)
                            onModelNameChange(ApiPreferences.DEFAULT_MODEL_NAME)
                            onSaveConfig()
                        } else {
                            // 否则显示获取token的对话框
                            showTokenInfoDialog = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors =
                            ButtonDefaults.buttonColors(
                                    containerColor =
                                            if (hasEnteredToken)
                                                    MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.primary
                            )
            ) {
                Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                ) {
                    if (hasEnteredToken) {
                        Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                            if (hasEnteredToken) "确认并保存" else "获取Token",
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            color =
                                    if (hasEnteredToken)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 底部选项 - 左右并排
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 左侧 - 薅作者的
                TextButton(
                        onClick = {
                            // 显示确认对话框
                            showFreeUsageDialog = true
                        },
                        modifier = Modifier.weight(1f)
                ) {
                    Text(
                            "薅作者的",
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp
                    )
                }

                // 分隔线
                Divider(
                        modifier =
                                Modifier.height(20.dp)
                                        .width(1.dp)
                                        .align(Alignment.CenterVertically),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // 右侧 - 自定义
                TextButton(
                        onClick = { onNavigateToSettings() },
                        modifier = Modifier.weight(1f)
                ) {
                    Text(
                            "自定义",
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 极简底部提示
            Text(
                    text = "自己的token更加稳定，薅作者的用多了作者会破产，软件就没有更新了",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                    fontSize = 10.sp
            )
        }
    }
}
