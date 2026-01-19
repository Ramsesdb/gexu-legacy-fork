package eu.kanade.tachiyomi.ui.reader

import android.annotation.SuppressLint
import android.app.assist.AssistContent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.LAYER_TYPE_HARDWARE
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.transition.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.hippo.unifile.UniFile
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.reader.ContextualNotePopup
import eu.kanade.presentation.reader.DisplayRefreshHost
import eu.kanade.presentation.reader.OrientationSelectDialog
import eu.kanade.presentation.reader.QuickNoteDialog
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.presentation.reader.ReaderPageActionsDialog
import eu.kanade.presentation.reader.ReaderPageIndicator
import eu.kanade.presentation.reader.ReadingModeSelectDialog
import eu.kanade.presentation.reader.appbars.ReaderAppBars
import eu.kanade.presentation.reader.settings.ReaderSettingsDialog
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.AddToLibraryFirst
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Error
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Success
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.isNightMode
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import eu.kanade.presentation.reader.LongPressContext as ComposeLongPressContext

class ReaderActivity : BaseActivity() {

    companion object {
        fun newIntent(context: Context, mangaId: Long?, chapterId: Long?, page: Int? = null): Intent {
            return Intent(context, ReaderActivity::class.java).apply {
                putExtra("manga", mangaId)
                putExtra("chapter", chapterId)
                if (page != null) {
                    putExtra("page", page)
                }
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    private val readerPreferences = Injekt.get<ReaderPreferences>()
    private val preferences = Injekt.get<BasePreferences>()

    lateinit var binding: ReaderActivityBinding

    val viewModel by viewModels<ReaderViewModel>()
    private var assistUrl: String? = null

    /**
     * Configuration at reader level, like background color or forced orientation.
     */
    private var config: ReaderConfig? = null

    private var menuToggleToast: Toast? = null
    private var readingModeToast: Toast? = null
    private val displayRefreshHost = DisplayRefreshHost()

    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }

    private var loadingIndicator: ReaderProgressIndicator? = null

    var isScrollingThroughPages = false
        private set

    // Flag to prevent key events from navigating the reader when AI chat is open
    // Using MutableStateFlow so NovelViewer can observe it reactively
    val isAiChatOpenFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    var isAiChatOpen: Boolean
        get() = isAiChatOpenFlow.value
        set(value) {
            isAiChatOpenFlow.value = value
        }

    /**
     * Capture the currently visible viewport of the reader as a Bitmap asynchronously.
     * Hides all overlays, waits for the next frame to be drawn, then captures with PixelCopy.
     * The callback is invoked on the main thread with the result.
     */
    fun captureViewportAsync(callback: (android.graphics.Bitmap?) -> Unit) {
        val view = binding.viewerContainer
        if (view.width == 0 || view.height == 0) {
            callback(null)
            return
        }

        // For older devices, use simple synchronous capture
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            val bitmap = try {
                val bmp = android.graphics.Bitmap.createBitmap(
                    view.width,
                    view.height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
                val canvas = android.graphics.Canvas(bmp)
                view.draw(canvas)
                bmp
            } catch (e: Exception) {
                null
            }
            callback(bitmap)
            return
        }

        // Save overlay visibility states
        val composeOverlay = binding.composeOverlay
        val navigationOverlay = binding.navigationOverlay
        val wasComposeVisible = composeOverlay.visibility == android.view.View.VISIBLE
        val wasNavVisible = navigationOverlay.visibility == android.view.View.VISIBLE

        // Hide all overlays
        composeOverlay.visibility = android.view.View.GONE
        navigationOverlay.visibility = android.view.View.GONE

        // Request layout immediately
        composeOverlay.requestLayout()
        window.decorView.requestLayout()

        // Wait for THREE frames to ensure visibility change is fully rendered
        // Frame 1: Layout pass happens
        // Frame 2: Draw pass happens
        // Frame 3: Buffer swap complete
        window.decorView.postOnAnimation {
            window.decorView.postOnAnimation {
                window.decorView.postOnAnimation {
                    // Now the visibility change should be rendered
                    performPixelCopy(view) { bitmap ->
                        // Restore overlays on main thread
                        if (wasComposeVisible) {
                            composeOverlay.visibility = android.view.View.VISIBLE
                        }
                        if (wasNavVisible) {
                            navigationOverlay.visibility = android.view.View.VISIBLE
                        }
                        callback(bitmap)
                    }
                }
            }
        }
    }

    /**
     * Perform PixelCopy asynchronously. Callback is invoked on main thread.
     */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private fun performPixelCopy(
        view: android.view.View,
        callback: (android.graphics.Bitmap?) -> Unit,
    ) {
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(
                view.width,
                view.height,
                android.graphics.Bitmap.Config.ARGB_8888,
            )

            val locationOnScreen = IntArray(2)
            view.getLocationOnScreen(locationOnScreen)

            val rect = android.graphics.Rect(
                locationOnScreen[0],
                locationOnScreen[1],
                locationOnScreen[0] + view.width,
                locationOnScreen[1] + view.height,
            )

            // Use main handler for callback - no blocking needed
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

            android.view.PixelCopy.request(
                window,
                rect,
                bitmap,
                { copyResult ->
                    if (copyResult == android.view.PixelCopy.SUCCESS) {
                        callback(bitmap)
                    } else {
                        callback(null)
                    }
                },
                mainHandler,
            )
        } catch (e: Exception) {
            callback(null)
        }
    }

    /**
     * Called when the activity is created. Initializes the presenter and configuration.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        registerSecureActivity(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                R.anim.shared_axis_x_push_enter,
                R.anim.shared_axis_x_push_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_push_enter, R.anim.shared_axis_x_push_exit)
        }

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        super.onCreate(savedInstanceState)

        binding = ReaderActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.setComposeOverlay()

        if (viewModel.needsInit()) {
            val manga = intent.extras?.getLong("manga", -1) ?: -1L
            val chapter = intent.extras?.getLong("chapter", -1) ?: -1L
            if (manga == -1L || chapter == -1L) {
                finish()
                return
            }
            NotificationReceiver.dismissNotification(this, manga.hashCode(), Notifications.ID_NEW_CHAPTERS)

            lifecycleScope.launchNonCancellable {
                val page = intent.extras?.getInt("page")
                val initResult = viewModel.init(manga, chapter, page)
                if (!initResult.getOrDefault(false)) {
                    val exception = initResult.exceptionOrNull() ?: IllegalStateException("Unknown err")
                    withUIContext {
                        setInitialChapterError(exception)
                    }
                }
            }
        }

        config = ReaderConfig()
        setMenuVisibility(viewModel.state.value.menuVisible)

        // Finish when incognito mode is disabled
        preferences.incognitoMode().changes()
            .drop(1)
            .onEach { if (!it) finish() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.isLoadingAdjacentChapter }
            .distinctUntilChanged()
            .onEach(::setProgressDialog)
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.manga }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { updateViewer() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.viewerChapters }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach(::setChapters)
            .launchIn(lifecycleScope)

        viewModel.eventFlow
            .onEach { event ->
                when (event) {
                    ReaderViewModel.Event.ReloadViewerChapters -> {
                        viewModel.state.value.viewerChapters?.let(::setChapters)
                    }
                    ReaderViewModel.Event.PageChanged -> {
                        displayRefreshHost.flash()
                    }
                    is ReaderViewModel.Event.SetOrientation -> {
                        setOrientation(event.orientation)
                    }
                    is ReaderViewModel.Event.SavedImage -> {
                        onSaveImageResult(event.result)
                    }
                    is ReaderViewModel.Event.ShareImage -> {
                        onShareImageResult(event.uri, event.page)
                    }
                    is ReaderViewModel.Event.CopyImage -> {
                        onCopyImageResult(event.uri)
                    }
                    is ReaderViewModel.Event.SetCoverResult -> {
                        onSetAsCoverResult(event.result)
                    }
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun ReaderActivityBinding.setComposeOverlay(): Unit = composeOverlay.setComposeContent {
        val state by viewModel.state.collectAsState()
        val showPageNumber by readerPreferences.showPageNumber().collectAsState()
        val settingsScreenModel = remember {
            ReaderSettingsScreenModel(
                readerState = viewModel.state,
                onChangeReadingMode = viewModel::setMangaReadingMode,
                onChangeOrientation = viewModel::setMangaOrientationType,
            )
        }
        var showAiChat by remember { mutableStateOf(false) }

        Box(modifier = Modifier.fillMaxSize()) {
            if (!state.menuVisible && showPageNumber) {
                ReaderPageIndicator(
                    currentPage = state.currentPage,
                    totalPages = state.totalPages,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
            }

            ContentOverlay(state = state)

            AppBars(
                state = state,
                onOpenAiChat = {
                    showAiChat = true
                    isAiChatOpen = true
                },
                onEditNotes = viewModel::openNotesDialog,
                onAddQuickNote = viewModel::openQuickNoteDialog,
            )
        }

        // Gexu AI Overlay - Separated from main content to avoid recomposition issues
        AiChatOverlayContent(
            visible = showAiChat,
            mangaTitle = state.manga?.title,
            onDismiss = {
                showAiChat = false
                isAiChatOpen = false
            },
        )

        val onDismissRequest = viewModel::closeDialog
        when (state.dialog) {
            is ReaderViewModel.Dialog.Loading -> {
                AlertDialog(
                    onDismissRequest = {},
                    confirmButton = {},
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(MR.strings.loading))
                        }
                    },
                )
            }
            is ReaderViewModel.Dialog.Settings -> {
                ReaderSettingsDialog(
                    onDismissRequest = onDismissRequest,
                    onShowMenus = { setMenuVisibility(true) },
                    onHideMenus = { setMenuVisibility(false) },
                    screenModel = settingsScreenModel,
                )
            }
            is ReaderViewModel.Dialog.ReadingModeSelect -> {
                ReadingModeSelectDialog(
                    onDismissRequest = onDismissRequest,
                    screenModel = settingsScreenModel,
                    onChange = { stringRes ->
                        menuToggleToast?.cancel()
                        if (!readerPreferences.showReadingMode().get()) {
                            menuToggleToast = toast(stringRes)
                        }
                    },
                )
            }
            is ReaderViewModel.Dialog.OrientationModeSelect -> {
                OrientationSelectDialog(
                    onDismissRequest = onDismissRequest,
                    screenModel = settingsScreenModel,
                    onChange = { stringRes ->
                        menuToggleToast?.cancel()
                        menuToggleToast = toast(stringRes)
                    },
                )
            }
            is ReaderViewModel.Dialog.PageActions -> {
                ReaderPageActionsDialog(
                    onDismissRequest = onDismissRequest,
                    onSetAsCover = viewModel::setAsCover,
                    onShare = viewModel::shareImage,
                    onSave = viewModel::saveImage,
                )
            }
            is ReaderViewModel.Dialog.Notes -> {
                ReaderNotesDialog(
                    initialNotes = state.manga?.notes,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { notes ->
                        viewModel.updateMangaNotes(notes)
                        onDismissRequest()
                    },
                )
            }
            is ReaderViewModel.Dialog.QuickNote -> {
                val dialog = state.dialog as ReaderViewModel.Dialog.QuickNote
                QuickNoteDialog(
                    chapterName = dialog.chapterName,
                    chapterNumber = dialog.chapterNumber,
                    pageNumber = dialog.pageNumber,
                    onDismiss = onDismissRequest,
                    onSave = viewModel::saveReaderNote,
                )
            }
            null -> {}
        }

        // Contextual long press popup for notes/bookmark/copy
        val longPressContext = state.longPressContext
        ContextualNotePopup(
            visible = longPressContext != null,
            context = longPressContext?.let { ctx ->
                ComposeLongPressContext(
                    x = ctx.x,
                    y = ctx.y,
                    chapterNumber = ctx.chapterNumber,
                    chapterName = ctx.chapterName,
                    pageNumber = ctx.pageNumber,
                )
            },
            onDismiss = viewModel::dismissLongPressPopup,
            onSaveNote = { noteText, tags -> viewModel.saveNoteFromContextual(noteText, tags) },
            onToggleBookmark = viewModel::toggleChapterBookmark,
            onCopyPage = { /* TODO: Implement copy page */ },
            onAskAi = {
                showAiChat = true
                isAiChatOpen = true
            },
        )
    }

    /**
     * Called when the activity is destroyed. Cleans up the viewer, configuration and any view.
     */
    override fun onDestroy() {
        super.onDestroy()
        viewModel.state.value.viewer?.destroy()
        config = null
        menuToggleToast?.cancel()
        readingModeToast?.cancel()
    }

    override fun onPause() {
        lifecycleScope.launchNonCancellable {
            viewModel.updateHistory()
        }
        super.onPause()
    }

    /**
     * Set menu visibility again on activity resume to apply immersive mode again if needed.
     * Helps with rotations.
     */
    override fun onResume() {
        super.onResume()
        viewModel.restartReadTimer()
        setMenuVisibility(viewModel.state.value.menuVisible)
    }

    /**
     * Called when the window focus changes. It sets the menu visibility to the last known state
     * to apply immersive mode again if needed.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setMenuVisibility(viewModel.state.value.menuVisible)
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        assistUrl?.let { outContent.webUri = it.toUri() }
    }

    /**
     * Called when the user clicks the back key or the button on the toolbar. The call is
     * delegated to the presenter.
     */
    override fun finish() {
        viewModel.onActivityFinish()
        super.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                R.anim.shared_axis_x_pop_enter,
                R.anim.shared_axis_x_pop_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_pop_enter, R.anim.shared_axis_x_pop_exit)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // Don't handle chapter navigation keys when AI chat is open
        if (isAiChatOpen) {
            return super.onKeyUp(keyCode, event)
        }
        if (keyCode == KeyEvent.KEYCODE_N) {
            loadNextChapter()
            return true
        } else if (keyCode == KeyEvent.KEYCODE_P) {
            loadPreviousChapter()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Dispatches a key event. If the viewer doesn't handle it, call the default implementation.
     * Skip viewer handling when AI chat is open to prevent navigation while typing.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Don't let viewer handle key events when AI chat is open
        if (isAiChatOpen) {
            return super.dispatchKeyEvent(event)
        }
        val handled = viewModel.state.value.viewer?.handleKeyEvent(event) ?: false
        return handled || super.dispatchKeyEvent(event)
    }

    /**
     * Dispatches a generic motion event. If the viewer doesn't handle it, call the default
     * implementation.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = viewModel.state.value.viewer?.handleGenericMotionEvent(event) ?: false
        return handled || super.dispatchGenericMotionEvent(event)
    }

    @Composable
    private fun ContentOverlay(state: ReaderViewModel.State) {
        val flashOnPageChange by readerPreferences.flashOnPageChange().collectAsState()

        val colorOverlayEnabled by readerPreferences.colorFilter().collectAsState()
        val colorOverlay by readerPreferences.colorFilterValue().collectAsState()
        val colorOverlayMode by readerPreferences.colorFilterMode().collectAsState()
        val colorOverlayBlendMode = remember(colorOverlayMode) {
            ReaderPreferences.ColorFilterMode.getOrNull(colorOverlayMode)?.second
        }

        ReaderContentOverlay(
            brightness = state.brightnessOverlayValue,
            color = colorOverlay.takeIf { colorOverlayEnabled },
            colorBlendMode = colorOverlayBlendMode,
        )

        if (flashOnPageChange) {
            DisplayRefreshHost(hostState = displayRefreshHost)
        }
    }

    @Composable
    fun AppBars(
        state: ReaderViewModel.State,
        onOpenAiChat: () -> Unit = {},
        onEditNotes: () -> Unit = {},
        onAddQuickNote: () -> Unit = {},
    ) {
        if (!ifSourcesLoaded()) {
            return
        }

        val isHttpSource = viewModel.getSource() is HttpSource

        val cropBorderPaged by readerPreferences.cropBorders().collectAsState()
        val cropBorderWebtoon by readerPreferences.cropBordersWebtoon().collectAsState()
        val isPagerType = ReadingMode.isPagerType(viewModel.getMangaReadingMode())
        val cropEnabled = if (isPagerType) cropBorderPaged else cropBorderWebtoon

        // Check network state for AI button visibility
        val context = LocalContext.current
        val isOnline by produceState(initialValue = false) {
            val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            value = connectivityManager?.activeNetworkInfo?.isConnected == true
        }

        ReaderAppBars(
            visible = state.menuVisible,

            mangaTitle = state.manga?.title,
            chapterTitle = state.currentChapter?.chapter?.name,
            navigateUp = onBackPressedDispatcher::onBackPressed,
            onClickTopAppBar = ::openMangaScreen,
            bookmarked = state.bookmarked,
            onToggleBookmarked = viewModel::toggleChapterBookmark,
            onOpenInWebView = ::openChapterInWebView.takeIf { isHttpSource },
            onOpenInBrowser = ::openChapterInBrowser.takeIf { isHttpSource },
            onShare = ::shareChapter.takeIf { isHttpSource },

            viewer = state.viewer,
            onNextChapter = ::loadNextChapter,
            enabledNext = state.viewerChapters?.nextChapter != null,
            onPreviousChapter = ::loadPreviousChapter,
            enabledPrevious = state.viewerChapters?.prevChapter != null,
            currentPage = state.currentPage,
            totalPages = state.totalPages,
            onPageIndexChange = {
                isScrollingThroughPages = true
                moveToPageIndex(it)
            },

            readingMode = ReadingMode.fromPreference(
                viewModel.getMangaReadingMode(resolveDefault = false),
            ),
            onClickReadingMode = viewModel::openReadingModeSelectDialog,
            orientation = ReaderOrientation.fromPreference(
                viewModel.getMangaOrientation(resolveDefault = false),
            ),
            onClickOrientation = viewModel::openOrientationModeSelectDialog,
            cropEnabled = cropEnabled,
            onClickCropBorder = {
                val enabled = viewModel.toggleCropBorders()
                menuToggleToast?.cancel()
                menuToggleToast = toast(if (enabled) MR.strings.on else MR.strings.off)
            },
            onClickSettings = viewModel::openSettingsDialog,
            onAiClick = onOpenAiChat,
            onEditNotes = onEditNotes,
            onAddQuickNote = onAddQuickNote,
            isOnline = isOnline,
        )
    }

    /**
     * Sets the visibility of the menu according to [visible].
     */
    private fun setMenuVisibility(visible: Boolean) {
        viewModel.showMenus(visible)
        if (visible) {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        } else if (readerPreferences.fullscreen().get()) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Called from the presenter when a manga is ready. Used to instantiate the appropriate viewer.
     */
    private fun updateViewer() {
        val prevViewer = viewModel.state.value.viewer
        val newViewer = ReadingMode.toViewer(viewModel.getMangaReadingMode(), this)

        if (window.sharedElementEnterTransition is MaterialContainerTransform) {
            // Wait until transition is complete to avoid crash on API 26
            window.sharedElementEnterTransition.doOnEnd {
                setOrientation(viewModel.getMangaOrientation())
            }
        } else {
            setOrientation(viewModel.getMangaOrientation())
        }

        // Destroy previous viewer if there was one
        if (prevViewer != null) {
            prevViewer.destroy()
            binding.viewerContainer.removeAllViews()
        }
        viewModel.onViewerLoaded(newViewer)
        updateViewerInset(readerPreferences.fullscreen().get(), readerPreferences.drawUnderCutout().get())
        binding.viewerContainer.addView(newViewer.getView())

        if (readerPreferences.showReadingMode().get()) {
            showReadingModeToast(viewModel.getMangaReadingMode())
        }

        loadingIndicator = ReaderProgressIndicator(this)
        binding.readerContainer.addView(loadingIndicator)

        startPostponedEnterTransition()
    }

    private fun openMangaScreen() {
        viewModel.manga?.id?.let { id ->
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = Constants.SHORTCUT_MANGA
                    putExtra(Constants.MANGA_EXTRA, id)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
    }

    private fun openChapterInWebView() {
        val manga = viewModel.manga ?: return
        val source = viewModel.getSource() ?: return
        assistUrl?.let {
            val intent = WebViewActivity.newIntent(this@ReaderActivity, it, source.id, manga.title)
            startActivity(intent)
        }
    }

    private fun openChapterInBrowser() {
        assistUrl?.let {
            openInBrowser(it.toUri(), forceDefaultBrowser = false)
        }
    }

    private fun shareChapter() {
        assistUrl?.let {
            val intent = it.toUri().toShareIntent(this, type = "text/plain")
            startActivity(Intent.createChooser(intent, stringResource(MR.strings.action_share)))
        }
    }

    private fun showReadingModeToast(mode: Int) {
        try {
            readingModeToast?.cancel()
            readingModeToast = toast(ReadingMode.fromPreference(mode).stringRes)
        } catch (_: ArrayIndexOutOfBoundsException) {
            logcat(LogPriority.ERROR) { "Unknown reading mode: $mode" }
        }
    }

    /**
     * Called from the presenter whenever a new [viewerChapters] have been set. It delegates the
     * method to the current viewer, but also set the subtitle on the toolbar, and
     * hides or disables the reader prev/next buttons if there's a prev or next chapter
     */
    @SuppressLint("RestrictedApi")
    private fun setChapters(viewerChapters: ViewerChapters) {
        binding.readerContainer.removeView(loadingIndicator)
        viewModel.state.value.viewer?.setChapters(viewerChapters)

        lifecycleScope.launchIO {
            viewModel.getChapterUrl()?.let { url ->
                assistUrl = url
            }
        }

        // Phase 2: Handle initial page navigation from intent
        val initialPage = intent.getIntExtra("page", -1)
        if (initialPage != -1) {
            // Remove the extra so it doesn't trigger again on rotation/reload
            intent.removeExtra("page")
            lifecycleScope.launch {
                // TOC pages are usually 1-indexed (e.g. page 5 means the 5th page), viewer uses 0-indexed?
                // Usually PDF pages are 1-indexed in UI but 0-indexed in list access.
                // let's assume the passed 'page' is the index if it came from PDF TOC which often gives page index or label.
                // However, PDF TOC usually gives page index (0-based) or page number (1-based).
                // The 'pdfToc' model likely has 'pageNumber'.
                // I'll assume 0-indexed for now or verify later.
                // But wait, standard is often 0-indexed in code.
                // Let's assume the passed page is the 0-based index.
                moveToPageIndex(initialPage)
            }
        }
    }

    /**
     * Called from the presenter if the initial load couldn't load the pages of the chapter. In
     * this case the activity is closed and a toast is shown to the user.
     */
    private fun setInitialChapterError(error: Throwable) {
        logcat(LogPriority.ERROR, error)
        finish()
        toast(error.message)
    }

    /**
     * Called from the presenter whenever it's loading the next or previous chapter. It shows or
     * dismisses a non-cancellable dialog to prevent user interaction according to the value of
     * [show]. This is only used when the next/previous buttons on the toolbar are clicked; the
     * other cases are handled with chapter transitions on the viewers and chapter preloading.
     */
    private fun setProgressDialog(show: Boolean) {
        if (show) {
            viewModel.showLoadingDialog()
        } else {
            viewModel.closeDialog()
        }
    }

    /**
     * Moves the viewer to the given page [index]. It does nothing if the viewer is null or the
     * page is not found.
     */
    private fun moveToPageIndex(index: Int) {
        val viewer = viewModel.state.value.viewer ?: return
        val currentChapter = viewModel.state.value.currentChapter ?: return
        val page = currentChapter.pages?.getOrNull(index) ?: return
        viewer.moveToPage(page)
    }

    /**
     * Tells the presenter to load the next chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    private fun loadNextChapter() {
        lifecycleScope.launch {
            viewModel.loadNextChapter()
            moveToPageIndex(0)
        }
    }

    /**
     * Tells the presenter to load the previous chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    private fun loadPreviousChapter() {
        lifecycleScope.launch {
            viewModel.loadPreviousChapter()
            moveToPageIndex(0)
        }
    }

    /**
     * Called from the viewer whenever a [page] is marked as active. It updates the values of the
     * bottom menu and delegates the change to the presenter.
     */
    fun onPageSelected(page: ReaderPage) {
        viewModel.onPageSelected(page)
    }

    /**
     * Called from the viewer whenever a [page] is long clicked. A bottom sheet with a list of
     * actions to perform is shown.
     */
    fun onPageLongTap(page: ReaderPage) {
        viewModel.openPageDialog(page)
    }

    /**
     * Called from the viewer when the given [chapter] should be preloaded. It should be called when
     * the viewer is reaching the beginning or end of a chapter or the transition page is active.
     */
    fun requestPreloadChapter(chapter: ReaderChapter) {
        lifecycleScope.launchIO { viewModel.preload(chapter) }
    }

    /**
     * Called from the viewer to toggle the visibility of the menu. It's implemented on the
     * viewer because each one implements its own touch and key events.
     */
    fun toggleMenu() {
        setMenuVisibility(!viewModel.state.value.menuVisible)
    }

    /**
     * Called from the viewer to show the menu.
     */
    fun showMenu() {
        if (!viewModel.state.value.menuVisible) {
            setMenuVisibility(true)
        }
    }

    /**
     * Called from the viewer to hide the menu.
     */
    fun hideMenu() {
        if (viewModel.state.value.menuVisible) {
            setMenuVisibility(false)
        }
    }

    /**
     * Called from the presenter when a page is ready to be shared. It shows Android's default
     * sharing tool.
     */
    private fun onShareImageResult(uri: Uri, page: ReaderPage) {
        val manga = viewModel.manga ?: return
        val chapter = page.chapter.chapter

        val intent = uri.toShareIntent(
            context = applicationContext,
            message = stringResource(MR.strings.share_page_info, manga.title, chapter.name, page.number),
        )
        startActivity(Intent.createChooser(intent, stringResource(MR.strings.action_share)))
    }

    private fun onCopyImageResult(uri: Uri) {
        val clipboardManager = applicationContext.getSystemService<ClipboardManager>() ?: return
        val clipData = ClipData.newUri(applicationContext.contentResolver, "", uri)
        clipboardManager.setPrimaryClip(clipData)
    }

    /**
     * Called from the presenter when a page is saved or fails. It shows a message or logs the
     * event depending on the [result].
     */
    private fun onSaveImageResult(result: ReaderViewModel.SaveImageResult) {
        when (result) {
            is ReaderViewModel.SaveImageResult.Success -> {
                toast(MR.strings.picture_saved)
            }
            is ReaderViewModel.SaveImageResult.Error -> {
                logcat(LogPriority.ERROR, result.error)
            }
        }
    }

    /**
     * Called from the presenter when a page is set as cover or fails. It shows a different message
     * depending on the [result].
     */
    private fun onSetAsCoverResult(result: ReaderViewModel.SetAsCoverResult) {
        toast(
            when (result) {
                Success -> MR.strings.cover_updated
                AddToLibraryFirst -> MR.strings.notification_first_add_to_library
                Error -> MR.strings.notification_cover_update_failed
            },
        )
    }

    /**
     * Forces the user preferred [orientation] on the activity.
     */
    private fun setOrientation(orientation: Int) {
        val newOrientation = ReaderOrientation.fromPreference(orientation)
        if (newOrientation.flag != requestedOrientation) {
            requestedOrientation = newOrientation.flag
        }
    }

    /**
     * Updates viewer inset depending on fullscreen reader preferences.
     */
    private fun updateViewerInset(fullscreen: Boolean, drawUnderCutout: Boolean) {
        if (!::binding.isInitialized) return
        val view = binding.viewerContainer

        view.applyInsetsPadding(ViewCompat.getRootWindowInsets(view), fullscreen, drawUnderCutout)
        ViewCompat.setOnApplyWindowInsetsListener(view) { view, windowInsets ->
            view.applyInsetsPadding(windowInsets, fullscreen, drawUnderCutout)
            windowInsets
        }
    }

    private fun View.applyInsetsPadding(
        windowInsets: WindowInsetsCompat?,
        fullscreen: Boolean,
        drawUnderCutout: Boolean,
    ) {
        val insets = when {
            !fullscreen -> windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars())
            !drawUnderCutout -> windowInsets?.getInsets(WindowInsetsCompat.Type.displayCutout())
            else -> null
        }
            ?: Insets.NONE

        setPadding(insets.left, insets.top, insets.right, insets.bottom)
    }

    /**
     * Class that handles the user preferences of the reader.
     */
    private inner class ReaderConfig {

        private fun getCombinedPaint(grayscale: Boolean, invertedColors: Boolean): Paint {
            return Paint().apply {
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix().apply {
                        if (grayscale) {
                            setSaturation(0f)
                        }
                        if (invertedColors) {
                            postConcat(
                                ColorMatrix(
                                    floatArrayOf(
                                        -1f, 0f, 0f, 0f, 255f,
                                        0f, -1f, 0f, 0f, 255f,
                                        0f, 0f, -1f, 0f, 255f,
                                        0f, 0f, 0f, 1f, 0f,
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
        }

        private val grayBackgroundColor = Color.rgb(0x20, 0x21, 0x25)

        /*
         * Initializes the reader subscriptions.
         */
        init {
            readerPreferences.readerTheme().changes()
                .onEach { theme ->
                    binding.readerContainer.setBackgroundColor(
                        when (theme) {
                            0 -> Color.WHITE
                            2 -> grayBackgroundColor
                            3 -> automaticBackgroundColor()
                            else -> Color.BLACK
                        },
                    )
                }
                .launchIn(lifecycleScope)

            preferences.displayProfile().changes()
                .onEach { setDisplayProfile(it) }
                .launchIn(lifecycleScope)

            readerPreferences.keepScreenOn().changes()
                .onEach(::setKeepScreenOn)
                .launchIn(lifecycleScope)

            readerPreferences.customBrightness().changes()
                .onEach(::setCustomBrightness)
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.grayscale().changes(),
                readerPreferences.invertedColors().changes(),
            ) { grayscale, invertedColors -> grayscale to invertedColors }
                .onEach { (grayscale, invertedColors) ->
                    setLayerPaint(grayscale, invertedColors)
                }
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.fullscreen().changes(),
                readerPreferences.drawUnderCutout().changes(),
            ) { fullscreen, drawUnderCutout -> fullscreen to drawUnderCutout }
                .onEach { (fullscreen, drawUnderCutout) ->
                    updateViewerInset(fullscreen, drawUnderCutout)
                }
                .launchIn(lifecycleScope)
        }

        /**
         * Picks background color for [ReaderActivity] based on light/dark theme preference
         */
        private fun automaticBackgroundColor(): Int {
            return if (baseContext.isNightMode()) {
                grayBackgroundColor
            } else {
                Color.WHITE
            }
        }

        /**
         * Sets the display profile to [path].
         */
        private fun setDisplayProfile(path: String) {
            val file = UniFile.fromUri(baseContext, path.toUri())
            if (file != null && file.exists()) {
                val inputStream = file.openInputStream()
                val outputStream = ByteArrayOutputStream()
                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                val data = outputStream.toByteArray()
                SubsamplingScaleImageView.setDisplayProfile(data)
                TachiyomiImageDecoder.displayProfile = data
            }
        }

        /**
         * Sets the keep screen on mode according to [enabled].
         */
        private fun setKeepScreenOn(enabled: Boolean) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /**
         * Sets the custom brightness overlay according to [enabled].
         */
        private fun setCustomBrightness(enabled: Boolean) {
            if (enabled) {
                readerPreferences.customBrightnessValue().changes()
                    .sample(100)
                    .onEach(::setCustomBrightnessValue)
                    .launchIn(lifecycleScope)
            } else {
                setCustomBrightnessValue(0)
            }
        }

        /**
         * Sets the brightness of the screen. Range is [-75, 100].
         * From -75 to -1 a semi-transparent black view is overlaid with the minimum brightness.
         * From 1 to 100 it sets that value as brightness.
         * 0 sets system brightness and hides the overlay.
         */
        private fun setCustomBrightnessValue(value: Int) {
            // Calculate and set reader brightness.
            val readerBrightness = when {
                value > 0 -> {
                    value / 100f
                }
                value < 0 -> {
                    0.01f
                }
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window.attributes = window.attributes.apply { screenBrightness = readerBrightness }

            viewModel.setBrightnessOverlayValue(value)
        }
        private fun setLayerPaint(grayscale: Boolean, invertedColors: Boolean) {
            val paint = if (grayscale || invertedColors) getCombinedPaint(grayscale, invertedColors) else null
            binding.viewerContainer.setLayerType(LAYER_TYPE_HARDWARE, paint)
        }
    }

    @Composable
    private fun AiChatOverlayContent(
        visible: Boolean,
        mangaTitle: String?,
        onDismiss: () -> Unit,
    ) {
        // Use remember to create a simple state holder for the chat
        var messages by remember { mutableStateOf(emptyList<tachiyomi.domain.ai.model.ChatMessage>()) }
        var isLoading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var attachedImage by remember { mutableStateOf<String?>(null) }
        var showVisualSelection by remember { mutableStateOf(false) }
        var capturedBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

        // Get AI repository
        val aiRepository: tachiyomi.domain.ai.repository.AiRepository = remember {
            uy.kohesive.injekt.Injekt.get()
        }
        val getReadingContext: tachiyomi.domain.ai.GetReadingContext = remember {
            uy.kohesive.injekt.Injekt.get()
        }
        val aiPreferences: tachiyomi.domain.ai.AiPreferences = remember {
            uy.kohesive.injekt.Injekt.get()
        }
        val isWebSearchEnabled by aiPreferences.enableWebSearch().collectAsState()
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        // Pause viewer scroll handling when chat is visible to prevent page changes
        LaunchedEffect(visible) {
            viewModel.state.value.viewer?.setPaused(visible)
        }

        // Visual Selection Screen (fullscreen overlay)
        if (showVisualSelection && capturedBitmap != null) {
            eu.kanade.presentation.ai.components.VisualSelectionScreen(
                bitmap = capturedBitmap!!,
                onConfirm = { selectedBitmap ->
                    // Convert to Base64
                    val stream = java.io.ByteArrayOutputStream()
                    selectedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
                    attachedImage = android.util.Base64.encodeToString(
                        stream.toByteArray(),
                        android.util.Base64.NO_WRAP,
                    )
                    showVisualSelection = false
                    capturedBitmap = null
                },
                onCancel = {
                    showVisualSelection = false
                    capturedBitmap = null
                },
            )
            return
        }

        eu.kanade.presentation.ai.components.AiChatOverlay(
            visible = visible,
            messages = messages,
            isLoading = isLoading,
            error = error,
            mangaTitle = mangaTitle,
            isWebSearchEnabled = isWebSearchEnabled,
            onToggleWebSearch = {
                if (!aiPreferences.canEnableWebSearch()) {
                    context.toast(stringResource(MR.strings.ai_web_search_unavailable))
                } else {
                    aiPreferences.enableWebSearch().set(!isWebSearchEnabled)
                }
            },
            onSendMessage = { content ->
                if (content.isBlank()) return@AiChatOverlay

                // Add user message with optional image AND placeholder for assistant
                val userMessage = tachiyomi.domain.ai.model.ChatMessage.user(content, attachedImage)
                val placeholder = tachiyomi.domain.ai.model.ChatMessage.assistant("")

                messages = messages + userMessage + placeholder

                attachedImage = null // Clear after sending
                isLoading = true
                error = null

                // Send to AI in background
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        // Build system prompt with manga context
                        val currentState = viewModel.state.value
                        val viewer = currentState.viewer
                        val textContentProvider = viewer as? tachiyomi.domain.ai.TextContentProvider

                        val systemPrompt = buildString {
                            // LANGUAGE INSTRUCTION FIRST - most important
                            appendLine(
                                "CRITICAL: You MUST respond in the SAME LANGUAGE as the user's message. Si el usuario escribe en español, responde en español. If the user writes in English, respond in English.",
                            )
                            appendLine()
                            appendLine(
                                "You are Gexu AI, a friendly reading companion for manga, manhwa, and light novels.",
                            )
                            appendLine()
                            viewModel.manga?.let { manga ->
                                // Check if we can use Reading Buddy mode (TextContentProvider available)
                                if (textContentProvider != null && aiPreferences.readingBuddyEnabled().get()) {
                                    // READING BUDDY MODE: Use full 3-tier context
                                    try {
                                        // Tier 3: Full text of last 10 pages
                                        val tier3Text = textContentProvider.getRecentText(10)

                                        // Tier 2: Key extracts from intermediate pages
                                        val currentPage = textContentProvider.getCurrentPage()
                                        val tier2End = (currentPage - 10).coerceAtLeast(0)
                                        val tier2Text = if (tier2End > 5) {
                                            textContentProvider.getFirstParagraphs(0, tier2End, skipEvery = 5)
                                        } else {
                                            null
                                        }

                                        val novelContext = getReadingContext.getContextForNovel(
                                            manga.id,
                                            tier3Text.ifBlank { "[No recent text available]" },
                                            tier2Text,
                                            currentPage,
                                            textContentProvider.getTotalPages(),
                                        )
                                        append(novelContext)
                                    } catch (_: Exception) {
                                        // Fallback to basic context
                                        appendLine("=== CURRENT READING CONTEXT ===")
                                        appendLine("Title: ${manga.title}")
                                        manga.genre?.take(5)?.let { genres ->
                                            appendLine("Genres: ${genres.joinToString(", ")}")
                                        }
                                        manga.description?.take(300)?.let { desc ->
                                            appendLine("Synopsis: $desc...")
                                        }
                                        appendLine()
                                        appendLine(
                                            "Chapter: ${currentState.currentChapter?.chapter?.name ?: "Unknown"}",
                                        )
                                        appendLine("Page: ${currentState.currentPage} of ${currentState.totalPages}")
                                        appendLine("===============================")
                                    }
                                } else {
                                    // STANDARD MODE: Only metadata (for manga images)
                                    appendLine("=== CURRENT READING CONTEXT ===")
                                    appendLine("Title: ${manga.title}")
                                    manga.genre?.take(5)?.let { genres ->
                                        appendLine("Genres: ${genres.joinToString(", ")}")
                                    }
                                    manga.description?.take(300)?.let { desc ->
                                        appendLine("Synopsis: $desc...")
                                    }
                                    appendLine()
                                    appendLine("Chapter: ${currentState.currentChapter?.chapter?.name ?: "Unknown"}")
                                    appendLine("Page: ${currentState.currentPage} of ${currentState.totalPages}")
                                    appendLine("===============================")
                                    appendLine()
                                    appendLine(
                                        "IMPORTANT: The user is actively reading this manga. You have full context of what they're reading.",
                                    )
                                    appendLine("- Answer questions about the current chapter and characters")
                                    appendLine("- NEVER spoil content from chapters the user hasn't reached yet")
                                    appendLine("- Be helpful and friendly")
                                    appendLine("- If the user sends an image, describe and analyze it in detail.")
                                    appendLine(
                                        "- IMPORTANT: Always respond in the SAME LANGUAGE as the user's message.",
                                    )
                                }
                            }
                        }

                        // Exclude placeholder from request
                        val messagesForApi = messages.dropLast(1)
                        val allMessages = listOf(
                            tachiyomi.domain.ai.model.ChatMessage.system(systemPrompt),
                        ) + messagesForApi

                        var fullResponse = ""

                        aiRepository.streamMessage(allMessages).collect { chunk ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                when (chunk) {
                                    is tachiyomi.domain.ai.model.StreamChunk.Text -> {
                                        fullResponse += chunk.delta
                                        // Update last message (placeholder)
                                        val currentList = messages.toMutableList()
                                        if (currentList.isNotEmpty()) {
                                            currentList[currentList.lastIndex] =
                                                tachiyomi.domain.ai.model.ChatMessage.assistant(fullResponse)
                                            messages = currentList
                                        }
                                    }
                                    is tachiyomi.domain.ai.model.StreamChunk.Done -> {
                                        isLoading = false
                                    }
                                    is tachiyomi.domain.ai.model.StreamChunk.Error -> {
                                        isLoading = false
                                        error = chunk.message
                                        // If no response generated yet, remove the empty placeholder
                                        if (fullResponse.isEmpty()) {
                                            messages = messages.dropLast(1)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            isLoading = false
                            error = e.message ?: "Error de conexión"
                            // Remove placeholder if failed immediately
                            if (messages.isNotEmpty() && messages.last().content.isEmpty()) {
                                messages = messages.dropLast(1)
                            }
                        }
                    }
                }
            },
            onClearConversation = {
                messages = emptyList()
                error = null
                attachedImage = null
            },
            onCaptureVision = {
                // Capture the visible viewport (what user sees) and show visual selection
                captureViewportAsync { bitmap ->
                    if (bitmap != null) {
                        capturedBitmap = bitmap
                        showVisualSelection = true
                    }
                }
            },
            hasAttachedImage = attachedImage != null,
            attachedImageBase64 = attachedImage,
            onClearAttachedImage = { attachedImage = null },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ReaderNotesDialog(
    initialNotes: String?,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var notes by remember(initialNotes) { mutableStateOf(initialNotes ?: "") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.action_notes)) },
        text = {
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = stringResource(MR.strings.action_edit_notes)) },
                maxLines = 10,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(notes) }) {
                Text(text = stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
