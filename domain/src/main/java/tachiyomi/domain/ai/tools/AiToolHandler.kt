package tachiyomi.domain.ai.tools

import kotlinx.coroutines.flow.firstOrNull
import tachiyomi.domain.ai.interactor.SearchLibrary
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.manga.repository.ReaderNotesRepository
import tachiyomi.domain.track.repository.TrackRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Executes AI tool calls and returns results.
 * This is provider-agnostic - results are returned as strings that can be
 * sent back to any AI provider (Gemini, OpenAI, etc.).
 */
class AiToolHandler(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val historyRepository: HistoryRepository,
    private val trackRepository: TrackRepository,
    private val readerNotesRepository: ReaderNotesRepository,
    private val categoryRepository: CategoryRepository,
    private val searchLibrary: SearchLibrary,
) {

    /**
     * Execute a tool by name with given arguments.
     * Returns a string result that will be sent back to the AI.
     */
    suspend fun execute(toolName: String, args: Map<String, String>): String {
        return try {
            when (toolName) {
                "get_library_stats" -> getLibraryStats()
                "search_library" -> searchLibrary(args["query"] ?: "")
                "get_full_manga_context" -> getFullMangaContext(args["title"] ?: "")
                "get_user_notes" -> getUserNotes(args["manga_title"])
                "get_reading_history" -> getReadingHistory(
                    args["limit"]?.toIntOrNull() ?: 10,
                )
                "get_categories" -> getCategories()
                "get_manga_by_category" -> getMangaByCategory(args["category_name"] ?: "")
                "get_reading_time_stats" -> getReadingTimeStats(
                    args["limit"]?.toIntOrNull() ?: 10,
                )
                // Phase 1 tools
                "get_pending_updates" -> getPendingUpdates(
                    args["limit"]?.toIntOrNull() ?: 10,
                )
                "get_tracker_scores" -> getTrackerScores()
                "find_similar_manga" -> findSimilarManga(
                    args["query"] ?: "",
                    args["limit"]?.toIntOrNull() ?: 5,
                )
                // Phase 2 tools
                "get_bookmarked_chapters" -> getBookmarkedChapters(args["manga_title"])
                "get_reading_streak" -> getReadingStreak()
                "get_completed_series" -> getCompletedSeries(
                    args["limit"]?.toIntOrNull() ?: 10,
                )
                // Phase 3 tools
                "get_dropped_series" -> getDroppedSeries(
                    args["days_inactive"]?.toIntOrNull() ?: 30,
                    args["limit"]?.toIntOrNull() ?: 10,
                )
                "get_author_works" -> getAuthorWorks(args["author_name"] ?: "")
                // Phase 4 tools
                "get_upcoming_manga" -> getUpcomingManga(
                    args["limit"]?.toIntOrNull() ?: 10,
                )
                "get_recently_added" -> getRecentlyAdded(
                    args["limit"]?.toIntOrNull() ?: 10,
                )
                "get_genre_breakdown" -> getGenreBreakdown()
                // Phase 5 tools
                "get_reading_patterns" -> getReadingPatterns()
                "get_notes_by_tag" -> getNotesByTag(
                    args["tag"] ?: "",
                    args["limit"]?.toIntOrNull() ?: 10,
                )
                "predict_completion_time" -> predictCompletionTime(args["manga_title"] ?: "")
                "get_monthly_summary" -> getMonthlySummary(
                    args["months_ago"]?.toIntOrNull() ?: 0,
                )
                else -> "Unknown tool: $toolName"
            }
        } catch (e: Exception) {
            "Error executing $toolName: ${e.message}"
        }
    }

    private suspend fun getLibraryStats(): String {
        val library = mangaRepository.getLibraryManga()
        val totalManga = library.size
        val totalChapters = library.sumOf { it.totalChapters }
        val readChapters = library.sumOf { it.readCount }
        val completedCount = library.count {
            it.manga.status == 2L && it.unreadCount == 0L
        }
        val inProgressCount = library.count {
            it.readCount > 0 && it.readCount < it.totalChapters
        }

        // Top genres
        val topGenres = library
            .flatMap { it.manga.genre ?: emptyList() }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(5)
            .joinToString(", ") { "${it.first} (${it.second})" }

        return buildString {
            appendLine("📊 LIBRARY STATISTICS:")
            appendLine("- Total series: $totalManga")
            appendLine("- Chapters read: $readChapters / $totalChapters")
            appendLine("- Completed: $completedCount series")
            appendLine("- In progress: $inProgressCount series")
            appendLine("- Top genres: $topGenres")
        }
    }

    private suspend fun searchLibrary(query: String): String {
        if (query.isBlank()) return "Please provide a search query."

        val library = mangaRepository.getLibraryManga()
        val queryLower = query.lowercase()

        val matches = library.filter { item ->
            item.manga.title.lowercase().contains(queryLower) ||
                item.manga.author?.lowercase()?.contains(queryLower) == true ||
                item.manga.genre?.any { it.lowercase().contains(queryLower) } == true
        }.take(8)

        if (matches.isEmpty()) {
            return "No manga/manhwa/novels matching '$query' found in library."
        }

        return buildString {
            appendLine("📚 SEARCH RESULTS FOR '$query':")
            matches.forEach { item ->
                val progress = "${item.readCount}/${item.totalChapters} chapters"
                appendLine("- ${item.manga.title} by ${item.manga.author ?: "Unknown"}")
                appendLine("  Genres: ${item.manga.genre?.take(3)?.joinToString(", ") ?: "N/A"}")
                appendLine("  Progress: $progress")
            }
        }
    }

    private suspend fun getFullMangaContext(title: String): String {
        if (title.isBlank()) return "Please provide a manga title."

        val library = mangaRepository.getLibraryManga()
        val queryLower = title.lowercase()

        val match = library.find {
            it.manga.title.lowercase().contains(queryLower)
        } ?: return "Manga '$title' not found in library."

        val manga = match.manga
        val mangaId = manga.id

        // Get additional data
        val tracks = try {
            trackRepository.getTracksByMangaId(mangaId)
        } catch (e: Exception) {
            emptyList()
        }

        val notes = try {
            readerNotesRepository.getNotesForAiContext(mangaId)
        } catch (e: Exception) {
            emptyList()
        }

        val bookmarks = try {
            chapterRepository.getBookmarkedChaptersByMangaId(mangaId)
        } catch (e: Exception) {
            emptyList()
        }

        val maxChapterRead = try {
            historyRepository.getMaxChapterReadForManga(mangaId)
        } catch (e: Exception) {
            0.0
        }

        return buildString {
            appendLine("📖 FULL CONTEXT: ${manga.title}")
            appendLine()

            // Basic info
            appendLine("📝 DESCRIPTION:")
            appendLine(manga.description?.take(500) ?: "No description available.")
            appendLine()

            appendLine("ℹ️ INFO:")
            appendLine("- Author: ${manga.author ?: "Unknown"}")
            appendLine("- Genres: ${manga.genre?.joinToString(", ") ?: "N/A"}")
            appendLine(
                "- Status: ${
                    when (manga.status) {
                        1L -> "Ongoing"
                        2L -> "Completed"
                        3L -> "Licensed"
                        4L -> "Publishing finished"
                        5L -> "Cancelled"
                        6L -> "On hiatus"
                        else -> "Unknown"
                    }
                }",
            )
            appendLine()

            appendLine("📊 READING PROGRESS:")
            appendLine("- Chapters read: ${match.readCount} / ${match.totalChapters}")
            appendLine("- Max chapter reached: ${maxChapterRead.toInt()}")
            appendLine("- Unread: ${match.unreadCount}")
            appendLine()

            // Tracker scores
            if (tracks.isNotEmpty()) {
                appendLine("⭐ TRACKER INFO:")
                tracks.forEach { track ->
                    if (track.score > 0) {
                        appendLine("- Score: ${track.score}/10")
                    }
                    if (track.startDate > 0) {
                        val dateFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())
                        appendLine("- Started: ${dateFormat.format(Date(track.startDate))}")
                    }
                    if (track.finishDate > 0) {
                        val dateFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())
                        appendLine("- Finished: ${dateFormat.format(Date(track.finishDate))}")
                    }
                }
                appendLine()
            }

            // User notes
            if (notes.isNotEmpty()) {
                appendLine("📝 USER'S NOTES (${notes.size} total):")
                notes.take(10).forEach { note ->
                    val chapterLabel = if (note.chapterNumber > 0) {
                        "Ch. ${note.chapterNumber.toInt()}"
                    } else {
                        note.chapterName
                    }
                    appendLine("- $chapterLabel, P${note.pageNumber}: \"${note.noteText}\"")
                }
                if (notes.size > 10) {
                    appendLine("... and ${notes.size - 10} more notes")
                }
                appendLine()
            }

            // Bookmarks
            if (bookmarks.isNotEmpty()) {
                appendLine("📌 BOOKMARKED CHAPTERS:")
                bookmarks.take(5)
                    .filter { it.chapterNumber <= maxChapterRead }
                    .forEach { chapter ->
                        appendLine("- Ch. ${chapter.chapterNumber.toInt()}: ${chapter.name}")
                    }
                appendLine()
            }

            appendLine("⚠️ ANTI-SPOILER: Do NOT reveal anything beyond chapter ${maxChapterRead.toInt()}.")
        }
    }

    private suspend fun getUserNotes(mangaTitle: String?): String {
        return if (mangaTitle.isNullOrBlank()) {
            // Get all recent notes
            val notes = try {
                readerNotesRepository.getAllRecentNotes(limit = 15)
            } catch (e: Exception) {
                emptyList()
            }

            if (notes.isEmpty()) {
                "User has no reading notes."
            } else {
                buildString {
                    appendLine("📝 RECENT READING NOTES:")
                    notes.forEach { note ->
                        val chapterLabel = if (note.chapterNumber > 0) {
                            "Ch. ${note.chapterNumber.toInt()}"
                        } else {
                            note.chapterName
                        }
                        appendLine(
                            "- ${note.mangaTitle} ($chapterLabel, P${note.pageNumber}): " +
                                "\"${note.noteText}\"",
                        )
                    }
                }
            }
        } else {
            // Get notes for specific manga
            val library = mangaRepository.getLibraryManga()
            val match = library.find {
                it.manga.title.lowercase().contains(mangaTitle.lowercase())
            } ?: return "Manga '$mangaTitle' not found in library."

            val notes = try {
                readerNotesRepository.getNotesForAiContext(match.manga.id)
            } catch (e: Exception) {
                emptyList()
            }

            if (notes.isEmpty()) {
                "No notes for '${match.manga.title}'."
            } else {
                buildString {
                    appendLine("📝 NOTES FOR ${match.manga.title}:")
                    notes.forEach { note ->
                        val chapterLabel = if (note.chapterNumber > 0) {
                            "Ch. ${note.chapterNumber.toInt()}"
                        } else {
                            note.chapterName
                        }
                        appendLine("- $chapterLabel, P${note.pageNumber}: \"${note.noteText}\"")
                    }
                }
            }
        }
    }

    private suspend fun getReadingHistory(limit: Int): String {
        val history = try {
            historyRepository.getHistory("").firstOrNull() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        if (history.isEmpty()) {
            return "No reading history available."
        }

        val dateFormat = SimpleDateFormat.getDateInstance()

        return buildString {
            appendLine("📅 RECENT READING HISTORY:")
            history.take(limit.coerceIn(1, 20)).forEach { item ->
                val dateStr = item.readAt?.let { dateFormat.format(it) } ?: "Unknown"
                appendLine("- ${item.title} (Ch. ${item.chapterNumber.toInt()}) - $dateStr")
            }
        }
    }

    private suspend fun getCategories(): String {
        val categories = try {
            categoryRepository.getAll()
        } catch (e: Exception) {
            return "Error getting categories: ${e.message}"
        }

        if (categories.isEmpty()) {
            return "User has no custom categories (using default library)."
        }

        return buildString {
            appendLine("📁 USER CATEGORIES:")
            categories.forEach { category ->
                appendLine("- ${category.name} (ID: ${category.id})")
            }
            appendLine()
            appendLine("Use get_manga_by_category to see manga in a specific category.")
        }
    }

    private suspend fun getMangaByCategory(categoryName: String): String {
        if (categoryName.isBlank()) return "Please provide a category name."

        val categories = try {
            categoryRepository.getAll()
        } catch (e: Exception) {
            return "Error getting categories: ${e.message}"
        }

        val queryLower = categoryName.lowercase()
        val matchedCategory = categories.find {
            it.name.lowercase().contains(queryLower)
        } ?: return "Category '$categoryName' not found. " +
            "Available: ${categories.joinToString(", ") { it.name }}"

        val library = mangaRepository.getLibraryManga()
        val mangaInCategory = library.filter { matchedCategory.id in it.categories }

        if (mangaInCategory.isEmpty()) {
            return "No manga in category '${matchedCategory.name}'."
        }

        return buildString {
            appendLine("📁 MANGA IN CATEGORY '${matchedCategory.name}':")
            appendLine("Total: ${mangaInCategory.size} series")
            appendLine()
            mangaInCategory.take(15).forEach { item ->
                val progress = "${item.readCount}/${item.totalChapters} chapters"
                val status = when {
                    item.unreadCount == 0L && item.totalChapters > 0 -> "✅ Completed"
                    item.hasStarted -> "📖 Reading"
                    else -> "📚 Not started"
                }
                appendLine("- ${item.manga.title}")
                appendLine("  $status | $progress")
            }
            if (mangaInCategory.size > 15) {
                appendLine("... and ${mangaInCategory.size - 15} more")
            }
        }
    }

    private suspend fun getReadingTimeStats(limit: Int): String {
        val library = mangaRepository.getLibraryManga()

        val readingDurations = try {
            historyRepository.getReadDurationByManga(limit.toLong().coerceIn(5, 20))
        } catch (e: Exception) {
            return "Error getting reading time stats: ${e.message}"
        }

        if (readingDurations.isEmpty()) {
            return "No reading time data available."
        }

        // Map manga IDs to titles and format durations
        val statsWithTitles = readingDurations.mapNotNull { (mangaId, durationMs) ->
            val manga = library.find { it.manga.id == mangaId }?.manga
            if (manga != null) {
                val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
                val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
                Triple(manga.title, hours, minutes)
            } else {
                null
            }
        }

        if (statsWithTitles.isEmpty()) {
            return "No reading time data for library manga."
        }

        val totalMs = readingDurations.sumOf { it.second }
        val totalHours = TimeUnit.MILLISECONDS.toHours(totalMs)
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(totalMs) % 60

        return buildString {
            appendLine("⏱️ READING TIME STATISTICS:")
            appendLine("Total reading time: ${totalHours}h ${totalMinutes}m")
            appendLine()
            appendLine("📊 TOP MANGA BY TIME SPENT:")
            statsWithTitles.forEachIndexed { index, (title, hours, minutes) ->
                val emoji = when (index) {
                    0 -> "🥇"
                    1 -> "🥈"
                    2 -> "🥉"
                    else -> "  "
                }
                appendLine("$emoji ${index + 1}. $title - ${hours}h ${minutes}m")
            }
            appendLine()
            appendLine("Higher reading time usually indicates favorite series.")
        }
    }

    // === PHASE 1 TOOLS ===

    private suspend fun getPendingUpdates(limit: Int): String {
        val library = mangaRepository.getLibraryManga()

        // Get manga with unread chapters, sorted by most unread first
        val pendingUpdates = library
            .filter { it.unreadCount > 0 }
            .sortedByDescending { it.unreadCount }
            .take(limit.coerceIn(1, 20))

        if (pendingUpdates.isEmpty()) {
            return "No pending updates. All caught up! 🎉"
        }

        val totalUnread = pendingUpdates.sumOf { it.unreadCount }

        return buildString {
            appendLine("📥 PENDING UPDATES ($totalUnread new chapters):")
            appendLine()
            pendingUpdates.forEach { item ->
                val manga = item.manga
                val status = when {
                    item.hasStarted -> "📖 In progress"
                    else -> "📚 Not started"
                }
                appendLine("- ${manga.title}")
                appendLine("  ${item.unreadCount} new chapters | $status")
            }
            appendLine()
            appendLine("These manga have new content to read!")
        }
    }

    private suspend fun getTrackerScores(): String {
        val library = mangaRepository.getLibraryManga()

        // Get all tracks for library manga
        val allTracks = library.flatMap { item ->
            try {
                trackRepository.getTracksByMangaId(item.manga.id).map { track ->
                    Triple(item.manga.title, track.score, track.status)
                }
            } catch (e: Exception) {
                emptyList()
            }
        }.filter { it.second > 0 } // Only scored items

        if (allTracks.isEmpty()) {
            return "No tracker scores found. The user hasn't rated any manga on tracking services."
        }

        // Sort by score descending
        val topRated = allTracks.sortedByDescending { it.second }.take(15)

        // Calculate average
        val avgScore = allTracks.map { it.second }.average()

        return buildString {
            appendLine("⭐ TRACKER SCORES (MAL/AniList/etc):")
            appendLine("Average score: ${String.format("%.1f", avgScore)}/10")
            appendLine("Total rated: ${allTracks.size} series")
            appendLine()
            appendLine("🏆 TOP RATED:")
            topRated.forEachIndexed { index, (title, score, _) ->
                val emoji = when (index) {
                    0 -> "🥇"
                    1 -> "🥈"
                    2 -> "🥉"
                    else -> "  "
                }
                appendLine("$emoji ${String.format("%.1f", score)}/10 - $title")
            }
            appendLine()
            appendLine("These are the user's personal ratings on tracking services.")
        }
    }

    private suspend fun findSimilarManga(query: String, limit: Int): String {
        if (query.isBlank()) return "Please provide a search query."

        val results = try {
            searchLibrary.await(query, limit.coerceIn(1, 10))
        } catch (e: Exception) {
            return "Error searching: ${e.message}. Make sure the library is indexed."
        }

        if (results.isEmpty()) {
            return "No similar manga found for '$query'. " +
                "Try a different search or make sure the library is indexed in Settings > AI."
        }

        return buildString {
            appendLine("🔍 SIMILAR TO '$query':")
            appendLine()
            results.forEachIndexed { index, manga ->
                appendLine("${index + 1}. ${manga.title}")
                manga.author?.let { appendLine("   Author: $it") }
                manga.genre?.take(3)?.let { genres ->
                    appendLine("   Genres: ${genres.joinToString(", ")}")
                }
                appendLine()
            }
            appendLine("These are semantically similar based on title, genres, and description.")
        }
    }

    // === PHASE 2 TOOLS ===

    private suspend fun getBookmarkedChapters(mangaTitle: String?): String {
        val library = mangaRepository.getLibraryManga()

        // Filter by title if provided
        val targetManga = if (!mangaTitle.isNullOrBlank()) {
            val queryLower = mangaTitle.lowercase()
            library.filter { it.manga.title.lowercase().contains(queryLower) }
        } else {
            library.filter { it.hasBookmarks }
        }

        if (targetManga.isEmpty()) {
            return if (mangaTitle != null) {
                "No manga matching '$mangaTitle' found with bookmarks."
            } else {
                "No bookmarked chapters found in the library."
            }
        }

        val allBookmarks = targetManga.flatMap { item ->
            try {
                chapterRepository.getBookmarkedChaptersByMangaId(item.manga.id).map { chapter ->
                    Triple(item.manga.title, chapter.chapterNumber, chapter.name)
                }
            } catch (e: Exception) {
                emptyList()
            }
        }.sortedWith(compareBy({ it.first }, { it.second }))

        if (allBookmarks.isEmpty()) {
            return "No bookmarked chapters found."
        }

        return buildString {
            appendLine("📌 BOOKMARKED CHAPTERS (${allBookmarks.size} total):")
            appendLine()

            // Group by manga
            allBookmarks.groupBy { it.first }.forEach { (title, chapters) ->
                appendLine("📖 $title:")
                chapters.take(5).forEach { (_, chapterNum, chapterName) ->
                    appendLine("  - Ch. ${chapterNum.toInt()}: $chapterName")
                }
                if (chapters.size > 5) {
                    appendLine("  ... and ${chapters.size - 5} more bookmarks")
                }
                appendLine()
            }
            appendLine("Bookmarks indicate important moments the user saved.")
        }
    }

    private suspend fun getReadingStreak(): String {
        val streak = try {
            historyRepository.getReadingStreak()
        } catch (e: Exception) {
            0
        }

        val daysLastWeek = try {
            historyRepository.getDaysReadLastWeek()
        } catch (e: Exception) {
            0L
        }

        val totalDuration = try {
            historyRepository.getTotalReadDuration()
        } catch (e: Exception) {
            0L
        }

        val totalHours = TimeUnit.MILLISECONDS.toHours(totalDuration)

        return buildString {
            appendLine("🔥 READING STREAK:")
            appendLine()
            if (streak > 0) {
                val streakEmoji = when {
                    streak >= 30 -> "🏆"
                    streak >= 14 -> "⭐"
                    streak >= 7 -> "🔥"
                    else -> "📖"
                }
                appendLine("$streakEmoji Current streak: $streak consecutive days!")
            } else {
                appendLine("📖 No active streak. Start reading today!")
            }
            appendLine()
            appendLine("📊 WEEKLY STATS:")
            appendLine("- Days read this week: $daysLastWeek/7")
            appendLine("- Total reading time: ${totalHours}h")
            appendLine()
            if (streak >= 7) {
                appendLine("Great reading habit! Keep it up! 🎉")
            } else if (daysLastWeek >= 4) {
                appendLine("Good week! You're building a nice habit.")
            }
        }
    }

    private suspend fun getCompletedSeries(limit: Int): String {
        val library = mangaRepository.getLibraryManga()
        val categories = try {
            categoryRepository.getAll()
        } catch (e: Exception) {
            emptyList()
        }

        // Find categories that suggest "completed" (user-created)
        val completedCategoryIds = categories
            .filter { cat ->
                val nameLower = cat.name.lowercase()
                nameLower.contains("complet") ||
                    nameLower.contains("terminad") ||
                    nameLower.contains("finish") ||
                    nameLower.contains("done") ||
                    nameLower.contains("leido") ||
                    nameLower.contains("leído")
            }
            .map { it.id }
            .toSet()

        // Find completed series by multiple criteria:
        // 1. All chapters read (unreadCount == 0)
        // 2. OR in a "completed" category (user organized)
        val completedSeries = library
            .filter { item ->
                val allChaptersRead = item.totalChapters > 0 &&
                    item.unreadCount == 0L &&
                    item.hasStarted
                val inCompletedCategory = item.categories.any { it in completedCategoryIds }
                allChaptersRead || inCompletedCategory
            }
            .sortedByDescending { it.totalChapters }
            .take(limit.coerceIn(1, 20))

        if (completedSeries.isEmpty()) {
            return "No completed series found. Keep reading! 📖"
        }

        val totalChaptersRead = completedSeries.sumOf { it.readCount }

        return buildString {
            appendLine("✅ COMPLETED SERIES (${completedSeries.size} finished):")
            appendLine("Total chapters read: $totalChaptersRead")
            if (completedCategoryIds.isNotEmpty()) {
                appendLine("(Also checking user's 'Completed' categories)")
            }
            appendLine()
            completedSeries.forEachIndexed { index, item ->
                val manga = item.manga
                // Check multiple completion indicators
                val inUserCategory = item.categories.any { it in completedCategoryIds }
                val allRead = item.unreadCount == 0L && item.totalChapters > 0
                val sourceComplete = manga.status == 2L

                val statusEmoji = when {
                    allRead && sourceComplete -> "📕" // Fully complete
                    allRead -> "📗" // Read all, source not marked complete
                    inUserCategory -> "📂" // User categorized as complete
                    else -> "📖"
                }
                appendLine("${index + 1}. $statusEmoji ${manga.title}")
                appendLine("   ${item.readCount}/${item.totalChapters} chapters")
            }
            appendLine()
            appendLine("📕 = Source marked complete | 📗 = All read | 📂 = In completed category")
        }
    }

    // === PHASE 3 TOOLS ===

    private suspend fun getDroppedSeries(daysInactive: Int, limit: Int): String {
        val library = mangaRepository.getLibraryManga()
        val now = System.currentTimeMillis()
        val inactiveThreshold = now - (daysInactive.coerceIn(7, 365) * 24 * 60 * 60 * 1000L)

        // Find series that were started but not read recently, still have unread chapters
        val droppedSeries = library
            .filter { item ->
                item.hasStarted &&
                    item.unreadCount > 0 &&
                    item.lastRead > 0 &&
                    item.lastRead < inactiveThreshold
            }
            .sortedBy { it.lastRead } // Oldest first (most likely dropped)
            .take(limit.coerceIn(1, 20))

        if (droppedSeries.isEmpty()) {
            return "No dropped series found! You're keeping up with everything. 🎉"
        }

        val dateFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())

        return buildString {
            appendLine("💤 POSSIBLY DROPPED SERIES (inactive $daysInactive+ days):")
            appendLine()
            droppedSeries.forEachIndexed { index, item ->
                val manga = item.manga
                val lastReadDate = dateFormat.format(Date(item.lastRead))
                val progress = "${item.readCount}/${item.totalChapters}"
                appendLine("${index + 1}. ${manga.title}")
                appendLine("   Progress: $progress | Last read: $lastReadDate")
                appendLine("   ${item.unreadCount} chapters behind")
                appendLine()
            }
            appendLine("These series haven't been touched in a while. Maybe worth picking back up?")
        }
    }

    private suspend fun getAuthorWorks(authorName: String): String {
        if (authorName.isBlank()) return "Please provide an author name."

        val library = mangaRepository.getLibraryManga()
        val queryLower = authorName.lowercase()

        // Find all manga by this author
        val authorWorks = library.filter { item ->
            val author = item.manga.author?.lowercase() ?: ""
            val artist = item.manga.artist?.lowercase() ?: ""
            author.contains(queryLower) || artist.contains(queryLower)
        }.sortedByDescending { it.readCount }

        if (authorWorks.isEmpty()) {
            return "No works by '$authorName' found in library. " +
                "Try a different spelling or check if you have their works."
        }

        // Get unique authors to display the full name
        val fullAuthorName = authorWorks.firstOrNull()?.manga?.author ?: authorName

        return buildString {
            appendLine("✍️ WORKS BY $fullAuthorName:")
            appendLine("Found ${authorWorks.size} series in library")
            appendLine()
            authorWorks.forEach { item ->
                val manga = item.manga
                val status = when {
                    item.unreadCount == 0L && item.totalChapters > 0 -> "✅ Completed"
                    item.hasStarted -> "📖 Reading (${item.readCount}/${item.totalChapters})"
                    else -> "📚 Not started"
                }
                appendLine("- ${manga.title}")
                appendLine("  $status")
                manga.genre?.take(3)?.let { genres ->
                    appendLine("  Genres: ${genres.joinToString(", ")}")
                }
                appendLine()
            }
            appendLine("All series by this author in the user's library.")
        }
    }

    // === PHASE 4 TOOLS ===

    private suspend fun getUpcomingManga(limit: Int): String {
        val library = mangaRepository.getLibraryManga()
        val now = System.currentTimeMillis()
        val oneWeekFromNow = now + (7 * 24 * 60 * 60 * 1000L)

        // Find manga with upcoming updates (nextUpdate is in the future)
        val upcomingManga = library
            .filter { item ->
                val manga = item.manga
                manga.nextUpdate > now && manga.nextUpdate < oneWeekFromNow
            }
            .sortedBy { it.manga.nextUpdate }
            .take(limit.coerceIn(1, 20))

        if (upcomingManga.isEmpty()) {
            // Fallback: show ongoing series that might update soon
            val ongoingSeries = library
                .filter { it.manga.status == 1L } // Ongoing
                .sortedByDescending { it.manga.lastUpdate }
                .take(limit.coerceIn(1, 20))

            if (ongoingSeries.isEmpty()) {
                return "No upcoming updates detected. Check back later!"
            }

            return buildString {
                appendLine("📅 ONGOING SERIES (may update soon):")
                appendLine()
                ongoingSeries.forEach { item ->
                    val manga = item.manga
                    val daysSinceUpdate = if (manga.lastUpdate > 0) {
                        ((now - manga.lastUpdate) / (24 * 60 * 60 * 1000L)).toInt()
                    } else {
                        -1
                    }
                    appendLine("- ${manga.title}")
                    if (daysSinceUpdate >= 0) {
                        appendLine("  Last update: $daysSinceUpdate days ago")
                    }
                }
            }
        }

        val dateFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

        return buildString {
            appendLine("📅 UPCOMING UPDATES (expected this week):")
            appendLine()
            upcomingManga.forEach { item ->
                val manga = item.manga
                val expectedDate = dateFormat.format(Date(manga.nextUpdate))
                appendLine("- ${manga.title}")
                appendLine("  Expected: $expectedDate")
            }
            appendLine()
            appendLine("Based on each series' update pattern.")
        }
    }

    private suspend fun getRecentlyAdded(limit: Int): String {
        val library = mangaRepository.getLibraryManga()

        val recentlyAdded = library
            .filter { it.manga.dateAdded > 0 }
            .sortedByDescending { it.manga.dateAdded }
            .take(limit.coerceIn(1, 20))

        if (recentlyAdded.isEmpty()) {
            return "No recently added manga found."
        }

        val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        return buildString {
            appendLine("🆕 RECENTLY ADDED TO LIBRARY:")
            appendLine()
            recentlyAdded.forEach { item ->
                val manga = item.manga
                val addedDate = dateFormat.format(Date(manga.dateAdded))
                val status = when {
                    item.hasStarted -> "📖 Started reading"
                    else -> "📚 Not started yet"
                }
                appendLine("- ${manga.title}")
                appendLine("  Added: $addedDate | $status")
            }
            appendLine()
            appendLine("Series the user recently discovered and added.")
        }
    }

    private suspend fun getGenreBreakdown(): String {
        val library = mangaRepository.getLibraryManga()

        if (library.isEmpty()) {
            return "Library is empty. No genre data available."
        }

        // Count all genres
        val genreCounts = library
            .flatMap { it.manga.genre ?: emptyList() }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }

        if (genreCounts.isEmpty()) {
            return "No genre data available in the library."
        }

        val totalGenreTags = genreCounts.sumOf { it.second }
        val topGenres = genreCounts.take(15)

        // Categorize genres
        val actionGenres = listOf("Action", "Adventure", "Martial Arts", "Military")
        val romanceGenres = listOf("Romance", "Shoujo", "Josei", "Harem")
        val fantasyGenres = listOf("Fantasy", "Supernatural", "Magic", "Isekai")
        val dramaGenres = listOf("Drama", "Psychological", "Tragedy", "Slice of Life")

        val actionCount = genreCounts.filter { it.first in actionGenres }.sumOf { it.second }
        val romanceCount = genreCounts.filter { it.first in romanceGenres }.sumOf { it.second }
        val fantasyCount = genreCounts.filter { it.first in fantasyGenres }.sumOf { it.second }
        val dramaCount = genreCounts.filter { it.first in dramaGenres }.sumOf { it.second }

        return buildString {
            appendLine("📊 GENRE BREAKDOWN:")
            appendLine("Total library: ${library.size} series")
            appendLine()
            appendLine("🏆 TOP GENRES:")
            topGenres.forEachIndexed { index, (genre, count) ->
                val percentage = (count * 100) / totalGenreTags
                val bar = "█".repeat((percentage / 5).coerceIn(1, 20))
                appendLine("${index + 1}. $genre: $count ($percentage%) $bar")
            }
            appendLine()
            appendLine("📈 CATEGORY SUMMARY:")
            appendLine("- Action/Adventure: $actionCount tags")
            appendLine("- Romance: $romanceCount tags")
            appendLine("- Fantasy/Supernatural: $fantasyCount tags")
            appendLine("- Drama/Psychological: $dramaCount tags")
            appendLine()
            appendLine("This shows the user's reading preferences by genre frequency.")
        }
    }

    // === PHASE 5 TOOLS ===

    private suspend fun getReadingPatterns(): String {
        val history = try {
            historyRepository.getHistory("").firstOrNull() ?: emptyList()
        } catch (e: Exception) {
            return "Unable to analyze reading patterns: ${e.message}"
        }

        if (history.isEmpty()) {
            return "No reading history available to analyze patterns."
        }

        // Analyze by hour of day
        val hourCounts = mutableMapOf<Int, Int>()
        val dayCounts = mutableMapOf<Int, Int>() // 1=Sunday, 7=Saturday

        val calendar = java.util.Calendar.getInstance()

        history.forEach { item ->
            item.readAt?.let { date ->
                calendar.time = date
                val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
                val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK)

                hourCounts[hour] = (hourCounts[hour] ?: 0) + 1
                dayCounts[dayOfWeek] = (dayCounts[dayOfWeek] ?: 0) + 1
            }
        }

        // Categorize time of day
        val morningReads = (6..11).sumOf { hourCounts[it] ?: 0 }
        val afternoonReads = (12..17).sumOf { hourCounts[it] ?: 0 }
        val eveningReads = (18..21).sumOf { hourCounts[it] ?: 0 }
        val nightReads = (22..23).sumOf { hourCounts[it] ?: 0 } +
            (0..5).sumOf { hourCounts[it] ?: 0 }

        val dayNames = listOf("", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        val topDays = dayCounts.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { dayNames[it.key] to it.value }

        val peakHour = hourCounts.maxByOrNull { it.value }?.key ?: 0

        return buildString {
            appendLine("⏰ READING PATTERNS:")
            appendLine()
            appendLine("📅 TIME OF DAY:")
            appendLine("- Morning (6AM-12PM): $morningReads sessions")
            appendLine("- Afternoon (12PM-6PM): $afternoonReads sessions")
            appendLine("- Evening (6PM-10PM): $eveningReads sessions")
            appendLine("- Night (10PM-6AM): $nightReads sessions")
            appendLine()
            val peakTime = when {
                nightReads >= maxOf(morningReads, afternoonReads, eveningReads) -> "🌙 Night owl!"
                morningReads >= maxOf(afternoonReads, eveningReads, nightReads) -> "🌅 Early bird!"
                eveningReads >= maxOf(morningReads, afternoonReads, nightReads) -> "🌆 Evening reader!"
                else -> "☀️ Daytime reader!"
            }
            appendLine("Peak reading time: ${peakHour}:00 - $peakTime")
            appendLine()
            appendLine("📆 FAVORITE DAYS:")
            topDays.forEach { (day, count) ->
                appendLine("- $day: $count sessions")
            }
        }
    }

    private suspend fun getNotesByTag(tagName: String, limit: Int): String {
        if (tagName.isBlank()) return "Please provide a tag name (THEORY, IMPORTANT, QUESTION, FAVORITE, SPOILER, FUNNY)"

        val tag = try {
            tachiyomi.domain.manga.model.NoteTag.valueOf(tagName.uppercase())
        } catch (e: Exception) {
            return "Invalid tag '$tagName'. Valid tags: THEORY, IMPORTANT, QUESTION, FAVORITE, SPOILER, FUNNY"
        }

        val allNotes = try {
            readerNotesRepository.getAllRecentNotes(50)
        } catch (e: Exception) {
            return "Error getting notes: ${e.message}"
        }

        // Filter by tag
        val filteredNotes = allNotes
            .filter { note -> tag in note.tags }
            .take(limit.coerceIn(1, 20))

        if (filteredNotes.isEmpty()) {
            return "No notes found with tag '${tag.emoji} ${tag.displayName}'."
        }

        return buildString {
            appendLine("${tag.emoji} NOTES TAGGED '${tag.displayName.uppercase()}':")
            appendLine("Found ${filteredNotes.size} notes")
            appendLine()
            filteredNotes.forEach { note ->
                appendLine("📖 ${note.mangaTitle}")
                appendLine("   Ch. ${note.chapterNumber.toInt()}, Page ${note.pageNumber}")
                appendLine("   \"${note.noteText.take(100)}${if (note.noteText.length > 100) "..." else ""}\"")
                appendLine()
            }
        }
    }

    private suspend fun predictCompletionTime(mangaTitle: String): String {
        if (mangaTitle.isBlank()) return "Please provide a manga title."

        val library = mangaRepository.getLibraryManga()
        val queryLower = mangaTitle.lowercase()

        val match = library.find { it.manga.title.lowercase().contains(queryLower) }
            ?: return "Manga '$mangaTitle' not found in library."

        val manga = match.manga
        val unreadChapters = match.unreadCount

        if (unreadChapters == 0L) {
            return "✅ '${manga.title}' is already fully read! No chapters remaining."
        }

        if (!match.hasStarted) {
            return "📚 '${manga.title}' hasn't been started yet. " +
                "No reading pace available for prediction."
        }

        // Get history for this manga to calculate reading pace
        val history = try {
            historyRepository.getHistoryByMangaId(manga.id)
        } catch (e: Exception) {
            return "Error getting reading history: ${e.message}"
        }

        if (history.size < 2) {
            return "Not enough reading history for '${manga.title}' to predict completion. " +
                "Read a few more chapters!"
        }

        // Calculate average chapters per day based on reading span
        val readDates = history.mapNotNull { it.readAt?.time }.sorted()
        val firstRead = readDates.firstOrNull() ?: 0L
        val lastRead = readDates.lastOrNull() ?: 0L
        val daySpan = ((lastRead - firstRead) / (24 * 60 * 60 * 1000.0)).coerceAtLeast(1.0)
        val chaptersRead = match.readCount.toDouble()
        val chaptersPerDay = chaptersRead / daySpan

        if (chaptersPerDay <= 0) {
            return "Unable to calculate reading pace for '${manga.title}'."
        }

        val daysToComplete = (unreadChapters / chaptersPerDay).toLong()
        val completionDate = Date(System.currentTimeMillis() + (daysToComplete * 24 * 60 * 60 * 1000))
        val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())

        return buildString {
            appendLine("🔮 COMPLETION PREDICTION: ${manga.title}")
            appendLine()
            appendLine("📊 Current Progress: ${match.readCount}/${match.totalChapters} chapters")
            appendLine("📚 Remaining: $unreadChapters chapters")
            appendLine()
            appendLine("⚡ Your Pace: ${String.format("%.1f", chaptersPerDay)} chapters/day")
            appendLine("📅 Estimated Completion: ${dateFormat.format(completionDate)}")
            appendLine("   (~$daysToComplete days from now)")
        }
    }

    private suspend fun getMonthlySummary(monthsAgo: Int): String {
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.MONTH, -monthsAgo.coerceIn(0, 12))

        val targetMonth = calendar.get(java.util.Calendar.MONTH)
        val targetYear = calendar.get(java.util.Calendar.YEAR)

        val monthNames = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )
        val monthName = monthNames[targetMonth]

        // Get history for the month
        val history = try {
            historyRepository.getHistory("").firstOrNull() ?: emptyList()
        } catch (e: Exception) {
            return "Error getting history: ${e.message}"
        }

        val historyCalendar = java.util.Calendar.getInstance()
        val monthHistory = history.filter { item ->
            item.readAt?.let { date ->
                historyCalendar.time = date
                historyCalendar.get(java.util.Calendar.MONTH) == targetMonth &&
                    historyCalendar.get(java.util.Calendar.YEAR) == targetYear
            } ?: false
        }

        if (monthHistory.isEmpty()) {
            return "No reading activity found for $monthName $targetYear."
        }

        val chaptersRead = monthHistory.size
        val uniqueSeries = monthHistory.map { it.mangaId }.distinct().size
        val totalDuration = monthHistory.sumOf { it.readDuration }
        val totalHours = TimeUnit.MILLISECONDS.toHours(totalDuration)
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(totalDuration) % 60

        // Top series for the month
        val topSeries = monthHistory
            .groupingBy { it.title }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)

        return buildString {
            appendLine("📅 $monthName $targetYear SUMMARY:")
            appendLine()
            appendLine("📊 OVERVIEW:")
            appendLine("- Chapters read: $chaptersRead")
            appendLine("- Series touched: $uniqueSeries")
            appendLine("- Reading time: ${totalHours}h ${totalMinutes}m")
            appendLine()
            appendLine("🏆 TOP SERIES:")
            topSeries.forEachIndexed { index, (title, count) ->
                val emoji = when (index) {
                    0 -> "🥇"
                    1 -> "🥈"
                    2 -> "🥉"
                    else -> "  "
                }
                appendLine("$emoji $title: $count chapters")
            }
            appendLine()
            val avgPerDay = chaptersRead / 30.0
            appendLine("Average: ${String.format("%.1f", avgPerDay)} chapters/day")
        }
    }
}
