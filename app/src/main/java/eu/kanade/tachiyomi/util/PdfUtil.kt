package eu.kanade.tachiyomi.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object PdfUtil {

    // Chunk size for processing - process this many pages before yielding
    private const val CHUNK_SIZE = 10
    private const val CHUNK_DELAY_MS = 50L // Small delay between chunks for GC

    suspend fun extractPdfText(
        inputStream: InputStream,
    ): String = withContext(Dispatchers.Default) {
        try {
            PDDocument.load(inputStream).use { document ->
                processSimplePdfText(document)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    suspend fun extractPdfText(
        bytes: ByteArray,
    ): String = withContext(Dispatchers.Default) {
        try {
            PDDocument.load(bytes).use { document ->
                processSimplePdfText(document)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    suspend fun extractPdfText(
        file: File,
    ): String = withContext(Dispatchers.Default) {
        try {
            PDDocument.load(file).use { document ->
                processSimplePdfText(document)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    private fun processSimplePdfText(document: PDDocument): String {
        val pageCount = document.numberOfPages
        // Reuse a single stripper instance to reduce allocations
        val stripper = PDFTextStripper().apply {
            sortByPosition = true
        }
        // Pre-allocate with estimated capacity (avg ~2KB per page)
        val sb = StringBuilder(pageCount * 2048)

        for (page in 1..pageCount) {
            stripper.startPage = page
            stripper.endPage = page
            val raw = stripper.getText(document)
            val cleaned = cleanPdfPageText(raw)
            if (cleaned.isNotBlank()) {
                sb.append(cleaned).append("\n\n")
            }
        }
        return sb.toString().trim()
    }

    /**
     * Progressive PDF text extraction - calls onPageExtracted for each page
     * allowing UI to update incrementally
     */
    suspend fun extractPdfTextProgressive(
        bytes: ByteArray,
        onProgress: (currentPage: Int, totalPages: Int, textSoFar: String?) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        try {
            PDDocument.load(bytes).use { document ->
                processPdfDocument(document, onProgress)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    suspend fun extractPdfTextProgressive(
        file: File,
        onProgress: (currentPage: Int, totalPages: Int, textSoFar: String?) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        try {
            PDDocument.load(file).use { document ->
                processPdfDocument(document, onProgress)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    suspend fun extractPdfPagesProgressive(
        file: File,
        onProgress: (currentPage: Int, totalPages: Int, pagesSoFar: List<String>) -> Unit,
    ): List<String> = withContext(Dispatchers.Default) {
        try {
            PDDocument.load(file).use { document ->
                processPdfDocumentPages(document, onProgress)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Extract PDF pages with disk caching.
     * Returns cached result if available, otherwise extracts and caches.
     */
    suspend fun extractPdfPagesWithCache(
        file: File,
        cacheDir: File,
        onProgress: (currentPage: Int, totalPages: Int, pagesSoFar: List<String>) -> Unit,
    ): List<String> = withContext(Dispatchers.Default) {
        val cacheKey = getCacheKey(file)
        val cacheFile = File(cacheDir, "pdf_text_cache_$cacheKey.txt")

        // Try to load from cache
        val cachedPages = loadFromCache(cacheFile)
        if (cachedPages != null) {
            // Report cached progress immediately
            withContext(Dispatchers.Main) {
                onProgress(cachedPages.size, cachedPages.size, cachedPages)
            }
            return@withContext cachedPages
        }

        // Extract fresh
        try {
            val pages = PDDocument.load(file).use { document ->
                processPdfDocumentPages(document, onProgress)
            }

            // Save to cache if successful
            if (pages.isNotEmpty()) {
                saveToCache(cacheFile, pages)
            }

            pages
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Generate a cache key based on file path, size, and last modified time.
     */
    private fun getCacheKey(file: File): String {
        val data = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(data.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Load cached pages from disk.
     * Returns null if cache doesn't exist or is corrupted.
     */
    private fun loadFromCache(cacheFile: File): List<String>? {
        if (!cacheFile.exists()) return null
        return try {
            val content = cacheFile.readText()
            if (content.isBlank()) return null
            // Pages are separated by a special delimiter
            content.split("\n---PAGE_DELIMITER---\n")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Save pages to disk cache.
     */
    private fun saveToCache(cacheFile: File, pages: List<String>) {
        try {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(pages.joinToString("\n---PAGE_DELIMITER---\n"))
        } catch (e: Exception) {
            // Ignore cache write errors
        }
    }

    private suspend fun processPdfDocument(
        document: PDDocument,
        onProgress: (currentPage: Int, totalPages: Int, textSoFar: String?) -> Unit,
    ): String {
        val pageCount = document.numberOfPages
        val stripper = PDFTextStripper().apply {
            sortByPosition = true
        }
        // Pre-allocate with estimated capacity (avg ~2KB per page)
        val sb = StringBuilder(pageCount * 2048)

        // Initial check on first page to fail fast if no text
        if (pageCount > 0) {
            stripper.startPage = 1
            stripper.endPage = 1
            val firstPageText = cleanPdfPageText(stripper.getText(document))
            if (firstPageText.isBlank() && pageCount > 1) {
                // Check second page just in case first is cover
                stripper.startPage = 2
                stripper.endPage = 2
                val secondPageText = cleanPdfPageText(stripper.getText(document))
                if (secondPageText.isBlank()) {
                    // Assume scanned PDF if first 2 pages are empty
                    return ""
                }
            }
        }

        for (page in 1..pageCount) {
            stripper.startPage = page
            stripper.endPage = page
            val raw = stripper.getText(document)
            val cleaned = cleanPdfPageText(raw)
            if (cleaned.isNotBlank()) {
                sb.append(cleaned).append("\n\n")
            }

            // OPTIMIZATION:
            // Only convert StringBuilder to String (expensive) for the first 50 pages
            // to give the user something to read immediately ("Proximity").
            // For the rest, only report page progress to avoid UI jank from massive allocations.
            // Always report full text on the very last page.
            val isEarlyPage = page <= 50 && page % 5 == 0
            val isFinalPage = page == pageCount
            val isProgressUpdate = page % 10 == 0

            if (isEarlyPage || isFinalPage || isProgressUpdate) {
                withContext(Dispatchers.Main) {
                    val textToSend = if (isEarlyPage || isFinalPage) sb.toString().trim() else null
                    onProgress(page, pageCount, textToSend)
                }
            }
        }
        return sb.toString().trim()
    }

    private suspend fun processPdfDocumentPages(
        document: PDDocument,
        onProgress: (currentPage: Int, totalPages: Int, pagesSoFar: List<String>) -> Unit,
    ): List<String> {
        val pageCount = document.numberOfPages
        val stripper = PDFTextStripper().apply {
            sortByPosition = true
        }
        // Pre-allocate list with known capacity
        val pages = ArrayList<String>(pageCount)

        // Initial check - fail fast if PDF has no text
        if (pageCount > 0) {
            stripper.startPage = 1
            stripper.endPage = 1
            val firstPageText = cleanPdfPageText(stripper.getText(document))
            if (firstPageText.isBlank() && pageCount > 1) {
                stripper.startPage = 2
                stripper.endPage = 2
                val secondPageText = cleanPdfPageText(stripper.getText(document))
                if (secondPageText.isBlank()) {
                    return emptyList()
                }
            }
        }

        // Process in chunks to reduce GC pressure
        var pagesInCurrentChunk = 0

        for (page in 1..pageCount) {
            stripper.startPage = page
            stripper.endPage = page
            val raw = stripper.getText(document)
            val cleaned = cleanPdfPageText(raw)
            pages.add(cleaned)

            pagesInCurrentChunk++

            // Yield between chunks to allow GC and UI updates
            if (pagesInCurrentChunk >= CHUNK_SIZE) {
                pagesInCurrentChunk = 0
                yield() // Allow other coroutines to run
                delay(CHUNK_DELAY_MS) // Give GC time to clean up
            }

            // Report progress: first 10 pages individually, then every 10 pages
            val shouldReport = page <= 10 || page % 10 == 0 || page == pageCount

            if (shouldReport) {
                val snapshot = pages.toList()
                withContext(Dispatchers.Main) {
                    onProgress(page, pageCount, snapshot)
                }
            }
        }
        return pages
    }

    private fun cleanPdfPageText(raw: String): String {
        val sb = StringBuilder()
        var prevLine: String? = null
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                sb.append("\n\n")
                prevLine = null
                continue
            }
            if (prevLine == null) {
                sb.append(trimmed)
            } else {
                if (prevLine.endsWith("-")) {
                    sb.setLength(sb.length - 1)
                    sb.append(trimmed)
                } else {
                    sb.append(" ").append(trimmed)
                }
            }
            prevLine = trimmed
        }
        return sb.toString()
    }

    // For rendering pages to bitmap (for OCR later)
    fun renderPdfPageToBitmap(file: File, pageIndex: Int): Bitmap? {
        return try {
            val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fileDescriptor)

            if (pageIndex >= renderer.pageCount) {
                renderer.close()
                fileDescriptor.close()
                return null
            }

            val page = renderer.openPage(pageIndex)
            val width = page.width * 2
            val height = page.height * 2

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            page.close()
            renderer.close()
            fileDescriptor.close()

            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
