package eu.kanade.tachiyomi.ui.reader

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artifex.mupdf.fitz.Document
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelTheme
import eu.kanade.tachiyomi.util.MuPdfUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.logcat
import java.io.File
import kotlin.math.absoluteValue

/**
 * Activity to directly read PDF files from external apps.
 * Opens the PDF immediately showing original pages without importing to library.
 */
class DirectPdfReaderActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        val uri = intent.data ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        if (uri == null) {
            Toast.makeText(this, "No se pudo abrir el archivo PDF", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val fileName = getFileName(uri) ?: "PDF"
        logcat { "DirectPdfReaderActivity: Opening PDF: $fileName from $uri" }

        setContent {
            TachiyomiTheme {
                DirectPdfReader(
                    uri = uri,
                    fileName = fileName,
                    onBack = { finish() },
                )
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            if (nameIndex >= 0) cursor.getString(nameIndex) else null
        } ?: uri.lastPathSegment
    }
}

// View modes for the PDF reader
enum class PdfViewMode {
    PDF_ORIGINAL, // Show PDF pages as images (vertical scroll)
    PDF_BOOK, // Show PDF pages as images (horizontal, like book)
    TEXT_SCROLL, // Show extracted text with vertical scroll
    TEXT_BOOK, // Show extracted text page by page (like a book)
}

// Preferences for the direct PDF reader
data class DirectPdfPrefs(
    val viewMode: PdfViewMode = PdfViewMode.PDF_ORIGINAL,
    val fontSizeSp: Int = 18,
    val theme: NovelTheme = NovelTheme.DARK,
)

@Composable
fun DirectPdfReader(
    uri: Uri,
    fileName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // PDF document state - separate documents for rendering vs text extraction
    var pdfDocument by remember { mutableStateOf<Document?>(null) }
    var textExtractionDocument by remember { mutableStateOf<Document?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var currentPage by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tempPdfFile by remember { mutableStateOf<File?>(null) }

    // Single-threaded executor for PDF rendering to avoid native concurrency issues
    val pdfExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }
    val pdfRenderDispatcher = remember(pdfExecutor) { pdfExecutor.asCoroutineDispatcher() }
    val isClosed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    // Mutex for text extraction document to prevent race with closing
    val textMutex = remember { kotlinx.coroutines.sync.Mutex() }

    // UI state
    var menuVisible by remember { mutableStateOf(false) }
    var prefs by remember { mutableStateOf(DirectPdfPrefs()) }

    // Text extraction state
    var textPages by remember { mutableStateOf<List<String>>(emptyList()) }
    var isExtractingText by remember { mutableStateOf(false) }
    var extractionProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Page cache for rendered bitmaps
    val pageCache = remember { mutableMapOf<Int, Bitmap>() }

    val listState = rememberLazyListState()

    // Open PDF on launch
    LaunchedEffect(uri) {
        try {
            withContext(Dispatchers.IO) {
                // Copy PDF to temp file
                val tempFile = File(context.cacheDir, "direct_pdf_${System.currentTimeMillis()}.pdf")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw Exception("No se pudo leer el archivo")

                tempPdfFile = tempFile

                // Open with MuPDF - one for rendering
                val doc = MuPdfUtil.openDocument(tempFile.absolutePath)
                if (doc == null) {
                    throw Exception("No se pudo abrir el PDF")
                }

                // Open a second instance for text extraction (avoids concurrency issues)
                val textDoc = MuPdfUtil.openDocument(tempFile.absolutePath)

                val count = MuPdfUtil.getPageCount(doc)

                withContext(Dispatchers.Main) {
                    pdfDocument = doc
                    textExtractionDocument = textDoc
                    pageCount = count
                    isLoading = false
                }
            }
        } catch (e: Exception) {
            logcat { "DirectPdfReaderActivity: Error opening PDF: ${e.message}" }
            withContext(Dispatchers.Main) {
                error = "Error al abrir el PDF: ${e.message}"
                isLoading = false
            }
        }
    }

    // Track current page from scroll (for vertical modes)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                if (index >= 0) {
                    currentPage = index
                }
            }
    }

    // Sync UI with currentPage whenever viewMode changes
    LaunchedEffect(prefs.viewMode) {
        if (prefs.viewMode == PdfViewMode.PDF_ORIGINAL || prefs.viewMode == PdfViewMode.TEXT_SCROLL) {
            listState.scrollToItem(currentPage)
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            isClosed.set(true) // Flag as closed to prevent new renders

            // Close documents
            // 1. Rendering Doc (Serialized on executor)
            pdfExecutor.execute {
                pdfDocument?.let { MuPdfUtil.closeDocument(it) }
                tempPdfFile?.delete()
                pageCache.values.forEach { it.recycle() }
            }

            // 2. Text Extraction Doc (Protected by Mutex, closed in background IO)
            val textDoc = textExtractionDocument
            textExtractionDocument = null
            // Launch in global/independent scope to ensure it runs even if composable scope is cancelled
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                textMutex.withLock {
                    textDoc?.let { MuPdfUtil.closeDocument(it) }
                }
            }

            // Shutdown dispatchers
            pdfRenderDispatcher.close()
        }
    }

    // Function to extract text - optimized for instant feel
    fun extractText(switchToBookMode: Boolean = false) {
        val doc = textExtractionDocument ?: return
        if (isExtractingText) return

        val totalPages = pageCount
        val startPage = currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))

        // Initialize with empty pages and switch mode IMMEDIATELY
        textPages = List(totalPages) { "" }
        prefs = prefs.copy(
            viewMode = if (switchToBookMode) PdfViewMode.TEXT_BOOK else PdfViewMode.TEXT_SCROLL,
        )

        // Extract in background
        scope.launch(Dispatchers.Default) {
            isExtractingText = true
            val extractedPages = ArrayList<String>(totalPages)
            repeat(totalPages) { extractedPages.add("") }

            try {
                // Helper to safely extract a page
                suspend fun safeExtract(page: Int): String {
                    if (isClosed.get()) return ""
                    return textMutex.withLock {
                        if (isClosed.get() || textExtractionDocument == null) return@withLock ""
                        MuPdfUtil.extractPageText(textExtractionDocument!!, page)
                    }
                }

                // Extract current page FIRST for instant feedback
                if (startPage in extractedPages.indices) {
                    extractedPages[startPage] = safeExtract(startPage)
                    withContext(Dispatchers.Main) { textPages = extractedPages.toList() }
                }

                // Extract radiating outward from current position
                var extracted = 1
                val maxRadius = maxOf(startPage, totalPages - startPage - 1)

                for (radius in 1..maxRadius) {
                    if (!isActive || isClosed.get()) break

                    val before = startPage - radius
                    if (before >= 0) {
                        extractedPages[before] = safeExtract(before)
                        extracted++
                    }

                    val after = startPage + radius
                    if (after < totalPages) {
                        extractedPages[after] = safeExtract(after)
                        extracted++
                    }

                    // Update UI every 5 pages
                    if (extracted % 5 == 0) {
                        withContext(Dispatchers.Main) {
                            textPages = extractedPages.toList()
                            extractionProgress = extracted to totalPages
                        }
                        kotlinx.coroutines.yield()
                    }
                }

                // Final update
                withContext(Dispatchers.Main) {
                    textPages = extractedPages.toList()

                    if (extractedPages.all { it.isBlank() }) {
                        Toast.makeText(
                            context,
                            "El PDF no contiene texto extraíble (puede ser escaneado)",
                            Toast.LENGTH_LONG,
                        ).show()
                        prefs = prefs.copy(viewMode = PdfViewMode.PDF_ORIGINAL)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isExtractingText = false
                    extractionProgress = null
                }
            }
        }
    }

    // Render page function - uses single-threaded dispatcher for native safety
    suspend fun renderPage(pageIndex: Int, width: Int): Bitmap? {
        if (pageIndex < 0 || pageIndex >= pageCount) return null
        pageCache[pageIndex]?.let { return it }
        val doc = pdfDocument ?: return null

        return withContext(pdfRenderDispatcher) {
            // Strict checks to avoid use-after-close race conditions
            if (isClosed.get()) return@withContext null
            try {
                // Double check cancellation
                ensureActive()
                MuPdfUtil.renderPage(doc, pageIndex, width)?.also { bitmap ->
                    pageCache[pageIndex] = bitmap
                }
            } catch (e: Exception) {
                logcat { "Error rendering page $pageIndex: ${e.message}" }
                null
            }
        }
    }

    // Determine total items based on view mode
    val totalItems = when (prefs.viewMode) {
        PdfViewMode.PDF_ORIGINAL, PdfViewMode.PDF_BOOK -> pageCount
        PdfViewMode.TEXT_SCROLL, PdfViewMode.TEXT_BOOK -> textPages.size.coerceAtLeast(1)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(prefs.theme.backgroundColor()),
    ) {
        when {
            isLoading -> {
                // Loading state
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = prefs.theme.textColor())
                    Spacer(modifier = Modifier.size(16.dp))
                    Text(
                        text = "Cargando PDF...",
                        color = prefs.theme.textColor(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = fileName,
                        color = prefs.theme.textColor().copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            error != null -> {
                // Error state
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "⚠️",
                        style = TextStyle(fontSize = 48.sp),
                    )
                    Spacer(modifier = Modifier.size(16.dp))
                    Text(
                        text = error!!,
                        color = prefs.theme.textColor(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            else -> {
                // Main content
                when (prefs.viewMode) {
                    PdfViewMode.PDF_ORIGINAL -> {
                        // PDF pages with vertical scroll
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { menuVisible = !menuVisible })
                                },
                        ) {
                            items(pageCount) { pageIndex ->
                                PdfPageRenderer(
                                    pageIndex = pageIndex,
                                    onRender = { width -> renderPage(pageIndex, width) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 4.dp),
                                )
                            }
                            item {
                                Spacer(modifier = Modifier.size(100.dp))
                            }
                        }
                    }

                    PdfViewMode.PDF_BOOK -> {
                        // PDF pages with horizontal pager (book style)
                        val pagerState = rememberPagerState(
                            initialPage = currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                            pageCount = { pageCount },
                        )

                        LaunchedEffect(pagerState) {
                            snapshotFlow { pagerState.currentPage }
                                .collect { page -> currentPage = page }
                        }

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { menuVisible = !menuVisible })
                                },
                            flingBehavior = PagerDefaults.flingBehavior(
                                state = pagerState,
                                snapAnimationSpec = tween(durationMillis = 300),
                            ),
                            pageSpacing = 8.dp,
                        ) { pageIndex ->
                            // Page flip effect
                            val pageOffset = (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        // Subtle page flip animation
                                        val scale = 1f - (pageOffset.absoluteValue * 0.1f).coerceIn(0f, 0.1f)
                                        scaleX = scale
                                        scaleY = scale
                                        alpha = 1f - (pageOffset.absoluteValue * 0.3f).coerceIn(0f, 0.3f)
                                    }
                                    .shadow(
                                        elevation = 4.dp,
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                    .background(Color.White),
                            ) {
                                PdfPageRenderer(
                                    pageIndex = pageIndex,
                                    onRender = { width -> renderPage(pageIndex, width) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }

                    PdfViewMode.TEXT_SCROLL -> {
                        // Text with vertical scroll
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { menuVisible = !menuVisible })
                                }
                                .padding(horizontal = 16.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                        ) {
                            itemsIndexed(textPages) { index, pageText ->
                                Column {
                                    Text(
                                        text = "— ${index + 1} —",
                                        color = prefs.theme.textColor().copy(alpha = 0.4f),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp),
                                        textAlign = TextAlign.Center,
                                    )

                                    Text(
                                        text = pageText,
                                        style = TextStyle(
                                            fontSize = prefs.fontSizeSp.sp,
                                            lineHeight = (prefs.fontSizeSp * 1.5f).sp,
                                            color = prefs.theme.textColor(),
                                        ),
                                        modifier = Modifier.padding(bottom = 24.dp),
                                    )
                                }
                            }

                            // Progress indicator if still extracting
                            if (isExtractingText && extractionProgress != null) {
                                item {
                                    ExtractionProgressIndicator(
                                        progress = extractionProgress!!,
                                        theme = prefs.theme,
                                    )
                                }
                            }

                            item {
                                Spacer(modifier = Modifier.size(100.dp))
                            }
                        }
                    }

                    PdfViewMode.TEXT_BOOK -> {
                        // Text with horizontal pager (book style - swipe pages)
                        val pagerState = rememberPagerState(
                            initialPage = 0,
                            pageCount = { textPages.size.coerceAtLeast(1) },
                        )

                        LaunchedEffect(pagerState) {
                            snapshotFlow { pagerState.currentPage }
                                .collect { page -> currentPage = page }
                        }

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { menuVisible = !menuVisible })
                                },
                            flingBehavior = PagerDefaults.flingBehavior(
                                state = pagerState,
                                snapAnimationSpec = tween(durationMillis = 300),
                            ),
                            pageSpacing = 0.dp,
                        ) { pageIndex ->
                            // Page flip effect
                            val pageOffset = (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        // Book page flip effect
                                        val scale = 1f - (pageOffset.absoluteValue * 0.05f).coerceIn(0f, 0.05f)
                                        scaleX = scale
                                        scaleY = scale

                                        // 3D rotation for page flip feel
                                        rotationY = pageOffset * -15f
                                        cameraDistance = 12f * density

                                        alpha = 1f - (pageOffset.absoluteValue * 0.2f).coerceIn(0f, 0.2f)
                                    }
                                    .background(prefs.theme.backgroundColor())
                                    .padding(horizontal = 24.dp, vertical = 16.dp),
                            ) {
                                if (pageIndex < textPages.size) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState()),
                                    ) {
                                        // Page number at top
                                        Text(
                                            text = "${pageIndex + 1}",
                                            color = prefs.theme.textColor().copy(alpha = 0.3f),
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 16.dp),
                                            textAlign = TextAlign.Center,
                                        )

                                        Text(
                                            text = textPages[pageIndex],
                                            style = TextStyle(
                                                fontSize = prefs.fontSizeSp.sp,
                                                lineHeight = (prefs.fontSizeSp * 1.6f).sp,
                                                color = prefs.theme.textColor(),
                                                textAlign = TextAlign.Justify,
                                            ),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                } else if (isExtractingText) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            CircularProgressIndicator(
                                                color = prefs.theme.textColor(),
                                                modifier = Modifier.size(32.dp),
                                                strokeWidth = 2.dp,
                                            )
                                            Spacer(modifier = Modifier.size(12.dp))
                                            Text(
                                                text = "Extrayendo...",
                                                color = prefs.theme.textColor().copy(alpha = 0.7f),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Extraction indicator overlay (when extracting in PDF mode)
                AnimatedVisibility(
                    visible =
                    isExtractingText &&
                        (prefs.viewMode == PdfViewMode.PDF_ORIGINAL || prefs.viewMode == PdfViewMode.PDF_BOOK),
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    Surface(
                        modifier = Modifier.padding(top = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Black.copy(alpha = 0.85f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = extractionProgress?.let { "${it.first}/${it.second}" } ?: "...",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }

                // Page counter (bottom right, minimal)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 12.dp),
                ) {
                    Text(
                        text = "${currentPage + 1}/$totalItems",
                        color = prefs.theme.textColor().copy(alpha = 0.4f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        // Settings menu (slide in from left)
        AnimatedVisibility(
            visible = menuVisible,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            DirectPdfReaderMenu(
                prefs = prefs,
                hasExtractedText = textPages.isNotEmpty(),
                isExtractingText = isExtractingText,
                onPrefsChanged = { prefs = it },
                onExtractText = { bookMode -> extractText(bookMode) },
                onDismiss = { menuVisible = false },
            )
        }
    }
}

@Composable
fun ExtractionProgressIndicator(
    progress: Pair<Int, Int>,
    theme: NovelTheme,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        shape = RoundedCornerShape(8.dp),
        color = theme.backgroundColor(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LinearProgressIndicator(
                progress = { progress.first.toFloat() / progress.second.toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "${progress.first}/${progress.second}",
                style = MaterialTheme.typography.labelSmall,
                color = theme.textColor().copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
fun PdfPageRenderer(
    pageIndex: Int,
    onRender: suspend (Int) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Get screen width
    val context = LocalContext.current
    val screenWidth = remember {
        context.resources.displayMetrics.widthPixels
    }

    LaunchedEffect(pageIndex) {
        isLoading = true
        bitmap = onRender(screenWidth)
        isLoading = false
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading || bitmap == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .size(300.dp),
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
                    contentDescription = "Página ${pageIndex + 1}",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                )
            }
        }
    }
}

@Composable
fun DirectPdfReaderMenu(
    prefs: DirectPdfPrefs,
    hasExtractedText: Boolean,
    isExtractingText: Boolean,
    onPrefsChanged: (DirectPdfPrefs) -> Unit,
    onExtractText: (Boolean) -> Unit, // Boolean = switch to book mode after extraction
    onDismiss: () -> Unit,
) {
    var showFontSettings by remember { mutableStateOf(false) }
    var showThemeSettings by remember { mutableStateOf(false) }

    val isPdfMode = prefs.viewMode == PdfViewMode.PDF_ORIGINAL || prefs.viewMode == PdfViewMode.PDF_BOOK
    val isBookMode = prefs.viewMode == PdfViewMode.PDF_BOOK || prefs.viewMode == PdfViewMode.TEXT_BOOK

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
            // Header
            Text(
                "Opciones",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp, start = 4.dp),
            )

            // Section: PDF View
            Text(
                "Visualización",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp, start = 4.dp),
            )

            // PDF Original (Vertical)
            ModeButton(
                text = "PDF Vertical",
                icon = Icons.Default.SwapVert,
                isSelected = prefs.viewMode == PdfViewMode.PDF_ORIGINAL,
                onClick = { onPrefsChanged(prefs.copy(viewMode = PdfViewMode.PDF_ORIGINAL)) },
            )

            Spacer(modifier = Modifier.size(4.dp))

            // PDF Book (Horizontal)
            ModeButton(
                text = "PDF Libro",
                icon = Icons.AutoMirrored.Filled.MenuBook,
                isSelected = prefs.viewMode == PdfViewMode.PDF_BOOK,
                onClick = { onPrefsChanged(prefs.copy(viewMode = PdfViewMode.PDF_BOOK)) },
            )

            Spacer(modifier = Modifier.size(12.dp))

            // Section: Text Mode
            Text(
                "Modo Texto",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp, start = 4.dp),
            )

            // Text Scroll (Vertical)
            FilledTonalButton(
                onClick = {
                    if (hasExtractedText) {
                        onPrefsChanged(prefs.copy(viewMode = PdfViewMode.TEXT_SCROLL))
                    } else if (!isExtractingText) {
                        onExtractText(false)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = !isExtractingText || hasExtractedText,
                colors = if (prefs.viewMode == PdfViewMode.TEXT_SCROLL) {
                    androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()
                },
            ) {
                if (isExtractingText && !hasExtractedText) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Icon(
                        Icons.Default.TextFields,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (hasExtractedText) "Texto Vertical" else "Extraer Texto",
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Spacer(modifier = Modifier.size(4.dp))

            // Text Book (Horizontal - like a book)
            FilledTonalButton(
                onClick = {
                    if (hasExtractedText) {
                        onPrefsChanged(prefs.copy(viewMode = PdfViewMode.TEXT_BOOK))
                    } else if (!isExtractingText) {
                        onExtractText(true)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = !isExtractingText || hasExtractedText,
                colors = if (prefs.viewMode == PdfViewMode.TEXT_BOOK) {
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
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (hasExtractedText) "Texto Libro" else "Extraer (Libro)",
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Spacer(modifier = Modifier.size(16.dp))

            // Font Size (for text modes)
            if (!isPdfMode || hasExtractedText) {
                DirectPdfSettingsSection(
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
            }

            // Theme
            DirectPdfSettingsSection(
                title = "Fondo: ${prefs.theme.name}",
                expanded = showThemeSettings,
                onToggle = { showThemeSettings = !showThemeSettings },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    NovelTheme.entries.forEach { theme ->
                        DirectPdfThemeChip(
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
private fun ModeButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = if (isSelected) {
            androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()
        },
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun DirectPdfSettingsSection(
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
private fun DirectPdfThemeChip(
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
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
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
