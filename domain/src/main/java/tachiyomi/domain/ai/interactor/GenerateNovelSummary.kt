package tachiyomi.domain.ai.interactor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.ai.model.ChatMessage
import tachiyomi.domain.ai.repository.AiRepository
import tachiyomi.domain.novelcontext.model.NovelContext
import tachiyomi.domain.novelcontext.repository.NovelContextRepository

/**
 * Interactor to generate progressive AI summaries for novels/PDFs.
 * Uses the existing summary (if any) + new text to create an updated summary.
 */
class GenerateNovelSummary(
    private val aiRepository: AiRepository,
    private val novelContextRepository: NovelContextRepository,
) {

    /**
     * Generate or update a progressive summary for the given manga.
     * Only updates if the current chapter is >= the stored chapter number
     * (prevents overwriting newer summaries when re-reading old chapters).
     *
     * @param mangaId The manga/novel ID
     * @param newText The new text content to incorporate (pages fromPage..toPage)
     * @param toPage The last page included in this summary update
     * @param chapterNumber The chapter number being summarized (e.g., 851.0 for "Chapter 851-900")
     * @return The generated summary text, or null if generation failed or skipped
     */
    suspend fun await(
        mangaId: Long,
        newText: String,
        toPage: Int,
        chapterNumber: Double = 0.0,
    ): String? = withContext(Dispatchers.IO) {
        if (newText.isBlank()) return@withContext null

        // 1. Get existing summary (if any)
        val existingContext = novelContextRepository.getByMangaId(mangaId)
        val existingSummary = existingContext?.summaryText
        val storedChapter = existingContext?.summaryChapterNumber ?: 0.0

        // 2. Check if we should update (only if reading forward or same chapter)
        if (chapterNumber < storedChapter) {
            // User is re-reading an old chapter, don't overwrite newer summary
            return@withContext null
        }

        // 3. Build summarization prompt
        val systemPrompt = buildSummarizationPrompt(existingSummary, newText)

        // 4. Call AI to generate summary
        val messages = listOf(
            ChatMessage.system(systemPrompt),
            ChatMessage.user("Generate the updated story summary now."),
        )

        val result = aiRepository.sendMessage(messages)

        result.fold(
            onSuccess = { response ->
                val summaryText = response.content.trim()
                if (summaryText.isNotBlank()) {
                    // 5. Save to database with chapter number
                    val updatedContext = NovelContext(
                        id = existingContext?.id ?: 0,
                        mangaId = mangaId,
                        summaryText = summaryText,
                        summaryLastPage = toPage,
                        summaryChapterNumber = chapterNumber,
                        updatedAt = System.currentTimeMillis(),
                    )
                    novelContextRepository.upsert(updatedContext)
                    summaryText
                } else {
                    null
                }
            },
            onFailure = { null },
        )
    }

    private fun buildSummarizationPrompt(existingSummary: String?, newText: String): String {
        return buildString {
            appendLine("You are a story summarizer for novels and light novels.")
            appendLine("Your task is to create or update a PROGRESSIVE SUMMARY of the story.")
            appendLine()
            appendLine("RULES:")
            appendLine("1. Keep the summary under 500 words")
            appendLine("2. Focus on: main plot events, character introductions, and key revelations")
            appendLine("3. Use present tense")
            appendLine("4. Do NOT include commentary or opinions")
            appendLine("5. Write in the same language as the source text")
            appendLine()

            if (existingSummary != null) {
                appendLine("EXISTING SUMMARY (from previous pages):")
                appendLine(existingSummary)
                appendLine()
                appendLine("NEW CONTENT TO INCORPORATE:")
                appendLine(newText.take(15000)) // Limit to avoid token overflow
                appendLine()
                appendLine(
                    "TASK: Update the existing summary to include the new events. " +
                        "Merge seamlessly, keeping the most important plot points.",
                )
            } else {
                appendLine("STORY CONTENT:")
                appendLine(newText.take(15000))
                appendLine()
                appendLine("TASK: Create a concise summary of the story so far.")
            }
        }
    }
}
