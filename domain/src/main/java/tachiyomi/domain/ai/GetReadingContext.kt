package tachiyomi.domain.ai

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.track.repository.TrackRepository
import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit

class GetReadingContext(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val historyRepository: HistoryRepository,
    private val categoryRepository: CategoryRepository,
    private val searchLibrary: tachiyomi.domain.ai.interactor.SearchLibrary,
    private val trackRepository: TrackRepository,
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

        if (relevantMangas.isEmpty()) return globalContext

        // 3. Enrich Context with Search Results
        return buildString {
            append(globalContext)
            appendLine()
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
            appendLine("INSTRUCTION: Use these specific titles to answer the user's request when relevant.")
            appendLine("--- END RAG ---")
        }
    }

    suspend fun getContextForManga(mangaId: Long, currentChapterId: Long? = null): String {
        val manga = mangaRepository.getMangaById(mangaId)
        val maxChapterRead = historyRepository.getMaxChapterReadForManga(mangaId)

        val currentChapter = if (currentChapterId != null) {
            chapterRepository.getChapterById(currentChapterId)
        } else null

        // Get tracker info for this specific manga
        val tracks = try { trackRepository.getTracksByMangaId(mangaId) } catch (e: Exception) { emptyList() }
        val trackerInfo = if (tracks.isNotEmpty()) {
            val scoredTracks = tracks.filter { it.score > 0 }
            if (scoredTracks.isNotEmpty()) {
                val avgScore = scoredTracks.map { it.score }.average()
                "User's score: ${String.format("%.1f", avgScore)}/10"
            } else null
        } else null

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
            appendLine("CRITICAL INSTRUCTION: Do NOT spoil anything beyond chapter $maxChapterRead.")
            appendLine("--- CONTEXT END ---")
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
        } catch (e: Exception) { 0L }
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
        } catch (e: Exception) { emptyList() }

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
                try { trackRepository.getTracksByMangaId(item.manga.id) } catch (e: Exception) { emptyList() }
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
        } catch (e: Exception) { null }

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
            appendLine()

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
            appendLine("2. For recommendations, suggest titles similar to their genres but NOT already in their library.")
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

