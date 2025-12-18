package eu.kanade.presentation.ai.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import tachiyomi.domain.ai.AiPreferences
import tachiyomi.domain.ai.AiProvider
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeySetupDialog(
    currentProvider: AiProvider,
    currentApiKey: String,
    onProviderChange: (AiProvider) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var showApiKey by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    val isValid = AiPreferences.isApiKeyValid(currentApiKey, currentProvider)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(MR.strings.ai_setup_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(MR.strings.ai_setup_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Provider dropdown
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = currentProvider.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(MR.strings.ai_provider)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        AiProvider.entries.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.displayName) },
                                onClick = {
                                    onProviderChange(provider)
                                    expanded = false
                                },
                            )
                        }
                    }
                }

                // API Key input
                OutlinedTextField(
                    value = currentApiKey,
                    onValueChange = onApiKeyChange,
                    label = { Text(stringResource(MR.strings.ai_api_key)) },
                    placeholder = { Text(getApiKeyHint(currentProvider)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showApiKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (showApiKey) "Hide" else "Show",
                            )
                        }
                    },
                    isError = currentApiKey.isNotBlank() && !isValid,
                    supportingText = if (currentApiKey.isNotBlank() && !isValid) {
                        { Text(stringResource(MR.strings.ai_api_key_invalid)) }
                    } else null,
                )

                // Help text
                Text(
                    text = getProviderHelpText(currentProvider),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = isValid,
            ) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

private fun getApiKeyHint(provider: AiProvider): String = when (provider) {
    AiProvider.OPENAI -> "sk-..."
    AiProvider.GEMINI -> "AIza..."
    AiProvider.ANTHROPIC -> "sk-ant-..."
    AiProvider.OPENROUTER -> "sk-or-..."
    AiProvider.CUSTOM -> "Your API key"
}

@Composable
private fun getProviderHelpText(provider: AiProvider): String = when (provider) {
    AiProvider.OPENAI -> stringResource(MR.strings.ai_help_openai)
    AiProvider.GEMINI -> stringResource(MR.strings.ai_help_gemini)
    AiProvider.ANTHROPIC -> stringResource(MR.strings.ai_help_anthropic)
    AiProvider.OPENROUTER -> stringResource(MR.strings.ai_help_openrouter)
    AiProvider.CUSTOM -> stringResource(MR.strings.ai_help_custom)
}
