@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.reader.viewer.novel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import eu.kanade.presentation.reader.ChapterTransition
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.MuPdfUtil
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.absoluteValue

// Reading direction for novel reader
enum class ReadingDirection {
    VERTICAL, // LazyColumn scroll
    BOOK, // HorizontalPager (book-style page swipe)
}

@Composable
fun NovelReaderScreen(
    items: List<NovelUiItem>,
    // Removed separate textPages/images lists
    // pages: List<ReaderPage> = emptyList(), // No longer needed as separate arg, contained in items
    url: String? = null,
    isLoading: Boolean = false,
    loadingMessage: String? = null,
    ocrProgress: Pair<Int, Int>? = null, // current, total
    initialOffset: Long,
    prefs: NovelPrefs,
    menuVisible: Boolean,
    forceHideMenu: Boolean = false, // When true, hides menu instantly without animation (for capture)
    showTextMode: Boolean = true,
    hasExtractedText: Boolean = false,
    hasOriginalImages: Boolean = false,
    isPdfMode: Boolean = false,
    initialPage: Int = 0, // Saved position - start here directly
    targetPageIndex: Int? = null, // For slider/manual navigation
    onTargetPageConsumed: () -> Unit = {},
    onOffsetChanged: (Long) -> Unit,
    onPrefsChanged: (NovelPrefs) -> Unit,
    onExtractOcr: () -> Unit,
    onToggleTextMode: () -> Unit = {},
    onRenderPage: (suspend (Int, Int) -> android.graphics.Bitmap?)? = null,
    onBack: () -> Unit,
    onToggleMenu: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onLoadPage: (ReaderPage) -> Unit = {}, // Trigger page loading for caching
    onTransitionClick: (eu.kanade.tachiyomi.ui.reader.model.ChapterTransition) -> Unit = {}, // Navigate to chapter
    // TOC support
    tocItems: List<MuPdfUtil.TocItem> = emptyList(),
    onTocNavigate: (Int) -> Unit = {},
    onAiClick: () -> Unit = {},
    onLongPress: (Float, Float, Int) -> Unit = { _, _, _ -> }, // x, y, pageIndex
) {
    // TOC modal state
    var showTocModal by remember { mutableStateOf(false) }
    // Calculate total items
    val totalItems = items.size

    // Standard state management
    // Use initialPage as key to recreate state on chapter transitions
    // This ensures LazyListState/PagerState are properly initialized for new chapter
    val listState = remember(initialPage) {
        LazyListState(firstVisibleItemIndex = initialPage.coerceAtLeast(0))
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceAtLeast(0),
        pageCount = { totalItems.coerceAtLeast(1) },
    )

    // Track if we've done the initial navigation (resets with key above)
    var hasNavigated by remember(initialPage) { mutableStateOf(false) }

    // Initial Scroll Logic for Async Content (e.g. Network Images)
    // LazyList/Pager init params fail if content isn't loaded yet (totalItems=0).
    // This watches for content load and forces the jump to saved position.
    LaunchedEffect(totalItems, initialPage) {
        // Jump conditions:
        // 1. Have enough items to reach the target
        // 2. Haven't already navigated for this initialPage value
        // 3. Target is valid (>= 0)
        if (totalItems > initialPage && initialPage >= 0 && !hasNavigated) {
            logcat {
                "NovelReaderScreen: Async content loaded ($totalItems items), jumping to saved page $initialPage"
            }
            if (prefs.readingDirection == ReadingDirection.BOOK) {
                pagerState.scrollToPage(initialPage)
            } else {
                listState.scrollToItem(initialPage)
            }
            hasNavigated = true
        }
    }

    // Handle ALL navigation via targetPageIndex (initial position + slider)
    LaunchedEffect(targetPageIndex, totalItems) {
        if (targetPageIndex != null && targetPageIndex >= 0 && totalItems > 0) {
            val safeTarget = targetPageIndex.coerceIn(0, totalItems - 1)

            // Only force scroll if we haven't navigated yet OR if it's a new target
            if (!hasNavigated ||
                (
                    prefs.readingDirection == ReadingDirection.VERTICAL &&
                        listState.firstVisibleItemIndex != safeTarget
                    ) ||
                (prefs.readingDirection == ReadingDirection.BOOK && pagerState.currentPage != safeTarget)
            ) {
                if (prefs.readingDirection == ReadingDirection.BOOK) {
                    pagerState.scrollToPage(safeTarget)
                } else {
                    listState.scrollToItem(safeTarget)
                }
                hasNavigated = true
            }
            onTargetPageConsumed()
        }
    }

    // Notify parent of current page changes
    // Only start tracking after we have content and have navigated to initial position
    val shouldTrackPages = totalItems > 1 && hasNavigated

    LaunchedEffect(listState, prefs.readingDirection, shouldTrackPages) {
        if (prefs.readingDirection == ReadingDirection.VERTICAL && shouldTrackPages) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .collect { index ->
                    if (index >= 0) {
                        onPageChanged(index)
                    }
                }
        }
    }

    // Track page in book mode
    LaunchedEffect(pagerState, prefs.readingDirection, shouldTrackPages) {
        if (prefs.readingDirection == ReadingDirection.BOOK && shouldTrackPages) {
            snapshotFlow { pagerState.currentPage }
                .collect { page ->
                    onPageChanged(page)
                }
        }
    }

    // Track the previous reading direction to detect changes
    var previousDirection by remember { mutableStateOf(prefs.readingDirection) }

    // Sync position when switching reading directions
    LaunchedEffect(prefs.readingDirection) {
        if (prefs.readingDirection != previousDirection && hasNavigated) {
            val currentPage = when (previousDirection) {
                ReadingDirection.VERTICAL -> listState.firstVisibleItemIndex
                ReadingDirection.BOOK -> pagerState.currentPage
            }

            // Apply to the new mode
            when (prefs.readingDirection) {
                ReadingDirection.VERTICAL -> {
                    if (currentPage >= 0 && currentPage < totalItems) {
                        listState.scrollToItem(currentPage)
                    }
                }
                ReadingDirection.BOOK -> {
                    if (currentPage >= 0 && currentPage < totalItems) {
                        pagerState.scrollToPage(currentPage)
                    }
                }
            }
            previousDirection = prefs.readingDirection
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = prefs.theme.backgroundColor(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Loading state - clean loader with optional message
            if (isLoading && items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (loadingMessage != null) {
                            Text(
                                text = loadingMessage,
                                color = prefs.theme.textColor(),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            } else if (ocrProgress != null && items.none { it is NovelUiItem.Content } && showTextMode) {
                // OCR in progress but no text yet - show full loading indicator (only in text mode)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { onToggleMenu() },
                                onLongPress = { offset ->
                                    // Not on specific page content, use page 0
                                    onLongPress(offset.x, offset.y, 0)
                                },
                            )
                        }
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.size(16.dp))
                    Text(
                        text = "Extrayendo texto...",
                        style = MaterialTheme.typography.titleMedium,
                        color = prefs.theme.textColor(),
                    )
                    Text(
                        text = "${ocrProgress.first} / ${ocrProgress.second}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = prefs.theme.textColor().copy(alpha = 0.7f),
                    )
                }
            } else {
                // Main content based on reading direction
                when (prefs.readingDirection) {
                    ReadingDirection.VERTICAL -> {
                        // Vertical scroll (LazyColumn)
                        val nestedScrollConnection = remember(items) {
                            object : NestedScrollConnection {
                                override fun onPostScroll(
                                    consumed: Offset,
                                    available: Offset,
                                    source: NestedScrollSource,
                                ): Offset {
                                    // Detect overscroll at bottom (dragging up, available.y < 0)
                                    if (available.y < -30f && source == NestedScrollSource.Drag) {
                                        // Find the Next transition (could be at end or before preserved items)
                                        val nextTransition = items.filterIsInstance<NovelUiItem.Transition>()
                                            .find {
                                                it.transition is eu.kanade.tachiyomi.ui.reader.model.ChapterTransition.Next
                                            }
                                        if (nextTransition != null) {
                                            onTransitionClick(nextTransition.transition)
                                        }
                                    }
                                    // Detect overscroll at top (dragging down, available.y > 0)
                                    if (available.y > 30f && source == NestedScrollSource.Drag) {
                                        // Find the Prev transition (may not be first item due to preserved content)
                                        val prevTransition = items.filterIsInstance<NovelUiItem.Transition>()
                                            .find {
                                                it.transition is eu.kanade.tachiyomi.ui.reader.model.ChapterTransition.Prev
                                            }
                                        if (prevTransition != null) {
                                            // Only trigger if we're near the top (within first 3 items)
                                            val firstVisible = listState.firstVisibleItemIndex
                                            if (firstVisible <= 2) {
                                                onTransitionClick(prevTransition.transition)
                                            }
                                        }
                                    }
                                    return super.onPostScroll(consumed, available, source)
                                }
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(nestedScrollConnection)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { onToggleMenu() },
                                        onLongPress = { offset ->
                                            val currentPageIndex = listState.firstVisibleItemIndex
                                            onLongPress(offset.x, offset.y, currentPageIndex)
                                        },
                                    )
                                }
                                .padding(horizontal = 16.dp),
                            contentPadding = PaddingValues(vertical = 12.dp),
                        ) {
                            // Unified Hybrid Rendering (Index by Index)
                            val totalItems = items.size

                            // Unified Hybrid Rendering (Index by Index)
                            items(
                                count = totalItems,
                                key = { index ->
                                    val item = items.getOrNull(index)
                                    when (item) {
                                        is NovelUiItem.Content -> "content_${item.chapter.chapter.id}_${item.index}"
                                        is NovelUiItem.Header -> "header_${item.chapter.chapter.id}"
                                        is NovelUiItem.Transition -> "trans_${item.transition.from.chapter.id}_${item.transition.to?.chapter?.id}"
                                        is NovelUiItem.Image -> "img_${item.url}_${item.index}"
                                        null -> "empty_$index"
                                    }
                                },
                            ) { index ->
                                val item = items.getOrNull(index)

                                when (item) {
                                    is NovelUiItem.Header -> {
                                        // Optional: Header content if needed
                                    }
                                    is NovelUiItem.Content -> {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 24.dp),
                                        ) {
                                            // Page number divider
                                            if (item.index > 0) {
                                                Text(
                                                    text = "— ${item.index + 1} —",
                                                    color = prefs.theme.textColor().copy(alpha = 0.4f),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(bottom = 8.dp),
                                                    textAlign = TextAlign.Center,
                                                )
                                            }

                                            // Text content
                                            val textColor = prefs.theme.textColor()

                                            if (item.text.isBlank()) {
                                                // Placeholder for Loading/Unextracted pages
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(200.dp) // Substantial height for visual feedback
                                                        .padding(16.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(24.dp),
                                                            strokeWidth = 2.dp,
                                                            color = MaterialTheme.colorScheme.primary,
                                                        )
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        Text(
                                                            text = "Cargando página ${item.index + 1}...",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = textColor.copy(alpha = 0.6f),
                                                        )
                                                    }
                                                }
                                            } else {
                                                val annotatedText = remember(item.text, prefs.fontSizeSp, textColor) {
                                                    androidx.compose.ui.text.buildAnnotatedString {
                                                        val lines = item.text.split("\n")
                                                        lines.forEachIndexed { i, line ->
                                                            val cleanLine = line.trim()
                                                            val isTitle =
                                                                cleanLine.isNotEmpty() && cleanLine.length < 100 && (
                                                                    cleanLine.startsWith("Capítulo", true) ||
                                                                        cleanLine.startsWith("Chapter", true) ||
                                                                        cleanLine.startsWith("Epílogo", true) ||
                                                                        cleanLine.startsWith("Epilogue", true) ||
                                                                        cleanLine.startsWith("Prólogo", true) ||
                                                                        cleanLine.startsWith("Prologue", true)
                                                                    )

                                                            if (isTitle) {
                                                                pushStyle(
                                                                    androidx.compose.ui.text.SpanStyle(
                                                                        fontSize = (prefs.fontSizeSp * 1.4f).sp,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = textColor,
                                                                    ),
                                                                )
                                                                append(line)
                                                                pop()
                                                            } else {
                                                                pushStyle(
                                                                    androidx.compose.ui.text.SpanStyle(
                                                                        fontSize = prefs.fontSizeSp.sp,
                                                                        color = textColor,
                                                                    ),
                                                                )
                                                                append(line)
                                                                pop()
                                                            }
                                                            if (i < lines.lastIndex) append("\n")
                                                        }
                                                    }
                                                }

                                                Text(
                                                    text = annotatedText,
                                                    style = TextStyle(
                                                        lineHeight = (prefs.fontSizeSp * prefs.lineHeightEm).sp,
                                                        textAlign = TextAlign.Justify,
                                                    ),
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                            }
                                        }
                                    }
                                    is NovelUiItem.Image -> {
                                        // Check if this is a PDF page
                                        if (item.url.startsWith("pdf://") && onRenderPage != null) {
                                            // Render PDF page directly
                                            val pageIndex = item.url.removePrefix("pdf://")
                                                .toIntOrNull() ?: 0
                                            PdfPageImage(
                                                pageIndex = pageIndex,
                                                onRenderPage = onRenderPage,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 8.dp),
                                                contentScale = ContentScale.FillWidth,
                                            )
                                        } else {
                                            // Use OptimizedReaderImage for regular images
                                            OptimizedReaderImage(
                                                page = item.page,
                                                imageUrl = item.url,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 8.dp),
                                                contentScale = ContentScale.FillWidth,
                                            )
                                        }
                                    }
                                    is NovelUiItem.Transition -> {
                                        // Transition UI - shows chapter navigation
                                        val transition = item.transition
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 32.dp, horizontal = 16.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            // Divider line
                                            Divider(
                                                modifier = Modifier
                                                    .fillMaxWidth(0.6f)
                                                    .padding(bottom = 16.dp),
                                                color = prefs.theme.textColor().copy(alpha = 0.3f),
                                            )

                                            when (transition) {
                                                is eu.kanade.tachiyomi.ui.reader.model
                                                    .ChapterTransition.Next,
                                                -> {
                                                    Text(
                                                        text = "Siguiente capítulo",
                                                        color = prefs.theme.textColor().copy(alpha = 0.7f),
                                                        style = MaterialTheme.typography.labelMedium,
                                                    )
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Text(
                                                        text = transition.to?.chapter?.name ?: "Fin",
                                                        color = prefs.theme.textColor(),
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        textAlign = TextAlign.Center,
                                                    )
                                                }
                                                is eu.kanade.tachiyomi.ui.reader.model
                                                    .ChapterTransition.Prev,
                                                -> {
                                                    Text(
                                                        text = "Capítulo anterior",
                                                        color = prefs.theme.textColor().copy(alpha = 0.7f),
                                                        style = MaterialTheme.typography.labelMedium,
                                                    )
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Text(
                                                        text = transition.to?.chapter?.name ?: "Inicio",
                                                        color = prefs.theme.textColor(),
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        textAlign = TextAlign.Center,
                                                    )
                                                }
                                            }

                                            // Divider line
                                            Divider(
                                                modifier = Modifier
                                                    .fillMaxWidth(0.6f)
                                                    .padding(top = 16.dp),
                                                color = prefs.theme.textColor().copy(alpha = 0.3f),
                                            )
                                        }
                                    }
                                    null -> {
                                        // Empty placeholder
                                    }
                                }
                            }

                            item {
                                Spacer(modifier = Modifier.padding(120.dp))
                            }
                        }
                    }
                    ReadingDirection.BOOK -> {
                        // Book mode with horizontal page swipe
                        // Detect when user swipes to a transition page and auto-navigate
                        LaunchedEffect(pagerState.currentPage, items) {
                            val currentItem = items.getOrNull(pagerState.currentPage)
                            if (currentItem is NovelUiItem.Transition) {
                                // Small delay to let the user see the transition before navigating
                                kotlinx.coroutines.delay(300)
                                onTransitionClick(currentItem.transition)
                            }
                        }

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { onToggleMenu() },
                                        onLongPress = { offset ->
                                            val currentPageIndex = pagerState.currentPage
                                            onLongPress(offset.x, offset.y, currentPageIndex)
                                        },
                                    )
                                },
                            beyondViewportPageCount = 1,
                            flingBehavior = PagerDefaults.flingBehavior(
                                state = pagerState,
                                snapAnimationSpec = tween(durationMillis = 150),
                            ),
                        ) { pageIndex ->
                            val item = items.getOrNull(pageIndex)

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                when (item) {
                                    is NovelUiItem.Header -> {
                                        // Optional: Header content if needed
                                    }
                                    is NovelUiItem.Content -> {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .verticalScroll(rememberScrollState())
                                                .padding(vertical = 16.dp),
                                        ) {
                                            // Page number
                                            Text(
                                                text = "— ${item.index + 1} —",
                                                color = prefs.theme.textColor().copy(alpha = 0.4f),
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(bottom = 8.dp),
                                                textAlign = TextAlign.Center,
                                            )

                                            val textColor = prefs.theme.textColor()

                                            if (item.text.isBlank()) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .weight(1f),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                    ) {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(24.dp),
                                                            strokeWidth = 2.dp,
                                                            color = MaterialTheme.colorScheme.primary,
                                                        )
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        Text(
                                                            text = "Cargando página ${item.index + 1}...",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = textColor.copy(alpha = 0.6f),
                                                        )
                                                    }
                                                }
                                            } else {
                                                Text(
                                                    text = item.text,
                                                    style = TextStyle(
                                                        fontSize = prefs.fontSizeSp.sp,
                                                        lineHeight = (prefs.fontSizeSp * prefs.lineHeightEm).sp,
                                                        color = textColor,
                                                        textAlign = TextAlign.Justify,
                                                    ),
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                            }
                                        }
                                    }
                                    is NovelUiItem.Image -> {
                                        // Check if this is a PDF page
                                        if (item.url.startsWith("pdf://") && onRenderPage != null) {
                                            // Render PDF page directly
                                            val pdfPageIndex = item.url.removePrefix("pdf://")
                                                .toIntOrNull() ?: 0
                                            PdfPageImage(
                                                pageIndex = pdfPageIndex,
                                                onRenderPage = onRenderPage,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(vertical = 8.dp),
                                                contentScale = ContentScale.Fit,
                                            )
                                        } else {
                                            OptimizedReaderImage(
                                                page = item.page,
                                                imageUrl = item.url,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(vertical = 8.dp),
                                                contentScale = ContentScale.Fit,
                                            )
                                        }
                                    }
                                    is NovelUiItem.Transition -> {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .pointerInput(item.transition) {
                                                    detectTapGestures(
                                                        onTap = { onTransitionClick(item.transition) },
                                                    )
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            ChapterTransition(
                                                transition = item.transition,
                                                currChapterDownloaded = false,
                                                goingToChapterDownloaded = false,
                                            )
                                        }
                                    }
                                    null -> {
                                        // Empty placeholder
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Only show menu if not force-hidden (for capture)
            // Using if instead of AnimatedVisibility when force-hiding to avoid exit animation
            if (!forceHideMenu) {
                AnimatedVisibility(
                    visible = menuVisible,
                    enter = slideInHorizontally { -it },
                    exit = slideOutHorizontally { -it },
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    NovelReaderControls(
                        prefs = prefs,
                        hasImages = items.any { it is NovelUiItem.Image },
                        showTextMode = showTextMode,
                        hasExtractedText = hasExtractedText,
                        hasOriginalImages = hasOriginalImages,
                        isPdfMode = isPdfMode,
                        onPrefsChanged = onPrefsChanged,
                        onExtractOcr = onExtractOcr,
                        onToggleTextMode = onToggleTextMode,
                        onToggleMenu = onToggleMenu,
                        hasToc = tocItems.isNotEmpty(),
                        onShowToc = { showTocModal = true },
                        onAiClick = onAiClick,
                    )
                }
            }

            // TOC Modal
            if (showTocModal && tocItems.isNotEmpty()) {
                val currentPage = when (prefs.readingDirection) {
                    ReadingDirection.VERTICAL -> listState.firstVisibleItemIndex
                    ReadingDirection.BOOK -> pagerState.currentPage
                }
                PdfTocModal(
                    tocItems = tocItems,
                    currentPage = currentPage,
                    onNavigate = { page ->
                        showTocModal = false
                        onTocNavigate(page)
                    },
                    onDismiss = { showTocModal = false },
                )
            }
        }
    }
}

@Composable
fun NovelReaderControls(
    prefs: NovelPrefs,
    hasImages: Boolean,
    showTextMode: Boolean = true,
    hasExtractedText: Boolean = false,
    hasOriginalImages: Boolean = false,
    isPdfMode: Boolean = false,
    onPrefsChanged: (NovelPrefs) -> Unit,
    onExtractOcr: () -> Unit,
    onToggleTextMode: () -> Unit = {},
    onToggleMenu: () -> Unit,
    hasToc: Boolean = false,
    onShowToc: () -> Unit = {},
    onAiClick: () -> Unit = {},
) {
    var showFontSettings by remember { mutableStateOf(false) }
    var showThemeSettings by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .width(280.dp)
            .padding(top = 70.dp, bottom = 90.dp),
        shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 4.dp,
        shadowElevation = 16.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            // Header - more compact
            Text(
                "Ajustes",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp, start = 4.dp),
            )

            // Reading Direction Toggle
            Text(
                "Dirección de Lectura",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp, start = 4.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Vertical button
                FilledTonalButton(
                    onClick = { onPrefsChanged(prefs.copy(readingDirection = ReadingDirection.VERTICAL)) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = if (prefs.readingDirection == ReadingDirection.VERTICAL) {
                        androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()
                    },
                ) {
                    Icon(
                        Icons.Default.SwapVert,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Scroll", style = MaterialTheme.typography.labelSmall)
                }

                // Book button
                FilledTonalButton(
                    onClick = { onPrefsChanged(prefs.copy(readingDirection = ReadingDirection.BOOK)) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = if (prefs.readingDirection == ReadingDirection.BOOK) {
                        androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()
                    },
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Libro", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.size(12.dp))

            // Toggle Text/Image mode - Show when we have original images
            // For PDFs: show toggle even without extracted text (can toggle between PDF pages and text view)
            // For OCR images: show toggle when we have both text and images
            if (hasOriginalImages && (hasExtractedText || isPdfMode)) {
                FilledTonalButton(
                    onClick = {
                        onToggleTextMode()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        if (showTextMode) {
                            Icons.Default.Visibility
                        } else {
                            Icons.Default.TextFields
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (showTextMode) "Ver Imágenes" else "Ver Texto",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
            }

            // OCR Button - Show when images are available but NOT for PDFs
            // (PDFs use MuPDF for direct text extraction, not OCR)
            if (hasImages && !isPdfMode) {
                FilledTonalButton(
                    onClick = {
                        onToggleMenu()
                        onExtractOcr()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Extraer Texto (OCR)", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(modifier = Modifier.size(8.dp))
            }

            // TOC Button - Show when PDF has table of contents
            if (hasToc) {
                FilledTonalButton(
                    onClick = {
                        onShowToc()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Tabla de Contenido", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(modifier = Modifier.size(12.dp))
            }

            // Font Size - Collapsible section
            SettingsSection(
                title = "Tamaño: ${prefs.fontSizeSp}sp",
                expanded = showFontSettings,
                onToggle = { showFontSettings = !showFontSettings },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "A",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "A",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = prefs.fontSizeSp.toFloat(),
                    onValueChange = { onPrefsChanged(prefs.copy(fontSizeSp = it.toInt())) },
                    valueRange = 12f..36f,
                    steps = 11,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.size(4.dp))

            // Theme - Collapsible section
            SettingsSection(
                title = "Tema: ${prefs.theme.name}",
                expanded = showThemeSettings,
                onToggle = { showThemeSettings = !showThemeSettings },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    NovelTheme.entries.forEach { theme ->
                        MiniThemeChip(
                            theme = theme,
                            isSelected = prefs.theme == theme,
                            onClick = { onPrefsChanged(prefs.copy(theme = theme)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun MiniThemeChip(
    theme: NovelTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor = when (theme) {
        NovelTheme.SYSTEM -> MaterialTheme.colorScheme.surface
        NovelTheme.DARK -> Color.Black
        NovelTheme.LIGHT -> Color.White
        NovelTheme.SEPIA -> Color(0xFFEADCC0)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        },
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Text(
                text = theme.name.first().toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = when (theme) {
                    NovelTheme.DARK -> Color.White
                    NovelTheme.SEPIA -> Color(0xFF5D4037)
                    else -> Color.Black
                },
            )
        }
    }
}

@Composable
private fun ThemeChip(
    theme: NovelTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            Text(
                text = theme.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

data class NovelPrefs(
    val fontSizeSp: Int = 18,
    val lineHeightEm: Float = 1.4f,
    val theme: NovelTheme = NovelTheme.SYSTEM,
    val readingDirection: ReadingDirection = ReadingDirection.VERTICAL,
)

enum class NovelTheme {
    SYSTEM,
    DARK,
    LIGHT,
    SEPIA,
    ;

    @Composable
    fun textColor(): Color = when (this) {
        SYSTEM -> MaterialTheme.colorScheme.onBackground
        DARK -> Color.White
        LIGHT -> Color(0xFF101010)
        SEPIA -> Color(0xFF5D4037)
    }

    @Composable
    fun backgroundColor(): Color = when (this) {
        SYSTEM -> MaterialTheme.colorScheme.background
        DARK -> Color.Black
        LIGHT -> Color(0xFFFDFDFD)
        SEPIA -> Color(0xFFEADCC0)
    }
}

/**
 * Composable that renders a PDF page using the onRenderPage callback.
 * Used when viewing local PDFs in image mode (not text reflow).
 */
@Composable
fun PdfPageImage(
    pageIndex: Int,
    onRenderPage: suspend (Int, Int) -> android.graphics.Bitmap?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillWidth,
) {
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Get screen width like PdfPageRenderer does
    val context = androidx.compose.ui.platform.LocalContext.current
    val screenWidth = remember {
        context.resources.displayMetrics.widthPixels
    }

    // Render the PDF page asynchronously
    LaunchedEffect(pageIndex) {
        isLoading = true
        try {
            bitmap = onRenderPage(pageIndex, screenWidth)
        } catch (e: Exception) {
            logcat { "PdfPageImage: Failed to render page $pageIndex: ${e.message}" }
        } finally {
            isLoading = false
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading || bitmap == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.dp,
                )
            }
        } else {
            bitmap?.let { bmp ->
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "PDF Page ${pageIndex + 1}",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = contentScale,
                )
            }
        }
    }
}
