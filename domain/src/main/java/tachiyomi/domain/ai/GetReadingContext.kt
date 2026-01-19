package tachiyomi.domain.ai

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.manga.repository.ReaderNotesRepository
import tachiyomi.domain.novelcontext.repository.NovelContextRepository
import tachiyomi.domain.track.repository.TrackRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class GetReadingContext(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val historyRepository: HistoryRepository,
    private val categoryRepository: CategoryRepository,
    private val searchLibrary: tachiyomi.domain.ai.interactor.SearchLibrary,
    private val trackRepository: TrackRepository,
    private val readerNotesRepository: ReaderNotesRepository,
    private val novelContextRepository: NovelContextRepository,
) {

    // Cache for global context (invalidates after 5 minutes)
    private var cachedGlobalContext: String? = null
    private var cacheTimestamp: Long = 0
    private val cacheMutex = Mutex()
    private val cacheValidityMs = TimeUnit.MINUTES.toMillis(5)

    /**
     * Enhanced Global Context using RAG (Retrieval Augmented Generation).
     * ALWAYS searches the library for mangas relevant to the user's query.
     */
    suspend fun getContextWithRag(query: String): String {
        // 1. Get Standard Global Context (Profile, History, Tastes, Stats)
        val globalContext = getGlobalContext()

        // 2. Perform Vector Search - now ALWAYS searches if query is meaningful
        if (query.length < 2) return globalContext

        val relevantMangas = try {
            searchLibrary.await(query, limit = 8)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }

        // 3. Search for notes related to the query (by manga title)
        val relatedNotes = try {
            readerNotesRepository.searchNotesByMangaTitle(query, limit = 10)
        } catch (e: Exception) {
            emptyList()
        }

        if (relevantMangas.isEmpty() && relatedNotes.isEmpty()) return globalContext

        // 4. Enrich Context with Search Results
        return buildString {
            append(globalContext)
            appendLine()

            if (relevantMangas.isNotEmpty()) {
                appendLine("--- RAG: RELEVANT LIBRARY ITEMS FOR \"$query\" ---")
                appendLine("These manga from the user's library are semantically related to their query:")

                relevantMangas.forEachIndexed { index, manga ->
                    appendLine("${index + 1}. ${manga.title}")
                    appendLine("   Author: ${manga.author ?: "Unknown"}")
                    appendLine("   Genres: ${manga.genre?.joinToString(", ") ?: "None"}")
                    val descriptionSnippet = manga.description?.take(200)?.replace("\n", " ") ?: ""
                    if (descriptionSnippet.isNotBlank()) {
                        appendLine("   Synopsis: $descriptionSnippet...")
                    }
                    appendLine()
                }
                appendLine("--- END RAG ---")
            }

            // Include user's notes related to the query
            if (relatedNotes.isNotEmpty()) {
                appendLine()
                appendLine("--- 📝 USER'S READING NOTES RELATED TO \"$query\" ---")
                relatedNotes.forEach { note ->
                    val chapterLabel = if (note.chapterNumber > 0) {
                        "Ch. ${note.chapterNumber.toInt()}"
                    } else {
                        note.chapterName
                    }
                    appendLine("- ${note.mangaTitle} ($chapterLabel, Page ${note.pageNumber}): \"${note.noteText}\"")
                }
                appendLine("--- END NOTES ---")
            }

            appendLine()
            appendLine("INSTRUCTION: Use these specific titles and notes to answer the user's request when relevant.")
        }
    }

    suspend fun getContextForManga(mangaId: Long, currentChapterId: Long? = null): String {
        val manga = mangaRepository.getMangaById(mangaId)
        val maxChapterRead = historyRepository.getMaxChapterReadForManga(mangaId)

        val currentChapter = if (currentChapterId != null) {
            chapterRepository.getChapterById(currentChapterId)
        } else {
            null
        }

        // Get tracker info for this specific manga
        val tracks = try {
            trackRepository.getTracksByMangaId(mangaId)
        } catch (e: Exception) {
            emptyList()
        }

        val trackerInfo = buildString {
            if (tracks.isNotEmpty()) {
                val scoredTracks = tracks.filter { it.score > 0 }
                if (scoredTracks.isNotEmpty()) {
                    val avgScore = scoredTracks.map { it.score }.average()
                    appendLine("User's score: ${String.format("%.1f", avgScore)}/10")
                }

                // Add tracking dates
                val dateFormatter = SimpleDateFormat("MMM yyyy", Locale.getDefault())
                tracks.forEach { track ->
                    if (track.startDate > 0) {
                        val startDate = dateFormatter.format(Date(track.startDate))
                        appendLine("📅 Started reading: $startDate")
                    }
                    if (track.finishDate > 0) {
                        val finishDate = dateFormatter.format(Date(track.finishDate))
                        appendLine("🏁 Finished reading: $finishDate")
                    }
                }
            }
        }.trim().ifEmpty { null }

        // Get reader notes for this manga
        val readerNotes = try {
            readerNotesRepository.getNotesForAiContext(mangaId)
        } catch (e: Exception) {
            emptyList()
        }

        return buildString {
            appendLine("--- CONTEXT START: CURRENT READING ---")
            appendLine("User is currently reading: ${manga.title}")
            appendLine("Synopsis: ${manga.description?.take(800) ?: "No description"}...")
            appendLine("Genres: ${manga.genre?.joinToString(", ") ?: "Unknown"}")
            appendLine("Author: ${manga.author ?: "Unknown"}")

            if (currentChapter != null) {
                appendLine("Current Activity: Reading Chapter ${currentChapter.chapterNumber} - ${currentChapter.name}")
            }

            appendLine("Reading Progress: User has read up to chapter $maxChapterRead.")
            if (trackerInfo != null) appendLine(trackerInfo)

            // Include user's reading notes
            if (readerNotes.isNotEmpty()) {
                appendLine()
                appendLine("📝 USER'S READING NOTES:")
                readerNotes.take(10).forEach { note ->
                    val chapterLabel = if (note.chapterNumber > 0) {
                        "Ch. ${note.chapterNumber.toInt()}"
                    } else {
                        note.chapterName
                    }
                    appendLine("- $chapterLabel, Page ${note.pageNumber}: \"${note.noteText}\"")
                }
                if (readerNotes.size > 10) {
                    appendLine("... and ${readerNotes.size - 10} more notes")
                }
            }

            // Include bookmarked chapters (user marked these as important)
            val bookmarkedChapters = try {
                chapterRepository.getBookmarkedChaptersByMangaId(mangaId)
            } catch (e: Exception) {
                emptyList()
            }

            if (bookmarkedChapters.isNotEmpty()) {
                appendLine()
                appendLine("📌 BOOKMARKED CHAPTERS (User marked these as important):")
                bookmarkedChapters
                    .filter { it.chapterNumber <= maxChapterRead } // Anti-spoiler: only show read bookmarks
                    .sortedBy { it.chapterNumber }
                    .take(15)
                    .forEach { chapter ->
                        appendLine("- Ch. ${chapter.chapterNumber.toInt()}: ${chapter.name}")
                    }
                if (bookmarkedChapters.size > 15) {
                    appendLine("... and ${bookmarkedChapters.size - 15} more bookmarks")
                }
            }

            appendLine()
            appendLine("CRITICAL INSTRUCTION: Do NOT spoil anything beyond chapter $maxChapterRead.")
            appendLine("--- CONTEXT END ---")
        }
    }

    /**
     * Specialized context for Novels/PDFs (Reading Buddy Mode).
     * Uses a 3-tier hybrid context system:
     * - Tier 1: AI-generated summary from DB (oldest content)
     * - Tier 2: Key extracts (first paragraphs from intermediate pages)
     * - Tier 3: Full text of recent pages (freshest content)
     *
     * @param tier3Text The full text of the last ~10 pages (extracted via TextContentProvider).
     * @param tier2Text Key extracts from intermediate pages (first paragraph every 5 pages).
     * @param currentPage Current page number (0-indexed internally, displayed as 1-indexed).
     * @param totalPages Total pages in document.
     */
    suspend fun getContextForNovel(
        mangaId: Long,
        tier3Text: String,
        tier2Text: String? = null,
        currentPage: Int,
        totalPages: Int,
    ): String {
        val manga = mangaRepository.getMangaById(mangaId)
        val novelContext = novelContextRepository.getByMangaId(mangaId)

        return buildString {
            // System Persona & Constraints
            appendLine("You are Gexu, the user's reading buddy for \"${manga.title}\".")
            appendLine("📖 USER IS ON PAGE ${currentPage + 1} of $totalPages.")
            appendLine("Author: ${manga.author ?: "Unknown"}")
            appendLine()

            // Tier 1: Summary (from DB)
            if (novelContext?.summaryText != null) {
                appendLine("═══ TIER 1: STORY SUMMARY (pages 1-${novelContext.summaryLastPage}) ═══")
                appendLine(novelContext.summaryText)
                appendLine()
            } else {
                appendLine("Synopsis: ${manga.description?.take(500) ?: "(No synopsis available)"}")
                appendLine()
            }

            // Tier 2: Key Extracts (intermediate pages)
            if (!tier2Text.isNullOrBlank()) {
                appendLine("═══ TIER 2: KEY EXCERPTS (intermediate pages) ═══")
                appendLine(tier2Text)
                appendLine()
            }

            // Tier 3: Recent Text (Passed from UI layer)
            if (tier3Text.isNotBlank()) {
                appendLine("═══ TIER 3: FULL TEXT (last ~10 pages) ═══")
                appendLine(tier3Text)
                appendLine()
                appendLine(
                    "The user is currently on PAGE ${currentPage + 1}. " +
                        "When they say 'here' or 'this part', refer to the end of the text above.",
                )
                appendLine()
            }

            // Rules
            appendLine("RULES:")
            appendLine("1. When user asks 'what's happening here?' - refer to Tier 3 (recent text).")
            appendLine("2. You have ONLY read up to page ${currentPage + 1}. NO SPOILERS beyond this.")
            appendLine("3. CRITICAL: Do NOT use outside knowledge to predict future events.")
            appendLine("4. React emotionally to plot events. Be an engaged reading buddy!")
            appendLine("5. Keep responses conversational, like texting a friend about the book.")
        }
    }

    suspend fun getGlobalContext(): String {
        // Check cache first
        cacheMutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedGlobalContext != null && (now - cacheTimestamp) < cacheValidityMs) {
                return cachedGlobalContext!!
            }
        }

        // Build fresh context
        val context = buildGlobalContextInternal()

        // Update cache
        cacheMutex.withLock {
            cachedGlobalContext = context
            cacheTimestamp = System.currentTimeMillis()
        }

        return context
    }

    /**
     * Invalidate the cache when library changes (call after indexing, etc.)
     */
    suspend fun invalidateCache() {
        cacheMutex.withLock {
            cachedGlobalContext = null
            cacheTimestamp = 0
        }
    }

    private suspend fun buildGlobalContextInternal(): String {
        // 1. Get Library Data
        val libraryItems = mangaRepository.getLibraryManga()
        val allCategories = categoryRepository.getAll()

        // 2. Calculate Statistics
        val totalManga = libraryItems.size
        val totalChapters = libraryItems.sumOf { it.totalChapters }
        val readChapters = libraryItems.sumOf { it.readCount }
        val readPercentage = if (totalChapters > 0) (readChapters * 100) / totalChapters else 0
        val startedCount = libraryItems.count { it.hasStarted }
        val completedCount = libraryItems.count {
            it.manga.status == 2L && it.unreadCount == 0L
        }

        // Read duration from history
        val totalReadDurationMs = try {
            historyRepository.getTotalReadDuration()
        } catch (e: Exception) {
            0L
        }
        val readDurationFormatted = formatDuration(totalReadDurationMs)

        // 3. Analyze Genres (User Tastes)
        val genreCounts = libraryItems
            .flatMap { it.manga.genre ?: emptyList() }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(5)
            .joinToString(", ") { "${it.first} (${it.second})" }

        // 4. Analyze Categories WITH manga titles
        val categorySummary = allCategories.joinToString("\n\n") { cat ->
            val mangasInCategory = libraryItems.filter { it.categories.contains(cat.id) }
            val count = mangasInCategory.size
            val titles = mangasInCategory
                .take(20) // Increased limit
                .joinToString(", ") { it.manga.title }
            if (count > 0) {
                "- ${cat.name} ($count series): $titles${if (count > 20) " ..." else ""}"
            } else {
                "- ${cat.name}: (empty)"
            }
        }

        // 5. Get Recently Read
        val recentHistory: List<HistoryWithRelations> = try {
            historyRepository.getHistory("").firstOrNull() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        // 6. Completed Series
        val completedSeries = libraryItems
            .filter { it.manga.status == 2L && it.readCount >= it.totalChapters && it.totalChapters > 0 }
            .take(10)
            .joinToString(", ") { it.manga.title }

        // 7. Currently Reading
        val ongoingSeries = libraryItems
            .filter { it.readCount > 0 && it.readCount < it.totalChapters }
            .sortedByDescending { it.lastRead }
            .take(10)
            .joinToString(", ") { "${it.manga.title} (${it.readCount}/${it.totalChapters})" }

        // 8. Tracker Statistics
        val trackerStats = try {
            val allTracks = libraryItems.flatMap { item ->
                try {
                    trackRepository.getTracksByMangaId(item.manga.id)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            val trackedCount = allTracks.distinctBy { it.mangaId }.size
            val scoredTracks = allTracks.filter { it.score > 0 }
            val meanScore = if (scoredTracks.isNotEmpty()) scoredTracks.map { it.score }.average() else null

            buildString {
                append("Tracked on MAL/AniList: $trackedCount series")
                if (meanScore != null) {
                    append(" | Mean score: ${String.format("%.1f", meanScore)}/10")
                }
            }
        } catch (e: Exception) {
            null
        }

        return buildString {
            appendLine("--- USER PROFILE CONTEXT ---")
            appendLine("Complete analysis of the user's manga library and reading habits:")
            appendLine()

            // Statistics Section
            appendLine("📊 STATISTICS:")
            appendLine("- Library size: $totalManga manga/manhwa/novels")
            appendLine("- Total read time: $readDurationFormatted")
            appendLine("- Chapters read: $readChapters / $totalChapters ($readPercentage%)")
            appendLine("- Started series: $startedCount")
            appendLine("- Completed series: $completedCount")
            if (trackerStats != null) appendLine("- $trackerStats")

            // NEW: Reading Habits & Streak
            val streak = try {
                historyRepository.getReadingStreak()
            } catch (e: Exception) {
                0
            }
            if (streak > 0) {
                appendLine("- 🔥 Current reading streak: $streak consecutive days")
            }
            appendLine()

            // NEW: Pending Updates
            val pendingUpdates = libraryItems
                .filter { it.unreadCount > 0 }
                .sortedByDescending { it.unreadCount }
                .take(8)
                .joinToString(", ") { "${it.manga.title} (${it.unreadCount} new)" }

            if (pendingUpdates.isNotBlank()) {
                appendLine("📥 PENDING UPDATES (Unread Chapters):")
                appendLine(pendingUpdates)
                appendLine()
            }

            // NEW: Time Spent per Manga
            val timePerManga = try {
                historyRepository.getReadDurationByManga(5).mapNotNull { (mangaId, duration) ->
                    val title = libraryItems.find { it.manga.id == mangaId }?.manga?.title
                    if (title != null) "$title (${formatDuration(duration)})" else null
                }.joinToString(", ")
            } catch (e: Exception) {
                ""
            }

            if (timePerManga.isNotBlank()) {
                appendLine("⏱️ MOST READ SERIES (Time Spent):")
                appendLine(timePerManga)
                appendLine()
            }

            // User Notes (manga general notes)
            val userNotes = libraryItems
                .filter { !it.manga.notes.isNullOrBlank() }
                .take(5)
                .joinToString("\n") {
                    "- ${it.manga.title}: \"${it.manga.notes?.replace("\n", " ")?.take(100)}\""
                }

            if (userNotes.isNotBlank()) {
                appendLine("📝 MANGA NOTES:")
                appendLine(userNotes)
                appendLine()
            }

            // Reader Notes (notes taken while reading)
            val readerNotes = try {
                readerNotesRepository.getAllRecentNotes(limit = 15)
            } catch (e: Exception) {
                emptyList()
            }

            if (readerNotes.isNotEmpty()) {
                appendLine("📖 RECENT READING NOTES:")
                readerNotes.forEach { note ->
                    val chapterLabel = if (note.chapterNumber > 0) {
                        "Ch. ${note.chapterNumber.toInt()}"
                    } else {
                        note.chapterName
                    }
                    appendLine("- ${note.mangaTitle} ($chapterLabel, Page ${note.pageNumber}): \"${note.noteText}\"")
                }
                appendLine()
            }

            appendLine("🎯 TOP GENRES (User Preferences):")
            appendLine(genreCounts.ifBlank { "Unknown" })
            appendLine()

            appendLine("📁 LIBRARY CATEGORIES (with titles):")
            appendLine(categorySummary)
            appendLine()

            if (completedSeries.isNotBlank()) {
                appendLine("✅ FULLY READ SERIES:")
                appendLine(completedSeries)
                appendLine()
            }
            if (ongoingSeries.isNotBlank()) {
                appendLine("📖 CURRENTLY READING:")
                appendLine(ongoingSeries)
                appendLine()
            }

            appendLine("📅 RECENT HISTORY:")
            if (recentHistory.isEmpty()) {
                appendLine("No recent history.")
            } else {
                recentHistory.take(10).forEach { item: HistoryWithRelations ->
                    val dateStr = item.readAt?.let { SimpleDateFormat.getDateInstance().format(it) } ?: "Unknown"
                    appendLine("- ${item.title} (Ch. ${item.chapterNumber}) - $dateStr")
                }
            }
            appendLine()

            appendLine("INSTRUCTIONS:")
            appendLine("1. You have FULL ACCESS to the user's library. Use specific manga titles when answering.")
            appendLine(
                "2. For recommendations, suggest titles similar to their genres but NOT already in their library.",
            )
            appendLine("3. You can reference their reading stats and habits naturally.")
            appendLine("--- END PROFILE ---")
        }
    }

    /**
     * Lightweight profile for reader context.
     */
    suspend fun getBriefProfile(): String {
        val libraryItems = mangaRepository.getLibraryManga()

        val topGenres = libraryItems
            .flatMap { it.manga.genre ?: emptyList() }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(3)
            .joinToString(", ") { it.first }

        val recentSeries = libraryItems
            .sortedByDescending { it.lastRead }
            .take(3)
            .joinToString(", ") { it.manga.title }

        return "(Brief Profile: User likes $topGenres. Recently read: $recentSeries)"
    }

    private fun formatDuration(millis: Long): String {
        if (millis <= 0) return "0 minutes"

        val days = TimeUnit.MILLISECONDS.toDays(millis)
        val hours = TimeUnit.MILLISECONDS.toHours(millis) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60

        return buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0) append("${hours}h ")
            if (minutes > 0 || (days == 0L && hours == 0L)) append("${minutes}m")
        }.trim()
    }
}
