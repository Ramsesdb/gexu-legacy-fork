package eu.kanade.presentation.ai.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tachiyomi.domain.ai.AiPreferences
import tachiyomi.domain.ai.AiProvider
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Dialog for managing API keys for multiple AI providers.
 * Allows users to configure keys for each provider and select the primary provider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageApiKeysDialog(
    aiPreferences: AiPreferences,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    // State for primary provider selection
    var primaryProvider by remember {
        mutableStateOf(AiProvider.fromName(aiPreferences.provider().get()))
    }
    var providerDropdownExpanded by remember { mutableStateOf(false) }

    // State for each provider's API key (local edits before saving)
    val apiKeys = remember {
        mutableStateMapOf<AiProvider, String>().apply {
            AiProvider.entries.forEach { provider ->
                this[provider] = aiPreferences.getApiKeyForProvider(provider)
            }
        }
    }

    // State for visibility toggles
    val keyVisibility = remember {
        mutableStateMapOf<AiProvider, Boolean>().apply {
            AiProvider.entries.forEach { provider ->
                this[provider] = false
            }
        }
    }

    // State for custom base URL
    var customBaseUrl by remember {
        mutableStateOf(aiPreferences.customBaseUrl().get())
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Title
                Text(
                    text = stringResource(MR.strings.ai_manage_api_keys),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(MR.strings.ai_manage_api_keys_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Primary provider selector
                Text(
                    text = stringResource(MR.strings.ai_primary_provider),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(modifier = Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = providerDropdownExpanded,
                    onExpandedChange = { providerDropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = primaryProvider.displayName,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = providerDropdownExpanded,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = providerDropdownExpanded,
                        onDismissRequest = { providerDropdownExpanded = false },
                    ) {
                        AiProvider.entries.forEach { provider ->
                            val hasKey = apiKeys[provider]?.isNotBlank() == true
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(provider.displayName)
                                        if (hasKey) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    primaryProvider = provider
                                    providerDropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                HorizontalDivider()

                Spacer(modifier = Modifier.height(16.dp))

                // Provider API key fields
                AiProvider.entries.forEach { provider ->
                    ProviderKeyField(
                        provider = provider,
                        apiKey = apiKeys[provider] ?: "",
                        customBaseUrl = if (provider == AiProvider.CUSTOM) customBaseUrl else "",
                        isVisible = keyVisibility[provider] == true,
                        isPrimary = provider == primaryProvider,
                        onApiKeyChange = { apiKeys[provider] = it },
                        onCustomBaseUrlChange = { customBaseUrl = it },
                        onToggleVisibility = {
                            keyVisibility[provider] = !(keyVisibility[provider] ?: false)
                        },
                        onOpenUrl = {
                            if (provider.apiKeyUrl.isNotBlank()) {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(provider.apiKeyUrl),
                                )
                                context.startActivity(intent)
                            }
                        },
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(MR.strings.action_cancel))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    TextButton(
                        onClick = {
                            // Save all changes
                            aiPreferences.provider().set(primaryProvider.name)
                            AiProvider.entries.forEach { provider ->
                                aiPreferences.setApiKeyForProvider(
                                    provider,
                                    apiKeys[provider] ?: "",
                                )
                            }
                            // Save custom base URL
                            aiPreferences.customBaseUrl().set(customBaseUrl)
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(MR.strings.action_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderKeyField(
    provider: AiProvider,
    apiKey: String,
    customBaseUrl: String,
    isVisible: Boolean,
    isPrimary: Boolean,
    onApiKeyChange: (String) -> Unit,
    onCustomBaseUrlChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    onOpenUrl: () -> Unit,
) {
    val isValid = apiKey.isBlank() || AiPreferences.isApiKeyValid(apiKey, provider)
    val hasKey = apiKey.isNotBlank()

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status icon
            Icon(
                imageVector = if (hasKey && isValid) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = if (hasKey && isValid) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(20.dp),
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = provider.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = if (isPrimary) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            if (isPrimary) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "(Principal)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(getApiKeyPlaceholder(provider))
            },
            singleLine = true,
            visualTransformation = if (isVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                Row {
                    IconButton(onClick = onToggleVisibility) {
                        Icon(
                            imageVector = if (isVisible) {
                                Icons.Default.VisibilityOff
                            } else {
                                Icons.Default.Visibility
                            },
                            contentDescription = if (isVisible) "Ocultar" else "Mostrar",
                        )
                    }

                    if (provider.apiKeyUrl.isNotBlank()) {
                        IconButton(onClick = onOpenUrl) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = "Obtener API Key",
                            )
                        }
                    }
                }
            },
            isError = hasKey && !isValid,
            supportingText = if (hasKey && !isValid) {
                { Text(stringResource(MR.strings.ai_api_key_invalid)) }
            } else {
                null
            },
        )

        // Show custom base URL field only for CUSTOM provider when key is configured
        AnimatedVisibility(
            visible = provider == AiProvider.CUSTOM && hasKey,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            CustomBaseUrlField(
                value = customBaseUrl,
                onValueChange = onCustomBaseUrlChange,
            )
        }
    }
}

@Composable
private fun CustomBaseUrlField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column {
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL") },
            placeholder = { Text("https://your-api.com/v1/chat/completions") },
            singleLine = true,
        )
    }
}

private fun getApiKeyPlaceholder(provider: AiProvider): String = when (provider) {
    AiProvider.OPENAI -> "sk-..."
    AiProvider.GEMINI -> "AIza..."
    AiProvider.ANTHROPIC -> "sk-ant-..."
    AiProvider.OPENROUTER -> "sk-or-..."
    AiProvider.CUSTOM -> "Tu API key"
}
