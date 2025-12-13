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
import kotlinx.coroutines.*
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
    private val scope = MainScope()
    private val pdfMutex = kotlinx.coroutines.sync.Mutex()

    // State
    private val prefs = mutableStateOf(NovelPrefs())
    private val content = MutableStateFlow<List<String>>(emptyList())
    private val images = MutableStateFlow<List<String>>(emptyList())
    private val currentUrl = MutableStateFlow<String?>(null)
    private val isLoading = MutableStateFlow(true)
    private val loadingMessage = MutableStateFlow<String?>(null)
    private val ocrProgress = MutableStateFlow<Pair<Int, Int>?>(null) // current, total
    private val lastUserVisiblePage = MutableStateFlow(0)

    // Page tracking for image-based chapters
    private var currentChapter: ReaderChapter? = null
    private var virtualPages: List<ReaderPage> = emptyList()

    // PDF Document for lazy rendering
    private var pdfDocument: com.artifex.mupdf.fitz.Document? = null
    private var pdfFile: File? = null

    companion object {
        private const val PDF_CACHE_FILENAME = "novel_viewer_current.pdf"
    }

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
        val url by currentUrl.collectAsState()
        val readerState by activity.viewModel.state.collectAsState()
        val loading by isLoading.collectAsState()
        val loadMsg by loadingMessage.collectAsState()
        val ocrProg by ocrProgress.collectAsState()
        var currentPrefs by remember { prefs }

        // Define renderer for PDF pages - Remember to avoid recomposition
        val pdfRenderer: suspend (Int, Int) -> android.graphics.Bitmap? = remember(currentPrefs.fontSizeSp) {
             { index, width -> renderPdfPage(index, width) }
        }

        NovelReaderScreen(
            textPages = textList,
            images = imageList,
            url = url,
            isLoading = loading,
            loadingMessage = loadMsg,
            ocrProgress = ocrProg,
            initialOffset = 0L,
            prefs = currentPrefs,
            menuVisible = readerState.menuVisible,
            onOffsetChanged = { /* Save progress */ },
            onPrefsChanged = { newPrefs -> currentPrefs = newPrefs },
            onBack = {
                activity.onBackPressedDispatcher.onBackPressed()
            },
            onToggleMenu = {
                activity.toggleMenu()
            },
            onExtractOcr = {
                scope.launch { extractTextFromImages() }
            },
            onRenderPage = pdfRenderer,
            onPageChanged = { listIndex ->
                 // Update user position for Lazy OCR
                 lastUserVisiblePage.value = listIndex

                 // listIndex maps to the items in LazyColumn.
                 // We have items(images) then items(textPages) then item(spacer).
                 // So the total items = images.size + textPages.size + 1 (spacer).

                 // However, virtualPages should align with these content items.
                 // We should check bounds.
                 if (listIndex >= 0 && listIndex < virtualPages.size) {
                     val page = virtualPages[listIndex]
                     activity.onPageSelected(page)
                 }
            }
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
                 withContext(Dispatchers.IO) {
                     currentCoroutineContext().ensureActive()
                     eu.kanade.tachiyomi.util.MuPdfUtil.renderPageReflow(safeDoc, index, width, prefs.value.fontSizeSp.toFloat())
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Error rendering PDF page $index: ${e.message}" }
            null
        }
    }

    private suspend fun extractTextFromImages() {
        val currentImages = images.value.toList()
        logcat { "OCR: Starting extraction with ${currentImages.size} images" }

        if (currentImages.isEmpty()) {
            logcat { "OCR: No images to process" }
            return
        }

        // DON'T clear images - keep them visible while OCR runs in background
        // User can view images while we extract text progressively
        withContext(Dispatchers.Main) {
            // Keep images visible: images.value = ... (don't clear!)
            content.value = emptyList()  // Clear only text content
            ocrProgress.value = 0 to currentImages.size  // Show initial progress
        }

        // Store text for each page separately for proper page tracking
        val pageTexts = mutableListOf<String>()
        val imageLoader = activity.imageLoader
        val total = currentImages.size

        // Check if we are in PDF mode and have the file
        val localPdfFile = pdfMutex.withLock { this.pdfFile }
        val isPdfMode = localPdfFile != null && currentImages.firstOrNull()?.startsWith("pdf://") == true

        try {
            logcat { "OCR: Creating TextRecognition client" }
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            withContext(Dispatchers.IO) {
                // If PDF mode, open a PRIVATE document instance for OCR to avoid locking the UI
                val ocrPdfDoc = if (isPdfMode) {
                    try {
                        eu.kanade.tachiyomi.util.MuPdfUtil.openDocument(localPdfFile!!.absolutePath)
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR) { "OCR: Failed to open private PDF doc: ${e.message}" }
                        null
                    }
                } else null

                try {
                    // LAZY OCR LOOP
                    // Process pages only when user is near them
                    var index = 0 // Start from first image
                    val bufferSize = 4 // Keep 4 pages ahead converted

                    while (index < currentImages.size && isActive) {
                        val userPage = lastUserVisiblePage.value
                        val targetPage = userPage + bufferSize

                        // If we are ahead of buffer, wait
                        if (index > targetPage) {
                            delay(500)
                            continue
                        }

                        val imgUrl = currentImages[index]

                        // Check cancellation
                        if (!isActive) throw CancellationException()

                        logcat { "OCR: Processing image ${index + 1}: $imgUrl" }

                        val bitmap: android.graphics.Bitmap? = if (isPdfMode && ocrPdfDoc != null) {
                            // Use private doc - NO MUTEX needed vs UI
                            val pageIndex = imgUrl.removePrefix("pdf://").toInt()
                            eu.kanade.tachiyomi.util.MuPdfUtil.renderPageReflow(ocrPdfDoc, pageIndex, 1080, prefs.value.fontSizeSp.toFloat())
                        } else if (imgUrl.startsWith("pdf://")) {
                            // Fallback to shared doc (slower, locks UI)
                            renderPdfPage(imgUrl.removePrefix("pdf://").toInt(), 1080)
                        } else {
                             val request = ImageRequest.Builder(activity)
                                .data(imgUrl)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .build()
                            val result = imageLoader.execute(request)
                            var bmp = (result.image as? coil3.BitmapImage)?.bitmap

                            // ML Kit requires software bitmap. Convert if HARDWARE.
                            if (bmp != null && bmp.config == android.graphics.Bitmap.Config.HARDWARE) {
                                bmp = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            }
                            bmp
                        }

                        // Yield to allow other IO tasks
                        yield()

                        if (bitmap != null) {
                            try {
                                val inputImage = InputImage.fromBitmap(bitmap, 0)
                                val task = recognizer.process(inputImage)
                                val ocrResult = Tasks.await(task)

                                // Store page text (even if empty, to maintain index alignment)
                                val extracted = if (ocrResult.text.isNotBlank()) ocrResult.text.trim() else ""
                                pageTexts.add(extracted)

                                // Update content in real-time
                                withContext(Dispatchers.Main) {
                                    val currentPages = pageTexts.toList()
                                    content.value = currentPages
                                    // Show progress: (index+1) / total
                                    ocrProgress.value = (index + 1) to total

                                    // Update total pages without moving position
                                    updateOcrPageCounter(index + 1, total, updatePosition = false)
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.ERROR) { "OCR: Error processing page ${index + 1}: ${e.message}" }
                                // Add empty placeholder to keep alignment?
                                // Actually pageTexts logic requires 1:1 mapping if we want reliable indexes?
                                // Current logic: content.value is list of texts.
                                // If I skip one, then text for page 5 might appear at index 4?
                                // Yes. So I MUST add a string even if error.
                                pageTexts.add("[Error OCR]")
                                withContext(Dispatchers.Main) {
                                    content.value = pageTexts.toList()
                                }
                            }
                        } else {
                             // Failed to load bitmap
                             pageTexts.add("") // Empty text placeholder
                             withContext(Dispatchers.Main) {
                                content.value = pageTexts.toList()
                            }
                        }
                        index++
                    }
                } finally {
                    // Close private doc
                    if (ocrPdfDoc != null) {
                         eu.kanade.tachiyomi.util.MuPdfUtil.closeDocument(ocrPdfDoc)
                    }
                }
            }

            // Final update
            withContext(Dispatchers.Main) {
                ocrProgress.value = null  // Clear progress indicator
                if (pageTexts.isNotEmpty()) {
                    content.value = pageTexts
                    images.value = emptyList()  // Only now clear images - we have text to show
                    // Finish: ensure we have total pages, stay on current or move to 1?
                    // Let's just update total. If user was reading, they stay.
                    updateOcrPageCounter(pageTexts.size, pageTexts.size, updatePosition = false)
                } else {
                    // Keep images visible if OCR failed
                    logcat { "OCR: No text extracted, keeping images visible" }
                }
            }

        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "OCR: Critical error: ${e.message}" }
             withContext(Dispatchers.Main) {
                ocrProgress.value = null  // Clear progress on error
                if (pageTexts.isNotEmpty()) {
                    content.value = pageTexts
                    images.value = emptyList()
                }
                // If no text was extracted, images remain visible as fallback
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

        scope.launch(Dispatchers.IO) {
            try {
                isLoading.value = true
                content.value = emptyList()
                images.value = emptyList() // Reset images
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

                // FIRST: Try to use the extension's page list (like other viewers do)
                // This is the correct way - uses the extension's built-in parsing
                val foundImages = fetchImages(httpSource, chapter.chapter)

                if (foundImages) {
                    // Extension provided images - we're done!
                    // User can then use OCR to extract text if needed
                    logcat { "NovelViewer: Extension provided ${images.value.size} images" }
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
                val isPdf = contentType.contains("application/pdf") || request.url.toString().endsWith(".pdf", true)

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
                            val linkTextLength = links.sumOf { it.text().length }
                            val linkDensity = if (text.isNotEmpty()) linkTextLength.toDouble() / text.length else 1.0
                            if (linkDensity > 0.25) continue

                            var score = text.length.toDouble() * 0.1
                            score += paragraphs.size * 50
                            if (paragraphs.isEmpty() && text.length > 2000) score -= 1000
                            val className = candidate.className().lowercase()
                            if (className.contains("content") || className.contains("article") || className.contains("story") || className.contains("post")) score += 500

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
                        content.value = emptyList()

                        // Notify activity of the first page to update counter
                        virtualPages.firstOrNull()?.let { firstPage ->
                            activity.onPageSelected(firstPage)
                        }
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
            val pdfFile = mangaDir?.findFile(chapterFileName)

            if (pdfFile == null) {
                withContext(Dispatchers.Main) {
                    isLoading.value = false
                    content.value = listOf("Archivo PDF no encontrado: $chapterFileName")
                    loadingMessage.value = null
                }
                return
            }

            // Check if it's a PDF
            if (!pdfFile.name.orEmpty().endsWith(".pdf", true)) {
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
                pdfFile.openInputStream().use { input ->
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

            // ATTEMPT NATIVE TEXT EXTRACTION USING MUPDF (much faster, no log spam)
            var extractedPages = emptyList<String>()
            try {
                withContext(Dispatchers.Main) {
                    loadingMessage.value = "Extrayendo texto..."
                }

                // Open document with MuPDF for text extraction
                val muPdfDoc = MuPdfUtil.openDocument(pdfCacheFile.absolutePath)
                if (muPdfDoc != null) {
                    extractedPages = MuPdfUtil.extractTextFromDocument(muPdfDoc) { page: Int, total: Int, pages: List<String> ->
                        withContext(Dispatchers.Main) {
                            loadingMessage.value = "Extrayendo texto: pág $page de $total..."

                            if (pages.isNotEmpty()) {
                                content.value = pages
                                updateOcrPageCounter(page, total, updatePosition = false)
                            }
                        }
                    }
                    MuPdfUtil.closeDocument(muPdfDoc)
                }

                if (extractedPages.isNotEmpty()) {
                     logcat { "NovelViewer: Text extracted with MuPDF (${extractedPages.size} pages)" }
                     withContext(Dispatchers.Main) {
                         isLoading.value = false
                         loadingMessage.value = null
                         content.value = extractedPages
                         images.value = emptyList()
                         // Final update. Move to Page 1 so the user sees the start of the book.
                         updateOcrPageCounter(1, extractedPages.size, updatePosition = true)
                     }
                     return
                } else {
                     logcat { "NovelViewer: PDF text too short or empty, falling back to images." }
                     withContext(Dispatchers.Main) {
                         content.value = emptyList()
                         // Reset counter just in case
                         updateOcrPageCounter(1, 1, updatePosition = false)
                     }
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "NovelViewer: MuPDF text extraction failed: ${e.message}" }
                // Continue to image fallback
            }

            // FALLBACK TO IMAGE MODE (MuPDF Reflow/Lazy)
            withContext(Dispatchers.Main) {
                loadingMessage.value = "Preparando modo visual..."
            }

            // Open with MuPDF
            val document = eu.kanade.tachiyomi.util.MuPdfUtil.openDocument(pdfCacheFile.absolutePath)

            if (document == null) {
                withContext(Dispatchers.Main) {
                    isLoading.value = false
                    content.value = listOf("Error al abrir el PDF con MuPDF.")
                    loadingMessage.value = null
                }
                return
            }

            // Save document for lazy rendering
            pdfMutex.withLock {
                pdfDocument = document
                this.pdfFile = pdfCacheFile
            }

            val pageCount = eu.kanade.tachiyomi.util.MuPdfUtil.getPageCount(document)
            logcat { "NovelViewer: PDF has $pageCount pages (Lazy Mode)" }

            if (pageCount == 0) {
                withContext(Dispatchers.Main) {
                    isLoading.value = false
                    content.value = listOf("El PDF está vacío o no se pudo leer.")
                    loadingMessage.value = null
                }
                // Close immediately if empty
                eu.kanade.tachiyomi.util.MuPdfUtil.closeDocument(document)
                pdfDocument = null
                return
            }

            // Create VIRTUAL URIs for pages
            // This is INSTANT compared to rendering images
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

                withContext(Dispatchers.Main) {
                    if (virtualPages.isNotEmpty()) {
                        activity.onPageSelected(virtualPages.first())
                    }
                }
            }

            withContext(Dispatchers.Main) {
                isLoading.value = false
                loadingMessage.value = null
                content.value = emptyList() // No text content initially
                images.value = virtualImages // Show virtual pages
            }

            logcat { "NovelViewer: Ready for lazy rendering of $pageCount pages" }



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
        // No-op for now
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        return false
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }
}
