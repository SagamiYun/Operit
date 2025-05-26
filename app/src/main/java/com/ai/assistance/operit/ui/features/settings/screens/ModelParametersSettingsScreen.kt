package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.animation.AnimatedVisibility
import com.ai.assistance.operit.api.LLMProviderFactory
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ParameterCategory
import com.ai.assistance.operit.data.model.ParameterValueType
import com.ai.assistance.operit.data.preferences.ApiPreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelParametersSettingsScreen() {
    val context = LocalContext.current
    val apiPreferences = remember { ApiPreferences(context) }
    val scope = rememberCoroutineScope()
    var showSaveSuccessMessage by remember { mutableStateOf(false) }
    var showToggleSaveMessage by remember { mutableStateOf(false) }
    var toggleSaveMessage by remember { mutableStateOf("") }
    
    // 状态：是否显示添加参数对话框
    var showAddParameterDialog by remember { mutableStateOf(false) }
    
    // State for all parameters
    var parameters by remember { mutableStateOf<List<ModelParameter<*>>>(emptyList()) }
    
    // Get the current provider type
    val providerType = apiPreferences.providerTypeFlow.collectAsState(
        initial = ApiPreferences.DEFAULT_PROVIDER_TYPE
    ).value
    
    // Convert provider type string to enum
    val currentProviderType = try {
        LLMProviderFactory.ProviderType.valueOf(providerType)
    } catch (e: Exception) {
        LLMProviderFactory.ProviderType.DEEPSEEK
    }
    
    // Load parameters from preferences
    LaunchedEffect(Unit) {
        parameters = apiPreferences.getAllModelParameters()
    }

    // Function to save parameters 
    val saveParameters = { newParameters: List<ModelParameter<*>> ->
        scope.launch {
            try {
                // Validate all parameters
                val hasErrors = newParameters.any { param ->
                    when (param.valueType) {
                        ParameterValueType.INT -> {
                            val value = param.currentValue as Int
                            val minValue = (param.minValue as Int?) ?: Int.MIN_VALUE
                            val maxValue = (param.maxValue as Int?) ?: Int.MAX_VALUE
                            value < minValue || value > maxValue
                        }
                        ParameterValueType.FLOAT -> {
                            val value = param.currentValue as Float
                            val minValue = (param.minValue as Float?) ?: Float.MIN_VALUE
                            val maxValue = (param.maxValue as Float?) ?: Float.MAX_VALUE
                            value < minValue || value > maxValue
                        }
                        else -> false
                    }
                }

                if (!hasErrors) {
                    // Save parameters using the new list-based approach
                    apiPreferences.saveModelParameters(newParameters)
                    showSaveSuccessMessage = true
                }
            } catch (e: Exception) {
                // Handle any exceptions
                e.printStackTrace()
            }
        }
    }
    
    // Helper function to update parameter value
    val updateParameterValue = { parameter: ModelParameter<*>, newValue: Any ->
        val newParameters = parameters.map { p ->
            if (p.id == parameter.id) {
                when (p.valueType) {
                    ParameterValueType.INT -> {
                        val intParam = p as ModelParameter<Int>
                        intParam.copy(currentValue = newValue as Int)
                    }
                    ParameterValueType.FLOAT -> {
                        val floatParam = p as ModelParameter<Float>
                        floatParam.copy(currentValue = newValue as Float)
                    }
                    ParameterValueType.STRING -> {
                        val stringParam = p as ModelParameter<String>
                        stringParam.copy(currentValue = newValue as String)
                    }
                    ParameterValueType.BOOLEAN -> {
                        val boolParam = p as ModelParameter<Boolean>
                        boolParam.copy(currentValue = newValue as Boolean)
                    }
                }
            } else {
                p
            }
        }
        parameters = newParameters
    }
    
    // Helper function to toggle parameter enabled state
    val toggleParameter = { parameter: ModelParameter<*>, isEnabled: Boolean ->
        val newParameters = parameters.map { p ->
            if (p.id == parameter.id) {
                when (p.valueType) {
                    ParameterValueType.INT -> {
                        val intParam = p as ModelParameter<Int>
                        intParam.copy(isEnabled = isEnabled)
                    }
                    ParameterValueType.FLOAT -> {
                        val floatParam = p as ModelParameter<Float>
                        floatParam.copy(isEnabled = isEnabled)
                    }
                    ParameterValueType.STRING -> {
                        val stringParam = p as ModelParameter<String>
                        stringParam.copy(isEnabled = isEnabled)
                    }
                    ParameterValueType.BOOLEAN -> {
                        val boolParam = p as ModelParameter<Boolean>
                        boolParam.copy(isEnabled = isEnabled)
                    }
                }
            } else {
                p
            }
        }
        parameters = newParameters
        
        // Automatically save when toggling
        scope.launch {
            try {
                apiPreferences.saveModelParameters(newParameters)
                toggleSaveMessage = "${parameter.name}已${if (isEnabled) "启用" else "禁用"}并保存"
                showToggleSaveMessage = true
                kotlinx.coroutines.delay(2000)
                showToggleSaveMessage = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    // 添加自定义参数处理函数
    val addCustomParameter = { parameter: ModelParameter<*> ->
        val newParameters = parameters + parameter
        parameters = newParameters
        
        // 保存参数
        scope.launch {
            apiPreferences.saveModelParameters(newParameters)
            toggleSaveMessage = "已添加并保存参数: ${parameter.name}"
            showToggleSaveMessage = true
            kotlinx.coroutines.delay(2000)
            showToggleSaveMessage = false
        }
    }

    // 如果显示添加参数对话框，则渲染对话框
    if (showAddParameterDialog) {
        AddParameterDialog(
            onDismiss = { showAddParameterDialog = false },
            onAddParameter = { param ->
                addCustomParameter(param)
                showAddParameterDialog = false
            },
            providerType = currentProviderType
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        // Screen title and explanation
        Text(
            text = "模型参数设置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        // Show provider type banner
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "当前LLM服务提供商: $providerType",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "显示适用于${providerType}服务的模型参数",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }
        
        Text(
            text = "启用参数时，该参数将被包含在API请求中。默认情况下，所有参数均处于关闭状态，使用模型的默认值。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // 添加自定义参数按钮 - 移动到顶部，更加醒目
        Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAddParameterDialog = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "添加自定义参数",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "创建自定义API参数并添加到请求中",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                }
                
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "添加",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        )
                        .padding(8.dp)
                )
            }
        }
        
        // Filter parameters based on provider type and show relevant ones
        // For custom parameters, always show them regardless of provider
        var filteredParams = parameters.filter { it.isCustom == true }
        
        // Add provider-specific parameters
        when (currentProviderType) {
            LLMProviderFactory.ProviderType.DEEPSEEK -> {
                // For DeepSeek, show all standard parameters (that are not custom)
                filteredParams = filteredParams + parameters.filter { !it.isCustom }
            }
            LLMProviderFactory.ProviderType.GEMINI -> {
                // For Gemini, only show temperature, maxOutputTokens, and topP
                filteredParams = filteredParams + parameters.filter { !it.isCustom && 
                    (it.apiName == "temperature" || 
                     it.apiName == "max_tokens" || 
                     it.apiName == "top_p" ||
                     // If there are Gemini-specific parameters, add them here
                     it.apiName.startsWith("gemini_")) 
                }
            }
        }

        // Group parameters by category
        val generationParameters = filteredParams.filter { it.category == ParameterCategory.GENERATION }
        val creativityParameters = filteredParams.filter { it.category == ParameterCategory.CREATIVITY }
        val repetitionParameters = filteredParams.filter { it.category == ParameterCategory.REPETITION }
        val advancedParameters = filteredParams.filter { it.category == ParameterCategory.ADVANCED }
        // Custom parameters - any that are added by the user (Note: technically they could be in any category)
        val customParameters = filteredParams.filter { it.isCustom == true }

        // Validation errors map
        val parameterErrors = remember { mutableStateMapOf<String, String>() }

        // Generation parameters section
        if (generationParameters.isNotEmpty()) {
            ModelParamSectionTitle(title = "生成参数", icon = Icons.Default.AutoFixHigh)
            generationParameters.forEach { parameter ->
                ParameterSectionWithToggle(
                    parameter = parameter,
                    onValueChange = { newValue -> updateParameterValue(parameter, newValue) },
                    onToggle = { isEnabled -> toggleParameter(parameter, isEnabled) },
                    error = parameterErrors[parameter.id],
                    onErrorChange = { error -> 
                        if (error != null) {
                            parameterErrors[parameter.id] = error
                        } else {
                            parameterErrors.remove(parameter.id)
                        }
                    }
                )
                
                // Temperature recommendation row for temperature parameter
                if (parameter.apiName == "temperature") {
                    TemperatureRecommendationRow()
                }
            }
        }

        // Creativity parameters section
        if (creativityParameters.isNotEmpty()) {
            ModelParamSectionTitle(title = "创造性参数", icon = Icons.Default.Lightbulb)
            creativityParameters.forEach { parameter ->
                ParameterSectionWithToggle(
                    parameter = parameter,
                    onValueChange = { newValue -> updateParameterValue(parameter, newValue) },
                    onToggle = { isEnabled -> toggleParameter(parameter, isEnabled) },
                    error = parameterErrors[parameter.id],
                    onErrorChange = { error -> 
                        if (error != null) {
                            parameterErrors[parameter.id] = error
                        } else {
                            parameterErrors.remove(parameter.id)
                        }
                    }
                )
            }
        }

        // Repetition parameters section
        if (repetitionParameters.isNotEmpty()) {
            ModelParamSectionTitle(title = "重复控制参数", icon = Icons.Default.Repeat)
            repetitionParameters.forEach { parameter ->
                ParameterSectionWithToggle(
                    parameter = parameter,
                    onValueChange = { newValue -> updateParameterValue(parameter, newValue) },
                    onToggle = { isEnabled -> toggleParameter(parameter, isEnabled) },
                    error = parameterErrors[parameter.id],
                    onErrorChange = { error -> 
                        if (error != null) {
                            parameterErrors[parameter.id] = error
                        } else {
                            parameterErrors.remove(parameter.id)
                        }
                    }
                )
            }
        }

        // Advanced parameters section
        if (advancedParameters.isNotEmpty()) {
            ModelParamSectionTitle(title = "高级参数", icon = Icons.Default.Settings)
            advancedParameters.forEach { parameter ->
                ParameterSectionWithToggle(
                    parameter = parameter,
                    onValueChange = { newValue -> updateParameterValue(parameter, newValue) },
                    onToggle = { isEnabled -> toggleParameter(parameter, isEnabled) },
                    error = parameterErrors[parameter.id],
                    onErrorChange = { error -> 
                        if (error != null) {
                            parameterErrors[parameter.id] = error
                        } else {
                            parameterErrors.remove(parameter.id)
                        }
                    }
                )
            }
        }

        // Custom parameters section (only show if there are custom parameters)
        if (customParameters.isNotEmpty()) {
            ModelParamSectionTitle(title = "自定义参数", icon = Icons.Default.Edit)
            customParameters.forEach { parameter ->
                ParameterSectionWithToggle(
                    parameter = parameter,
                    onValueChange = { newValue -> updateParameterValue(parameter, newValue) },
                    onToggle = { isEnabled -> toggleParameter(parameter, isEnabled) },
                    error = parameterErrors[parameter.id],
                    onErrorChange = { error -> 
                        if (error != null) {
                            parameterErrors[parameter.id] = error
                        } else {
                            parameterErrors.remove(parameter.id)
                        }
                    },
                    showDeleteOption = true,
                    onDeleteParameter = { paramId ->
                        // 删除自定义参数
                        val newParameters = parameters.filter { it.id != paramId }
                        parameters = newParameters
                        
                        // 保存新的参数列表
                        scope.launch {
                            try {
                                apiPreferences.saveModelParameters(newParameters)
                                toggleSaveMessage = "参数已删除并保存"
                                showToggleSaveMessage = true
                                kotlinx.coroutines.delay(2000)
                                showToggleSaveMessage = false
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                )
            }
        }

        // Toggle save message card (lightweight notification) - moved just above the buttons
        AnimatedVisibility(visible = showToggleSaveMessage) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = toggleSaveMessage,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        // Success message card - moved just above the buttons
        AnimatedVisibility(visible = showSaveSuccessMessage) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "设置已成功保存",
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                LaunchedEffect(showSaveSuccessMessage) {
                    kotlinx.coroutines.delay(2000)
                    showSaveSuccessMessage = false
                }
            }
        }

        // Save and Reset buttons - simplified text
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            // Reset all parameters to default values
                            val resetParameters = parameters.map { param ->
                                when (param.valueType) {
                                    ParameterValueType.INT -> {
                                        val intParam = param as ModelParameter<Int>
                                        intParam.copy(currentValue = intParam.defaultValue, isEnabled = false)
                                    }
                                    ParameterValueType.FLOAT -> {
                                        val floatParam = param as ModelParameter<Float>
                                        floatParam.copy(currentValue = floatParam.defaultValue, isEnabled = false)
                                    }
                                    ParameterValueType.STRING -> {
                                        val stringParam = param as ModelParameter<String>
                                        stringParam.copy(currentValue = stringParam.defaultValue, isEnabled = false)
                                    }
                                    ParameterValueType.BOOLEAN -> {
                                        val boolParam = param as ModelParameter<Boolean>
                                        boolParam.copy(currentValue = boolParam.defaultValue, isEnabled = false)
                                    }
                                }
                            }
                            parameters = resetParameters
                            
                            // Save the reset values
                            apiPreferences.saveModelParameters(resetParameters)
                            toggleSaveMessage = "所有参数已重置为默认值"
                            showToggleSaveMessage = true
                            kotlinx.coroutines.delay(2000)
                            showToggleSaveMessage = false
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                },
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "重置",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("重置")
            }
            
            Button(
                onClick = {
                    saveParameters(parameters)
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).padding(start = 8.dp)
                ) {
                    Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = "保存",
                    modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                Text("保存")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ModelParamSectionTitle(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
    Divider(modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
fun ParameterSectionWithToggle(
    parameter: ModelParameter<*>,
    onValueChange: (Any) -> Unit,
    onToggle: (Boolean) -> Unit,
    error: String? = null,
    onErrorChange: (String?) -> Unit,
    showDeleteOption: Boolean = false,
    onDeleteParameter: (String) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // 外层内容
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = parameter.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                
                    if (parameter.description.isNotEmpty()) {
                        Text(
                            text = parameter.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 删除按钮（仅针对自定义参数显示）
                    if (showDeleteOption) {
                        IconButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "删除参数",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    
                    // 启用/禁用开关
                    Switch(
                        checked = parameter.isEnabled,
                        onCheckedChange = { onToggle(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                    
                    // 展开/收起按钮
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "收起" else "展开",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            
            // API名称显示
            Text(
                text = "API名称: ${parameter.apiName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
            )
            
            // 参数值设置（仅在展开时显示）
            if (expanded) {
                Divider(modifier = Modifier.padding(vertical = 6.dp))
                
                when (parameter.valueType) {
                    ParameterValueType.INT -> {
                        val intParam = parameter as ModelParameter<Int>
                        var textValue by remember { mutableStateOf(intParam.currentValue.toString()) }
                        var isError by remember { mutableStateOf(false) }
                        
                        // Validate on value change
                        val validateIntValue = { value: String ->
                            try {
                                val intValue = value.toInt()
                                val minValue = intParam.minValue ?: Int.MIN_VALUE
                                val maxValue = intParam.maxValue ?: Int.MAX_VALUE
                                
                                if (intValue < minValue || intValue > maxValue) {
                                    onErrorChange("值必须在 $minValue 和 $maxValue 之间")
                                    true
                                } else {
                                    onErrorChange(null)
                                    onValueChange(intValue)
                                    false
                                }
                            } catch (e: NumberFormatException) {
                                onErrorChange("必须是整数")
                                true
                            }
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = textValue,
                                onValueChange = { 
                                    textValue = it
                                    isError = validateIntValue(it)
                                },
                                label = { Text("值") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                isError = isError,
                                supportingText = {
                                    if (isError) {
                                        Text(error ?: "")
                                    } else if (intParam.minValue != null && intParam.maxValue != null) {
                                        Text("范围: ${intParam.minValue} - ${intParam.maxValue}")
                                    }
                                },
                                singleLine = true
                            )
                        }
                    }
                    ParameterValueType.FLOAT -> {
                        val floatParam = parameter as ModelParameter<Float>
                        var textValue by remember { mutableStateOf(floatParam.currentValue.toString()) }
                        var isError by remember { mutableStateOf(false) }
                        
                        // Validate on value change
                        val validateFloatValue = { value: String ->
                            try {
                                val floatValue = value.toFloat()
                                val minValue = floatParam.minValue ?: Float.MIN_VALUE
                                val maxValue = floatParam.maxValue ?: Float.MAX_VALUE
                                
                                if (floatValue < minValue || floatValue > maxValue) {
                                    onErrorChange("值必须在 $minValue 和 $maxValue 之间")
                                    true
                                } else {
                                    onErrorChange(null)
                                    onValueChange(floatValue)
                                    false
                                }
                            } catch (e: NumberFormatException) {
                                onErrorChange("必须是浮点数")
                                true
                            }
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = textValue,
                                onValueChange = { 
                                    textValue = it
                                    isError = validateFloatValue(it)
                                },
                                label = { Text("值") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                                isError = isError,
                                supportingText = {
                                    if (isError) {
                                        Text(error ?: "")
                                    } else if (floatParam.minValue != null && floatParam.maxValue != null) {
                                        Text("范围: ${floatParam.minValue} - ${floatParam.maxValue}")
                                    }
                                },
                                singleLine = true
                            )
                        }
                    }
                    ParameterValueType.STRING -> {
                        val stringParam = parameter as ModelParameter<String>
                        var textValue by remember { mutableStateOf(stringParam.currentValue) }
                        
                        OutlinedTextField(
                            value = textValue,
                            onValueChange = { 
                                textValue = it
                                onValueChange(it)
                            },
                            label = { Text("值") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    ParameterValueType.BOOLEAN -> {
                        val boolParam = parameter as ModelParameter<Boolean>
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "值:",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(end = 16.dp)
                            )
                            Switch(
                                checked = boolParam.currentValue,
                                onCheckedChange = { onValueChange(it) }
                            )
                        }
                    }
                }
                
                // 显示默认值
                Text(
                    text = "默认值: ${parameter.defaultValue}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
    
    // 删除确认对话框
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("您确定要删除参数 \"${parameter.name}\" 吗？此操作无法撤销。") },
            confirmButton = {
                Button(
                    onClick = { 
                        onDeleteParameter(parameter.id)
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun TemperatureRecommendationRow() {
    Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
                text = "温度推荐设置",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
        )

        Card(
                shape = RoundedCornerShape(16.dp),
                colors =
                        CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                modifier = Modifier.padding(vertical = 2.dp)
        ) {
            Text(
                    text = "1.3",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddParameterDialog(
    onDismiss: () -> Unit,
    onAddParameter: (ModelParameter<*>) -> Unit,
    providerType: LLMProviderFactory.ProviderType
) {
    var paramName by remember { mutableStateOf("") }
    var apiName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(ParameterValueType.FLOAT) }
    var defaultValue by remember { mutableStateOf("0.0") }
    var minValue by remember { mutableStateOf("0.0") }
    var maxValue by remember { mutableStateOf("1.0") }
    var selectedCategory by remember { mutableStateOf(ParameterCategory.ADVANCED) }
    
    // 验证状态
    var hasNameError by remember { mutableStateOf(false) }
    var hasApiNameError by remember { mutableStateOf(false) }
    var hasValueError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    
    // 为不同提供商准备提示信息和前缀
    val providerInfo = when(providerType) {
        LLMProviderFactory.ProviderType.DEEPSEEK -> Pair(
            "添加DeepSeek API参数", 
            ""
        )
        LLMProviderFactory.ProviderType.GEMINI -> Pair(
            "添加Gemini API参数",
            "gemini_"
        )
    }
    
    // 滚动状态
    val scrollState = rememberScrollState()

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 550.dp), // 限制最大高度
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(scrollState) // 添加滚动功能
            ) {
                Text(
                    text = providerInfo.first,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                // 添加提供商特定的说明
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (providerType) {
                                LLMProviderFactory.ProviderType.DEEPSEEK -> 
                                    "DeepSeek API支持多种自定义参数，请参考官方文档了解更多信息"
                                LLMProviderFactory.ProviderType.GEMINI -> 
                                    "Gemini API使用generationConfig对象包装参数，自定义参数将会添加gemini_前缀"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 参数名称
                OutlinedTextField(
                    value = paramName,
                    onValueChange = { 
                        paramName = it 
                        hasNameError = it.isBlank()
                    },
                    label = { Text("参数名称") },
                    isError = hasNameError,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // API名称
                OutlinedTextField(
                    value = apiName,
                    onValueChange = { 
                        apiName = it 
                        hasApiNameError = it.isBlank()
                    },
                    label = { 
                        if (providerInfo.second.isNotEmpty()) {
                            Text("API参数名 (自动添加前缀: ${providerInfo.second})")
                        } else {
                            Text("API参数名") 
                        }
                    },
                    isError = hasApiNameError,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 描述
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("参数描述") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 参数类型
                Text(
                    text = "参数类型",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // 水平滚动的类型选择器
                @OptIn(ExperimentalMaterial3Api::class)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ParameterValueType.values().forEachIndexed { index, type ->
                        SegmentedButton(
                            selected = selectedType == type,
                            onClick = { 
                                selectedType = type
                                // 根据类型设置默认值
                                defaultValue = when(type) {
                                    ParameterValueType.INT -> "0"
                                    ParameterValueType.FLOAT -> "0.0"
                                    ParameterValueType.STRING -> ""
                                    ParameterValueType.BOOLEAN -> "false"
                                }
                                minValue = when(type) {
                                    ParameterValueType.INT -> "0"
                                    ParameterValueType.FLOAT -> "0.0"
                                    else -> ""
                                }
                                maxValue = when(type) {
                                    ParameterValueType.INT -> "100"
                                    ParameterValueType.FLOAT -> "1.0"
                                    else -> ""
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = ParameterValueType.values().size),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = type.name,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 默认值
                OutlinedTextField(
                    value = defaultValue,
                    onValueChange = { 
                        defaultValue = it 
                        // 验证值格式
                        hasValueError = try {
                            when(selectedType) {
                                ParameterValueType.INT -> it.toInt().toString() != it
                                ParameterValueType.FLOAT -> it.toFloat().toString() != it && it.toDouble().toString() != it
                                else -> false
                            }
                        } catch (e: Exception) {
                            errorMessage = "无效的${selectedType.name}值"
                            true
                        }
                    },
                    label = { Text("默认值") },
                    isError = hasValueError,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when(selectedType) {
                            ParameterValueType.INT -> KeyboardType.Number
                            ParameterValueType.FLOAT -> KeyboardType.Decimal
                            else -> KeyboardType.Text
                        }
                    ),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 仅对数值类型显示最小值和最大值
                if (selectedType == ParameterValueType.INT || selectedType == ParameterValueType.FLOAT) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = minValue,
                            onValueChange = { minValue = it },
                            label = { Text("最小值") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = if(selectedType == ParameterValueType.INT) 
                                    KeyboardType.Number else KeyboardType.Decimal
                            ),
                            singleLine = true
                        )
                        
                        OutlinedTextField(
                            value = maxValue,
                            onValueChange = { maxValue = it },
                            label = { Text("最大值") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = if(selectedType == ParameterValueType.INT) 
                                    KeyboardType.Number else KeyboardType.Decimal
                            ),
                            singleLine = true
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // 参数类别
                Text(
                    text = "参数类别",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // 类别选择
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(ParameterCategory.values()) { category ->
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                            label = { 
                                Text(
                                    when(category) {
                                        ParameterCategory.GENERATION -> "生成参数"
                                        ParameterCategory.CREATIVITY -> "创造性参数"
                                        ParameterCategory.REPETITION -> "重复控制参数"
                                        ParameterCategory.ADVANCED -> "高级参数"
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        )
                    }
                }

                // 错误消息
                if (hasValueError || hasNameError || hasApiNameError) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when {
                            hasNameError -> "请输入参数名称"
                            hasApiNameError -> "请输入API参数名"
                            hasValueError -> errorMessage
                            else -> ""
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 按钮行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Text("取消")
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Button(
                        onClick = {
                            // 验证输入
                            if (paramName.isBlank()) {
                                hasNameError = true
                                return@Button
                            }
                            
                            if (apiName.isBlank()) {
                                hasApiNameError = true
                                return@Button
                            }
                            
                            // 添加提供商特定的前缀
                            val finalApiName = if (providerInfo.second.isNotEmpty() && !apiName.startsWith(providerInfo.second)) {
                                providerInfo.second + apiName
                            } else {
                                apiName
                            }
                            
                            // 创建参数对象
                            val parameter = try {
                                when (selectedType) {
                                    ParameterValueType.INT -> {
                                        val defVal = defaultValue.toIntOrNull() ?: 0
                                        val minVal = minValue.toIntOrNull()
                                        val maxVal = maxValue.toIntOrNull()
                                        ModelParameter(
                                            id = finalApiName,
                                            name = paramName,
                                            apiName = finalApiName,
                                            description = description,
                                            defaultValue = defVal,
                                            currentValue = defVal,
                                            isEnabled = true,
                                            valueType = ParameterValueType.INT,
                                            minValue = minVal,
                                            maxValue = maxVal,
                                            icon = "settings",
                                            category = selectedCategory,
                                            isCustom = true
                                        )
                                    }
                                    ParameterValueType.FLOAT -> {
                                        val defVal = defaultValue.toFloatOrNull() ?: 0f
                                        val minVal = minValue.toFloatOrNull()
                                        val maxVal = maxValue.toFloatOrNull()
                                        ModelParameter(
                                            id = finalApiName,
                                            name = paramName,
                                            apiName = finalApiName,
                                            description = description,
                                            defaultValue = defVal,
                                            currentValue = defVal,
                                            isEnabled = true,
                                            valueType = ParameterValueType.FLOAT,
                                            minValue = minVal,
                                            maxValue = maxVal,
                                            icon = "settings",
                                            category = selectedCategory,
                                            isCustom = true
                                        )
                                    }
                                    ParameterValueType.STRING -> {
                                        ModelParameter(
                                            id = finalApiName,
                                            name = paramName,
                                            apiName = finalApiName,
                                            description = description,
                                            defaultValue = defaultValue,
                                            currentValue = defaultValue,
                                            isEnabled = true,
                                            valueType = ParameterValueType.STRING,
                                            icon = "settings",
                                            category = selectedCategory,
                                            isCustom = true
                                        )
                                    }
                                    ParameterValueType.BOOLEAN -> {
                                        val defVal = defaultValue.lowercase() == "true"
                                        ModelParameter(
                                            id = finalApiName,
                                            name = paramName,
                                            apiName = finalApiName,
                                            description = description,
                                            defaultValue = defVal,
                                            currentValue = defVal,
                                            isEnabled = true,
                                            valueType = ParameterValueType.BOOLEAN,
                                            icon = "settings",
                                            category = selectedCategory,
                                            isCustom = true
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                hasValueError = true
                                errorMessage = "参数值格式错误: ${e.message}"
                                return@Button
                            }
                            
                            if (parameter != null) {
                                onAddParameter(parameter)
                            }
                        }
                    ) {
                        Text("添加参数")
                    }
                }
            }
        }
    }
}
