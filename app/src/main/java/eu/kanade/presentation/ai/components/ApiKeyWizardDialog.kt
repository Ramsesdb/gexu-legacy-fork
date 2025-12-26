package eu.kanade.presentation.ai.components

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import eu.kanade.tachiyomi.R
import tachiyomi.domain.ai.AiPreferences
import tachiyomi.domain.ai.AiProvider
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Multi-step wizard dialog for API key setup.
 * Guides users through provider selection and API key acquisition.
 */
@Composable
fun ApiKeyWizardDialog(
    currentProvider: AiProvider,
    currentApiKey: String,
    onProviderChange: (AiProvider) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var showApiKey by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val isValid = AiPreferences.isApiKeyValid(currentApiKey, currentProvider)

    // Auto-detect API key from clipboard when returning to app
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && step == 2) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val pastedText = clipData.getItemAt(0).text?.toString() ?: ""
                    if (pastedText.isNotBlank() &&
                        AiPreferences.isApiKeyValid(pastedText, currentProvider)
                    ) {
                        onApiKeyChange(pastedText)
                        step = 3 // Move to paste step with detected key
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Step indicator
                StepIndicator(currentStep = step, totalSteps = 4)
                Spacer(modifier = Modifier.height(24.dp))

                // Animated step content
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        if (targetState > initialState) {
                            slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                        } else {
                            slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                        }
                    },
                    label = "wizard_step",
                ) { currentStep ->
                    when (currentStep) {
                        0 -> WelcomeStep()
                        1 -> ProviderStep(
                            selectedProvider = currentProvider,
                            onProviderSelect = onProviderChange,
                        )
                        2 -> GetKeyStep(
                            provider = currentProvider,
                            onOpenUrl = { url ->
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                                )
                            },
                        )
                        3 -> PasteKeyStep(
                            apiKey = currentApiKey,
                            provider = currentProvider,
                            showApiKey = showApiKey,
                            onApiKeyChange = onApiKeyChange,
                            onToggleVisibility = { showApiKey = !showApiKey },
                            onPasteFromClipboard = {
                                val clipboard = context.getSystemService(
                                    Context.CLIPBOARD_SERVICE,
                                ) as ClipboardManager
                                val clipData = clipboard.primaryClip
                                if (clipData != null && clipData.itemCount > 0) {
                                    val pastedText = clipData.getItemAt(0).text?.toString() ?: ""
                                    onApiKeyChange(pastedText)
                                }
                            },
                            isValid = isValid,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Navigation buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (step > 0) {
                        OutlinedButton(onClick = { step-- }) {
                            Text(stringResource(MR.strings.ai_wizard_back))
                        }
                    } else {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(MR.strings.action_cancel))
                        }
                    }

                    when (step) {
                        0 -> Button(onClick = { step = 1 }) {
                            Text(stringResource(MR.strings.ai_wizard_start))
                        }
                        1, 2 -> Button(onClick = { step++ }) {
                            Text(stringResource(MR.strings.ai_wizard_next))
                        }
                        3 -> Button(
                            onClick = onConfirm,
                            enabled = isValid,
                        ) {
                            Text(stringResource(MR.strings.action_save))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(currentStep: Int, totalSteps: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(totalSteps) { index ->
            val isCompleted = index < currentStep
            val isCurrent = index == currentStep

            Box(
                modifier = Modifier
                    .size(if (isCurrent) 12.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isCompleted -> MaterialTheme.colorScheme.primary
                            isCurrent -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "🤖",
            style = MaterialTheme.typography.displayMedium,
        )
        Text(
            text = stringResource(MR.strings.ai_wizard_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(MR.strings.ai_wizard_welcome_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Free tier info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(MR.strings.ai_wizard_free_info),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(MR.strings.ai_wizard_free_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(MR.strings.ai_wizard_no_card),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Security info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(MR.strings.ai_wizard_why_needed),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(MR.strings.ai_wizard_why_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProviderStep(
    selectedProvider: AiProvider,
    onProviderSelect: (AiProvider) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(MR.strings.ai_wizard_step_provider),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        // Show main providers (exclude CUSTOM for simplicity)
        listOf(
            AiProvider.GEMINI,
            AiProvider.OPENAI,
            AiProvider.ANTHROPIC,
            AiProvider.OPENROUTER,
        ).forEach { provider ->
            val isSelected = provider == selectedProvider
            val isRecommended = provider == AiProvider.GEMINI

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onProviderSelect(provider) },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onProviderSelect(provider) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = provider.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        if (isRecommended) {
                            Text(
                                text = stringResource(MR.strings.ai_wizard_gemini_recommended),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GetKeyStep(
    provider: AiProvider,
    onOpenUrl: (String) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = stringResource(MR.strings.ai_wizard_step_get),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        // Visual guide image for each provider
        val guideDrawable = when (provider) {
            AiProvider.GEMINI -> R.drawable.ai_wizard_gemini_guide
            AiProvider.OPENAI -> R.drawable.ai_wizard_openai_guide
            AiProvider.ANTHROPIC -> R.drawable.ai_wizard_anthropic_guide
            AiProvider.OPENROUTER -> R.drawable.ai_wizard_openrouter_guide
            AiProvider.CUSTOM -> null
        }

        guideDrawable?.let { drawable ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Image(
                    painter = painterResource(drawable),
                    contentDescription = "${provider.displayName} guide",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
        Text(
            text = getWizardInstructions(provider),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Button(
            onClick = { onOpenUrl(provider.apiKeyUrl) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(MR.strings.ai_wizard_get_key))
        }

        Text(
            text = stringResource(MR.strings.ai_wizard_back_to_app),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PasteKeyStep(
    apiKey: String,
    provider: AiProvider,
    showApiKey: Boolean,
    onApiKeyChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    onPasteFromClipboard: () -> Unit,
    isValid: Boolean,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(MR.strings.ai_wizard_step_paste),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text(stringResource(MR.strings.ai_api_key)) },
            placeholder = { Text(getApiKeyHint(provider)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showApiKey) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                Row {
                    IconButton(onClick = onPasteFromClipboard) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = stringResource(MR.strings.ai_wizard_paste_key),
                        )
                    }
                    IconButton(onClick = onToggleVisibility) {
                        Icon(
                            imageVector = if (showApiKey) {
                                Icons.Default.VisibilityOff
                            } else {
                                Icons.Default.Visibility
                            },
                            contentDescription = null,
                        )
                    }
                }
            },
            isError = apiKey.isNotBlank() && !isValid,
            supportingText = if (apiKey.isNotBlank()) {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isValid) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(MR.strings.ai_wizard_key_detected),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(stringResource(MR.strings.ai_api_key_invalid))
                        }
                    }
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun getWizardInstructions(provider: AiProvider): String = when (provider) {
    AiProvider.GEMINI -> stringResource(MR.strings.ai_wizard_instructions_gemini)
    AiProvider.OPENAI -> stringResource(MR.strings.ai_wizard_instructions_openai)
    AiProvider.ANTHROPIC -> stringResource(MR.strings.ai_wizard_instructions_anthropic)
    AiProvider.OPENROUTER -> stringResource(MR.strings.ai_wizard_instructions_openrouter)
    AiProvider.CUSTOM -> ""
}

private fun getApiKeyHint(provider: AiProvider): String = when (provider) {
    AiProvider.OPENAI -> "sk-..."
    AiProvider.GEMINI -> "AIza..."
    AiProvider.ANTHROPIC -> "sk-ant-..."
    AiProvider.OPENROUTER -> "sk-or-..."
    AiProvider.CUSTOM -> "Your API key"
}
