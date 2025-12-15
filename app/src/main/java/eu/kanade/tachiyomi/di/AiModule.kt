package eu.kanade.tachiyomi.di

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import tachiyomi.data.ai.AiRepositoryImpl
import tachiyomi.domain.ai.AiPreferences
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

        addSingletonFactory {
            tachiyomi.domain.ai.GetReadingContext(
                mangaRepository = get(),
                chapterRepository = get(),
                historyRepository = get(),
                categoryRepository = get(),
            )
        }
    }
}

