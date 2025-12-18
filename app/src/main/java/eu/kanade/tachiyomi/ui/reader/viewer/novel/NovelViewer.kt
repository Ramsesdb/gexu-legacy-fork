package eu.kanade.tachiyomi.ui.reader.viewer.novel

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.util.view.setComposeContent
import tachiyomi.domain.source.service.SourceManager
import eu.kanade.tachiyomi.source.online.HttpSource
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.injectLazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import okhttp3.Request
import androidx.compose.runtime.remember
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import eu.kanade.tachiyomi.util.PdfUtil
import eu.kanade.tachiyomi.util.MuPdfUtil
import java.io.File
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.source.model.SChapter
import coil3.imageLoader
import coil3.request.ImageRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import eu.kanade.presentation.theme.TachiyomiTheme
import logcat.LogPriority
import logcat.logcat
import okio.buffer
import okio.sink
import coil3.request.CachePolicy

class NovelViewer(private val activity: ReaderActivity) : Viewer {

    private val composeView = ComposeView(activity)

    private val sourceManager: SourceManager by injectLazy()
    private val getManga: GetManga by injectLazy()
    private val getPdfToc: tachiyomi.domain.pdftoc.interactor.GetPdfToc by injectLazy()
    private val savePdfToc: tachiyomi.domain.pdftoc.interactor.SavePdfToc by injectLazy()
    private val scope = MainScope()
    private val pdfMutex = kotlinx.coroutines.sync.Mutex()

    // State
    private val prefs = mutableStateOf(NovelPrefs())
    private val content = MutableStateFlow<List<String>>(emptyList())
    private val images = MutableStateFlow<List<String>>(emptyList())
    private val originalImages = MutableStateFlow<List<String>>(emptyList()) // Preserve original images for toggle
    private val currentUrl = MutableStateFlow<String?>(null)
    private val isLoading = MutableStateFlow(true)
    private val loadingMessage = MutableStateFlow<String?>(null)
    private val ocrProgress = MutableStateFlow<Pair<Int, Int>?>(null) // current, total
    private val lastUserVisiblePage = MutableStateFlow(0)
    private val showTextMode = MutableStateFlow(true) // true = show text, false = show images/PDF
    private val targetPageIndex = MutableStateFlow<Int?>(null) // Target page for slider navigation
    private val initialPageIndex = MutableStateFlow(0) // Initial page to start at (from saved position)

    // Page tracking for image-based chapters
    private var currentChapter: ReaderChapter? = null
    private var nextChapter: ReaderChapter? = null
    private var prevChapter: ReaderChapter? = null
    private var virtualPages: List<ReaderPage> = emptyList()
    private var preloadRequested = false // Prevent duplicate preload requests
    private var savedPagePosition = 0 // Saved position for text extraction ordering

    // PDF Document for lazy rendering
    private var pdfDocument: com.artifex.mupdf.fitz.Document? = null
    private var pdfFile: File? = null

    // PDF Table of Contents (in-memory, extracted when PDF opens)
    private val tocItems = MutableStateFlow<List<MuPdfUtil.TocItem>>(emptyList())

    companion object {
        private const val PDF_CACHE_FILENAME = "novel_viewer_current.pdf"
    }

    /**
     * Whether navigation is paused (e.g., when AI chat is open).
     */
    private var isPaused: Boolean = false

    /**
     * Set the paused state. When paused, key events won't be processed.
     */
    override fun setPaused(paused: Boolean) {
        isPaused = paused
    }

    // Position restore is now handled by initialPageIndex - no need for the old approach

    override fun getView(): View = composeView

    init {
        composeView.setContent {
            TachiyomiTheme {
                Content()
            }
        }
    }

    @Composable
    private fun Content() {
        val textList by content.collectAsState()
        val imageList by images.collectAsState()
        val originalImageList by originalImages.collectAsState()
        val url by currentUrl.collectAsState()
        val readerState by activity.viewModel.state.collectAsState()
        val loading by isLoading.collectAsState()
        val loadMsg by loadingMessage.collectAsState()
        val ocrProg by ocrProgress.collectAsState()
        val textMode by showTextMode.collectAsState()
        val targetPage by targetPageIndex.collectAsState()
        val initialPage by initialPageIndex.collectAsState()
        val toc by tocItems.collectAsState()
        var currentPrefs by remember { prefs }
        var showAiChat by remember { mutableStateOf(false) }

        // Define renderer for PDF pages - Remember to avoid recomposition
        val pdfRenderer: suspend (Int, Int) -> android.graphics.Bitmap? = remember(currentPrefs.fontSizeSp) {
             { index, width -> renderPdfPage(index, width) }
        }

        NovelReaderScreen(
            textPages = if (textMode) textList else emptyList(),
            images = if (textMode) imageList else originalImageList,
            pages = virtualPages, // Pass ReaderPage objects for cached image loading
            url = url,
            isLoading = loading,
            loadingMessage = loadMsg,
            ocrProgress = ocrProg,
            initialOffset = 0L,
            prefs = currentPrefs,
            menuVisible = readerState.menuVisible,
            showTextMode = textMode,
            hasExtractedText = textList.isNotEmpty(),
            hasOriginalImages = originalImageList.isNotEmpty(),
            isPdfMode = originalImageList.firstOrNull()?.startsWith("pdf://") == true,
            initialPage = initialPage,
            targetPageIndex = targetPage,
            onTargetPageConsumed = { targetPageIndex.value = null },
            onOffsetChanged = { /* Save progress */ },
            onPrefsChanged = { newPrefs -> currentPrefs = newPrefs },
            onBack = {
                activity.onBackPressedDispatcher.onBackPressed()
            },
            onToggleMenu = {
                activity.toggleMenu()
            },
            onToggleTextMode = {
                showTextMode.value = !showTextMode.value
            },
            onExtractOcr = {
                scope.launch {
                    extractTextFromImages(startPagePriority = lastUserVisiblePage.value)
                }
            },
            onRenderPage = pdfRenderer,
            onPageChanged = { listIndex ->
                 // Update user position for Lazy OCR
                 lastUserVisiblePage.value = listIndex

                 // Notify activity and check preload
                 if (listIndex >= 0 && listIndex < virtualPages.size) {
                     val page = virtualPages[listIndex]
                     activity.onPageSelected(page)

                     // Check if we should preload next chapter (within last 5 pages)
                     val totalPages = virtualPages.size
                     val pagesRemaining = totalPages - listIndex - 1
                     if (pagesRemaining < 5 && !preloadRequested && nextChapter != null) {
                         preloadRequested = true
                         logcat {
                            "NovelViewer: Requesting preload of next chapter (${pagesRemaining} pages remaining)"
                        }
                         activity.requestPreloadChapter(nextChapter!!)
                     }
                 }
            },
            onLoadPage = { page ->
                // Trigger page loading via pageLoader for disk caching (like WebtoonViewer)
                scope.launch(Dispatchers.IO) {
                    page.chapter.pageLoader?.loadPage(page)
                }
            },
            // TOC support
            tocItems = toc,
            onTocNavigate = { pageNumber ->
                // Navigate to the specified page
                // Use images.value.size for validation (reactive) instead of virtualPages.size (not reactive in Compose)
                val totalPages = images.value.size.coerceAtLeast(virtualPages.size)
                if (pageNumber >= 0 && pageNumber < totalPages) {
                    targetPageIndex.value = pageNumber
                }
            },
            onAiClick = {
                showAiChat = true
            }
        )

        // Gexu AI Overlay
        NovelAiChatOverlay(
            visible = showAiChat,
            mangaTitle = readerState.manga?.title,
            onDismiss = { showAiChat = false }
        )

    }

    private suspend fun renderPdfPage(index: Int, width: Int): android.graphics.Bitmap? {
        val doc = pdfDocument ?: return null
        // Critical section: Ensure we don't access pdfDocument while it's being destroyed
        return try {
            pdfMutex.withLock {
                // Check if cancelled before starting heavy work
                currentCoroutineContext().ensureActive()

                 val safeDoc = pdfDocument ?: return@withLock null
                 val isOriginalMode = !showTextMode.value
                 withContext(Dispatchers.IO) {
                     currentCoroutineContext().ensureActive()
                     if (isOriginalMode) {
                         // Original PDF page rendering (no reflow, preserves original layout)
                         eu.kanade.tachiyomi.util.MuPdfUtil.renderPage(safeDoc, index, width)
                     } else {
                         // Reflow rendering (text-friendly with adjustable font size)
                         eu.kanade.tachiyomi.util.MuPdfUtil.renderPageReflow(safeDoc, index, width, prefs.value.fontSizeSp.toFloat())
                     }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Error rendering PDF page $index: ${e.message}" }
            null
        }
    }

    private suspend fun extractTextFromImages(startPagePriority: Int? = null) {
        val currentImages = images.value.toList()
        // Determine priority start page: passed arg -> saved position -> or 0
        val priorityStart = startPagePriority ?: savedPagePosition.coerceIn(0, currentImages.lastIndex)

        logcat { "OCR: Starting prioritized extraction with ${currentImages.size} images, priority: $priorityStart" }

        if (currentImages.isEmpty()) {
            logcat { "OCR: No images to process" }
            return
        }

        // DON'T clear images - keep them visible while OCR runs in background
        withContext(Dispatchers.Main) {
            content.value = emptyList()  // Clear text content initially
            showTextMode.value = true    // Switch to text mode container
            ocrProgress.value = 0 to currentImages.size
        }

        // Initialize user-facing list with empty placeholders
        // We use a ConcurrentHashMap internally for safe updates, but push strict Lists to UI
        val total = currentImages.size
        val pageResults = java.util.concurrent.ConcurrentHashMap<Int, String>()
        for (i in 0 until total) pageResults[i] = ""

        val imageLoader = activity.imageLoader

        // Priority Queue Creation
        val priorityList = LinkedHashSet<Int>() // Use Set to avoid duplicates

        // 1. Immediate Context (Start + 5)
        for (i in priorityStart..minOf(priorityStart + 5, total - 1)) priorityList.add(i)
        // 2. Previous Context (Start - 3)
        for (i in (priorityStart - 1) downTo maxOf(0, priorityStart - 3)) priorityList.add(i)
        // 3. Forward Rest
        for (i in (priorityStart + 6) until total) priorityList.add(i)
        // 4. Backward Rest
        for (i in (priorityStart - 4) downTo 0) priorityList.add(i)

        // Check PDF mode
        val localPdfFile = pdfMutex.withLock { this.pdfFile }
        val isPdfMode = localPdfFile != null && currentImages.firstOrNull()?.startsWith("pdf://") == true

        try {
            logcat { "OCR: Creating TextRecognition client" }
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            withContext(Dispatchers.IO) {
                // If PDF mode, open a PRIVATE document instance for OCR
                val ocrPdfDoc = if (isPdfMode) {
                    try {
                        eu.kanade.tachiyomi.util.MuPdfUtil.openDocument(localPdfFile!!.absolutePath)
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR) { "OCR: Failed to open private PDF doc: ${e.message}" }
                        null
                    }
                } else null

                try {
                    var processedCount = 0
                    var isFirstBatch = true
                    val processedIndices = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

                    // DYNAMIC OCR LOOP - OPTIMIZED
                    while (isActive && processedCount < total) {
                        // 1. Determine next best page based on WHERE THE USER IS NOW
                        val center = lastUserVisiblePage.value

                        // Priority Ranges:
                        // A: Immediate View: Center -> Center + 2
                        // B: Preload Forward: Center + 3 -> Center + 10
                        // C: Preload Backward: Center - 1 -> Center - 5
                        // D: Rest of book (Slower)

                        var nextIndex = -1

                        // Local helper to find unprocessed index in range
                        fun findUnprocessed(range: IntProgression): Int? {
                            for (i in range) {
                                if (i in 0 until total && !processedIndices.contains(i)) return i
                            }
                            return null
                        }

                        // Search in strict priority order
                        val pA = findUnprocessed(center..minOf(center + 2, total - 1))
                        val pB = findUnprocessed((center + 3)..minOf(center + 10, total - 1))
                        val pC = findUnprocessed((center - 1) downTo maxOf(0, center - 5))

                        nextIndex = pA ?: pB ?: pC ?: -1

                        // If nothing immediate, check distant pages but throttling
                        if (nextIndex == -1) {
                             val pD = findUnprocessed((center + 11) until total)
                             val pE = findUnprocessed((center - 6) downTo 0)
                             nextIndex = pD ?: pE ?: -1
                        }

                        if (nextIndex == -1) break

                        // Mark as processed immediately so we don't pick it again
                        processedIndices.add(nextIndex)
                        val index = nextIndex

                        // Throttle heuristic:
                        // If the page is "far" (priority D), sleep a bit to save resources
                        val isFar = index > (center + 10) || index < (center - 5)
                         if (isFar) {
                            delay(150) // Slow down for distant pages
                        }

                        // 2. Process the selected index
                        val imgUrl = currentImages[index]

                        val bitmap: android.graphics.Bitmap? = if (isPdfMode && ocrPdfDoc != null) {
                            val pageIndex = imgUrl.removePrefix("pdf://").toInt()
                            eu.kanade.tachiyomi.util.MuPdfUtil.renderPageReflow(ocrPdfDoc, pageIndex, 1080, prefs.value.fontSizeSp.toFloat())
                        } else if (imgUrl.startsWith("pdf://")) {
                             renderPdfPage(imgUrl.removePrefix("pdf://").toInt(), 1080)
                        } else if (imgUrl.startsWith("page://") || virtualPages.isNotEmpty()) {
                             // FAST PATH: Use page.stream from virtualPages (ChapterLoader pages)
                             val pageIndex = if (imgUrl.startsWith("page://")) {
                                 imgUrl.removePrefix("page://").toIntOrNull() ?: index
                             } else {
                                 index
                             }
                             val page = virtualPages.getOrNull(pageIndex)
                             val stream = page?.stream
                             if (stream != null) {
                                 try {
                                     stream().use { inputStream ->
                                         android.graphics.BitmapFactory.decodeStream(inputStream)
                                     }
                                 } catch (e: Exception) {
                                     logcat(LogPriority.WARN) { "OCR: Failed to decode stream for page $pageIndex: ${e.message}" }
                                     null
                                 }
                             } else {
                                 // Stream not available yet - page might not be loaded
                                 // Try to load it via pageLoader first
                                 val loader = page?.chapter?.pageLoader
                                 if (loader != null && page.status == eu.kanade.tachiyomi.source.model.Page.State.Queue) {
                                     try {
                                         // Trigger loading and wait for it
                                         loader.loadPage(page)
                                         // Wait for stream to become available (with timeout)
                                         var attempts = 0
                                         while (page.stream == null && attempts < 50 && isActive) {
                                             delay(100)
                                             attempts++
                                         }
                                         page.stream?.let { s ->
                                             s().use { inputStream ->
                                                 android.graphics.BitmapFactory.decodeStream(inputStream)
                                             }
                                         }
                                     } catch (e: Exception) {
                                         logcat(LogPriority.WARN) { "OCR: Failed to load page $pageIndex: ${e.message}" }
                                         null
                                     }
                                 } else {
                                     null
                                 }
                             }
                        } else {
                             // Use standard image loader for real URLs
                             if (!isActive) break
                             try {
                                 val request = ImageRequest.Builder(activity)
                                    .data(imgUrl)
                                    .memoryCachePolicy(CachePolicy.ENABLED)
                                    // Resize to avoid OOM on huge images for OCR
                                    .size(1080, 1920) // Limit size for OCR efficiency
                                    .build()
                                val result = imageLoader.execute(request)
                                var bmp = (result.image as? coil3.BitmapImage)?.bitmap
                                if (bmp != null && bmp.config == android.graphics.Bitmap.Config.HARDWARE) {
                                    bmp = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                }
                                bmp
                             } catch(e: Exception) {
                                 null
                             }
                        }

                        // Yield for UI responsiveness often
                        yield()

                        if (bitmap != null) {
                            try {
                                val inputImage = InputImage.fromBitmap(bitmap, 0)
                                val task = recognizer.process(inputImage)
                                val ocrResult = Tasks.await(task)
                                val extracted = if (ocrResult.text.isNotBlank()) ocrResult.text.trim() else ""

                                pageResults[index] = extracted
                                processedCount++

                                // Update UI in batches or immediately if it's the current page
                                val isPriority = index in (center - 2)..(center + 5)
                                if (isPriority || processedCount % 5 == 0) {
                                    withContext(Dispatchers.Main) {
                                        // Update content list efficiently
                                        val currentList = (0 until total).map { pageResults[it] ?: "" }
                                        content.value = currentList
                                        ocrProgress.value = processedCount to total

                                        // Initial Jump logic
                                        if (isFirstBatch && index == center && extracted.isNotEmpty()) {
                                            isFirstBatch = false
                                            updateOcrPageCounter(1, total, updatePosition = false)
                                            if (virtualPages.isNotEmpty()) {
                                                 val target = center.coerceIn(0, virtualPages.lastIndex)
                                                 moveToPage(virtualPages[target])
                                            }
                                        } else {
                                             updateOcrPageCounter(1, total, updatePosition = false)
                                        }
                                    }
                                    // Yield again after UI update
                                    yield()
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.ERROR) { "OCR: Error page $index: ${e.message}" }
                                pageResults[index] = "[Error OCR]"
                            }
                        } else {
                             pageResults[index] = ""
                        }
                    }

                } finally {
                    if (ocrPdfDoc != null) {
                         eu.kanade.tachiyomi.util.MuPdfUtil.closeDocument(ocrPdfDoc)
                    }
                }
            }

            // Final clean up
            withContext(Dispatchers.Main) {
                ocrProgress.value = null
                val finalList = (0 until total).map { pageResults[it] ?: "" }
                if (finalList.any { it.isNotEmpty() }) {
                    content.value = finalList
                    images.value = emptyList() // Hide images only if we have text
                } else {
                    logcat { "OCR: No text extracted, keeping images visible" }
                }
            }

        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "OCR: Critical error: ${e.message}" }
            withContext(Dispatchers.Main) {
                ocrProgress.value = null
                // Attempt to show whatever we got
                val finalList = (0 until total).map { pageResults[it] ?: "" }
                if (finalList.any { it.isNotEmpty() }) {
                    content.value = finalList
                    images.value = emptyList()
                }
            }
        }
    }

    // ... updateOcrPageCounter ...

    override fun destroy() {
        scope.cancel()
        // Run cleanup in a detached scope to ensure it executes even after main scope cancellation
        // and waits for the mutex (active renders) to release.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                pdfMutex.withLock {
                    pdfDocument?.let {
                         try { it.destroy() } catch(e: Exception) {}
                    }
                    pdfDocument = null

                    // Cleanup temp file
                    pdfFile?.delete()
                    pdfFile = null
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    /**
     * Cleanup stale PDF cache files from old code that used timestamp-based names.
     * Also manages PDF text cache to prevent excessive disk usage.
     */
    private fun cleanupStalePdfCache() {
        try {
            val cacheDir = activity.cacheDir
            cacheDir.listFiles()?.forEach { file ->
                // Delete old timestamp-based PDF files and temp stream files
                if (file.name.startsWith("current_pdf_") ||
                    file.name.startsWith("temp_stream_") ||
                    file.name.startsWith("pdf_cover_")) {
                    file.delete()
                }
            }

            // Manage PDF text cache directory - keep only last 5 cached PDFs
            val pdfTextCacheDir = java.io.File(cacheDir, "pdf_text_cache")
            if (pdfTextCacheDir.exists()) {
                val cacheFiles = pdfTextCacheDir.listFiles()
                    ?.filter { it.name.startsWith("pdf_text_cache_") }
                    ?.sortedByDescending { it.lastModified() }

                // Keep only the 5 most recent cache files
                cacheFiles?.drop(5)?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    override fun setChapters(chapters: ViewerChapters) {
        val chapter = chapters.currChapter
        currentChapter = chapter
        nextChapter = chapters.nextChapter
        prevChapter = chapters.prevChapter
        preloadRequested = false // Reset preload flag for new chapter

    // Check if Activity has a requested page in Intent (overrides history)
    // This fixes TOC navigation where we want to jump to a specific page, not resume history
    val intentPage = activity.intent.getIntExtra("page", -1)
    if (intentPage != -1) {
        logcat { "NovelViewer: Found page extra in intent: $intentPage" }
        chapter.requestedPage = intentPage
    }

    // IMPORTANT: For NovelViewer, we handle loading ourselves (not using ChapterLoader)
        // So we need to set requestedPage from last_page_read, same as ChapterLoader does
        // Note: We always restore position for novels, even for "read" chapters
        if (chapter.requestedPage == 0 && chapter.chapter.last_page_read > 0) {
            chapter.requestedPage = chapter.chapter.last_page_read
            logcat { "NovelViewer: Set requestedPage from last_page_read: ${chapter.requestedPage}" }
        }

        // Save the starting position - this is used for:
        // 1. Initializing the UI at this position directly (no scroll needed)
        // 2. Prioritizing text extraction around this position
        savedPagePosition = chapter.requestedPage.coerceAtLeast(0)
        initialPageIndex.value = savedPagePosition

        logcat { "NovelViewer: setChapters - savedPagePosition=$savedPagePosition, requestedPage=${chapter.requestedPage}, last_page_read=${chapter.chapter.last_page_read}" }

        // If chapter already has pages loaded (from ChapterLoader), use them
        val existingPages = chapters.currChapter.pages
        if (existingPages != null && existingPages.isNotEmpty()) {
            virtualPages = existingPages
            logcat { "NovelViewer: Using ${existingPages.size} pre-loaded pages" }
        }

        scope.launch(Dispatchers.IO) {
            try {
                isLoading.value = true
                content.value = emptyList()
                images.value = emptyList() // Reset images
                originalImages.value = emptyList() // Reset original images
                showTextMode.value = true // Reset to text mode
                // Reset state safely and cleanup old PDF cache files
                pdfMutex.withLock {
                    pdfDocument?.let { try { it.destroy() } catch (e: Exception) {} }
                    pdfDocument = null
                    pdfFile?.delete()
                    pdfFile = null
                }
                // Cleanup any stale PDF temp files (from crashes or old code)
                cleanupStalePdfCache()

                val mangaId = chapter.chapter.manga_id ?: return@launch
// ... rest of setChapters

                val manga = getManga.await(mangaId) ?: return@launch
                val source = sourceManager.get(manga.source)

                // Check if this is a LocalSource (source ID 0)
                if (manga.source == 0L) {
                    // Local source - handle PDF directly
                    handleLocalPdf(chapter)
                    return@launch
                }

                // For HTTP sources
                val httpSource = source as? HttpSource
                if (httpSource == null) {
                    isLoading.value = false
                    content.value = listOf("Fuente no soportada para modo novela.")
                    return@launch
                }

                // OPTIMIZATION: Fast path - if pages have pageLoader (from ChapterLoader), use them!
                // The pageLoader will fetch imageUrl lazily via HttpPageLoader.loadPage()
                // This is how WebtoonViewer works - it doesn't need imageUrl pre-filled
                val hasPageLoader = existingPages?.any { it.chapter.pageLoader != null } == true

                if (hasPageLoader && existingPages != null) {
                    logcat { "NovelViewer: FAST PATH - Using ${existingPages.size} pages with pageLoader (like WebtoonViewer)" }

                    // Create placeholder URLs - OptimizedReaderImage will use page.stream after loading
                    // The actual imageUrl will be fetched by HttpPageLoader.loadPage()
                    val placeholderUrls = existingPages.mapIndexed { index, page ->
                        // Use existing imageUrl if available, otherwise placeholder
                        page.imageUrl ?: "page://${index}"
                    }

                    withContext(Dispatchers.Main) {
                        images.value = placeholderUrls
                        originalImages.value = placeholderUrls
                        showTextMode.value = false // Show images immediately
                        isLoading.value = false
                    }
                    // NO auto-OCR - user must request it via button
                    return@launch
                }

                // FIRST: Try to use the extension's page list (like other viewers do)
                // This is the correct way - uses the extension's built-in parsing
                val foundImages = fetchImages(httpSource, chapter.chapter)

                if (foundImages) {
                    // Extension provided images - show immediately (like WebtoonViewer)
                    logcat { "NovelViewer: Extension provided ${images.value.size} images" }
                    withContext(Dispatchers.Main) {
                        showTextMode.value = false
                        isLoading.value = false
                    }
                    // NO auto-OCR - user must request it via button
                    return@launch
                }

                // FALLBACK: Extension didn't provide images, try manual HTML extraction
                logcat { "NovelViewer: No images from extension, trying HTML extraction" }

                // Use the Source's logic to build the request.
                val method = HttpSource::class.java.getDeclaredMethod("pageListRequest", eu.kanade.tachiyomi.source.model.SChapter::class.java)
                method.isAccessible = true
                val request = try {
                    method.invoke(httpSource, chapter.chapter) as Request
                } catch (e: Exception) {
                     Request.Builder()
                        .url(if (chapter.chapter.url.startsWith("http")) chapter.chapter.url else httpSource.baseUrl + chapter.chapter.url)
                        .headers(httpSource.headers)
                        .build()
                }

                currentUrl.value = request.url.toString()

                // Fetch
                val response = httpSource.client.newCall(request).execute()
                currentUrl.value = response.request.url.toString()

                if (!response.isSuccessful) {
                    isLoading.value = false
                    content.value = listOf("Error al cargar el capítulo.")
                    return@launch
                }


                // PDF Check
                val contentType = response.header("Content-Type", "") ?: ""
                val isPdf = contentType.contains("application/pdf") ||
                    request.url.toString().endsWith(".pdf", true)

                if (isPdf) {
                    try {
                        // Use a temp file instead of loading all bytes to memory
                        val cacheDir = activity.cacheDir
                        val tempPdf = File(cacheDir, "temp_stream_${System.currentTimeMillis()}.pdf")

                        if (saveResponseToFile(response, tempPdf)) {
                           val pdfText = PdfUtil.extractPdfText(tempPdf)

                           // Clean up
                           tempPdf.delete()

                           withContext(Dispatchers.Main) {
                                isLoading.value = false
                                content.value = if (pdfText.length > 50) listOf(pdfText) else listOf("PDF sin texto extraíble.")
                           }
                        } else {
                            throw Exception("Failed to save PDF stream")
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            isLoading.value = false
                            content.value = listOf("Error al procesar PDF.")
                        }
                    }
                    return@launch
                }

                val rawContent = response.body.string().trim()

                // JSON/API Detection - try images again
                if (rawContent.startsWith("{") || rawContent.startsWith("[")) {
                    withContext(Dispatchers.Main) {
                        isLoading.value = false
                        content.value = listOf("Formato no soportado para modo texto.")
                    }
                    return@launch
                }

                val isCloudflare = rawContent.contains("Just a moment...") ||
                                   rawContent.contains("Enable JavaScript") ||
                                   rawContent.contains("challenge-form") ||
                                   rawContent.contains("challenge-platform")

                val sb = StringBuilder()
                var isAdTrap = false

                // Only attempt text extraction if NOT Cloudflare
                // Only attempt text extraction if NOT Cloudflare
                if (!isCloudflare) {
                     content.value = listOf("Procesando texto...")

                     var extractedText: List<String>? = null

                     try {
                         // 1. Try Readability4J (Best quality)
                         val readability = net.dankito.readability4j.Readability4J(currentUrl.value ?: "", rawContent)
                         val article = readability.parse()

                         if (article.textContent != null && article.textContent!!.length > 200) {
                             val sb = StringBuilder()

                             // Add Title
                             if (!article.title.isNullOrEmpty()) {
                                 sb.append(article.title).append("\n\n")
                             } else {
                                 // Fallback title from Jsoup if missing
                                 val docTitle = Jsoup.parse(rawContent).title()
                                     .replace(Regex("(?i)(read|free|manga|online|page|chapter).*"), "")
                                     .trim().trimEnd('-', '|')
                                 if (docTitle.isNotEmpty()) sb.append(docTitle).append("\n\n")
                             }

                             // Add Byline/Author if present
                             if (!article.byline.isNullOrEmpty()) {
                                 sb.append("By: ${article.byline}").append("\n\n")
                             }

                             // Process content
                             sb.append(article.textContent)

                             val lines = sb.toString().lines()
                                 .map { it.trim() }
                                 .filter { it.isNotBlank() && !pageNumberRegex.matches(it) && !it.contains("Next Chapter", true) }

                             if (lines.isNotEmpty()) {
                                 extractedText = lines
                                 logcat { "NovelViewer: Extracted content via Readability4J" }
                             }
                         }
                     } catch (e: Exception) {
                         logcat(LogPriority.WARN) { "NovelViewer: Readability4J failed: ${e.message}" }
                     }

                     // 2. Fallback to manual Jsoup heuristic if Readability4J failed or returned little text
                     if (extractedText == null || extractedText.isEmpty()) {
                         logcat { "NovelViewer: Readability4J returned empty, falling back to Jsoup heuristics" }

                        // Pre-process HTML
                        val processedHtml = rawContent.replace(Regex("(?i)<br\\s*/?>"), "\n")
                        val doc = Jsoup.parse(processedHtml)

                        // 1. Remove obvious junk
                        val junkSelectors = listOf(
                            "script", "style", "nav", "footer", "header", "aside",
                            ".sidebar", ".widget", ".menu", ".comments", "#comments",
                            ".pagination", ".pager", ".breadcrumb", ".social", ".share"
                        )
                        junkSelectors.forEach { doc.select(it).remove() }

                        val junkRegex = "(?i)comment|meta|footer|foot|header|menu|nav|pagination|pager|sidebar|ad|share|social|popup|cookie|banner".toRegex()
                        doc.allElements.forEach { el ->
                            if (el.id().matches(junkRegex) || el.className().matches(junkRegex)) {
                                el.remove()
                            }
                        }

                        // 2. Score paragraphs
                        var bestContainer: org.jsoup.nodes.Element? = null
                        var maxScore = 0.0

                        val candidates = doc.select("div, article, section, td")
                        for (candidate in candidates) {
                            val text = candidate.text()
                            if (text.length < 100) continue
                            if (isMostlyPageNumbers(text)) continue

                            val paragraphs = candidate.select("p")
                            val links = candidate.select("a")
                            // ktlint-disable standard:max-line-length
                            val linkTextLength = links.sumOf { it.text().length }
                            // ktlint-enable standard:max-line-length
                            val linkDensity = if (text.isNotEmpty()) {
                                linkTextLength.toDouble() / text.length
                            } else {
                                1.0
                            }
                            if (linkDensity > 0.25) continue

                            var score = text.length.toDouble() * 0.1
                            score += paragraphs.size * 50
                            if (paragraphs.isEmpty() && text.length > 2000) {
                                score -= 1000
                            }
                            val className = candidate.className().lowercase()
                            if (className.contains("content") ||
                                className.contains("article") ||
                                className.contains("story") ||
                                className.contains("post")
                            ) {
                                score += 500
                            }

                            if (score > maxScore) {
                                maxScore = score
                                bestContainer = candidate
                            }
                        }

                        val target = if (bestContainer != null && !isMostlyPageNumbers(bestContainer.text())) bestContainer else doc.body()

                        // 3. Final construction
                        val title = doc.title()
                            .replace(Regex("(?i)(read|free|manga|online|page|chapter).*"), "")
                            .trim()
                            .trimEnd('-', '|')

                        // AD-TRAP DETECTION
                        isAdTrap = title.contains("Sweet Tooth", true) ||
                                       title.contains("Recipes", true) ||
                                       title.contains("Captcha", true) ||
                                       doc.text().contains("Click here to continue", true)

                        if (!isAdTrap) {
                             val sb = StringBuilder()
                            if (title.isNotEmpty()) sb.append(title).append("\n\n")

                            val rawTextLines = target.text().lines()
                            val cleanLines = rawTextLines
                                .map { it.trim() }
                                .filter { it.length > 3 && !pageNumberRegex.matches(it) && !it.contains("Next Chapter", true) }

                            if (cleanLines.isNotEmpty()) {
                                cleanLines.forEach { sb.append(it).append("\n\n") }
                                extractedText = sb.toString().split("\n\n").filter { it.isNotBlank() }
                            }
                        }
                     }

                     // FINALIZE
                     if (extractedText != null && extractedText.isNotEmpty() && !isAdTrap) {
                          withContext(Dispatchers.Main) {
                            isLoading.value = false
                            content.value = extractedText
                            images.value = emptyList()
                        }
                        return@launch
                     } else {
                         // Triggers failure block below
                         isAdTrap = true // Force fallback to images if both methods failed
                     }
                }

                // CHECK RESULT & FALLBACK (Logic preserved but simplified check)
                if (isCloudflare || isAdTrap) {
                    // Try to fetch images using standard Extension Logic
                    val foundImages = fetchImages(httpSource, chapter.chapter)

                    if (!foundImages) {
                        // FINAL FAILURE
                        withContext(Dispatchers.Main) {
                            isLoading.value = false
                            content.value = if (isCloudflare) {
                                listOf("Protección Cloudflare. Abre WebView para resolver.")
                            } else {
                                listOf("No se encontró contenido válido.")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading.value = false
                    logcat(LogPriority.ERROR) { "NovelViewer: Error loading chapter: ${e.message}" }
                    content.value = listOf("Error al cargar el capítulo.")
                }
            }
        }
    }

    // Helper to stream response to file
    private fun saveResponseToFile(response: okhttp3.Response, file: File): Boolean {
        return try {
            response.body.source().use { source ->
                file.sink().buffer().use { sink ->
                    sink.writeAll(source)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun fetchImages(source: HttpSource, chapter: eu.kanade.tachiyomi.source.model.SChapter): Boolean {
        return try {
            val pageList = source.getPageList(chapter)
            if (pageList.isNotEmpty()) {
                // Determine Image URLs
                val imageUrls = pageList.mapNotNull { page ->
                     if (page.imageUrl != null) page.imageUrl
                     else {
                         try { source.getImageUrl(page) } catch(e: Exception) { null }
                     }
                }

                if (imageUrls.isNotEmpty()) {
                    // Create virtual pages for the page counter
                    val readerChapter = currentChapter
                    if (readerChapter != null) {
                        virtualPages = imageUrls.mapIndexed { index, url ->
                            ReaderPage(index, imageUrl = url).apply {
                                this.chapter = readerChapter
                            }
                        }
                        // Set the pages on the chapter so the slider works
                        readerChapter.state = ReaderChapter.State.Loaded(virtualPages)
                    }

                    withContext(Dispatchers.Main) {
                        isLoading.value = false
                        images.value = imageUrls
                        originalImages.value = imageUrls // Save original images for toggle
                        content.value = emptyList()
                        // Position is now handled by initialPageIndex - UI starts at saved page directly
                    }
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun handleLocalPdf(chapter: ReaderChapter) {
        try {
            val chapterUrl = chapter.chapter.url
            logcat { "NovelViewer: Handling local PDF with MuPDF at $chapterUrl" }

            // Show loading message
            withContext(Dispatchers.Main) {
                loadingMessage.value = "Cargando PDF..."
            }

            // Get the local source file system
            val storageManager: tachiyomi.domain.storage.service.StorageManager = uy.kohesive.injekt.Injekt.get()
            val localDir = storageManager.getLocalSourceDirectory()

            if (localDir == null) {
                withContext(Dispatchers.Main) {
                    isLoading.value = false
                    content.value = listOf("Directorio local no configurado.")
                    loadingMessage.value = null
                }
                return
            }

            // Parse the URL: "mangaName/chapterFile.pdf"
            val parts = chapterUrl.split("/", limit = 2)
            if (parts.size < 2) {
                withContext(Dispatchers.Main) {
                    isLoading.value = false
                    content.value = listOf("Formato de capítulo no válido.")
                    loadingMessage.value = null
                }
                return
            }

            val mangaDirName = parts[0]
            val chapterFileName = parts[1]

            val mangaDir = localDir.findFile(mangaDirName)
            val pdfFileDoc = mangaDir?.findFile(chapterFileName)

            if (pdfFileDoc == null) {
                withContext(Dispatchers.Main) {
                    isLoading.value = false
                    content.value = listOf("Archivo PDF no encontrado: $chapterFileName")
                    loadingMessage.value = null
                }
                return
            }

            // Check if it's a PDF
            if (!pdfFileDoc.name.orEmpty().endsWith(".pdf", true)) {
                withContext(Dispatchers.Main) {
                    isLoading.value = false
                    content.value = listOf("El archivo no es un PDF.")
                    loadingMessage.value = null
                }
                return
            }

            // Copy PDF to cache first (needed for both text extraction and MuPDF)
            val cacheDir = activity.cacheDir
            val pdfCacheFile = java.io.File(cacheDir, PDF_CACHE_FILENAME)

            withContext(Dispatchers.Main) {
                loadingMessage.value = "Preparando PDF..."
            }

            try {
                pdfFileDoc.openInputStream().use { input ->
                    pdfCacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading.value = false
                    content.value = listOf("Error al copiar el archivo PDF.")
                    loadingMessage.value = null
                }
                return
            }

            // FIRST: Open document for lazy rendering (keep it open for viewing original PDF)
            val document = eu.kanade.tachiyomi.util.MuPdfUtil.openDocument(pdfCacheFile.absolutePath)

            if (document == null) {
                withContext(Dispatchers.Main) {
                    isLoading.value = false
                    content.value = listOf("Error al abrir el PDF con MuPDF.")
                    loadingMessage.value = null
                }
                return
            }

            val pageCount = eu.kanade.tachiyomi.util.MuPdfUtil.getPageCount(document)
            logcat { "NovelViewer: PDF has $pageCount pages" }

            if (pageCount == 0) {
                withContext(Dispatchers.Main) {
                    isLoading.value = false
                    content.value = listOf("El PDF está vacío o no se pudo leer.")
                    loadingMessage.value = null
                }
                eu.kanade.tachiyomi.util.MuPdfUtil.closeDocument(document)
                return
            }

            // Save document for lazy rendering
            pdfMutex.withLock {
                pdfDocument = document
                this.pdfFile = pdfCacheFile
            }

            // Extract Table of Contents with DATABASE PERSISTENCE
            // 1. First try to load from DB (instant)
            // 2. If not in DB, extract from PDF and save to DB for next time
            // IMPORTANT: Must use pdfMutex because MuPDF is NOT thread-safe
            val chapterId = currentChapter?.chapter?.id ?: 0L
            scope.launch(Dispatchers.Default) {
                try {
                    // First: Try to load from database (INSTANT)
                    val cachedToc = getPdfToc.await(chapterId)
                    if (cachedToc.isNotEmpty()) {
                        logcat { "NovelViewer: Loaded ${cachedToc.size} TOC entries from DB (instant)" }
                        val tocFromDb = cachedToc.map { entry ->
                            MuPdfUtil.TocItem(entry.title, entry.pageNumber, entry.level)
                        }
                        withContext(Dispatchers.Main) {
                            tocItems.value = tocFromDb
                        }
                        return@launch // Done! No need to extract
                    }

                    // Not in DB: Extract from PDF (slower, but cached for next time)
                    logcat { "NovelViewer: No cached TOC, extracting from PDF..." }

                    // Wait until initial text extraction is done before scanning TOC
                    // This prevents race conditions with MuPDF document access
                    delay(2000) // Give time for priority extraction to finish

                    val toc = pdfMutex.withLock {
                        val doc = pdfDocument ?: return@launch
                        MuPdfUtil.extractTableOfContents(doc)
                    }

                    if (toc.isNotEmpty()) {
                        logcat { "NovelViewer: PDF has ${toc.size} TOC entries, saving to DB" }

                        // Save to database for next time
                        val entriesToSave = toc.mapIndexed { index, item ->
                            tachiyomi.domain.pdftoc.model.PdfTocEntry(
                                chapterId = chapterId,
                                title = item.title,
                                pageNumber = item.pageNumber,
                                level = item.level,
                                sortOrder = index,
                            )
                        }
                        savePdfToc.await(chapterId, entriesToSave)

                        withContext(Dispatchers.Main) {
                            tocItems.value = toc
                        }
                    } else {
                        logcat { "NovelViewer: PDF has no TOC" }
                    }
                } catch (e: Exception) {
                    logcat { "NovelViewer: TOC extraction failed: ${e.message}" }
                }
            }

            // Create VIRTUAL URIs for pages - always available for "View Original" toggle
            val virtualImages = (0 until pageCount).map { "pdf://$it" }

            // Create virtual pages for tracking
            val readerChapter = currentChapter
            if (readerChapter != null) {
                virtualPages = (0 until pageCount).map { index ->
                    ReaderPage(index).apply {
                        this.chapter = readerChapter
                    }
                }
                readerChapter.state = ReaderChapter.State.Loaded(virtualPages)
            }

            // Start extraction with priority around the saved position
            // This is the optimized logic: convert where the user IS first.
            withContext(Dispatchers.Main) {
                isLoading.value = true
                loadingMessage.value = null // Just loader, cleaner look
                // Set images for PDF toggle availability, but start in text mode once ready
                images.value = virtualImages
                originalImages.value = virtualImages
            }

            scope.launch(Dispatchers.IO) {
                try {
                    val muPdfDoc = MuPdfUtil.openDocument(pdfCacheFile.absolutePath)
                    if (muPdfDoc != null) {
                        var isFirstPriorityBatch = true

                        val extractedPages = MuPdfUtil.extractTextWithPriority(muPdfDoc, savedPagePosition) { page: Int, total: Int, pages: List<String> ->
                            withContext(Dispatchers.Main) {
                                // CRITICAL: As soon as we have the priority text (first batch), open the reader
                                if (isFirstPriorityBatch && pages.isNotEmpty()) {
                                    isLoading.value = false
                                    loadingMessage.value = null

                                    content.value = pages
                                    showTextMode.value = true

                                    // Move to correct position immediately once text layout is ready
                                    // This works because the text list is now populated (with placeholders + content around cursor)
                                    val targetPage = savedPagePosition.coerceIn(0, pages.withIndex().last().index)
                                    moveToPage(virtualPages[targetPage])

                                    isFirstPriorityBatch = false
                                    logcat { "NovelViewer: Priority text loaded. Opened at page $targetPage" }
                                } else if (!isFirstPriorityBatch) {
                                    // Progressive update for remaining pages
                                    content.value = pages
                                }
                            }
                        }
                        MuPdfUtil.closeDocument(muPdfDoc)

                        // Final consistency check
                        if (extractedPages.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                if (content.value.isEmpty()) {
                                    content.value = extractedPages
                                    showTextMode.value = true
                                }
                                updateOcrPageCounter(1, extractedPages.size, updatePosition = false)
                                logcat { "NovelViewer: Full extraction complete (${extractedPages.size} pages)" }
                            }
                        } else {
                            // Fallback to PDF only if absolutely no text found
                            withContext(Dispatchers.Main) {
                                isLoading.value = false
                                loadingMessage.value = "No se pudo extraer texto"
                                showTextMode.value = false
                                content.value = emptyList()

                                // Jump in PDF mode
                                if (virtualPages.isNotEmpty()) {
                                    moveToPage(virtualPages[savedPagePosition.coerceIn(0, virtualPages.lastIndex)])
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.WARN) { "NovelViewer: Text extraction failed: ${e.message}" }
                    withContext(Dispatchers.Main) {
                        isLoading.value = false
                        loadingMessage.value = null
                         // Fallback to PDF mode on error
                        showTextMode.value = false
                        if (virtualPages.isNotEmpty()) {
                            moveToPage(virtualPages[savedPagePosition.coerceIn(0, virtualPages.lastIndex)])
                        }
                    }
                }
            }



        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Error handling local PDF: ${e.message}" }
            withContext(Dispatchers.Main) {
                isLoading.value = false
                loadingMessage.value = null
                content.value = listOf("Error al leer el PDF: ${e.message}")
            }
        }
    }

    private fun updateOcrPageCounter(currentPageIndex: Int, totalPages: Int, updatePosition: Boolean = true) {
        val readerChapter = currentChapter ?: return

        // Only recreate virtual pages if count changed to avoid object churn,
        // but for safety we recreate if total differs.
        if (virtualPages.size != totalPages) {
            virtualPages = (0 until totalPages).map { index ->
                ReaderPage(index).apply {
                    this.chapter = readerChapter
                }
            }
            // Set the pages on the chapter
            readerChapter.state = ReaderChapter.State.Loaded(virtualPages)
        }

        // Notify activity of current page
        if (updatePosition && currentPageIndex > 0 && virtualPages.isNotEmpty()) {
            val pageToSelect = virtualPages.getOrNull(currentPageIndex - 1) ?: virtualPages.first()
            activity.onPageSelected(pageToSelect)
        }
    }

    private val pageNumberRegex = Regex("""^\s*\d+[\s/]*(\d+)?\s*$""") // Matches "1", "1/200", "5 / 20"

    private fun isMostlyPageNumbers(text: String): Boolean {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        if (lines.size < 5) return false

        val numericLines = lines.count { pageNumberRegex.matches(it) }
        val ratio = numericLines.toDouble() / lines.size.toDouble()
        return ratio > 0.6
    }

    override fun moveToPage(page: ReaderPage) {
        val position = virtualPages.indexOf(page)
        if (position != -1) {
            targetPageIndex.value = position
        } else {
            logcat { "NovelViewer: Page $page not found in virtualPages" }
        }
    }

    /**
     * Moves to the next page in the list.
     */
    private fun moveToNext() {
        val current = targetPageIndex.value ?: lastUserVisiblePage.value
        val nextIndex = (current + 1).coerceAtMost(virtualPages.lastIndex)
        if (nextIndex != current && virtualPages.isNotEmpty()) {
            targetPageIndex.value = nextIndex
            virtualPages.getOrNull(nextIndex)?.let { page ->
                activity.onPageSelected(page)
            }
        }
    }

    /**
     * Moves to the previous page in the list.
     */
    private fun moveToPrevious() {
        val current = targetPageIndex.value ?: lastUserVisiblePage.value
        val prevIndex = (current - 1).coerceAtLeast(0)
        if (prevIndex != current && virtualPages.isNotEmpty()) {
            targetPageIndex.value = prevIndex
            virtualPages.getOrNull(prevIndex)?.let { page ->
                activity.onPageSelected(page)
            }
        }
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        // Don't handle key events when paused (e.g., AI chat is open)
        if (isPaused) return false

        val isUp = event.action == KeyEvent.ACTION_UP
        val readerPreferences: ReaderPreferences = Injekt.get()
        val volumeKeysEnabled = readerPreferences.readWithVolumeKeys().get()
        val volumeKeysInverted = readerPreferences.readWithVolumeKeysInverted().get()

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!volumeKeysInverted) moveToNext() else moveToPrevious()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!volumeKeysInverted) moveToPrevious() else moveToNext()
                }
            }
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            -> if (isUp) moveToPrevious()

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            -> if (isUp) moveToNext()

            else -> return false
        }
        return true
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }

    @Composable
    private fun NovelAiChatOverlay(
        visible: Boolean,
        mangaTitle: String?,
        onDismiss: () -> Unit,
    ) {
        var messages by remember { mutableStateOf(emptyList<tachiyomi.domain.ai.model.ChatMessage>()) }
        var isLoading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        val aiRepository: tachiyomi.domain.ai.repository.AiRepository = remember {
            Injekt.get()
        }

        eu.kanade.presentation.ai.components.AiChatOverlay(
            visible = visible,
            messages = messages,
            isLoading = isLoading,
            error = error,
            mangaTitle = mangaTitle,
            onSendMessage = { content ->
                if (content.isBlank()) return@AiChatOverlay

                val userMessage = tachiyomi.domain.ai.model.ChatMessage.user(content)
                messages = messages + userMessage
                isLoading = true
                error = null

                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val manga = activity.viewModel.manga
                        val currentState = activity.viewModel.state.value
                        val systemPrompt = buildString {
                            appendLine("You are Gexu AI, a friendly reading companion for manga, manhwa, and light novels.")
                            appendLine()
                            manga?.let { m ->
                                appendLine("=== CURRENT READING CONTEXT ===")
                                appendLine("Title: ${m.title}")
                                m.genre?.take(5)?.let { genres ->
                                    appendLine("Genres: ${genres.joinToString(", ")}")
                                }
                                m.description?.take(300)?.let { desc ->
                                    appendLine("Synopsis: $desc...")
                                }
                                appendLine()
                                appendLine("Chapter: ${currentState.currentChapter?.chapter?.name ?: "Unknown"}")
                                appendLine("Page: ${currentState.currentPage} of ${currentState.totalPages}")
                                appendLine("===============================")
                                appendLine()
                                appendLine(
                                    "IMPORTANT: The user is actively reading this content. You have full context."
                                )
                                appendLine("- Answer questions about the current chapter and characters")
                                appendLine("- NEVER spoil content from chapters the user hasn't reached yet")
                                appendLine("- Be helpful and friendly")
                            }
                        }

                        val allMessages = listOf(
                            tachiyomi.domain.ai.model.ChatMessage.system(systemPrompt)
                        ) + messages

                        val result = aiRepository.sendMessage(allMessages)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            result.fold(
                                onSuccess = { response ->
                                    messages = messages + response
                                    isLoading = false
                                },
                                onFailure = { e ->
                                    isLoading = false
                                    error = e.message ?: "Error desconocido"
                                }
                            )
                        }
                    } catch (e: Exception) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            isLoading = false
                            error = e.message ?: "Error de conexión"
                        }
                    }
                }
            },
            onClearConversation = {
                messages = emptyList()
                error = null
            },
            onDismiss = onDismiss,
        )
    }
}
