package eu.kanade.tachiyomi.ui.reader.viewer.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.tachiyomi.util.MuPdfUtil

/**
 * Modal dialog to display PDF Table of Contents.
 * Allows navigation to specific chapters within the PDF.
 */
@Composable
fun PdfTocModal(
    tocItems: List<MuPdfUtil.TocItem>,
    currentPage: Int,
    onNavigate: (pageNumber: Int) -> Unit,
    onDismiss: () -> Unit
) {
    // Find the current chapter based on page position
    val currentChapterIndex = remember(currentPage, tocItems) {
        tocItems.indexOfLast { it.pageNumber <= currentPage }.coerceAtLeast(0)
    }

    val listState = rememberLazyListState()

    // Scroll to current chapter on open
    LaunchedEffect(currentChapterIndex) {
        if (currentChapterIndex > 0 && tocItems.isNotEmpty()) {
            listState.animateScrollToItem(currentChapterIndex.coerceAtMost(tocItems.lastIndex))
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tabla de Contenido",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // Chapter count info
                Text(
                    text = "${tocItems.size} capítulos",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                HorizontalDivider()

                // TOC List
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    itemsIndexed(tocItems) { index, item ->
                        val isCurrentChapter = index == currentChapterIndex

                        TocListItem(
                            item = item,
                            isCurrentChapter = isCurrentChapter,
                            onClick = { onNavigate(item.pageNumber) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TocListItem(
    item: MuPdfUtil.TocItem,
    isCurrentChapter: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isCurrentChapter) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    val textColor = if (isCurrentChapter) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    // Indent based on level (16dp per level)
    val startPadding = 16.dp + (item.level * 16).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable { onClick() }
            .padding(start = startPadding, end = 16.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Chapter title
        Text(
            text = item.title,
            style = if (item.level == 0) {
                MaterialTheme.typography.bodyLarge
            } else {
                MaterialTheme.typography.bodyMedium
            },
            fontWeight = if (isCurrentChapter) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Page number badge
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = if (isCurrentChapter) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            Text(
                text = "${item.pageNumber + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = if (isCurrentChapter) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
