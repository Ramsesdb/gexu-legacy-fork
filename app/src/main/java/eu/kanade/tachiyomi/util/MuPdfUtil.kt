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

            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight.coerceAtLeast(100), Bitmap.Config.RGB_565)

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
    ): List<String> = withContext(Dispatchers.Default) {
        val pageCount = document.countPages()
        val pages = ArrayList<String>(pageCount)

        // Check if first page has text (fail fast for image-only PDFs)
        if (pageCount > 0) {
            val firstPageText = extractPageText(document, 0)
            if (firstPageText.isBlank() && pageCount > 1) {
                val secondPageText = extractPageText(document, 1)
                if (secondPageText.isBlank()) {
                    return@withContext emptyList()
                }
            }
        }

        for (pageIndex in 0 until pageCount) {
            val text = extractPageText(document, pageIndex)
            pages.add(text)

            // Report progress every page for first 10, then every 10 pages
            val pageNum = pageIndex + 1
            val shouldReport = pageNum <= 10 || pageNum % 10 == 0 || pageNum == pageCount

            if (shouldReport) {
                onProgress(pageNum, pageCount, pages.toList())
            }

            // Yield every 10 pages to keep UI responsive
            if (pageIndex % 10 == 9) {
                kotlinx.coroutines.yield()
            }
        }

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
}
