package eu.kanade.tachiyomi.ui.reader.viewer.novel

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

sealed interface NovelUiItem {
    data class Header(val chapter: ReaderChapter) : NovelUiItem
    data class Content(val text: String, val chapter: ReaderChapter, val index: Int) : NovelUiItem
    data class Image(
        val url: String,
        val chapter: ReaderChapter,
        val index: Int,
        val page: ReaderPage? = null,
    ) : NovelUiItem
    data class Transition(
        val transition: eu.kanade.tachiyomi.ui.reader.model.ChapterTransition,
    ) : NovelUiItem
}
