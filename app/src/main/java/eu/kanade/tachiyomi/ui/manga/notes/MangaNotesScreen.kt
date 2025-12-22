package eu.kanade.tachiyomi.ui.manga.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.manga.MangaNotesScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.domain.manga.interactor.DeleteReaderNote
import tachiyomi.domain.manga.interactor.GetReaderNotes
import tachiyomi.domain.manga.interactor.UpdateMangaNotes
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.ReaderNote
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaNotesScreen(
    private val manga: Manga,
) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        val screenModel = rememberScreenModel { Model(manga) }
        val state by screenModel.state.collectAsState()

        MangaNotesScreen(
            state = state,
            navigateUp = navigator::pop,
            onUpdateNotes = screenModel::updateNotes,
            onDeleteReaderNote = screenModel::deleteReaderNote,
            onNavigateToPage = { chapterId, pageNumber ->
                // Navigate to reader at specific chapter and page
                context.startActivity(
                    ReaderActivity.newIntent(context, manga.id, chapterId, pageNumber),
                )
            },
        )
    }

    class Model(
        private val manga: Manga,
        private val updateMangaNotes: UpdateMangaNotes = Injekt.get(),
        private val getReaderNotes: GetReaderNotes = Injekt.get(),
        private val deleteReaderNote: DeleteReaderNote = Injekt.get(),
    ) : StateScreenModel<State>(State(manga, manga.notes ?: "", emptyList())) {

        init {
            // Load reader notes
            screenModelScope.launch {
                getReaderNotes.subscribe(manga.id).collect { notes ->
                    mutableState.update { it.copy(readerNotes = notes) }
                }
            }
        }

        fun updateNotes(content: String) {
            if (content == state.value.notes) return

            mutableState.update {
                it.copy(notes = content)
            }

            screenModelScope.launchNonCancellable {
                updateMangaNotes(manga.id, content)
            }
        }

        fun deleteReaderNote(noteId: Long) {
            screenModelScope.launchNonCancellable {
                deleteReaderNote.await(noteId)
            }
        }
    }

    @Immutable
    data class State(
        val manga: Manga,
        val notes: String,
        val readerNotes: List<ReaderNote>,
    )
}
