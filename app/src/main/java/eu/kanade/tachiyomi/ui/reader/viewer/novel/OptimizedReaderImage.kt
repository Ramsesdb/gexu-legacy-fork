package eu.kanade.tachiyomi.ui.reader.viewer.novel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat

/**
 * Optimized image component for Novel Reader that follows WebtoonPageHolder's pattern:
 *
 * 1. Call page.chapter.pageLoader?.loadPage(page) to trigger loading + priority queue
 * 2. Observe page.statusFlow for state changes
 * 3. When Ready, use page.stream to load from ChapterCache
 *
 * If page doesn't have pageLoader (e.g., from fetchImages), falls back to Coil.
 */
@Composable
fun OptimizedReaderImage(
    page: ReaderPage?,
    imageUrl: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillWidth
) {
    val context = LocalContext.current
    var bitmap by remember(imageUrl) { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember(imageUrl) { mutableStateOf(true) }
    var loadProgress by remember(imageUrl) { mutableFloatStateOf(0f) }
    var useCoilFallback by remember(imageUrl) { mutableStateOf(false) }

    // Follow WebtoonPageHolder.loadPageAndProcessStatus pattern
    LaunchedEffect(page, imageUrl) {
        isLoading = true
        useCoilFallback = false
        bitmap = null
        loadProgress = 0f

        if (page == null) {
            useCoilFallback = true
            return@LaunchedEffect
        }

        val loader = page.chapter.pageLoader
        if (loader == null) {
            // No pageLoader = pages created by fetchImages, use Coil fallback
            logcat { "OptimizedReaderImage: No pageLoader for page ${page.index}, using Coil" }
            useCoilFallback = true
            return@LaunchedEffect
        }

        // WebtoonPageHolder pattern: launch loadPage AND observe statusFlow together
        try {
            supervisorScope {
                // Launch loadPage in background (same as WebtoonPageHolder)
                launch(Dispatchers.IO) {
                    loader.loadPage(page)
                }

                // Observe status changes
                page.statusFlow.collectLatest { state ->
                    when (state) {
                        Page.State.Queue -> {
                            isLoading = true
                            loadProgress = 0f
                        }
                        Page.State.LoadPage -> {
                            isLoading = true
                            loadProgress = 0f
                        }
                        Page.State.DownloadImage -> {
                            isLoading = true
                            page.progressFlow.collectLatest { progress ->
                                loadProgress = progress / 100f
                            }
                        }
                        Page.State.Ready -> {
                            // Image cached! Load from stream
                            val stream = page.stream
                            if (stream != null) {
                                withContext(Dispatchers.IO) {
                                    if (!isActive) return@withContext
                                    try {
                                        val decodedBitmap = stream().use { inputStream ->
                                            BitmapFactory.decodeStream(inputStream)
                                        }
                                        if (isActive && decodedBitmap != null) {
                                            withContext(Dispatchers.Main) {
                                                bitmap = decodedBitmap
                                                isLoading = false
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                useCoilFallback = true
                                            }
                                        }
                                    } catch (e: Exception) {
                                        logcat(LogPriority.WARN) { "OptimizedReaderImage: Stream decode failed: ${e.message}" }
                                        withContext(Dispatchers.Main) {
                                            useCoilFallback = true
                                        }
                                    }
                                }
                            } else {
                                useCoilFallback = true
                            }
                        }
                        is Page.State.Error -> {
                            logcat(LogPriority.WARN) { "OptimizedReaderImage: Page error: ${state.error.message}" }
                            useCoilFallback = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "OptimizedReaderImage: Loading failed: ${e.message}" }
            useCoilFallback = true
        }
    }

    // Cleanup bitmap on dispose
    DisposableEffect(imageUrl) {
        onDispose {
            bitmap = null
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        when {
            // Successfully loaded from cache
            bitmap != null -> {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Page ${page?.index ?: 0}",
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // Fallback to Coil with aggressive caching
            useCoilFallback -> {
                // Get actual URL - might be placeholder like "page://0" if using pageLoader path
                val actualUrl = if (imageUrl.startsWith("page://")) {
                    page?.imageUrl ?: imageUrl
                } else {
                    imageUrl
                }

                // Don't load if still placeholder (shouldn't happen, but safety check)
                if (actualUrl.startsWith("page://")) {
                    // Show loading instead
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                } else {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(actualUrl)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        contentDescription = "Page ${page?.index ?: 0}",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = contentScale,
                        onSuccess = { isLoading = false },
                        onError = { isLoading = false }
                    )
                }
            }
            // Loading state with progress
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (loadProgress > 0f) {
                        CircularProgressIndicator(
                            progress = { loadProgress },
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    } else {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}



