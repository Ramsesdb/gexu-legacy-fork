package eu.kanade.tachiyomi.ui.reader

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.logcat
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Activity to handle opening PDF files from external apps (file manager, etc.)
 * Imports the PDF to the local source and opens the library.
 */
class PdfViewerActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)

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

        logcat { "PdfViewerActivity: Opening PDF from URI: $uri" }

        scope.launch {
            try {
                openPdfFromUri(uri)
            } catch (e: Exception) {
                logcat { "PdfViewerActivity: Error opening PDF: ${e.message}" }
                Toast.makeText(this@PdfViewerActivity, "Error al abrir PDF: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private suspend fun openPdfFromUri(uri: Uri) {
        withContext(Dispatchers.IO) {
            // Get file name from URI
            val fileName = getFileName(uri) ?: "external_pdf.pdf"

            // Import to local source
            val storageManager: StorageManager = Injekt.get()
            val localDir = storageManager.getLocalSourceDirectory()

            if (localDir == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@PdfViewerActivity,
                        "Primero configura el directorio de fuente local en Ajustes > Almacenamiento",
                        Toast.LENGTH_LONG
                    ).show()
                    // Open main app anyway
                    openMainApp()
                }
                return@withContext
            }

            // Create a special directory for external PDFs
            val externalPdfDir = localDir.findFile("_PDFs Externos")
                ?: localDir.createDirectory("_PDFs Externos")
                ?: run {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PdfViewerActivity, "Error al crear directorio", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@withContext
                }

            // Generate unique filename
            val baseName = fileName.removeSuffix(".pdf").removeSuffix(".PDF")
            var finalFileName = "$baseName.pdf"
            var counter = 1
            while (externalPdfDir.findFile(finalFileName) != null) {
                finalFileName = "$baseName ($counter).pdf"
                counter++
            }

            // Copy file to local source
            val targetFile = externalPdfDir.createFile(finalFileName)
            if (targetFile == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PdfViewerActivity, "Error al crear archivo", Toast.LENGTH_SHORT).show()
                    finish()
                }
                return@withContext
            }

            contentResolver.openInputStream(uri)?.use { input ->
                targetFile.openOutputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PdfViewerActivity, "Error al leer el archivo", Toast.LENGTH_SHORT).show()
                    finish()
                }
                return@withContext
            }

            logcat { "PdfViewerActivity: PDF imported to local source: ${targetFile.name}" }

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@PdfViewerActivity,
                    "PDF importado a \"PDFs Externos\". Búscalo en Browse > Fuente Local",
                    Toast.LENGTH_LONG
                ).show()

                // Open main app
                openMainApp()
            }
        }
    }

    private fun openMainApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        finish()
    }

    private fun getFileName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            if (nameIndex >= 0) cursor.getString(nameIndex) else null
        } ?: uri.lastPathSegment
    }
}
