package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Loader used to load pages from a .pdf file.
 */
internal class PdfPageLoader(
    private val context: Context,
    private val file: UniFile,
) : PageLoader() {

    override var isLocal: Boolean = true

    private var tempFile: File? = null
    private var pdfRenderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null

    override suspend fun getPages(): List<ReaderPage> {
        // Copy UniFile to a temp file for PdfRenderer (requires seekable file)
        val temp = File.createTempFile("pdf_", ".pdf", context.cacheDir)
        file.openInputStream().use { input ->
            temp.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        tempFile = temp

        fileDescriptor = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
        pdfRenderer = PdfRenderer(fileDescriptor!!)

        val pageCount = pdfRenderer!!.pageCount

        return (0 until pageCount).map { index ->
            ReaderPage(index).apply {
                stream = { renderPage(index) }
                status = Page.State.Ready
            }
        }
    }

    private fun renderPage(pageIndex: Int): ByteArrayInputStream {
        val renderer = pdfRenderer ?: throw IllegalStateException("PdfRenderer not initialized")
        val page = renderer.openPage(pageIndex)

        // Render at 2x density for better quality
        val scale = 2
        val width = page.width * scale
        val height = page.height * scale

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Fill with white background
        bitmap.eraseColor(android.graphics.Color.WHITE)

        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        bitmap.recycle()

        return ByteArrayInputStream(outputStream.toByteArray())
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        try {
            pdfRenderer?.close()
            fileDescriptor?.close()
            tempFile?.delete()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }
}
