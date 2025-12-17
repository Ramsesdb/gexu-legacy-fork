package eu.kanade.presentation.ai.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tachiyomi.domain.ai.model.AiConversation
import java.text.SimpleDateFormat
import java.util.*

/**
 * Conversation history drawer content.
 * Shows a list of saved conversations that can be loaded or deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationHistoryDrawer(
    conversations: List<AiConversation>,
    currentConversationId: Long?,
    onSelectConversation: (Long) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onNewConversation: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(modifier = modifier.width(300.dp)) {
        Column(modifier = Modifier.fillMaxHeight()) {
            // Header
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "Chat History",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                        )
                    }
                }
            }

            // New Conversation Button
            ListItem(
                headlineContent = { Text("New Conversation") },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier = Modifier.clickable {
                    onNewConversation()
                    onClose()
                },
            )

            HorizontalDivider()

            // Conversation List
            if (conversations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Text(
                            text = "No saved conversations",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(
                        items = conversations,
                        key = { it.id },
                    ) { conversation ->
                        ConversationItem(
                            conversation = conversation,
                            isSelected = conversation.id == currentConversationId,
                            onSelect = {
                                onSelectConversation(conversation.id)
                                onClose()
                            },
                            onDelete = { onDeleteConversation(conversation.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationItem(
    conversation: AiConversation,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    ListItem(
        headlineContent = {
            Text(
                text = conversation.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = dateFormat.format(conversation.updatedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                )
            }
        },
        modifier = Modifier.clickable(onClick = onSelect),
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    )
}
