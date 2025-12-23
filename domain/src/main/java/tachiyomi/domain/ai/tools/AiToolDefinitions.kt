package tachiyomi.domain.ai.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Definitions of AI tools (functions) that Gemini can call.
 * These are sent in the API request to enable function calling.
 */
object AiToolDefinitions {

    /**
     * All available tools for the AI to use
     */
    val allTools: List<FunctionDeclaration> = listOf(
        FunctionDeclaration(
            name = "get_library_stats",
            description = "Get statistics about the user's manga/manhwa/novel library " +
                "including total count, read chapters, completion rate, and top genres.",
            parameters = FunctionParameters(
                properties = emptyMap(),
                required = emptyList(),
            ),
        ),
        FunctionDeclaration(
            name = "search_library",
            description = "Search the user's library for manga/manhwa/novels matching a query. " +
                "Returns titles, authors, and genres of matching items.",
            parameters = FunctionParameters(
                properties = mapOf(
                    "query" to PropertyDef(
                        type = "string",
                        description = "Search query (title, genre, author, etc.)",
                    ),
                ),
                required = listOf("query"),
            ),
        ),
        FunctionDeclaration(
            name = "get_full_manga_context",
            description = "Get COMPLETE information about a specific manga/manhwa/novel: " +
                "description, chapters read, user notes, bookmarks, tracker scores, " +
                "reading dates, and current progress. Use this when discussing a specific series.",
            parameters = FunctionParameters(
                properties = mapOf(
                    "title" to PropertyDef(
                        type = "string",
                        description = "Title of the manga/manhwa/novel to look up",
                    ),
                ),
                required = listOf("title"),
            ),
        ),
        FunctionDeclaration(
            name = "get_user_notes",
            description = "Get reading notes the user has taken. " +
                "Can filter by manga title or get all recent notes.",
            parameters = FunctionParameters(
                properties = mapOf(
                    "manga_title" to PropertyDef(
                        type = "string",
                        description = "Optional: filter notes by manga title",
                    ),
                ),
                required = emptyList(),
            ),
        ),
        FunctionDeclaration(
            name = "get_reading_history",
            description = "Get the user's recent reading history showing what they've been reading.",
            parameters = FunctionParameters(
                properties = mapOf(
                    "limit" to PropertyDef(
                        type = "integer",
                        description = "Maximum number of history items to return (default 10)",
                    ),
                ),
                required = emptyList(),
            ),
        ),
        FunctionDeclaration(
            name = "get_categories",
            description = "Get all categories the user has created to organize their library. " +
                "Categories like 'Favorites', 'To Read', 'Completed' etc. " +
                "Useful to understand how the user organizes their collection.",
            parameters = FunctionParameters(
                properties = emptyMap(),
                required = emptyList(),
            ),
        ),
        FunctionDeclaration(
            name = "get_manga_by_category",
            description = "Get all manga/manhwa/novels in a specific category. " +
                "Use after get_categories to see contents of a category like 'Favorites' or 'Completed'.",
            parameters = FunctionParameters(
                properties = mapOf(
                    "category_name" to PropertyDef(
                        type = "string",
                        description = "Name of the category to get manga from",
                    ),
                ),
                required = listOf("category_name"),
            ),
        ),
        FunctionDeclaration(
            name = "get_reading_time_stats",
            description = "Get reading time statistics showing how much time " +
                "the user has spent reading each manga. " +
                "Useful to determine favorites based on engagement.",
            parameters = FunctionParameters(
                properties = mapOf(
                    "limit" to PropertyDef(
                        type = "integer",
                        description = "Maximum number of manga to return (default 10)",
                    ),
                ),
                required = emptyList(),
            ),
        ),
        // Phase 1: High priority tools
        FunctionDeclaration(
            name = "get_pending_updates",
            description = "Get manga/manhwa/novels with new unread chapters. " +
                "Shows what the user should read next based on new content available.",
            parameters = FunctionParameters(
                properties = mapOf(
                    "limit" to PropertyDef(
                        type = "integer",
                        description = "Maximum number of manga to return (default 10)",
                    ),
                ),
                required = emptyList(),
            ),
        ),
        FunctionDeclaration(
            name = "get_tracker_scores",
            description = "Get the user's personal scores and ratings from tracking services " +
                "(like MyAnimeList, AniList). Shows what the user has rated highest.",
            parameters = FunctionParameters(
                properties = emptyMap(),
                required = emptyList(),
            ),
        ),
        FunctionDeclaration(
            name = "find_similar_manga",
            description = "Find manga/manhwa/novels similar to a given title or description " +
                "using semantic search. Great for recommendations.",
            parameters = FunctionParameters(
                properties = mapOf(
                    "query" to PropertyDef(
                        type = "string",
                        description = "Title, genre, or description to find similar content for",
                    ),
                    "limit" to PropertyDef(
                        type = "integer",
                        description = "Maximum number of similar manga to return (default 5)",
                    ),
                ),
                required = listOf("query"),
            ),
        ),
        // Phase 2: More useful tools
        FunctionDeclaration(
            name = "get_bookmarked_chapters",
            description = "Get all bookmarked chapters across the user's library. " +
                "Bookmarks indicate important or memorable moments the user saved.",
            parameters = FunctionParameters(
                properties = mapOf(
                    "manga_title" to PropertyDef(
                        type = "string",
                        description = "Optional: filter bookmarks by manga title",
                    ),
                ),
                required = emptyList(),
            ),
        ),
        FunctionDeclaration(
            name = "get_reading_streak",
            description = "Get the user's current reading streak " +
                "(consecutive days of reading) and weekly reading stats.",
            parameters = FunctionParameters(
                properties = emptyMap(),
                required = emptyList(),
            ),
        ),
        FunctionDeclaration(
            name = "get_completed_series",
            description = "Get manga/manhwa/novels that the user has finished reading. " +
                "Shows series where all chapters have been read.",
            parameters = FunctionParameters(
                properties = mapOf(
                    "limit" to PropertyDef(
                        type = "integer",
                        description = "Maximum number of series to return (default 10)",
                    ),
                ),
                required = emptyList(),
            ),
        ),
        // Phase 3: Final tools
        FunctionDeclaration(
            name = "get_dropped_series",
            description = "Get manga/manhwa/novels that the user started but hasn't read in a long time. " +
                "These might be dropped or forgotten series.",
            parameters = FunctionParameters(
                properties = mapOf(
                    "days_inactive" to PropertyDef(
                        type = "integer",
                        description = "Days since last read to consider dropped (default 30)",
                    ),
                    "limit" to PropertyDef(
                        type = "integer",
                        description = "Maximum number of series to return (default 10)",
                    ),
                ),
                required = emptyList(),
            ),
        ),
        FunctionDeclaration(
            name = "get_author_works",
            description = "Find all works by a specific author in the user's library. " +
                "Useful to find other series by a favorite author.",
            parameters = FunctionParameters(
                properties = mapOf(
                    "author_name" to PropertyDef(
                        type = "string",
                        description = "Author name to search for",
                    ),
                ),
                required = listOf("author_name"),
            ),
        ),
        // Phase 4: Advanced insight tools
        FunctionDeclaration(
            name = "get_upcoming_manga",
            description = "Get manga/manhwa/novels expected to receive new chapters soon. " +
                "Shows series with scheduled updates based on their update patterns.",
            parameters = FunctionParameters(
                properties = mapOf(
                    "limit" to PropertyDef(
                        type = "integer",
                        description = "Maximum number of series to return (default 10)",
                    ),
                ),
                required = emptyList(),
            ),
        ),
        FunctionDeclaration(
            name = "get_recently_added",
            description = "Get manga/manhwa/novels recently added to the library. " +
                "Shows when the user discovered new series.",
            parameters = FunctionParameters(
                properties = mapOf(
                    "limit" to PropertyDef(
                        type = "integer",
                        description = "Maximum number of series to return (default 10)",
                    ),
                ),
                required = emptyList(),
            ),
        ),
        FunctionDeclaration(
            name = "get_genre_breakdown",
            description = "Get a detailed breakdown of genres in the user's library. " +
                "Shows percentages and counts to understand reading preferences deeply.",
            parameters = FunctionParameters(
                properties = emptyMap(),
                required = emptyList(),
            ),
        ),
        // Phase 5: Advanced insight tools
        FunctionDeclaration(
            name = "get_reading_patterns",
            description = "Analyze when the user reads most. " +
                "Shows preferred reading times (morning/afternoon/night) and days of week.",
            parameters = FunctionParameters(
                properties = emptyMap(),
                required = emptyList(),
            ),
        ),
        FunctionDeclaration(
            name = "get_notes_by_tag",
            description = "Get reading notes filtered by tag type. " +
                "Tags include: CHARACTER, PLOT, THEORY, QUESTION, FAVORITE, IMPORTANT.",
            parameters = FunctionParameters(
                properties = mapOf(
                    "tag" to PropertyDef(
                        type = "string",
                        description = "Tag to filter by (CHARACTER, PLOT, THEORY, QUESTION, FAVORITE, IMPORTANT)",
                    ),
                    "limit" to PropertyDef(
                        type = "integer",
                        description = "Maximum notes to return (default 10)",
                    ),
                ),
                required = listOf("tag"),
            ),
        ),
        FunctionDeclaration(
            name = "predict_completion_time",
            description = "Predict when the user will finish reading a specific manga " +
                "based on their reading pace for that series.",
            parameters = FunctionParameters(
                properties = mapOf(
                    "manga_title" to PropertyDef(
                        type = "string",
                        description = "Title of the manga to predict completion for",
                    ),
                ),
                required = listOf("manga_title"),
            ),
        ),
        FunctionDeclaration(
            name = "get_monthly_summary",
            description = "Get a summary of reading activity for the current or specified month. " +
                "Shows chapters read, series started/finished, and reading time.",
            parameters = FunctionParameters(
                properties = mapOf(
                    "months_ago" to PropertyDef(
                        type = "integer",
                        description = "How many months ago (0 = current month, 1 = last month, etc.)",
                    ),
                ),
                required = emptyList(),
            ),
        ),
    )

    /**
     * Data classes for serialization to Gemini API format
     */
    @Serializable
    data class FunctionDeclaration(
        val name: String,
        val description: String,
        val parameters: FunctionParameters,
    )

    @Serializable
    data class FunctionParameters(
        val type: String = "object",
        val properties: Map<String, PropertyDef>,
        val required: List<String>,
    )

    @Serializable
    data class PropertyDef(
        val type: String,
        val description: String,
    )

    /**
     * Response structure when Gemini calls a function
     */
    @Serializable
    data class FunctionCall(
        val name: String,
        val args: Map<String, String> = emptyMap(),
    )

    /**
     * Result we send back to Gemini after executing a function
     */
    @Serializable
    data class FunctionResponse(
        val name: String,
        val response: FunctionResponseContent,
    )

    @Serializable
    data class FunctionResponseContent(
        val result: String,
    )
}
