package tachiyomi.domain.ai.model

/**
 * Represents a chunk of data received during AI response streaming.
 */
sealed class StreamChunk {
    /**
     * A text chunk from the AI response.
     * @param delta The incremental text content received.
     */
    data class Text(val delta: String) : StreamChunk()

    /**
     * An error occurred during streaming.
     * @param message The error message.
     */
    data class Error(val message: String) : StreamChunk()

    /**
     * Streaming has completed successfully.
     */
    data object Done : StreamChunk()
}
