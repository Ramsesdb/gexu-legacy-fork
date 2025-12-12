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

    // Page tracking for image-based chapters
    private var currentChapter: ReaderChapter? = null
    private var virtualPages: List<ReaderPage> = emptyList()

    // PDF Document for lazy rendering
    private var pdfDocument: com.artifex.mupdf.fitz.Document? = null
    private var pdfFile: File? = null

    // Cache for extracted PDF text (avoids re-extraction on re-entry)
    companion object {
        private val pdfTextCache = mutableMapOf<String, String>()
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

        // Clear images and start showing text progressively
        withContext(Dispatchers.Main) {
            images.value = emptyList()
            content.value = emptyList()
            ocrProgress.value = null
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
                    var index = 1
                    for (imgUrl in currentImages) {
                        // Check cancellation
                        if (!isActive) throw CancellationException()

                        logcat { "OCR: Processing image $index: $imgUrl" }

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

                                // Store page text
                                if (ocrResult.text.isNotBlank()) {
                                    pageTexts.add(ocrResult.text.trim())
                                }

                                // Update content in real-time
                                withContext(Dispatchers.Main) {
                                    val currentPages = pageTexts.toList()
                                    content.value = currentPages
                                    ocrProgress.value = index to total
                                    // Update total pages but don't move the user repeatedly while loading
                                    updateOcrPageCounter(index, total, updatePosition = false)
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.ERROR) { "OCR: Error processing page $index: ${e.message}" }
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
                if (pageTexts.isNotEmpty()) {
                    content.value = pageTexts
                    // Finish: ensure we have total pages, stay on current or move to 1?
                    // Let's just update total. If user was reading, they stay.
                    updateOcrPageCounter(pageTexts.size, pageTexts.size, updatePosition = false)
                } else {
                    content.value = listOf("No se pudo extraer texto.")
                }
            }

        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "OCR: Critical error: ${e.message}" }
             withContext(Dispatchers.Main) {
                content.value = if (pageTexts.isNotEmpty()) pageTexts else listOf("Error al procesar OCR.")
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

    override fun setChapters(chapters: ViewerChapters) {
        val chapter = chapters.currChapter
        currentChapter = chapter

        scope.launch(Dispatchers.IO) {
            try {
                isLoading.value = true
                content.value = emptyList()
                images.value = emptyList() // Reset images
                // Reset state safely
                pdfMutex.withLock {
                    pdfDocument?.let { try { it.destroy() } catch (e: Exception) {} }
                    pdfDocument = null
                    pdfFile?.delete()
                    pdfFile = null
                }

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
                if (!isCloudflare) {
                     content.value = listOf("Procesando texto...")

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
                        if (title.isNotEmpty()) sb.append(title).append("\n\n")

                        val rawTextLines = target.text().lines()
                        rawTextLines.forEach { line ->
                            val t = line.trim()
                             if (t.length > 3 && !pageNumberRegex.matches(t) && !t.contains("Next Chapter", true)) {
                                 sb.append(t).append("\n\n")
                             }
                        }
                    } else {
                         withContext(Dispatchers.Main) {
                            isLoading.value = false
                            content.value = listOf("Contenido no válido.")
                        }
                    }
                }


                // CHECK RESULT & FALLBACK
                if (isCloudflare || sb.length < 150 || isAdTrap) {
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
                } else {
                     // SUCCESS TEXT
                     withContext(Dispatchers.Main) {
                        isLoading.value = false
                        content.value = sb.toString().split("\n\n").filter { it.isNotBlank() }
                        images.value = emptyList()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading.value = false
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
            val pdfCacheFile = java.io.File(cacheDir, "current_pdf_${System.currentTimeMillis()}.pdf")

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

            // ATTEMPT NATIVE TEXT EXTRACTION (READING MODE)
            var extractedPages = emptyList<String>()
            try {
                withContext(Dispatchers.Main) {
                    loadingMessage.value = "Verificando modo lectura..."
                }

                extractedPages = PdfUtil.extractPdfPagesProgressive(pdfCacheFile) { page: Int, total: Int, pages: List<String> ->
                     loadingMessage.value = "Extrayendo texto: pág $page de $total..."

                     if (pages.isNotEmpty()) {
                         content.value = pages
                         // Update chapter definition but DO NOT move the reader position
                         updateOcrPageCounter(page, total, updatePosition = false)
                     }
                }

                if (extractedPages.isNotEmpty()) {
                     logcat { "NovelViewer: Text extracted natively (${extractedPages.size} pages)" }
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
                logcat(LogPriority.WARN) { "NovelViewer: Native extraction failed: ${e.message}" }
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
