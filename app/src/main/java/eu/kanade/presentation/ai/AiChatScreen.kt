package eu.kanade.presentation.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.ai.components.ConversationHistoryDrawer
import eu.kanade.tachiyomi.ui.ai.AiChatScreenModel
import tachiyomi.domain.ai.model.ChatMessage
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    state: AiChatScreenModel.State,
    onSendMessage: (String) -> Unit,
    onClearConversation: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleHistory: () -> Unit = {},
    onCloseHistory: () -> Unit = {},
    onSelectConversation: (Long) -> Unit = {},
    onDeleteConversation: (Long) -> Unit = {},
    onNewConversation: () -> Unit = {},
    onToggleWebSearch: () -> Unit,
) {
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val drawerState = rememberDrawerState(
        initialValue = if (state.showHistoryDrawer) DrawerValue.Open else DrawerValue.Closed,
    )

    // Sync drawer state with our state
    LaunchedEffect(state.showHistoryDrawer) {
        if (state.showHistoryDrawer) {
            drawerState.open()
        } else {
            drawerState.close()
        }
    }

    // Update state when drawer is closed by gesture
    LaunchedEffect(drawerState.isClosed) {
        if (drawerState.isClosed && state.showHistoryDrawer) {
            onCloseHistory()
        }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationHistoryDrawer(
                conversations = state.savedConversations,
                currentConversationId = state.currentConversationId,
                onSelectConversation = onSelectConversation,
                onDeleteConversation = onDeleteConversation,
                onNewConversation = onNewConversation,
                onClose = onCloseHistory,
            )
        },
        gesturesEnabled = state.showHistoryDrawer,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(stringResource(MR.strings.label_ai_chat))
                        }
                    },
                    actions = {
                        // History button
                        BadgedBox(
                            badge = {
                                if (state.savedConversations.isNotEmpty()) {
                                    Badge { Text(state.savedConversations.size.toString()) }
                                }
                            },
                        ) {
                            IconButton(onClick = onToggleHistory) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = "History",
                                )
                            }
                        }
                        if (state.messages.isNotEmpty()) {
                            IconButton(onClick = onClearConversation) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Clear conversation",
                                )
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                            )
                        }
                    },
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                // Chat messages - fills available space
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.messages.isEmpty() && !state.isLoading) {
                        item {
                            EmptyConversationHint()
                        }
                    }

                    // Filter out system messages and empty assistant placeholders (but keep user messages with images)
                    val filteredMessages = state.messages.filter {
                        it.role != ChatMessage.Role.SYSTEM &&
                            (it.role != ChatMessage.Role.ASSISTANT || it.content.isNotEmpty())
                    }
                    itemsIndexed(
                        items = filteredMessages,
                        key = { index, message -> "${index}_${message.timestamp}" },
                    ) { _, message ->
                        ChatBubble(message = message)
                    }

                    // Loading indicator
                    if (state.isLoading && filteredMessages.lastOrNull()?.role != ChatMessage.Role.ASSISTANT) {
                        item {
                            LoadingBubble()
                        }
                    }

                    // Error message
                    if (state.error != null) {
                        item {
                            ErrorMessage(error = state.error)
                        }
                    }
                }

                // Input area - compact with buttons inside
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 8.dp, bottom = 16.dp)
                        .navigationBarsPadding(),
                    placeholder = {
                        Text(
                            text = stringResource(MR.strings.ai_chat_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = RoundedCornerShape(28.dp),
                    maxLines = 4,
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputText.isNotBlank() && !state.isLoading) {
                                onSendMessage(inputText)
                                inputText = ""
                                keyboardController?.hide()
                            }
                        },
                    ),
                    trailingIcon = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Web Search Toggle
                            IconButton(
                                onClick = onToggleWebSearch,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Public,
                                    contentDescription = stringResource(MR.strings.ai_web_search),
                                    tint = if (state.isWebSearchEnabled) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    },
                                )
                            }

                            // Send button
                            FilledIconButton(
                                onClick = {
                                    if (inputText.isNotBlank() && !state.isLoading) {
                                        onSendMessage(inputText)
                                        inputText = ""
                                        keyboardController?.hide()
                                    }
                                },
                                enabled = inputText.isNotBlank() && !state.isLoading,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    modifier = Modifier
                                        .size(20.dp)
                                        .offset(x = 1.dp),
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyConversationHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        )
        Text(
            text = stringResource(MR.strings.ai_chat_welcome_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(MR.strings.ai_chat_welcome_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == ChatMessage.Role.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ) {
            if (isUser) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                eu.kanade.presentation.manga.components.MarkdownRender(
                    content = message.content,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun LoadingBubble() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "loading")
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(3) { index ->
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.2f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = index * 200),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "dot $index",
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorMessage(error: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text = error,
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
