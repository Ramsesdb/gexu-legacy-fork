package tachiyomi.domain.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Central point for invalidating AI-related caches.
 * Call these methods when data changes that affects AI context.
 */
object AiCacheInvalidator {

    private var contextInvalidator: (suspend () -> Unit)? = null
    private var scope: CoroutineScope? = null

    /**
     * Initialize the invalidator with the GetReadingContext instance.
     * Should be called during app DI setup.
     */
    fun initialize(scope: CoroutineScope, invalidateContext: suspend () -> Unit) {
        this.scope = scope
        this.contextInvalidator = invalidateContext
    }

    /**
     * Call when library content changes (manga added/removed/modified).
     */
    fun onLibraryChanged() {
        scope?.launch {
            contextInvalidator?.invoke()
        }
    }

    /**
     * Call when reading history changes.
     */
    fun onHistoryChanged() {
        scope?.launch {
            contextInvalidator?.invoke()
        }
    }

    /**
     * Call when categories change.
     */
    fun onCategoriesChanged() {
        scope?.launch {
            contextInvalidator?.invoke()
        }
    }
}
