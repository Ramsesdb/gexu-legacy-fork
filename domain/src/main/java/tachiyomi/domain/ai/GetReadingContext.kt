package tachiyomi.domain.ai

import kotlinx.coroutines.flow.firstOrNull
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.category.repository.CategoryRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GetReadingContext(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val historyRepository: HistoryRepository,
    private val categoryRepository: CategoryRepository,
) {

    suspend fun getContextForManga(mangaId: Long, currentChapterId: Long? = null): String {
        val manga = mangaRepository.getMangaById(mangaId)

        // Anti-Spoiler Logic: Get max chapter number in a single optimized query
        // This replaces the previous N+1 query pattern (1 per chapter in history)
        val maxChapterRead = historyRepository.getMaxChapterReadForManga(mangaId)

        val currentChapter = if (currentChapterId != null) {
            chapterRepository.getChapterById(currentChapterId)
        } else {
            null
        }

        return buildString {
            appendLine("--- CONTEXT START: CURRENT READING ---")
            appendLine("User is currently reading: ${manga.title}")
            appendLine("Synopsis: ${manga.description?.take(800)}...") // Truncate for token limit
            appendLine("Genres: ${manga.genre?.joinToString(", ")}")
            appendLine("Author: ${manga.author}")

            if (currentChapter != null) {
                appendLine("Current Activity: Reading Chapter ${currentChapter.chapterNumber} - ${currentChapter.name}")
            }

            appendLine("Reading Progress: User has read up to chapter $maxChapterRead.")
            appendLine("CRITICAL INSTRUCTION: Do NOT spoil anything beyond chapter $maxChapterRead.")
            appendLine("--- CONTEXT END ---")
        }
    }

    suspend fun getGlobalContext(): String {
        // 1. Get Library Data
        val libraryItems = mangaRepository.getLibraryManga()
        val allCategories = categoryRepository.getAll()

        // 2. Analyze Genres (User Tastes)
        val genreCounts = libraryItems
            .flatMap { it.manga.genre ?: emptyList() }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(5)
            .joinToString(", ") { "${it.first} (${it.second})" }

        // 3. Analyze Categories
        val categorySummary = allCategories.joinToString("\n") { cat ->
            val count = libraryItems.count { it.categories.contains(cat.id) }
            "- ${cat.name}: $count series"
        }

        // 4. Get Recently Read
        val recentHistory: List<HistoryWithRelations> = try {
            historyRepository.getHistory("").firstOrNull() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        // 5. Completed Series
        // SManga.COMPLETED = 2
        val completedSeries = libraryItems
            .filter { it.manga.status == 2L.toLong() && it.readCount >= it.totalChapters && it.totalChapters > 0 }
            .take(6)
            .joinToString(", ") { it.manga.title }

        val ongoingSeries = libraryItems
            .filter { it.readCount > 0 && it.readCount < it.totalChapters }
            .sortedByDescending { it.lastRead }
            .take(6)
            .joinToString(", ") { "${it.manga.title} (${it.readCount}/${it.totalChapters})" }

        return buildString {
            appendLine("--- USER PROFILE CONTEXT ---")
            appendLine("Here is an analysis of the user's library and preferences:")
            appendLine()
            appendLine("TOP GENRES (User Tastes):")
            appendLine(genreCounts.ifBlank { "Unknown" })
            appendLine()
            appendLine("LIBRARY CATEGORIES:")
            appendLine(categorySummary)
            appendLine()
            if (completedSeries.isNotBlank()) {
                appendLine("RECENTLY COMPLETED / FULLY READ SERIES:")
                appendLine(completedSeries)
                appendLine()
            }
            if (ongoingSeries.isNotBlank()) {
                appendLine("CURRENTLY READING:")
                appendLine(ongoingSeries)
                appendLine()
            }
            appendLine("RECENT HISTORY:")
            if (recentHistory.isEmpty()) {
                appendLine("No recent history.")
            } else {
                recentHistory.take(7).forEach { item: HistoryWithRelations ->
                val dateStr = item.readAt?.let { SimpleDateFormat.getDateInstance().format(it) } ?: "Unknown"
                appendLine("- ${item.title} (Chapter ${item.chapterNumber}) - Last read: $dateStr")
            }
            }
            appendLine()
            appendLine("INSTRUCTIONS:")
            appendLine("1. Use this profile to personalize your responses.")
            appendLine("2. If the user asks for recommendations, suggest titles similar to their Top Genres but NOT in their library.")
            appendLine("3. Be aware of what they have already read to avoid redundancy.")
            appendLine("--- END PROFILE ---")
        }
    }

    /**
     * Lightweight profile for reader context.
     * Only includes top genres and a few recent series.
     * Used when user is in a specific manga but might ask general questions.
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

        return buildString {
            appendLine("(Brief Profile: User likes $topGenres. Recently read: $recentSeries)")
        }
    }
}
