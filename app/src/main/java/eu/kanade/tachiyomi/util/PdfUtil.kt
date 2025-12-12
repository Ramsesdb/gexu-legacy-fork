package eu.kanade.tachiyomi.util

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import java.io.File

object PdfUtil {

    suspend fun extractPdfText(
        inputStream: InputStream
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
        bytes: ByteArray
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
        file: File
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
        val stripper = PDFTextStripper().apply {
            sortByPosition = true
        }
        val sb = StringBuilder()

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
        onProgress: (currentPage: Int, totalPages: Int, textSoFar: String?) -> Unit
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
        onProgress: (currentPage: Int, totalPages: Int, textSoFar: String?) -> Unit
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
        onProgress: (currentPage: Int, totalPages: Int, pagesSoFar: List<String>) -> Unit
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

    private suspend fun processPdfDocument(
        document: PDDocument,
        onProgress: (currentPage: Int, totalPages: Int, textSoFar: String?) -> Unit
    ): String {
        val pageCount = document.numberOfPages
        val stripper = PDFTextStripper().apply {
            sortByPosition = true
        }
        val sb = StringBuilder()

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
        onProgress: (currentPage: Int, totalPages: Int, pagesSoFar: List<String>) -> Unit
    ): List<String> {
        val pageCount = document.numberOfPages
        val stripper = PDFTextStripper().apply {
            sortByPosition = true
        }
        val pages = mutableListOf<String>()

        // Initial check
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

        for (page in 1..pageCount) {
            stripper.startPage = page
            stripper.endPage = page
            val raw = stripper.getText(document)
            val cleaned = cleanPdfPageText(raw)
            // Even empty pages should be preserved to keep page count sync
            pages.add(cleaned)

            val isEarlyPage = page <= 50
            val isFinalPage = page == pageCount
            val isProgressUpdate = page % 10 == 0

            if (isEarlyPage || isFinalPage || isProgressUpdate) {
                withContext(Dispatchers.Main) {
                    onProgress(page, pageCount, pages.toList())
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
