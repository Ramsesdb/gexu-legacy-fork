package eu.kanade.tachiyomi.ui.browse.source.browse

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * FAB for importing PDFs into LocalSource
 */
@Composable
fun ImportPdfFab(
    onImportComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showDialog by remember { mutableStateOf(false) }
    var selectedPdfUri by remember { mutableStateOf<Uri?>(null) }
    var selectedPdfName by remember { mutableStateOf("") }
    var existingNovels by remember { mutableStateOf<List<String>>(emptyList()) }
    var showDuplicateDialog by remember { mutableStateOf(false) }
    var duplicateInfo by remember { mutableStateOf<DuplicateInfo?>(null) }
    var pendingNovelName by remember { mutableStateOf("") }

    val pdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedPdfUri = uri
            selectedPdfName = getFileName(context, uri)
            existingNovels = getExistingNovels()
            showDialog = true
        }
    }

    FloatingActionButton(
        onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primary,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "Importar PDF",
        )
    }

    if (showDialog && selectedPdfUri != null) {
        ImportPdfDialog(
            pdfName = selectedPdfName,
            existingNovels = existingNovels,
            onDismiss = {
                showDialog = false
                selectedPdfUri = null
            },
            onConfirm = { novelName ->
                scope.launch {
                    val duplicate = checkForDuplicate(novelName, selectedPdfName)
                    if (duplicate != null) {
                        pendingNovelName = novelName
                        duplicateInfo = duplicate
                        showDialog = false
                        showDuplicateDialog = true
                    } else {
                        importPdf(context, selectedPdfUri!!, novelName, DuplicateAction.CREATE_COPY)
                        showDialog = false
                        selectedPdfUri = null
                        onImportComplete()
                    }
                }
            }
        )
    }

    if (showDuplicateDialog && duplicateInfo != null) {
        DuplicateFileDialog(
            fileName = duplicateInfo!!.fileName,
            novelName = duplicateInfo!!.novelName,
            onDismiss = {
                showDuplicateDialog = false
                duplicateInfo = null
                selectedPdfUri = null
            },
            onAction = { action ->
                scope.launch {
                    if (action != DuplicateAction.SKIP) {
                        importPdf(context, selectedPdfUri!!, pendingNovelName, action)
                    }
                    showDuplicateDialog = false
                    duplicateInfo = null
                    selectedPdfUri = null
                    onImportComplete()
                }
            }
        )
    }
}

@Composable
private fun ImportPdfDialog(
    pdfName: String,
    existingNovels: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selectedOption by remember { mutableStateOf<ImportOption>(ImportOption.NewNovel) }
    var newNovelName by remember { mutableStateOf(pdfName.removeSuffix(".pdf").removeSuffix(".PDF")) }
    var selectedExistingNovel by remember { mutableStateOf(existingNovels.firstOrNull() ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Importar PDF") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Archivo: $pdfName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Option: New novel
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RadioButton(
                        selected = selectedOption == ImportOption.NewNovel,
                        onClick = { selectedOption = ImportOption.NewNovel }
                    )
                    Text("Crear nueva novela")
                }

                if (selectedOption == ImportOption.NewNovel) {
                    OutlinedTextField(
                        value = newNovelName,
                        onValueChange = { newNovelName = it },
                        label = { Text("Nombre de la novela") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 40.dp),
                        singleLine = true
                    )
                }

                // Option: Add to existing
                if (existingNovels.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = selectedOption == ImportOption.ExistingNovel,
                            onClick = { selectedOption = ImportOption.ExistingNovel }
                        )
                        Text("Agregar a novela existente")
                    }

                    if (selectedOption == ImportOption.ExistingNovel) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .padding(start = 40.dp)
                        ) {
                            items(existingNovels) { novel ->
                                Surface(
                                    onClick = { selectedExistingNovel = novel },
                                    color = if (selectedExistingNovel == novel)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        MaterialTheme.colorScheme.surface,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = novel,
                                        modifier = Modifier.padding(12.dp),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val novelName = when (selectedOption) {
                        ImportOption.NewNovel -> newNovelName
                        ImportOption.ExistingNovel -> selectedExistingNovel
                    }
                    if (novelName.isNotBlank()) {
                        onConfirm(novelName)
                    }
                }
            ) {
                Text("Importar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

private sealed class ImportOption {
    data object NewNovel : ImportOption()
    data object ExistingNovel : ImportOption()
}

private sealed class DuplicateAction {
    data object SKIP : DuplicateAction()
    data object REPLACE : DuplicateAction()
    data object CREATE_COPY : DuplicateAction()
}

private data class DuplicateInfo(
    val fileName: String,
    val novelName: String,
)

@Composable
private fun DuplicateFileDialog(
    fileName: String,
    novelName: String,
    onDismiss: () -> Unit,
    onAction: (DuplicateAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text("Archivo duplicado") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "El archivo \"$fileName\" ya existe en la novela \"$novelName\".",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "¿Qué deseas hacer?",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(DuplicateAction.REPLACE) }) {
                Text("Reemplazar")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onAction(DuplicateAction.SKIP) }) {
                    Text("Saltar")
                }
                TextButton(onClick = { onAction(DuplicateAction.CREATE_COPY) }) {
                    Text("Crear copia")
                }
            }
        }
    )
}

private fun getFileName(context: Context, uri: Uri): String {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        cursor.moveToFirst()
        if (nameIndex >= 0) cursor.getString(nameIndex) else null
    } ?: uri.lastPathSegment ?: "document.pdf"
}

private fun getExistingNovels(): List<String> {
    val storageManager: StorageManager = Injekt.get()
    val localDir = storageManager.getLocalSourceDirectory() ?: return emptyList()

    return localDir.listFiles()
        ?.filter { it.isDirectory && !it.name.orEmpty().startsWith(".") }
        ?.mapNotNull { it.name }
        ?.sorted()
        ?: emptyList()
}

private fun checkForDuplicate(novelName: String, fileName: String): DuplicateInfo? {
    val storageManager: StorageManager = Injekt.get()
    val localDir = storageManager.getLocalSourceDirectory() ?: return null

    val novelDir = localDir.findFile(novelName) ?: return null

    // Check if file with same name exists
    if (novelDir.findFile(fileName) != null) {
        return DuplicateInfo(fileName, novelName)
    }

    return null
}

private suspend fun importPdf(context: Context, pdfUri: Uri, novelName: String, action: DuplicateAction) {
    withContext(Dispatchers.IO) {
        val storageManager: StorageManager = Injekt.get()
        val localDir = storageManager.getLocalSourceDirectory() ?: return@withContext

        // Create or get novel directory
        val novelDir = localDir.findFile(novelName)
            ?: localDir.createDirectory(novelName)
            ?: return@withContext

        // Get filename
        val fileName = getFileName(context, pdfUri)

        val finalFileName = when (action) {
            DuplicateAction.REPLACE -> {
                // Delete existing file first
                novelDir.findFile(fileName)?.delete()
                fileName
            }
            DuplicateAction.CREATE_COPY -> {
                // Generate unique name
                var newName = fileName
                var counter = 1
                while (novelDir.findFile(newName) != null) {
                    val baseName = fileName.removeSuffix(".pdf").removeSuffix(".PDF")
                    newName = "$baseName ($counter).pdf"
                    counter++
                }
                newName
            }
            DuplicateAction.SKIP -> return@withContext
        }

        // Create and copy file
        val pdfFile = novelDir.createFile(finalFileName) ?: return@withContext

        context.contentResolver.openInputStream(pdfUri)?.use { input ->
            pdfFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}

