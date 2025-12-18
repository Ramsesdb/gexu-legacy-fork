package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.presentation.more.settings.Preference
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.domain.ai.AiPreferences
import tachiyomi.domain.ai.AiProvider
import tachiyomi.domain.ai.AiTone
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Settings screen for Gexu AI configuration.
 * Allows users to manage AI providers, personality settings, and behavior.
 */
object SettingsGexuAiScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_gexu_ai

    @Composable
    override fun getPreferences(): List<Preference> {
        val aiPreferences = remember { Injekt.get<AiPreferences>() }

        return listOf(
            getProviderGroup(aiPreferences),
            getPersonalityGroup(aiPreferences),
            getRagGroup(aiPreferences),
            getBehaviorGroup(aiPreferences),
        )
    }

    @Composable
    private fun getRagGroup(aiPreferences: AiPreferences): Preference.PreferenceGroup {
        val indexLibrary = remember { Injekt.get<tachiyomi.domain.ai.interactor.IndexLibrary>() }
        val vectorStore = remember { Injekt.get<tachiyomi.domain.ai.repository.VectorStore>() }
        val modelDownloadManager = remember { Injekt.get<tachiyomi.domain.ai.service.ModelDownloadManager>() }
        val context = androidx.compose.ui.platform.LocalContext.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()

        // State for index count
        val indexCount = androidx.compose.runtime.produceState<Long>(initialValue = 0) {
            value = try {
                vectorStore.count()
            } catch (e: Exception) {
                0
            }
        }

        // State for model availability
        val isModelAvailable = androidx.compose.runtime.produceState(initialValue = false) {
            value = try {
                modelDownloadManager.isModelAvailable()
            } catch (e: Exception) {
                false
            }
        }

        // State for download progress
        var downloadProgress by remember { mutableFloatStateOf(0f) }
        var isDownloading by remember { mutableStateOf(false) }

        return Preference.PreferenceGroup(
            title = "RAG / Contexto Avanzado",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.InfoPreference(
                    title = "Elementos indexados: ${indexCount.value}",
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "Indexar Biblioteca",
                    subtitle = "Analiza y guarda el contenido de tus mangas para que la IA los " +
                        "entienda (Puede tardar).",
                    onClick = {
                        // Start background job
                        eu.kanade.tachiyomi.data.ai.LibraryIndexingJob.startNow(context)
                        android.widget.Toast.makeText(context, "Indexado iniciado en segundo plano", android.widget.Toast.LENGTH_SHORT).show()
                    }
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "Borrar Índice",
                    subtitle = "Elimina todos los datos vectoriales generados.",
                    onClick = {
                        scope.launch {
                            vectorStore.deleteAll()
                            android.widget.Toast.makeText(context, "Índice borrado", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                ),
                // Local model section
                Preference.PreferenceItem.InfoPreference(
                    title = if (isModelAvailable.value) {
                        "✓ Modelo local disponible (offline)"
                    } else if (isDownloading) {
                        "Descargando modelo... ${(downloadProgress * 100).toInt()}%"
                    } else {
                        "Modelo local no instalado"
                    },
                ),
                if (!isModelAvailable.value && !isDownloading) {
                    Preference.PreferenceItem.TextPreference(
                        title = "Descargar Modelo Local",
                        subtitle = "~${tachiyomi.domain.ai.service.ModelDownloadManager.APPROXIMATE_SIZE_MB}MB - Permite embeddings sin internet",
                        onClick = {
                            isDownloading = true
                            scope.launch {
                                modelDownloadManager.downloadModel(
                                    onProgress = { progress -> downloadProgress = progress },
                                    onComplete = { success, error ->
                                        isDownloading = false
                                        downloadProgress = 0f
                                        val message = if (success) "Modelo descargado" else "Error: $error"
                                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    )
                } else if (isModelAvailable.value) {
                    Preference.PreferenceItem.TextPreference(
                        title = "Eliminar Modelo Local",
                        subtitle = "Libera espacio de almacenamiento",
                        onClick = {
                            scope.launch {
                                val deleted = modelDownloadManager.deleteModel()
                                val message = if (deleted) "Modelo eliminado" else "Error al eliminar"
                                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                } else {
                    Preference.PreferenceItem.InfoPreference(title = "")
                },
            ),
        )
    }

    @Composable
    private fun getProviderGroup(aiPreferences: AiPreferences): Preference.PreferenceGroup {
        val currentProvider by aiPreferences.provider().collectAsState()
        val provider = AiProvider.fromName(currentProvider)

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.ai_providers_title),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = aiPreferences.provider(),
                    entries = AiProvider.entries
                        .associateWith { it.displayName }
                        .mapKeys { it.key.name }
                        .toImmutableMap(),
                    title = stringResource(MR.strings.ai_provider),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = aiPreferences.apiKey(),
                    title = stringResource(MR.strings.ai_api_key),
                    subtitle = when (provider) {
                        AiProvider.OPENAI -> stringResource(MR.strings.ai_help_openai)
                        AiProvider.GEMINI -> stringResource(MR.strings.ai_help_gemini)
                        AiProvider.ANTHROPIC -> stringResource(MR.strings.ai_help_anthropic)
                        AiProvider.OPENROUTER -> stringResource(MR.strings.ai_help_openrouter)
                        AiProvider.CUSTOM -> stringResource(MR.strings.ai_help_custom)
                    },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = aiPreferences.model(),
                    title = stringResource(MR.strings.ai_select_model),
                    subtitle = when (provider) {
                        AiProvider.OPENAI -> "e.g., gpt-4o, gpt-4o-mini"
                        AiProvider.GEMINI -> "e.g., gemini-1.5-flash, gemini-1.5-pro"
                        AiProvider.ANTHROPIC -> "e.g., claude-3-haiku-20240307"
                        AiProvider.OPENROUTER -> "e.g., openai/gpt-4o"
                        AiProvider.CUSTOM -> "Model name from your provider"
                    },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = aiPreferences.customBaseUrl(),
                    title = "Custom Base URL",
                    subtitle = "Only used when provider is Custom",
                ),
            ),
        )
    }

    @Composable
    private fun getPersonalityGroup(aiPreferences: AiPreferences): Preference.PreferenceGroup {
        // Use collectAsState for reactive slider updates
        val temperatureValue by aiPreferences.temperature().collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.ai_personality_title),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = aiPreferences.tone(),
                    entries = AiTone.entries
                        .associateWith { it.displayName }
                        .mapKeys { it.key.name }
                        .toImmutableMap(),
                    title = stringResource(MR.strings.ai_tone),
                    subtitle = stringResource(MR.strings.ai_personality_summary),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = aiPreferences.customInstructions(),
                    title = stringResource(MR.strings.ai_custom_instructions),
                    subtitle = stringResource(MR.strings.ai_custom_instructions_hint),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = (temperatureValue * 10).toInt(),
                    valueRange = 0..10,
                    title = stringResource(MR.strings.ai_temperature),
                    subtitle = stringResource(MR.strings.ai_temperature_balanced),
                    onValueChanged = {
                        aiPreferences.temperature().set(it / 10f)
                    },
                ),
            ),
        )
    }

    @Composable
    private fun getBehaviorGroup(aiPreferences: AiPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.ai_behavior_title),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = aiPreferences.includeContext(),
                    title = stringResource(MR.strings.ai_include_context),
                    subtitle = stringResource(MR.strings.ai_include_context_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = aiPreferences.antiSpoilerMode(),
                    title = stringResource(MR.strings.ai_anti_spoiler),
                    subtitle = stringResource(MR.strings.ai_anti_spoiler_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = aiPreferences.includeDescription(),
                    title = stringResource(MR.strings.ai_synopsis),
                    subtitle = stringResource(MR.strings.ai_synopsis_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = aiPreferences.includeHistory(),
                    title = stringResource(MR.strings.ai_include_history),
                    subtitle = stringResource(MR.strings.ai_include_history_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = aiPreferences.persistConversations(),
                    title = stringResource(MR.strings.ai_persist_conversations),
                    subtitle = stringResource(MR.strings.ai_persist_conversations_summary),
                ),
            ),
        )
    }
}
