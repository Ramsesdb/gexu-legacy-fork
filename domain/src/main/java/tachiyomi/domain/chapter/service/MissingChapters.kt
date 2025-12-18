package tachiyomi.domain.chapter.service

import tachiyomi.domain.chapter.model.Chapter
import kotlin.math.floor

/**
 * Pattern to detect chapter ranges in file names
 * Matches: "1801 - 1900", "100-200", "50 – 100" (with en-dash or em-dash)
 */
private val chapterRangePattern = Regex("""(\d+)\s*[-–—]\s*(\d+)""")

/**
 * Pattern for ranges using "to", "a", "al" (Spanish)
 * Matches: "100 to 200", "100 a 200", "100 al 200"
 */
private val chapterRangeWithTo = Regex("""(\d+)\s+(?:to|a|al)\s+(\d+)""", RegexOption.IGNORE_CASE)

/**
 * Data class to hold chapter range info
 */
private data class ChapterRange(val start: Int, val end: Int)

/**
 * Try to extract a chapter range from a chapter name
 * Returns null if no valid range is found
 */
private fun extractChapterRange(chapterName: String): ChapterRange? {
    // Try standard range pattern first (e.g., "1801 - 1900" or "100-200")
    chapterRangePattern.find(chapterName)?.let { match ->
        val start = match.groupValues[1].toIntOrNull()
        val end = match.groupValues[2].toIntOrNull()
        if (start != null && end != null && end > start && (end - start) <= 500) {
            return ChapterRange(start, end)
        }
    }

    // Try "to/a/al" pattern (e.g., "100 to 200")
    chapterRangeWithTo.find(chapterName)?.let { match ->
        val start = match.groupValues[1].toIntOrNull()
        val end = match.groupValues[2].toIntOrNull()
        if (start != null && end != null && end > start && (end - start) <= 500) {
            return ChapterRange(start, end)
        }
    }

    return null
}

/**
 * Calculate missing chapters count from a list of chapters.
 * This version is aware of chapter ranges in names (e.g. "1801-1900").
 */
fun List<Chapter>.missingChaptersCount(manga: tachiyomi.domain.manga.model.Manga? = null): Int {
    if (this.isEmpty()) {
        return 0
    }

    val sortedChapters = this
        .filter { it.isRecognizedNumber }
        .sortedBy { it.chapterNumber }

    if (sortedChapters.isEmpty()) {
        return 0
    }

    var missingChaptersCount = 0
    var previousEnd = -1.0

    for (i in sortedChapters.indices) {
        val currentChapter = sortedChapters[i]
        val currentNumber = currentChapter.chapterNumber

        // Try to find the start of this chapter range
        // If it's a range like "1801-1900", start is 1801.
        // If regular chapter 1801, start is 1801.
        val currentRange = extractChapterRange(currentChapter.name)
        val currentStart = currentRange?.start?.toDouble() ?: currentNumber
        val currentEnd = currentRange?.end?.toDouble() ?: currentNumber

        if (i > 0) { // Skip first chapter check against non-existent previous
            if (previousEnd > -1.0 && currentStart > previousEnd + 1) {
                missingChaptersCount += (currentStart - previousEnd - 1).toInt()
            }
        }

        previousEnd = currentEnd
    }

    return missingChaptersCount
}

fun List<Double>.missingChaptersCount(): Int {
    if (this.isEmpty()) {
        return 0
    }

    val chapters = this
        // Ignore unknown chapter numbers
        .filterNot { it == -1.0 }
        // Convert to integers, as we cannot check if 16.5 is missing
        .map(Double::toInt)
        // Only keep unique chapters so that -1 or 16 are not counted multiple times
        .distinct()
        .sorted()

    if (chapters.isEmpty()) {
        return 0
    }

    var missingChaptersCount = 0
    var previousChapter = 0 // The actual chapter number, not the array index

    // We go from 0 to lastChapter - Make sure to use the current index instead of the value
    for (i in chapters.indices) {
        val currentChapter = chapters[i]
        if (currentChapter > previousChapter + 1) {
            // Add the amount of missing chapters
            missingChaptersCount += currentChapter - previousChapter - 1
        }
        previousChapter = currentChapter
    }

    return missingChaptersCount
}

/**
 * Calculate the gap between two chapters, considering chapter ranges.
 *
 * For regular chapters: Gap = higher - lower - 1
 * For chapter ranges (compilations/novels):
 *   - higherChapter's START is compared to lowerChapter's END
 *   - Example: "1801-1900" vs "1701-1800" -> 1801 - 1800 - 1 = 0 (no gap)
 */
fun calculateChapterGap(higherChapter: Chapter?, lowerChapter: Chapter?): Int {
    if (higherChapter == null || lowerChapter == null) return 0
    if (!higherChapter.isRecognizedNumber || !lowerChapter.isRecognizedNumber) return 0

    // Try to extract ranges from chapter names
    val higherRange = extractChapterRange(higherChapter.name)
    val lowerRange = extractChapterRange(lowerChapter.name)

    // If both chapters have ranges, use range-aware calculation
    if (higherRange != null && lowerRange != null) {
        // Higher chapter's START should follow lower chapter's END
        // Gap = higherStart - lowerEnd - 1
        val gap = higherRange.start - lowerRange.end - 1
        return if (gap > 0) gap else 0
    }

    // If only higher has a range, use its start
    if (higherRange != null) {
        val gap = higherRange.start - floor(lowerChapter.chapterNumber).toInt() - 1
        return if (gap > 0) gap else 0
    }

    // If only lower has a range, use its end
    if (lowerRange != null) {
        val gap = floor(higherChapter.chapterNumber).toInt() - lowerRange.end - 1
        return if (gap > 0) gap else 0
    }

    // Standard calculation for non-range chapters
    return calculateChapterGap(higherChapter.chapterNumber, lowerChapter.chapterNumber)
}

fun calculateChapterGap(higherChapterNumber: Double, lowerChapterNumber: Double): Int {
    if (higherChapterNumber < 0.0 || lowerChapterNumber < 0.0) return 0
    return floor(higherChapterNumber).toInt() - floor(lowerChapterNumber).toInt() - 1
}
