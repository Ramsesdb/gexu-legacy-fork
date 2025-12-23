package eu.kanade.presentation.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import tachiyomi.domain.manga.model.NoteTag
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Data class representing the position and context of a long press event.
 */
data class LongPressContext(
    val x: Float,
    val y: Float,
    val chapterNumber: Double,
    val chapterName: String,
    val pageNumber: Int,
)

/**
 * Sealed class representing the modes of the contextual popup.
 */
sealed class ContextualPopupMode {
    /** Initial mode showing quick action buttons */
    object Menu : ContextualPopupMode()

    /** Expanded mode for writing a note */
    object NoteInput : ContextualPopupMode()
}

/**
 * Contextual popup that appears at the long press position.
 * Shows quick action buttons: Note, Bookmark, Copy.
 * When Note is selected, expands to show text input.
 */
@Composable
fun ContextualNotePopup(
    visible: Boolean,
    context: LongPressContext?,
    onDismiss: () -> Unit,
    onSaveNote: (String, List<NoteTag>) -> Unit,
    onToggleBookmark: () -> Unit,
    onCopyPage: () -> Unit,
    onAskAi: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (context == null) return

    var mode by remember(visible) { mutableStateOf<ContextualPopupMode>(ContextualPopupMode.Menu) }
    var noteText by remember(visible) { mutableStateOf("") }
    var selectedTags by remember(visible) { mutableStateOf<Set<NoteTag>>(emptySet()) }
    val focusRequester = remember { FocusRequester() }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    // Calculate position to keep popup on screen
    val screenWidth = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeight = with(density) { configuration.screenHeightDp.dp.toPx() }
    val popupWidth = with(density) { 280.dp.toPx() }
    val popupHeight = with(density) { if (mode is ContextualPopupMode.NoteInput) 200.dp.toPx() else 120.dp.toPx() }

    // Adjust position to stay within screen bounds
    val adjustedX = context.x.coerceIn(16f, screenWidth - popupWidth - 16f)
    val adjustedY = (context.y - popupHeight / 2).coerceIn(16f, screenHeight - popupHeight - 16f)

    // Request focus when switching to note input mode
    LaunchedEffect(mode) {
        if (mode is ContextualPopupMode.NoteInput) {
            focusRequester.requestFocus()
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        // Semi-transparent backdrop
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable(onClick = onDismiss),
            )
        }

        // Popup content
        AnimatedVisibility(
            visible = visible,
            enter = scaleIn(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                initialScale = 0.6f,
            ) + fadeIn(),
            exit = scaleOut(targetScale = 0.8f) + fadeOut(),
            modifier = Modifier.offset {
                IntOffset(adjustedX.toInt(), adjustedY.toInt())
            },
        ) {
            Card(
                modifier = Modifier.widthIn(max = 280.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                ) {
                    // Header with location
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Cap. ${formatChapterNumber(context.chapterNumber)} • Pág. ${context.pageNumber}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(MR.strings.action_close),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    when (mode) {
                        is ContextualPopupMode.Menu -> {
                            // Quick action buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                QuickActionButton(
                                    icon = Icons.Default.Edit,
                                    label = stringResource(MR.strings.action_notes),
                                    onClick = { mode = ContextualPopupMode.NoteInput },
                                )
                                QuickActionButton(
                                    icon = Icons.Default.Bookmark,
                                    label = stringResource(MR.strings.action_bookmark),
                                    onClick = {
                                        onToggleBookmark()
                                        onDismiss()
                                    },
                                )
                                QuickActionButton(
                                    icon = Icons.Default.ContentCopy,
                                    label = stringResource(MR.strings.copy),
                                    onClick = {
                                        onCopyPage()
                                        onDismiss()
                                    },
                                )
                                if (onAskAi != null) {
                                    QuickActionButton(
                                        icon = Icons.Outlined.AutoAwesome,
                                        label = "AI",
                                        onClick = {
                                            onAskAi()
                                            onDismiss()
                                        },
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }

                        is ContextualPopupMode.NoteInput -> {
                            // Note input field
                            OutlinedTextField(
                                value = noteText,
                                onValueChange = { noteText = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                                placeholder = {
                                    Text(
                                        "💭 Tu pensamiento...",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                                        alpha = 0.3f,
                                    ),
                                ),
                                shape = RoundedCornerShape(12.dp),
                                minLines = 2,
                                maxLines = 4,
                                textStyle = MaterialTheme.typography.bodyMedium,
                            )

                            Spacer(Modifier.height(8.dp))

                            // Tag selector with emoji + displayName
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                NoteTag.entries.forEach { tag ->
                                    val isSelected = tag in selectedTags
                                    Surface(
                                        onClick = {
                                            selectedTags = if (isSelected) {
                                                selectedTags - tag
                                            } else {
                                                selectedTags + tag
                                            }
                                        },
                                        modifier = Modifier.height(32.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        tonalElevation = if (isSelected) 2.dp else 0.dp,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(
                                                horizontal = 10.dp,
                                                vertical = 4.dp,
                                            ),
                                        ) {
                                            Text(
                                                text = tag.emoji,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = tag.displayName,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))

                            // Action buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Back to menu button
                                IconButton(
                                    onClick = { mode = ContextualPopupMode.Menu },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(MR.strings.action_cancel),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }

                                Spacer(Modifier.weight(1f))

                                Button(
                                    onClick = {
                                        if (noteText.isNotBlank()) {
                                            onSaveNote(noteText.trim(), selectedTags.toList())
                                            onDismiss()
                                        }
                                    },
                                    enabled = noteText.isNotBlank(),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = ButtonDefaults.ContentPadding,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(MR.strings.action_save))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Quick action button for the contextual menu.
 */
@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(22.dp),
                tint = tint,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatChapterNumber(number: Double): String {
    return if (number == number.toLong().toDouble()) {
        number.toLong().toString()
    } else {
        number.toString()
    }
}
