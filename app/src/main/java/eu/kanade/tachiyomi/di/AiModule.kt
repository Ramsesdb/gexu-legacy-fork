package eu.kanade.tachiyomi.di

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import tachiyomi.data.ai.AiConversationRepositoryImpl
import tachiyomi.data.ai.AiRepositoryImpl
import tachiyomi.domain.ai.AiPreferences
import tachiyomi.domain.ai.repository.AiConversationRepository
import tachiyomi.domain.ai.repository.AiRepository
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

class AiModule : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory<AiRepository> {
            AiRepositoryImpl(
                client = get<NetworkHelper>().client,
                aiPreferences = get<AiPreferences>(),
                json = Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                },
            )
        }

        addSingletonFactory<AiConversationRepository> {
            AiConversationRepositoryImpl(
                handler = get(),
            )
        }

        // Cloud embedding service (Gemini)
        addSingletonFactory<tachiyomi.data.ai.EmbeddingServiceImpl> {
            tachiyomi.data.ai.EmbeddingServiceImpl(
                client = get<NetworkHelper>().client,
                preferences = get(),
                json = Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                },
            )
        }

        // Local embedding service (USE)
        addSingletonFactory<tachiyomi.data.ai.LocalEmbeddingService> {
            val context = get<android.app.Application>()
            tachiyomi.data.ai.LocalEmbeddingService(context)
        }

        // Hybrid service (used for indexing - tries cloud first, falls back to local)
        addSingletonFactory<tachiyomi.domain.ai.service.EmbeddingService> {
            val context = get<android.app.Application>()
            tachiyomi.data.ai.HybridEmbeddingService(
                context = context,
                cloudService = get<tachiyomi.data.ai.EmbeddingServiceImpl>(),
                localService = get<tachiyomi.data.ai.LocalEmbeddingService>(),
            )
        }

        addSingletonFactory<tachiyomi.domain.ai.repository.VectorStore> {
            tachiyomi.data.ai.VectorStoreImpl(
                handler = get(),
            )
        }

        addSingletonFactory {
            tachiyomi.domain.ai.interactor.IndexLibrary(
                mangaRepository = get(),
                embeddingService = get(),
                vectorStore = get(),
            )
        }

        // SearchLibrary uses both services to match the source used for indexing
        addSingletonFactory {
            tachiyomi.domain.ai.interactor.SearchLibrary(
                mangaRepository = get(),
                cloudService = get<tachiyomi.data.ai.EmbeddingServiceImpl>(),
                localService = get<tachiyomi.data.ai.LocalEmbeddingService>(),
                vectorStore = get(),
            )
        }

        addSingletonFactory {
            tachiyomi.domain.ai.GetReadingContext(
                mangaRepository = get(),
                chapterRepository = get(),
                historyRepository = get(),
                categoryRepository = get(),
                searchLibrary = get(),
                trackRepository = get(),
                readerNotesRepository = get(),
            )
        }

        addSingletonFactory<tachiyomi.domain.ai.service.ModelDownloadManager> {
            tachiyomi.data.ai.ModelDownloadManagerImpl(
                context = get<android.app.Application>(),
                client = get<NetworkHelper>().client,
                aiPreferences = get(),
            )
        }

        // Initialize the cache invalidator with GetReadingContext
        // This allows other parts of the app to trigger cache invalidation
        val getReadingContext = get<tachiyomi.domain.ai.GetReadingContext>()
        tachiyomi.domain.ai.AiCacheInvalidator.initialize(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO),
            invalidateContext = { getReadingContext.invalidateCache() },
        )
    }
}
