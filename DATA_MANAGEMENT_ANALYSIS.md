# Gexu Data Management Analysis

This document provides a comprehensive technical analysis of how data is managed, stored, and accessed within the Gexu project.

## 1. High-Level Architecture

Gexu follows a Clean Architecture approach with distinct layers for data management:

1.  **Domain Layer (Use Cases)**: Defines *what* needs to be done (e.g., `GetChapter`).
2.  **Data Layer (Repositories)**: Defines *how* it is done (e.g., `ChapterRepositoryImpl`).
3.  **Data Sources**:
    *   **Database**: SQLite (managed by SQLDelight).
    *   **Preferences**: Key-Value storage (SharedPreferences/DataStore).
    *   **File System**: Covers, downloads, and backups.
    *   **Network**: API calls (Source extensions, AI services).

---

## 2. Database Layer (SQLDelight)

The application uses **SQLDelight**, which generates type-safe Kotlin code from SQL files. This ensures that database queries are verified at compile time.

### 2.1 Core Tables
The schema is defined in `data/src/main/sqldelight/tachiyomi/data/`.

| Table | Description | Key Columns |
| :--- | :--- | :--- |
| **`mangas`** | The central entity. Stores details about the comic/novel. | `_id`, `source`, `url`, `title`, `thumbnail_url`, `favorite` |
| **`chapters`** | Individual chapters linked to a manga. | `_id`, `manga_id`, `url`, `name`, `read`, `last_page_read` |
| **`history`** | Reading history (Recents). | `chapter_id`, `last_read` (timestamp), `time_read` (duration) |
| **`categories`** | User-defined groups (e.g., "Reading", "Completed"). | `_id`, `name`, `sort_order` |
| **`manga_sync`** | Tracking data (MAL, AniList). | `manga_id`, `sync_id`, `remote_id`, `score`, `status` |
| **`pdf_toc_entries`** | **[GEXU SPECIFIC]** Caches PDF Table of Contents. | `chapter_id`, `title`, `page_number`, `level` |

### 2.2 Database Handler (`AndroidDatabaseHandler.kt`)
*   **Driver**: `AndroidSqliteDriver`.
*   **Reactive**: All queries can be exposed as Kotlin `Flow`s (via `asFlow()`), allowing the UI to update automatically when data changes.
*   **Transactions**: Supports suspending transactions, ensuring complex operations (like updating a library) are atomic.

---

## 3. Preference Management (`PreferenceStore`)

Settings and lightweight state are managed via a key-value store abstraction.

*   **Implementation**: `AndroidPreferenceStore` serves as a wrapper around Android's `SharedPreferences`.
*   **Reactivity**: Uses `callbackFlow` to emit new values immediately when a preference changes.
*   **Usage**: Used for app themes, reader settings, download preferences, and AI API keys.

---

## 4. File System & Image Storage

Large binary data is not stored in the database but on the file system.

### 4.1 Cover Images (`CoverCache.kt`)
*   **Location**: `context.getExternalFilesDir("covers")`
*   **Naming**: Files are named using the MD5 hash of the image URL.
*   **Custom Covers**: Users can set custom covers, stored in a separate `covers/custom` subdirectory.

### 4.2 Downloads (`DownloadManager.kt`)
*   **Structure**: `Downloads / {Source Name} / {Manga Title} / {Chapter Name}`
*   **Provider**: `DownloadProvider` manages physical file paths.
*   **Format**: Can be directories of images or `.cbz` archives.

---

## 5. AI & External Integration

### 5.1 AI Repository (`AiRepositoryImpl.kt`)
*   **State**: Stateless. Does not store conversation history in a local database.
*   **Providers**: Supports OpenAI, Gemini, and Anthropic.
*   **Authentication**: API Keys are stored securely in `AiPreferences`.
*   **Transport**: Uses `OkHttpClient` for direct REST API calls.

---

## 6. Backup & Restore Architecture

Data portability is handled via specific backup creators.

*   **Formats**:
    *   `.json`: Human-readable text format.
    *   `.tachibk`: Gzip-compressed Protobuf (Protocol Buffers) file.
*   **Orchestration**: `BackupCreator` gathers data from all repositories (`Manga`, `Category`, `Preference`, `Source`) and serializes it.
*   **Restoration**: Reverses the process, mapping external IDs back to local database IDs.

---

## 7. Data Flow Example: Reading a Chapter

1.  **User Action**: User opens a chapter and scrolls to page 5.
2.  **UI Event**: Reader triggers a save progress event.
3.  **Repository**: `ChapterRepository.updateChapter()` is called.
4.  **Database**:
    *   `UPDATE chapters SET last_page_read = 5 WHERE _id = 123;`
    *   `INSERT OR REPLACE INTO history ...` (Updates "Last Read" timestamp).
5.  **Reactive Update**: Any other screen observing `getHistory()` (like the "Recents" tab) automatically receives the new data via Flow emissions and updates the UI.


🔬 Análisis Profundo del Proyecto Gexu
Este documento presenta un análisis exhaustivo de la arquitectura de Gexu, una aplicación de lectura de manga/novelas basada en Tachiyomi/Mihon, con extensiones significativas para AI y lectura de PDFs.

📐 Arquitectura General
Gexu sigue una Clean Architecture estricta con separación clara de responsabilidades:

Capa de Datos
Capa de Dominio
Capa de Presentación
Jetpack Compose UI
ViewModels
Use Cases / Interactors
Repository Interfaces
Domain Models
Repository Implementations
SQLDelight DB
PreferenceStore
Network/OkHttp
File System
Módulos Principales
Módulo	Propósito
app	UI (Compose), ViewModels, Activities, DI Modules
domain	Use Cases, Interfaces de Repository, Models de negocio
data	Implementaciones de Repository, SQLDelight, Mappers
core	Utilidades comunes compartidas entre módulos
source-api	API para extensiones de fuentes de manga
source-local	Lector de archivos locales (CBZ, PDF, etc.)
🗄️ Gestión de Datos
1. Base de Datos (SQLDelight)
La aplicación usa SQLDelight, que genera código Kotlin type-safe a partir de archivos 
.sq
. Los querys se verifican en tiempo de compilación.

Esquema de Tablas Principales
Tabla	Descripción	Columnas Clave
mangas	Entidad central. Información del manga/novela	_id, source, url, title, favorite, thumbnail_url
chapters	Capítulos individuales	_id, manga_id, read, last_page_read, chapter_number
history	Historial de lectura (Recientes)	chapter_id, last_read, time_read
categories	Grupos definidos por usuario	_id, name, sort_order
manga_sync	Datos de tracking (MAL, AniList)	manga_id, sync_id, score, status
ai_conversations	[GEXU] Conversaciones con IA	_id, manga_id, title, created_at
ai_messages	[GEXU] Mensajes dentro de conversaciones	conversation_id, role, content
manga_embeddings	[GEXU] Vectores para búsqueda semántica	manga_id, embedding (BLOB)
pdf_toc_entries	[GEXU] Cache de tablas de contenido PDF	chapter_id, title, page_number
Triggers de Versionado
El esquema incluye triggers automáticos para sincronización:

-- Actualiza versión cuando cambian datos importantes
CREATE TRIGGER update_manga_version AFTER UPDATE ON mangas
BEGIN
    UPDATE mangas SET version = version + 1
    WHERE _id = new._id AND new.is_syncing = 0;
END;
Reactividad con Flows
Todas las queries pueden exponerse como Flow para actualizaciones automáticas de UI:

// En AndroidDatabaseHandler.kt
override fun <T : Any> subscribeToList(block: Database.() -> Query<T>): Flow<List<T>> {
    return block(db).asFlow().mapToList(queryDispatcher)
}
2. Sistema de Preferencias
Configuraciones livianas se manejan via PreferenceStore:

Implementación	Wrapper sobre SharedPreferences
Reactividad	callbackFlow emite cambios inmediatamente
Uso	Temas, configuración del lector, API keys de AI, etc.
3. Sistema de Archivos
Los datos binarios se almacenan en el filesystem:

Covers (CoverCache.kt)
Ubicación: context.getExternalFilesDir("covers")
Naming: Hash MD5 de la URL
Custom: Subdirectorio covers/custom
Downloads (DownloadManager.kt)
Estructura: Downloads / {Source} / {Manga} / {Chapter}
Formatos: Directorios de imágenes o archivos .cbz
🤖 Sistema de IA (Gexu AI)
Una de las extensiones más significativas del proyecto. Arquitectura modular con soporte multi-proveedor.

Componentes Principales
External
Data Layer
Domain Layer
AiRepository
AiConversationRepository
VectorStore
EmbeddingService
GetReadingContext
AiRepositoryImpl
AiConversationRepositoryImpl
VectorStoreImpl
EmbeddingServiceImpl
OpenAI API
Gemini API
Anthropic API
Proveedores Soportados
Proveedor	Endpoint	Notas
OpenAI	api.openai.com/v1/chat/completions	Compatible con GPT-4, GPT-3.5
Gemini	generativelanguage.googleapis.com	Gemini 1.5 Flash/Pro
Anthropic	api.anthropic.com/v1/messages	Claude 3
OpenRouter	openrouter.ai/api/v1/chat/completions	Múltiples modelos
Custom	URL configurable	Cualquier API compatible con OpenAI
RAG (Retrieval Augmented Generation)
El sistema implementa búsqueda semántica en la biblioteca:

// VectorStoreImpl.kt - Búsqueda con Cosine Similarity
override suspend fun search(queryVector: FloatArray, limit: Int): List<Long> {
    ensureCacheLoaded()
    return memoryCache.entries
        .map { it.key to cosineSimilarity(queryVector, it.value) }
        .sortedByDescending { it.second }
        .take(limit)
        .map { it.key }
}
Flujo RAG
Usuario hace pregunta → se genera embedding del query
Búsqueda vectorial en manga_embeddings
Resultados inyectados como contexto al prompt
LLM responde con información relevante de la biblioteca
Anti-Spoilers
Lógica inteligente para evitar spoilers basada en historial:

// GetReadingContext.kt
val maxChapterRead = historyRepository.getMaxChapterReadForManga(mangaId)
// ...
appendLine("CRITICAL INSTRUCTION: Do NOT spoil anything beyond chapter $maxChapterRead.")
Query optimizada para obtener progreso máximo en una sola consulta:

-- history.sq
getMaxChapterReadForManga:
SELECT coalesce(MAX(C.chapter_number), 0.0) AS maxChapterNumber
FROM history H
JOIN chapters C ON H.chapter_id = C._id
WHERE C.manga_id = :mangaId AND H.last_read > 0;
🔄 Flujo de Datos: Ejemplo de Lectura
Database
HistoryRepo
ChapterRepo
ViewModel
ReaderUI
User
Database
HistoryRepo
ChapterRepo
ViewModel
ReaderUI
User
Abre capítulo, scrollea a página 5
saveProgress(page=5)
updateChapter(lastPageRead=5)
UPDATE chapters SET last_page_read = 5
upsertHistory(chapterId, now)
INSERT/UPDATE history
Flow emission
UI de "Recientes" se actualiza automáticamente
💾 Backup y Restore
Formatos Soportados
Formato	Descripción
.json
Texto legible, útil para debug
.tachibk
Protobuf comprimido con Gzip (recomendado)
Módulos de Backup
BackupCreator.kt
 - Orquestador principal
MangaBackupCreator.kt
 - Serializa mangas y capítulos
CategoriesBackupCreator.kt
 - Serializa categorías
PreferenceBackupCreator.kt
 - Serializa preferencias
🧩 Inyección de Dependencias (Injekt)
El proyecto usa Injekt para DI. Los módulos se registran en archivos como 
AiModule.kt
:

class AiModule : InjektModule {
    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory<AiRepository> {
            AiRepositoryImpl(
                client = get<NetworkHelper>().client,
                aiPreferences = get<AiPreferences>(),
                json = Json { ignoreUnknownKeys = true }
            )
        }
        // ... más singletons
    }
}
📊 Resumen de Rutas de Datos
Tipo de Dato	Ubicación	Tecnología
Metadata de manga/chapters	SQLite	SQLDelight
Historial de lectura	SQLite	SQLDelight
Conversaciones AI	SQLite	SQLDelight
Embeddings vectoriales	SQLite + RAM cache	SQLDelight + ConcurrentHashMap
Configuraciones de usuario	SharedPreferences	PreferenceStore
Imágenes de portada	Filesystem + Coil cache	CoverCache.kt
Capítulos descargados	Filesystem	DownloadProvider.kt
Backups	Filesystem	Protobuf/JSON
API keys	EncryptedSharedPreferences	AiPreferences.kt
✅ Fortalezas Arquitectónicas
Separación de capas clara - Domain no depende de Data
Reactividad completa - Flows en toda la capa de datos
Type-safety - SQLDelight valida queries en compilación
Extensibilidad - Fácil agregar nuevos proveedores de AI
Eficiencia - Cache en memoria para embeddings, queries optimizadas
⚠️ Áreas de Mejora Potencial
Persistencia de chat - Implementada pero las conversaciones podrían sincronizarse
Memoria a largo plazo de AI - Los embeddings no incluyen resúmenes de contenido leído
Multimodalidad - El AI no puede "ver" imágenes aún (preparado pero no implementado)