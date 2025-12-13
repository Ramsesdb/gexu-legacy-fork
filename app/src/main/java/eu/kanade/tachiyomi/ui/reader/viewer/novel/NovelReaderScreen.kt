package eu.kanade.tachiyomi.ui.reader.viewer.novel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

@Composable
fun NovelReaderScreen(
    textPages: List<String>,
    images: List<String>,
    url: String? = null,
    isLoading: Boolean = false,
    loadingMessage: String? = null,
    ocrProgress: Pair<Int, Int>? = null, // current, total
    initialOffset: Long,
    prefs: NovelPrefs,
    menuVisible: Boolean,
    onOffsetChanged: (Long) -> Unit,
    onPrefsChanged: (NovelPrefs) -> Unit,
    onExtractOcr: () -> Unit,
    onRenderPage: (suspend (Int, Int) -> android.graphics.Bitmap?)? = null,
    onBack: () -> Unit,
    onToggleMenu: () -> Unit,
    onPageChanged: (Int) -> Unit,
) {
    val listState = rememberLazyListState()

    // Notify parent of current page changes with debounce/throttling
    // snapshotFlow emits only when the value changes (distinctUntilChanged is implicit)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                // Basic throttling validation
                if (index >= 0) {
                     onPageChanged(index)
                }
            }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = prefs.theme.backgroundColor()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // Loading state - clean loader with optional message
            if (isLoading && textPages.isEmpty() && images.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (loadingMessage != null) {
                            Text(
                                text = loadingMessage,
                                color = prefs.theme.textColor(),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else if (ocrProgress != null && textPages.isEmpty()) {
                // OCR in progress but no text yet - show full loading indicator
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { onToggleMenu() })
                        }
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.size(16.dp))
                    Text(
                        text = "Extrayendo texto...",
                        style = MaterialTheme.typography.titleMedium,
                        color = prefs.theme.textColor()
                    )
                    Text(
                        text = "${ocrProgress.first} / ${ocrProgress.second}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = prefs.theme.textColor().copy(alpha = 0.7f)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                onToggleMenu()
                            })
                        }
                        .padding(horizontal = 16.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
                ) {
                    // 1. Show extracted text first
                    if (textPages.isNotEmpty()) {
                        items(textPages) { pageText ->
                            Text(
                                text = pageText,
                                style = TextStyle(
                                    fontSize = prefs.fontSizeSp.sp,
                                    lineHeight = (prefs.fontSizeSp * prefs.lineHeightEm).sp,
                                    color = prefs.theme.textColor()
                                ),
                                modifier = Modifier.padding(bottom = 24.dp)
                            )
                        }
                    }

                    // 2. Show remaining images (those that haven't been processed yet)
                    // This creates the effect of replacing images with text as OCR progresses
                    val processedCount = textPages.size
                    if (images.size > processedCount) {
                        val remainingImages = images.subList(processedCount, images.size)
                        items(remainingImages) { imageUrl ->
                            if (imageUrl.startsWith("pdf://")) {
                                val pageIndex = imageUrl.removePrefix("pdf://").toIntOrNull() ?: 0
                                PdfPageItem(
                                    pageIndex = pageIndex,
                                    renderer = onRenderPage,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                )
                            } else {
                                AsyncImage(
                                    model = imageUrl,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    contentScale = ContentScale.FillWidth
                                )
                            }
                        }
                        item {
                             Spacer(modifier = Modifier.padding(16.dp))
                        }
                    }

                    // Progress bar at bottom content when OCR is running
                    if (ocrProgress != null && textPages.isNotEmpty()) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                color = prefs.theme.backgroundColor(),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    androidx.compose.material3.LinearProgressIndicator(
                                        progress = { ocrProgress.first.toFloat() / ocrProgress.second.toFloat() },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    Text(
                                        text = "Extrayendo texto: ${ocrProgress.first} de ${ocrProgress.second}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = prefs.theme.textColor().copy(alpha = 0.7f),
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Extra spacer at bottom for main menu
                    item {
                        Spacer(modifier = Modifier.padding(bottom = 120.dp))
                    }
                }
            }

            AnimatedVisibility(
                visible = menuVisible,
                enter = slideInHorizontally { -it },
                exit = slideOutHorizontally { -it },
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                NovelReaderControls(
                    prefs = prefs,
                    hasImages = images.isNotEmpty(),
                    onPrefsChanged = onPrefsChanged,
                    onExtractOcr = onExtractOcr,
                    onToggleMenu = onToggleMenu
                )
            }
        }
    }
}

@Composable
fun NovelReaderControls(
    prefs: NovelPrefs,
    hasImages: Boolean,
    onPrefsChanged: (NovelPrefs) -> Unit,
    onExtractOcr: () -> Unit,
    onToggleMenu: () -> Unit,
) {
    var showFontSettings by remember { mutableStateOf(false) }
    var showThemeSettings by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .width(260.dp)
            .padding(top = 70.dp, bottom = 90.dp),
        shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 4.dp,
        shadowElevation = 16.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Header - more compact
            Text(
                "Ajustes",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
            )

            // OCR Button - Always visible at top when images present
            if (hasImages) {
                androidx.compose.material3.FilledTonalButton(
                    onClick = {
                        onToggleMenu()
                        onExtractOcr()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Extraer Texto (OCR)", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(modifier = Modifier.size(12.dp))
            }

            // Font Size - Collapsible section
            SettingsSection(
                title = "Tamaño: ${prefs.fontSizeSp}sp",
                expanded = showFontSettings,
                onToggle = { showFontSettings = !showFontSettings }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("A", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("A", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Slider(
                    value = prefs.fontSizeSp.toFloat(),
                    onValueChange = { onPrefsChanged(prefs.copy(fontSizeSp = it.toInt())) },
                    valueRange = 12f..36f,
                    steps = 11,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.size(4.dp))

            // Theme - Collapsible section
            SettingsSection(
                title = "Tema: ${prefs.theme.name}",
                expanded = showThemeSettings,
                onToggle = { showThemeSettings = !showThemeSettings }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    NovelTheme.entries.forEach { theme ->
                        MiniThemeChip(
                            theme = theme,
                            isSelected = prefs.theme == theme,
                            onClick = { onPrefsChanged(prefs.copy(theme = theme)) },
                            modifier = Modifier.weight(1f)
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
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
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
    modifier: Modifier = Modifier
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
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        },
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Text(
                text = theme.name.first().toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = when (theme) {
                    NovelTheme.DARK -> Color.White
                    NovelTheme.SEPIA -> Color(0xFF5D4037)
                    else -> Color.Black
                }
            )
        }
    }
}

@Composable
private fun ThemeChip(
    theme: NovelTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(vertical = 12.dp)
        ) {
            Text(
                text = theme.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

data class NovelPrefs(
    val fontSizeSp: Int = 18,
    val lineHeightEm: Float = 1.4f,
    val theme: NovelTheme = NovelTheme.SYSTEM,
)

enum class NovelTheme {
    SYSTEM, DARK, LIGHT, SEPIA;

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
