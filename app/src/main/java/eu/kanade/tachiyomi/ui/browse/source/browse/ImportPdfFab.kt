package eu.kanade.tachiyomi.ui.browse.source.browse

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * FAB for importing PDFs into LocalSource - supports multiple file selection
 */
@Composable
fun ImportPdfFab(
    onImportComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State for multiple files
    var showDialog by remember { mutableStateOf(false) }
    var selectedPdfUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedPdfNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var existingNovels by remember { mutableStateOf<List<String>>(emptyList()) }

    // Import progress state
    var isImporting by remember { mutableStateOf(false) }
    var importProgress by remember { mutableFloatStateOf(0f) }
    var importingFileName by remember { mutableStateOf("") }

    // Duplicate handling state
    var showDuplicateDialog by remember { mutableStateOf(false) }
    var duplicateInfo by remember { mutableStateOf<DuplicateInfo?>(null) }
    var pendingNovelName by remember { mutableStateOf("") }
    var pendingUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var currentDuplicateIndex by remember { mutableStateOf(0) }
    var applyToAll by remember { mutableStateOf(false) }
    var globalDuplicateAction by remember { mutableStateOf<DuplicateAction?>(null) }

    // Multiple file picker
    val pdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedPdfUris = uris
            selectedPdfNames = uris.map { getFileName(context, it) }
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
            contentDescription = "Importar PDFs",
        )
    }

    // Main import dialog
    if (showDialog && selectedPdfUris.isNotEmpty()) {
        ImportMultiplePdfDialog(
            fileCount = selectedPdfUris.size,
            fileNames = selectedPdfNames,
            existingNovels = existingNovels,
            onDismiss = {
                showDialog = false
                selectedPdfUris = emptyList()
                selectedPdfNames = emptyList()
            },
            onConfirm = { novelName ->
                showDialog = false
                pendingNovelName = novelName
                pendingUris = selectedPdfUris
                currentDuplicateIndex = 0
                applyToAll = false
                globalDuplicateAction = null

                // Start importing
                scope.launch {
                    isImporting = true
                    importProgress = 0f

                    val totalFiles = pendingUris.size
                    var importedCount = 0
                    var skippedCount = 0

                    for ((index, uri) in pendingUris.withIndex()) {
                        val fileName = getFileName(context, uri)
                        importingFileName = fileName
                        importProgress = index.toFloat() / totalFiles

                        // Check for duplicate
                        val duplicate = checkForDuplicate(novelName, fileName)

                        val action = if (duplicate != null) {
                            if (globalDuplicateAction != null) {
                                globalDuplicateAction!!
                            } else {
                                // Show duplicate dialog and wait for user response
                                currentDuplicateIndex = index
                                duplicateInfo = duplicate
                                showDuplicateDialog = true

                                // Wait for user to make a choice (this will be handled by callback)
                                // For now, skip and let the dialog handle it
                                continue
                            }
                        } else {
                            DuplicateAction.CREATE_COPY
                        }

                        if (action != DuplicateAction.SKIP) {
                            importPdf(context, uri, novelName, action)
                            importedCount++
                        } else {
                            skippedCount++
                        }
                    }

                    importProgress = 1f
                    isImporting = false
                    importingFileName = ""
                    selectedPdfUris = emptyList()
                    selectedPdfNames = emptyList()

                    // Show completion toast
                    withContext(Dispatchers.Main) {
                        val message = when {
                            skippedCount > 0 -> "$importedCount archivos importados, $skippedCount omitidos"
                            importedCount == 1 -> "Archivo importado correctamente"
                            else -> "$importedCount archivos importados"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }

                    onImportComplete()
                }
            }
        )
    }

    // Import progress dialog
    if (isImporting) {
        AlertDialog(
            onDismissRequest = { /* Cannot dismiss while importing */ },
            icon = {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("Importando archivos...") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = importingFileName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    LinearProgressIndicator(
                        progress = { importProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "${(importProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {}
        )
    }

    // Duplicate handling dialog (for batch imports)
    if (showDuplicateDialog && duplicateInfo != null) {
        BatchDuplicateFileDialog(
            fileName = duplicateInfo!!.fileName,
            novelName = duplicateInfo!!.novelName,
            remainingFiles = pendingUris.size - currentDuplicateIndex,
            onDismiss = {
                showDuplicateDialog = false
                duplicateInfo = null
                // Skip this file and continue with the rest
                scope.launch {
                    continueImportAfterDuplicate(
                        context = context,
                        novelName = pendingNovelName,
                        uris = pendingUris,
                        startIndex = currentDuplicateIndex + 1,
                        action = DuplicateAction.SKIP,
                        applyToAll = false,
                        onProgress = { progress, fileName ->
                            importProgress = progress
                            importingFileName = fileName
                        },
                        onComplete = { imported, skipped ->
                            isImporting = false
                            selectedPdfUris = emptyList()
                            Toast.makeText(
                                context,
                                "$imported importados, $skipped omitidos",
                                Toast.LENGTH_SHORT
                            ).show()
                            onImportComplete()
                        }
                    )
                }
            },
            onAction = { action, applyAll ->
                showDuplicateDialog = false
                duplicateInfo = null

                if (applyAll) {
                    globalDuplicateAction = action
                }

                scope.launch {
                    // First, handle the current file
                    if (action != DuplicateAction.SKIP) {
                        val currentUri = pendingUris[currentDuplicateIndex]
                        importPdf(context, currentUri, pendingNovelName, action)
                    }

                    // Then continue with the rest
                    continueImportAfterDuplicate(
                        context = context,
                        novelName = pendingNovelName,
                        uris = pendingUris,
                        startIndex = currentDuplicateIndex + 1,
                        action = if (applyAll) action else null,
                        applyToAll = applyAll,
                        onProgress = { progress, fileName ->
                            importProgress = progress
                            importingFileName = fileName
                        },
                        onComplete = { imported, skipped ->
                            isImporting = false
                            selectedPdfUris = emptyList()
                            val msg = if (skipped > 0) {
                                "$imported importados, $skipped omitidos"
                            } else {
                                "$imported archivos importados"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            onImportComplete()
                        }
                    )
                }
            }
        )
    }
}

@Composable
private fun ImportMultiplePdfDialog(
    fileCount: Int,
    fileNames: List<String>,
    existingNovels: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selectedOption by remember { mutableStateOf<ImportOption>(ImportOption.NewNovel) }
    // For new novel, suggest name from first file or common prefix
    val suggestedName = remember(fileNames) {
        if (fileNames.size == 1) {
            fileNames.first().removeSuffix(".pdf").removeSuffix(".PDF")
        } else {
            // Try to find common prefix
            val commonPrefix = findCommonPrefix(fileNames.map {
                it.removeSuffix(".pdf").removeSuffix(".PDF")
            })
            if (commonPrefix.length > 3) commonPrefix.trimEnd('_', '-', ' ') else "Nueva Novela"
        }
    }
    var newNovelName by remember { mutableStateOf(suggestedName) }
    var selectedExistingNovel by remember { mutableStateOf(existingNovels.firstOrNull() ?: "") }
    var showFileList by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (fileCount > 1) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                if (fileCount == 1) "Importar PDF"
                else "Importar $fileCount PDFs"
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // File summary
                if (fileCount == 1) {
                    Text(
                        text = "Archivo: ${fileNames.first()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "$fileCount archivos seleccionados",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showFileList = !showFileList }) {
                            Text(if (showFileList) "Ocultar" else "Ver lista")
                        }
                    }

                    // Expandable file list
                    if (showFileList) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .padding(start = 8.dp)
                        ) {
                            items(fileNames) { name ->
                                Text(
                                    text = "• $name",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

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
                Text(if (fileCount == 1) "Importar" else "Importar todo")
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
private fun BatchDuplicateFileDialog(
    fileName: String,
    novelName: String,
    remainingFiles: Int,
    onDismiss: () -> Unit,
    onAction: (DuplicateAction, Boolean) -> Unit,
) {
    var applyToAll by remember { mutableStateOf(false) }

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
                    text = "El archivo \"$fileName\" ya existe en \"$novelName\".",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "¿Qué deseas hacer?",
                    style = MaterialTheme.typography.bodyMedium
                )

                // Apply to all checkbox (only show if there are more files)
                if (remainingFiles > 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = applyToAll,
                            onCheckedChange = { applyToAll = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Aplicar a todos los duplicados restantes",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(DuplicateAction.REPLACE, applyToAll) }) {
                Text("Reemplazar", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onAction(DuplicateAction.SKIP, applyToAll) }) {
                    Text("Saltar")
                }
                TextButton(onClick = { onAction(DuplicateAction.CREATE_COPY, applyToAll) }) {
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

private suspend fun continueImportAfterDuplicate(
    context: Context,
    novelName: String,
    uris: List<Uri>,
    startIndex: Int,
    action: DuplicateAction?,
    applyToAll: Boolean,
    onProgress: (Float, String) -> Unit,
    onComplete: (imported: Int, skipped: Int) -> Unit,
) {
    withContext(Dispatchers.IO) {
        var imported = 0
        var skipped = 0
        val total = uris.size

        for (i in startIndex until uris.size) {
            val uri = uris[i]
            val fileName = getFileName(context, uri)

            withContext(Dispatchers.Main) {
                onProgress(i.toFloat() / total, fileName)
            }

            val duplicate = checkForDuplicate(novelName, fileName)

            val fileAction = if (duplicate != null && applyToAll && action != null) {
                action
            } else if (duplicate != null) {
                // If not apply to all but there's a duplicate, create copy by default
                DuplicateAction.CREATE_COPY
            } else {
                DuplicateAction.CREATE_COPY
            }

            if (fileAction != DuplicateAction.SKIP) {
                importPdf(context, uri, novelName, fileAction)
                imported++
            } else {
                skipped++
            }
        }

        withContext(Dispatchers.Main) {
            onProgress(1f, "")
            onComplete(imported, skipped)
        }
    }
}

/**
 * Find common prefix among a list of strings
 */
private fun findCommonPrefix(strings: List<String>): String {
    if (strings.isEmpty()) return ""
    if (strings.size == 1) return strings.first()

    val first = strings.first()
    var prefixLength = 0

    for (i in first.indices) {
        val char = first[i]
        if (strings.all { it.length > i && it[i] == char }) {
            prefixLength = i + 1
        } else {
            break
        }
    }

    return first.substring(0, prefixLength)
}
