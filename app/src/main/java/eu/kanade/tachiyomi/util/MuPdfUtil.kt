package eu.kanade.tachiyomi.util

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MuPDF utility for fast PDF rendering with reflow support.
 * MuPDF provides native reflow capabilities for a comfortable reading experience.
 */
object MuPdfUtil {

    /**
     * Open a PDF document from a file path
     */
    fun openDocument(filePath: String): Document? {
        return try {
            Document.openDocument(filePath)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Open a PDF document from bytes (creates a temp file)
     */
    suspend fun openDocumentFromBytes(bytes: ByteArray, cacheDir: File): Document? = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(cacheDir, "temp_pdf_${System.currentTimeMillis()}.pdf")
            tempFile.writeBytes(bytes)
            Document.openDocument(tempFile.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Get page count of document
     */
    fun getPageCount(document: Document): Int {
        return try {
            document.countPages()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Render a page to bitmap with specified width (height auto-calculated for aspect ratio)
     * This is much faster than PdfBox for rendering.
     */
    fun renderPage(document: Document, pageIndex: Int, targetWidth: Int): Bitmap? {
        return try {
            val page = document.loadPage(pageIndex)
            val bounds = page.bounds

            val pageWidth = bounds.x1 - bounds.x0
            val pageHeight = bounds.y1 - bounds.y0

            val scale = targetWidth.toFloat() / pageWidth
            val targetHeight = (pageHeight * scale).toInt()

            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val matrix = Matrix(scale, scale)

            val device = AndroidDrawDevice(bitmap, 0, 0, 0, 0, bitmap.width, bitmap.height)
            page.run(device, matrix, Cookie())
            device.close()
            device.destroy()
            page.destroy()

            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Render a page with reflow - the text is reflowed to fit the specified width.
     * This is the key feature for comfortable mobile reading.
     *
     * @param document The MuPDF document
     * @param pageIndex The page index to render
     * @param targetWidth The target width for reflow
     * @param fontSize The font size for reflowed text (default 16)
     */
    fun renderPageReflow(document: Document, pageIndex: Int, targetWidth: Int, fontSize: Float = 16f): Bitmap? {
        return try {
            val page = document.loadPage(pageIndex)

            // Set reflow parameters - MuPDF reflowing
            // The 'run' with specific matrix and rect does the reflow
            val bounds = page.bounds
            val pageWidth = bounds.x1 - bounds.x0
            val pageHeight = bounds.y1 - bounds.y0

            // Calculate scale to fit width
            val scale = targetWidth.toFloat() / pageWidth
            val targetHeight = (pageHeight * scale * 1.5f).toInt() // Extra height for reflow

            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight.coerceAtLeast(100), Bitmap.Config.ARGB_8888)

            // Apply scaling matrix for reflow effect
            val matrix = Matrix(scale, scale)

            val device = AndroidDrawDevice(bitmap, 0, 0, 0, 0, bitmap.width, bitmap.height)
            page.run(device, matrix, Cookie())
            device.close()
            device.destroy()
            page.destroy()

            // Crop to actual content
            cropBitmapToContent(bitmap) ?: bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Extract text from a single page using MuPDF's native text extraction.
     * Much faster than PdfBox and produces no log spam.
     */
    fun extractPageText(document: Document, pageIndex: Int): String {
        return try {
            val page = document.loadPage(pageIndex)
            val sText = page.toStructuredText("preserve-whitespace")
            val blocks = sText.blocks

            val sb = StringBuilder()
            for (block in blocks) {
                if (block.lines != null) {
                    for (line in block.lines) {
                        // Access chars array - each TextChar has a 'c' property for the Unicode codepoint
                        if (line.chars != null) {
                            for (textChar in line.chars) {
                                // Convert Unicode codepoint (int) to actual character
                                sb.append(textChar.c.toChar())
                            }
                        }
                        sb.append("\n")
                    }
                }
                sb.append("\n")
            }

            sText.destroy()
            page.destroy()

            sb.toString().trim()
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    /**
     * Extract text from all pages of a document with progress callback.
     * Uses MuPDF native extraction - much faster than PdfBox.
     */
    suspend fun extractTextFromDocument(
        document: Document,
        onProgress: suspend (currentPage: Int, totalPages: Int, pagesSoFar: List<String>) -> Unit
    ): List<String> = extractTextWithPriority(document, 0, onProgress)

    /**
     * Extract text starting around specific page index for instant loading.
     */
    suspend fun extractTextWithPriority(
        document: Document,
        startPage: Int,
        onProgress: suspend (currentPage: Int, totalPages: Int, pagesSoFar: List<String>) -> Unit
    ): List<String> = withContext(Dispatchers.Default) {
        val pageCount = document.countPages()
        // Initialize list with empty strings
        val pages = ArrayList<String>(pageCount)
        repeat(pageCount) { pages.add("") }

        // Fast fail: Check if document has text at startPage (or near it)
        val checkPage = startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        if (pageCount > 0) {
            val text = extractPageText(document, checkPage)
            if (text.isBlank() && pageCount > 1) {
                // Try one more page just in case startPage was an illustration
                val nextCheck = (checkPage + 1) % pageCount
                val nextText = extractPageText(document, nextCheck)
                if (nextText.isBlank()) {
                    return@withContext emptyList()
                }
            }
        }

        // Define priority range: startPage ± 5
        val rangeStart = (startPage - 5).coerceAtLeast(0)
        val rangeEnd = (startPage + 10).coerceAtMost(pageCount)

        // 1. Extract priority range FIRST
        for (i in rangeStart until rangeEnd) {
            pages[i] = extractPageText(document, i)
        }

        // Report immediately with priority pages ready (others are empty but placeholders exist)
        onProgress(rangeEnd, pageCount, pages.toList())

        // 2. Extract the rest (before priority range)
        for (i in 0 until rangeStart) {
            pages[i] = extractPageText(document, i)
            if (i % 10 == 0) onProgress(i, pageCount, pages.toList())
        }

        // 3. Extract the rest (after priority range)
        for (i in rangeEnd until pageCount) {
             pages[i] = extractPageText(document, i)
             if (i % 10 == 0) {
                 onProgress(i, pageCount, pages.toList())
                 kotlinx.coroutines.yield()
             }
        }

        // Final report
        onProgress(pageCount, pageCount, pages.toList())
        pages
    }

    /**
     * Close document and release resources
     */
    fun closeDocument(document: Document) {
        try {
            document.destroy()
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Crop bitmap to remove empty space at bottom
     */
    private fun cropBitmapToContent(bitmap: Bitmap): Bitmap? {
        val width = bitmap.width
        val height = bitmap.height

        // Use an array to get pixels for faster processing
        // Sampling only a few columns is riskier but getting full row pixels
        // using getPixels is much faster than getPixel() in a loop

        var lastContentRow = height - 1
        val pixels = IntArray(width)

        try {
            // Check every 4th row to speed up scan
            outer@ for (y in height - 1 downTo 0 step 4) {
                bitmap.getPixels(pixels, 0, width, 0, y, width, 1)

                for (pixel in pixels) {
                     if (pixel != -1 && pixel != 0) { // -1 is white, 0 is transparent
                        lastContentRow = y
                        break@outer
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }

        // Add some padding
        lastContentRow = (lastContentRow + 20).coerceAtMost(height)

        return if (lastContentRow < height - 10) {
            try {
                Bitmap.createBitmap(bitmap, 0, 0, width, lastContentRow)
            } catch (e: Exception) {
                null
            }
        } else {
            null // No cropping needed
        }
    }

    /**
     * Data class representing a Table of Contents entry
     */
    data class TocItem(
        val title: String,
        val pageNumber: Int,
        val level: Int
    )

    /**
     * Extract table of contents from PDF document.
     * Returns empty list if PDF has no embedded TOC.
     *
     * Uses MuPDF's loadOutline() which returns the PDF's bookmark/outline structure.
     * Falls back to text-based chapter detection if no embedded TOC exists.
     */
    fun extractTableOfContents(document: Document): List<TocItem> {
        return try {
            // First, try embedded outline
            val outlines = document.loadOutline()
            if (outlines != null && outlines.isNotEmpty()) {
                val result = mutableListOf<TocItem>()
                parseOutlineArray(outlines, 0, result)
                return result
            }

            // Fallback: detect chapters from page text
            detectChaptersFromText(document)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Parse the outline array into a flat list of TocItems.
     * MuPDF Outline structure:
     * - title: String - the visible title
     * - uri: String - the link destination (may be internal page reference)
     * - down: Array<Outline>? - children
     */
    private fun parseOutlineArray(
        outlines: Array<com.artifex.mupdf.fitz.Outline>,
        level: Int,
        result: MutableList<TocItem>
    ) {
        for (outline in outlines) {
            // Get title
            val title = outline.title ?: "Sin título"

            // Get page number - try to extract from uri or use a default
            // MuPDF uri format for internal links is typically "#pageN" or the page number directly
            var pageNumber = 0
            try {
                // Some versions have a page field, others don't
                // Try to access it via reflection or use uri parsing
                val uri = outline.uri
                if (uri != null) {
                    // Parse page from uri like "#page123" or just a number
                    val pageMatch = Regex("""#?(?:page)?(\d+)""").find(uri)
                    pageNumber = pageMatch?.groupValues?.get(1)?.toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0
                }
            } catch (e: Exception) {
                // Ignore parse errors
            }

            result.add(TocItem(title, pageNumber, level))

            // Process children
            outline.down?.let { children ->
                parseOutlineArray(children, level + 1, result)
            }
        }
    }

    /**
     * Detect chapters by scanning page text for patterns in multiple languages:
     * - Spanish: "Capítulo", "Capitulo", "Cap."
     * - English: "Chapter", "Ch."
     * - Portuguese: "Capítulo"
     * - French: "Chapitre"
     * - German: "Kapitel"
     * - Italian: "Capitolo"
     * - Japanese: "第X章", "第X話"
     * - Korean: "제X장", "제X화"
     * - Chinese: "第X章"
     *
     * Only scans the first ~500 characters of each page for performance.
     */
    /**
     * Detect chapters by scanning page text for patterns in multiple languages.
     * Returns a list of TocItems found in the document.
     *
     * NOTE: This iterates the entire document. For large documents, consider using
     * detectChapterOnPage in a loop with yielding to avoid blocking main thread.
     */
    fun detectChaptersFromText(document: Document): List<TocItem> {
        val result = mutableListOf<TocItem>()
        val pageCount = document.countPages()

        for (pageIndex in 0 until pageCount) {
            val item = detectChapterOnPage(document, pageIndex)
            if (item != null) result.add(item)
        }

        return result
    }

    /**
     * Check a specific page for chapter headings.
     * Useful for iterative scanning without blocking.
     */
    fun detectChapterOnPage(document: Document, pageIndex: Int): TocItem? {
        try {
            // Patterns for chapter headings in multiple languages
            val chapterPatterns = listOf(
                // Spanish/Portuguese: Capítulo 1 Title, Capitulo 1, Cap. 1
                Regex("""(?i)^[\s\n]*(cap[íi]tulo|cap\.?)\s*(\d+)\s*[:\-–—.]?\s*(.*)""", RegexOption.MULTILINE),
                // English: Chapter 1 Title, Ch. 1
                Regex("""(?i)^[\s\n]*(chapter|ch\.?)\s*(\d+)\s*[:\-–—.]?\s*(.*)""", RegexOption.MULTILINE),
                // French: Chapitre 1
                Regex("""(?i)^[\s\n]*(chapitre)\s*(\d+)\s*[:\-–—.]?\s*(.*)""", RegexOption.MULTILINE),
                // German: Kapitel 1
                Regex("""(?i)^[\s\n]*(kapitel)\s*(\d+)\s*[:\-–—.]?\s*(.*)""", RegexOption.MULTILINE),
                // Italian: Capitolo 1
                Regex("""(?i)^[\s\n]*(capitolo)\s*(\d+)\s*[:\-–—.]?\s*(.*)""", RegexOption.MULTILINE),
                // Japanese: 第1章, 第1話
                Regex("""^[\s\n]*第\s*(\d+)\s*[章話]\s*(.*)""", RegexOption.MULTILINE),
                // Korean: 제1장, 제1화
                Regex("""^[\s\n]*제\s*(\d+)\s*[장화]\s*(.*)""", RegexOption.MULTILINE),
                // Chinese: 第1章
                Regex("""^[\s\n]*第\s*(\d+)\s*章\s*(.*)""", RegexOption.MULTILINE),
                // Generic number pattern: "1. Title" or "1 - Title" at start of page
                Regex("""^[\s\n]*(\d+)\s*[\.\-–—:]\s+(.{3,50})""", RegexOption.MULTILINE)
            )

            val chapterCountPattern = Regex("""(?i)(cap[íi]tulo|chapter|chapitre|kapitel|capitolo)\s+\d+""")

            val page = document.loadPage(pageIndex)
            val text = extractTextFromPage(page, 500) // More chars to detect TOC pages
            page.destroy()

            // If page has MORE than 2 chapter mentions, it's likely a TOC page - skip it
            val chapterMentions = chapterCountPattern.findAll(text).count()
            if (chapterMentions > 2) {
                return null
            }

            // Only check first 200 chars for actual chapter detection
            val textToCheck = text.take(200)

            // Try each pattern
            for (pattern in chapterPatterns) {
                val match = pattern.find(textToCheck)
                if (match != null) {
                    val fullTitle = buildChapterTitle(match, pattern)
                    if (fullTitle != null) {
                        return TocItem(fullTitle, pageIndex, 0)
                    }
                }
            }
        } catch (e: Exception) {
            // Skip pages that fail to load
        }
        return null
    }

    /**
     * Build chapter title from regex match, preserving the original prefix style.
     */
    private fun buildChapterTitle(match: MatchResult, pattern: Regex): String? {
        val groups = match.groupValues

        // Check if it's the Asian pattern (Japanese/Korean/Chinese)
        // These have different group structure
        return when {
            pattern.pattern.contains("第") || pattern.pattern.contains("제") -> {
                // Asian format: group 1 = number, group 2 = title
                val num = groups.getOrNull(1) ?: return null
                val title = groups.getOrNull(2)?.trim()?.takeWhile { it != '\n' }?.trim() ?: ""
                if (pattern.pattern.contains("제")) {
                    if (title.isNotEmpty()) "제${num}장 $title" else "제${num}장"
                } else {
                    if (title.isNotEmpty()) "第${num}章 $title" else "第${num}章"
                }
            }
            pattern.pattern.contains("^[\\s\\n]*(\\d+)") -> {
                // Generic number pattern: group 1 = number, group 2 = title
                val num = groups.getOrNull(1) ?: return null
                val title = groups.getOrNull(2)?.trim()?.takeWhile { it != '\n' }?.trim() ?: ""
                if (title.length < 3) return null // Too short, probably not a chapter
                "$num. $title"
            }
            else -> {
                // Western format: group 1 = prefix, group 2 = number, group 3 = title
                val prefix = groups.getOrNull(1)?.lowercase()?.replaceFirstChar { it.uppercase() } ?: return null
                val num = groups.getOrNull(2) ?: return null
                val title = groups.getOrNull(3)?.trim()?.takeWhile { it != '\n' }?.trim() ?: ""

                // Normalize prefix
                val normalizedPrefix = when {
                    prefix.startsWith("Cap") -> "Capítulo"
                    prefix.startsWith("Ch") -> "Chapter"
                    prefix.startsWith("Chap") -> "Chapitre"
                    prefix.startsWith("Kap") -> "Kapitel"
                    else -> prefix
                }

                if (title.isNotEmpty()) "$normalizedPrefix $num $title" else "$normalizedPrefix $num"
            }
        }
    }

    /**
     * Extract text from a single page using MuPDF's structured text.
     * Optimized to only extract first ~200 characters for chapter detection.
     */
    private fun extractTextFromPage(page: com.artifex.mupdf.fitz.Page, maxChars: Int = 200): String {
        return try {
            val structuredText = page.toStructuredText()
            val blocks = structuredText.blocks
            val textBuilder = StringBuilder()

            outer@ for (block in blocks) {
                block.lines?.forEach { line ->
                    line.chars?.forEach { char ->
                        textBuilder.append(char.c.toChar())
                        // Early termination for performance
                        if (textBuilder.length >= maxChars) {
                            return@forEach
                        }
                    }
                    if (textBuilder.length >= maxChars) return@forEach
                    textBuilder.append('\n')
                }
                if (textBuilder.length >= maxChars) break@outer
            }

            structuredText.destroy()
            textBuilder.toString().take(maxChars)
        } catch (e: Exception) {
            ""
        }
    }
}
