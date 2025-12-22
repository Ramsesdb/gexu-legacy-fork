# 🧠 Gexu AI - Roadmap de Mejoras (Actualizado 22 Dic 2024)

> Documento consolidado con el estado actual de las funcionalidades de IA y mejoras pendientes.

---

## 📊 Resumen Ejecutivo

| Categoría | Estado | Mejoras Pendientes |
|-----------|--------|--------------------|
| **Sistema de Notas** | ✅ Funcional | 4 pendientes |
| **Contexto para IA** | ✅ Avanzado | 2 pendientes |
| **UX del Chat** | ✅ Completo | 3 pendientes |
| **Embeddings/RAG** | ✅ Híbrido | 2 pendientes |
| **Funcionalidades Avanzadas** | 🔜 Futuro | 5 pendientes |

---

## ✅ FUNCIONALIDADES COMPLETADAS

### Chat de IA
- [x] **Streaming de Respuestas** - `streamMessage()` implementado para Gemini y OpenAI/compatible
- [x] **Markdown Rendering** - `MarkdownRender` integrado en `ChatBubble` de ambas pantallas
- [x] **Web Search (Gemini Grounding)** - Toggle funcional con `enableWebSearch` preference
- [x] **Visión/Análisis de Imágenes** - `onCaptureVision` captura páginas para análisis
- [x] **Multi-provider** - OpenAI, Gemini, Anthropic, OpenRouter, Custom
- [x] **Persistencia de conversaciones** - `AiConversationRepository` con historial
- [x] **Historial de conversaciones** - Drawer lateral con historial navegable

### Contexto de Lectura
- [x] **Anti-Spoiler Mode** - Limita conocimiento al capítulo máximo leído
- [x] **Bookmarks en Contexto** - `getContextForManga()` incluye capítulos marcados
- [x] **Notas en Contexto IA** - `getNotesForAiContext()` inyecta notas del usuario
- [x] **Perfil del Usuario** - Top géneros, series completadas, en lectura
- [x] **Tracker Scores** - Puntuaciones de trackers incluidas

### Sistema de Notas
- [x] **Notas del Lector** - CRUD completo en `reader_notes.sq`
- [x] **Navegación desde Notas** - `onNavigateToPage(chapterId, pageNumber)` funcional
- [x] **Vista Unificada** - `MangaNotesScreen` con tabs (General/Por Capítulo)

### RAG & Embeddings
- [x] **Embeddings Híbridos** - Cloud (Gemini 768-dim) + Local (MediaPipe 100-dim)
- [x] **VectorStore** - Búsqueda semántica en biblioteca
- [x] **Indexación Automática** - `manga_embeddings.sq` con persistencia

---

## 🔴 CRÍTICO: Backup de Notas NO Implementado

**Estado**: ⚠️ LAS NOTAS DEL LECTOR NO SE INCLUYEN EN BACKUP/RESTORE

**Problema**: La tabla `reader_notes` no está siendo respaldada. Los usuarios pueden perder todas sus notas al restaurar un backup.

**Archivos a modificar**:
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/` - Crear `BackupReaderNote.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/create/BackupCreator.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/BackupRestorer.kt`

**Solución**:
```kotlin
// BackupReaderNote.kt
@Serializable
data class BackupReaderNote(
    @ProtoNumber(1) var mangaId: Long,
    @ProtoNumber(2) var chapterId: Long,
    @ProtoNumber(3) var pageNumber: Int = 0,
    @ProtoNumber(4) var noteText: String,
    @ProtoNumber(5) var createdAt: Long,
)
```

**Esfuerzo**: 2-3 horas  
**Prioridad**: **P0** - CRÍTICO

---

## 🏆 Matriz de Priorización (Pendientes)

| # | Mejora | Impacto | Esfuerzo | Prioridad |
|---|--------|---------|----------|-----------|
| 1 | Backup/Restore Notas | 🔥🔥🔥 | Medio | **P0** |
| 2 | Búsqueda en Notas | 🔥🔥 | Bajo | **P0** |
| 3 | Tags/Etiquetas Notas | 🔥🔥 | Medio | **P1** |
| 4 | Fechas de Tracking | 🔥🔥 | Bajo | **P1** |
| 5 | Quick Actions Dinámicas | 🔥🔥 | Medio | **P1** |
| 6 | Caché de Respuestas | 🔥 | Medio | **P2** |
| 7 | Feedback Loop | 🔥 | Bajo | **P2** |
| 8 | Resúmenes de Arcos | 🔥🔥 | Medio | **P2** |
| 9 | Exportar Notas | 🔥 | Bajo | **P2** |
| 10 | Memoria Semántica | 🔥🔥 | Alto | **P3** |
| 11 | OCR de Páginas | 🔥🔥 | Medio | **P3** |
| 12 | Agent Tools | 🔥🔥 | Alto | **P3** |

---

# 📝 MEJORAS PENDIENTES

---

## 1. Búsqueda de Notas ⭐ P0

**Estado**: Parcialmente implementado  
**Esfuerzo**: 45 minutos  
**Impacto**: Alto

**Lo que existe**: `searchNotesByMangaTitle` busca por título del manga.

**Lo que falta**: Búsqueda por contenido de la nota (texto).

**Añadir en `reader_notes.sq`**:
```sql
searchNotesByText:
SELECT 
    RN._id,
    RN.manga_id,
    M.title AS manga_title,
    RN.chapter_id,
    C.chapter_number,
    C.name AS chapter_name,
    RN.page_number,
    RN.note_text,
    RN.created_at
FROM reader_notes RN
JOIN chapters C ON RN.chapter_id = C._id
JOIN mangas M ON RN.manga_id = M._id
WHERE RN.note_text LIKE '%' || :query || '%'
ORDER BY RN.created_at DESC
LIMIT :limit;
```

**UI**: Añadir `SearchBar` en `MangaNotesScreen.kt` en el tab de notas del lector.

---

## 2. Tags/Etiquetas en Notas ⭐ P1

**Estado**: No implementado  
**Esfuerzo**: 2-3 horas  
**Impacto**: Medio-Alto

**Migración (datos/19.sqm)**:
```sql
ALTER TABLE reader_notes ADD COLUMN tags TEXT DEFAULT NULL;
```

```kotlin
enum class NoteTag(val displayName: String, val emoji: String) {
    THEORY("Teoría", "💭"),
    IMPORTANT("Importante", "⭐"),
    QUESTION("Pregunta", "❓"),
    FAVORITE("Favorito", "❤️"),
    SPOILER("Spoiler", "🚨"),
    FUNNY("Gracioso", "😂"),
}
```

---

## 3. Fechas de Tracking en Contexto ⭐ P1

**Estado**: Parcialmente implementado (scores sí, fechas no)  
**Esfuerzo**: 20 minutos  
**Impacto**: Medio

**Añadir en `GetReadingContext.kt` dentro de `getContextForManga()`**:
```kotlin
tracks.forEach { track ->
    if (track.startedReadingDate > 0) {
        val startDate = SimpleDateFormat("MMM yyyy", Locale.getDefault())
            .format(Date(track.startedReadingDate))
        appendLine("📅 Started reading: $startDate")
    }
    if (track.finishedReadingDate > 0) {
        val finishDate = SimpleDateFormat("MMM yyyy", Locale.getDefault())
            .format(Date(track.finishedReadingDate))
        appendLine("🏁 Finished reading: $finishDate")
    }
}
```

---

## 4. Quick Actions Contextuales ⭐ P1

**Estado**: Existen hints estáticos en `QuickHints()`  
**Esfuerzo**: 2 horas  
**Impacto**: Alto

**Lo que falta**: Los hints deberían ser dinámicos basados en:
- Manga actual (título, género)
- Capítulo siendo leído
- Si tiene notas o bookmarks
- Cantidad de capítulos sin leer

**Refactorizar `QuickHints` a `ContextualQuickActions`**:
```kotlin
@Composable
fun ContextualQuickActions(
    mangaTitle: String?,
    currentChapter: Double?,
    hasNotes: Boolean,
    unreadCount: Long,
    onAction: (String) -> Unit,
) {
    val actions = remember(mangaTitle, currentChapter, hasNotes) {
        buildList {
            if (mangaTitle != null) {
                add("¿De qué trata $mangaTitle?")
                if (currentChapter != null && currentChapter > 1) {
                    add("Resúmeme hasta el cap. ${currentChapter.toInt()}")
                }
            }
            if (hasNotes) add("Analiza mis notas")
            add("¿Qué debería leer hoy?")
        }
    }
    // ... render chips
}
```

---

## 5. Caché de Respuestas ⭐ P2

**Estado**: No implementado  
**Esfuerzo**: 2-3 horas  
**Impacto**: Medio (ahorro de tokens/costos)

```kotlin
class AiResponseCache {
    private data class CacheEntry(
        val response: String,
        val timestamp: Long,
        val contextHash: String,
    )
    
    private val cache = LruCache<String, CacheEntry>(50)
    private val ttlMs = TimeUnit.HOURS.toMillis(24)
    
    suspend fun getCached(query: String, contextHash: String): String? {
        val key = "${query.lowercase().trim()}|$contextHash"
        val entry = cache.get(key) ?: return null
        
        if (System.currentTimeMillis() - entry.timestamp > ttlMs) {
            cache.remove(key)
            return null
        }
        return entry.response
    }
    
    fun cache(query: String, contextHash: String, response: String) {
        val key = "${query.lowercase().trim()}|$contextHash"
        cache.put(key, CacheEntry(response, System.currentTimeMillis(), contextHash))
    }
}
```

---

## 6. Feedback Loop ⭐ P2

**Estado**: No implementado  
**Esfuerzo**: 1 hora  
**Impacto**: Medio

Añadir thumbs up/down a los mensajes del asistente en `ChatBubble`:

```kotlin
if (message.role == ChatMessage.Role.ASSISTANT) {
    Row(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = { onFeedback(positive = true) }) {
            Icon(Icons.Default.ThumbUp, "Útil")
        }
        IconButton(onClick = { onFeedback(positive = false) }) {
            Icon(Icons.Default.ThumbDown, "No útil")
        }
    }
}
```

---

## 7. Exportar Notas ⭐ P2

**Estado**: No implementado  
**Esfuerzo**: 1 hora  
**Impacto**: Bajo

```kotlin
class NotesExporter {
    fun exportToMarkdown(mangaTitle: String, notes: List<ReaderNote>): String {
        return buildString {
            appendLine("# Notas de $mangaTitle")
            appendLine()
            notes.groupBy { it.chapterNumber.toInt() }.forEach { (ch, chNotes) ->
                appendLine("## Capítulo $ch")
                chNotes.forEach { note ->
                    appendLine("- **Pág ${note.pageNumber}**: ${note.noteText}")
                }
                appendLine()
            }
        }
    }
}
```

---

## 8. Resúmenes Automáticos de Arcos ⭐ P2

**Estado**: No implementado  
**Esfuerzo**: 3-4 horas  
**Impacto**: Alto (valor diferenciador)

```kotlin
class ChapterSummaryManager(
    private val aiRepository: AiRepository,
) {
    suspend fun getRecap(mangaId: Long, upToChapter: Double): String {
        val prompt = """
        El usuario ha leído hasta el capítulo ${upToChapter.toInt()}.
        Basándote en tu conocimiento, proporciona un resumen de:
        - Trama principal hasta ese punto
        - Personajes importantes
        - Eventos clave (sin spoilers)
        Máximo 250 palabras.
        """
        return aiRepository.sendMessage(listOf(ChatMessage.user(prompt)))
            .getOrNull()?.content ?: "No se pudo generar resumen."
    }
}
```

---

# 🔮 BACKLOG (Futuro)

| Mejora | Descripción | Esfuerzo |
|--------|-------------|----------|
| **Memoria Semántica** | Recordar preferencias del usuario entre sesiones | Alto |
| **OCR de Páginas** | Extraer texto, diálogos, SFX de páginas | Medio |
| **Agent Tools** | Function calling para acciones en la app | Alto |
| **Proactive Suggestions** | Notificaciones inteligentes | Alto |
| **Notas con Imágenes** | Adjuntar screenshots a notas | Medio |
| **Notas de Voz** | Capturar y transcribir audio | Alto |

---

# ✅ Próximos Pasos Recomendados

## Semana 1: Quick Wins
1. **🔴 CRÍTICO**: Implementar backup/restore de `reader_notes`
2. Añadir búsqueda por contenido en notas (SQL query + UI)

## Semana 2: UX Improvements
3. Añadir fechas de tracking al contexto
4. Refactorizar hints a Quick Actions dinámicas

## Semana 3-4: Value-Add
5. Tags para notas (migración DB + UI)
6. Exportar notas a Markdown
7. Feedback loop en mensajes

---

> 💡 **Nota**: El sistema de IA está muy avanzado. El foco principal ahora debería ser:
> 1. **Seguridad de datos** (backup de notas)
> 2. **Organización** (búsqueda y tags)
> 3. **Diferenciación** (resúmenes de arcos, memoria)
