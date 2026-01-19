package tachiyomi.domain.manga.model

/**
 * Tags available for categorizing reader notes.
 * @property displayName User-facing name in Spanish
 * @property emoji Emoji representation for visual display
 */
enum class NoteTag(val displayName: String, val emoji: String) {
    THEORY("Teoría", "💭"),
    IMPORTANT("Importante", "⭐"),
    QUESTION("Pregunta", "❓"),
    FAVORITE("Favorito", "❤️"),
    SPOILER("Spoiler", "🚨"),
    FUNNY("Gracioso", "😂"),
    ;

    companion object {
        /**
         * Parse a comma-separated string of tag names into a list of NoteTags.
         */
        fun fromString(tagsString: String?): List<NoteTag> {
            if (tagsString.isNullOrBlank()) return emptyList()
            return tagsString.split(",")
                .mapNotNull { tagName ->
                    entries.find { it.name == tagName.trim() }
                }
        }

        /**
         * Convert a list of NoteTags to a comma-separated string for storage.
         */
        fun toStorageString(tags: List<NoteTag>): String? {
            if (tags.isEmpty()) return null
            return tags.joinToString(",") { it.name }
        }
    }
}
