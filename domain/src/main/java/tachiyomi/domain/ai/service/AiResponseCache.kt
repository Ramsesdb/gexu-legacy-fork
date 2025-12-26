package tachiyomi.domain.ai.service

import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * In-memory LRU cache for AI responses.
 * Saves API tokens by caching responses for repeated or similar queries.
 *
 * Key features:
 * - LRU eviction at 50 entries
 * - 24h TTL (time-to-live)
 * - Context-aware keys (same query + different manga = different cache)
 */
class AiResponseCache(
    private val maxSize: Int = 50,
    private val ttlMs: Long = TimeUnit.HOURS.toMillis(24),
) {
    private data class CacheEntry(
        val response: String,
        val timestamp: Long,
        val contextHash: String,
    )

    private val cache = object : LinkedHashMap<String, CacheEntry>(
        maxSize,
        0.75f,
        true, // accessOrder = true for LRU behavior
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CacheEntry>?,
        ): Boolean {
            return size > maxSize
        }
    }

    /**
     * Get a cached response for the given query and context.
     *
     * @param query The user's query (normalized: lowercase, trimmed)
     * @param contextHash A hash representing the current context (manga ID, etc.)
     * @return The cached response, or null if not found or expired
     */
    @Synchronized
    fun getCached(query: String, contextHash: String): String? {
        val key = buildKey(query, contextHash)
        val entry = cache[key] ?: return null

        // Check TTL expiration
        if (System.currentTimeMillis() - entry.timestamp > ttlMs) {
            cache.remove(key)
            return null
        }

        return entry.response
    }

    /**
     * Cache a response for the given query and context.
     *
     * @param query The user's query (will be normalized)
     * @param contextHash A hash representing the current context
     * @param response The AI's response to cache
     */
    @Synchronized
    fun cache(query: String, contextHash: String, response: String) {
        if (query.isBlank() || response.isBlank()) return

        val key = buildKey(query, contextHash)
        cache[key] = CacheEntry(
            response = response,
            timestamp = System.currentTimeMillis(),
            contextHash = contextHash,
        )
    }

    /**
     * Clear all cached responses.
     */
    @Synchronized
    fun clear() {
        cache.clear()
    }

    /**
     * Invalidate all cache entries for a specific manga.
     * Called when manga data changes (e.g., reading progress updated).
     *
     * @param mangaId The manga ID to invalidate
     */
    @Synchronized
    fun invalidateForManga(mangaId: Long) {
        val mangaHash = "manga:$mangaId"
        val keysToRemove = cache.entries
            .filter { it.value.contextHash.contains(mangaHash) }
            .map { it.key }
            .toList() // Avoid ConcurrentModificationException

        keysToRemove.forEach { cache.remove(it) }
    }

    /**
     * Get current cache size (for debugging/statistics).
     */
    @Synchronized
    fun size(): Int = cache.size

    private fun buildKey(query: String, contextHash: String): String {
        val normalized = query.lowercase().trim()
        return "$normalized|$contextHash"
    }

    companion object {
        /**
         * Build a context hash from available context parameters.
         * Different contexts will produce different cache keys.
         */
        fun buildContextHash(
            mangaId: Long? = null,
            mangaTitle: String? = null,
            isReadingBuddyEnabled: Boolean = false,
        ): String {
            val parts = buildList {
                mangaId?.let { add("manga:$it") }
                mangaTitle?.let { add("title:${it.take(20)}") }
                if (isReadingBuddyEnabled) add("buddy:true")
            }
            val combined = parts.joinToString("|").ifEmpty { "global" }

            // Create a short hash for compactness
            return try {
                val digest = MessageDigest.getInstance("MD5")
                val bytes = digest.digest(combined.toByteArray())
                bytes.take(8).joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                combined.hashCode().toString()
            }
        }
    }
}
