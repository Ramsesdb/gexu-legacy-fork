package eu.kanade.tachiyomi.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.ai.AiChatScreen
import eu.kanade.presentation.ai.components.ApiKeySetupDialog
import eu.kanade.presentation.util.Tab
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Gexu AI Tab for bottom navigation.
 * Provides access to the AI reading companion.
 */
data object AiTab : Tab {

    // Channel to receive mangaId from reader
    private val mangaIdChannel = Channel<Long?>(Channel.CONFLATED)

    suspend fun setMangaContext(mangaId: Long?) {
        mangaIdChannel.send(mangaId)
    }

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            return TabOptions(
                index = 5u, // After "More" tab
                title = stringResource(MR.strings.label_ai_chat),
                icon = rememberVectorPainter(Icons.Outlined.AutoAwesome),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        // Scroll to top or refresh
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { AiChatScreenModel() }
        val state by screenModel.state.collectAsState()

        // Listen for manga context from reader
        LaunchedEffect(Unit) {
            mangaIdChannel.receiveAsFlow().collect { mangaId ->
                screenModel.setCurrentManga(mangaId)
            }
        }

        // Show API key setup dialog if not configured
        if (state.showApiKeySetup) {
            ApiKeySetupDialog(
                currentProvider = state.selectedProvider,
                currentApiKey = state.apiKey,
                onProviderChange = screenModel::setProvider,
                onApiKeyChange = screenModel::setApiKey,
                onDismiss = screenModel::dismissApiKeySetup,
                onConfirm = screenModel::saveApiKey,
            )
        }

        AiChatScreen(
            state = state,
            onSendMessage = screenModel::sendMessage,
            onClearConversation = screenModel::clearConversation,
            onOpenSettings = screenModel::showApiKeySetup,
        )
    }
}
