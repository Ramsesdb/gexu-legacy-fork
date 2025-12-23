package eu.kanade.presentation.manga

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.manga.components.MangaNotesTextArea
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesScreen
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.NoteTag
import tachiyomi.domain.manga.model.ReaderNote
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun MangaNotesScreen(
    state: MangaNotesScreen.State,
    navigateUp: () -> Unit,
    onUpdateNotes: (String) -> Unit,
    onDeleteReaderNote: (Long) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onNavigateToPage: (chapterId: Long, pageNumber: Int) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { topBarScrollBehavior ->
            AppBar(
                titleContent = {
                    AppBarTitle(
                        title = stringResource(MR.strings.action_notes),
                        subtitle = state.manga.title,
                    )
                },
                navigateUp = navigateUp,
                scrollBehavior = topBarScrollBehavior,
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding)
                .imePadding(),
        ) {
            // Tabs
            TabRow(
                selectedTabIndex = pagerState.currentPage,
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text = { Text(stringResource(MR.strings.notes_tab_general)) },
                    icon = { Icon(Icons.Outlined.EditNote, null, Modifier.size(18.dp)) },
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = {
                        Text(
                            if (state.readerNotes.isNotEmpty()) {
                                stringResource(MR.strings.notes_tab_chapter_count, state.readerNotes.size)
                            } else {
                                stringResource(MR.strings.notes_tab_chapter)
                            },
                        )
                    },
                    icon = { Icon(Icons.Outlined.MenuBook, null, Modifier.size(18.dp)) },
                )
            }

            // Pager content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> {
                        // General notes tab
                        MangaNotesTextArea(
                            state = state,
                            onUpdate = onUpdateNotes,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    1 -> {
                        // Reader notes tab
                        ReaderNotesContent(
                            notes = state.filteredNotes,
                            searchQuery = state.searchQuery,
                            onSearchQueryChange = onSearchQueryChange,
                            onDelete = onDeleteReaderNote,
                            onNavigateToPage = onNavigateToPage,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderNotesContent(
    notes: List<ReaderNote>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onDelete: (Long) -> Unit,
    onNavigateToPage: (chapterId: Long, pageNumber: Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text(stringResource(MR.strings.action_search_hint)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Outlined.Clear,
                            contentDescription = stringResource(MR.strings.action_cancel),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.padding.medium)
                .padding(top = MaterialTheme.padding.small),
        )

        // Tag filter chips
        var selectedTagFilter by remember { mutableStateOf<NoteTag?>(null) }
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
        ) {
            items(NoteTag.entries) { tag ->
                FilterChip(
                    selected = selectedTagFilter == tag,
                    onClick = {
                        selectedTagFilter = if (selectedTagFilter == tag) null else tag
                    },
                    label = {
                        Text(
                            text = "${tag.emoji} ${tag.displayName}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
        }

        // Filter notes by selected tag
        val displayedNotes = remember(notes, selectedTagFilter) {
            if (selectedTagFilter == null) {
                notes
            } else {
                notes.filter { note -> selectedTagFilter in note.tags }
            }
        }

        if (displayedNotes.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(MaterialTheme.padding.large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(MR.strings.notes_empty_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(MR.strings.notes_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(MaterialTheme.padding.medium),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    items = displayedNotes.sortedByDescending { it.createdAt },
                    key = { it.id },
                ) { note ->
                    ReaderNoteCard(
                        note = note,
                        onDelete = { onDelete(note.id) },
                        onClick = { onNavigateToPage(note.chapterId, note.pageNumber) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderNoteCard(
    note: ReaderNote,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    val dateFormatter = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.padding.medium),
        ) {
            // Header with chapter info and actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "📍",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.width(4.dp))
                    val chapterLabel = if (note.chapterNumber > 0) {
                        note.chapterNumber.toInt().toString()
                    } else {
                        note.chapterName.take(20)
                    }
                    Text(
                        text = stringResource(MR.strings.notes_location_format, chapterLabel, note.pageNumber),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = dateFormatter.format(note.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Eliminar",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            // Tags display with emoji + name
            if (note.tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    note.tags.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = tag.emoji,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = tag.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Note text
            Text(
                text = note.noteText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
