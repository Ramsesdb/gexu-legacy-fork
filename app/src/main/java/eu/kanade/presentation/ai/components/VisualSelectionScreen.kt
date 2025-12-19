package eu.kanade.presentation.ai.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Visual Selection Mode - "Circle to Search" inspired overlay.
 * Allows users to select the entire page or crop a specific region.
 */
@Composable
fun VisualSelectionScreen(
    bitmap: Bitmap,
    onConfirm: (Bitmap) -> Unit,
    onCancel: () -> Unit,
) {
    var selectionMode by remember { mutableStateOf(SelectionMode.FULL_PAGE) }
    var selectionRect by remember { mutableStateOf<Rect?>(null) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    val overlayAlpha by animateFloatAsState(
        targetValue = if (selectionMode == SelectionMode.REGION && selectionRect != null) 0.6f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "overlayAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Background image
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Captured page",
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { containerSize = it }
                .pointerInput(selectionMode) {
                    if (selectionMode == SelectionMode.REGION) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragStart = offset
                                selectionRect = Rect(offset, Size.Zero)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val current = change.position
                                selectionRect = Rect(
                                    left = min(dragStart.x, current.x),
                                    top = min(dragStart.y, current.y),
                                    right = max(dragStart.x, current.x),
                                    bottom = max(dragStart.y, current.y),
                                )
                            },
                        )
                    }
                },
            contentScale = ContentScale.Fit,
        )

        // Selection overlay (dim everything except selected area)
        if (selectionMode == SelectionMode.REGION && selectionRect != null) {
            val primaryColor = MaterialTheme.colorScheme.primary
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
            ) {
                // Draw dimmed overlay
                drawRect(
                    color = Color.Black.copy(alpha = overlayAlpha),
                    size = size,
                )

                // Cut out selected region (make it transparent)
                selectionRect?.let { rect ->
                    drawRect(
                        color = Color.Transparent,
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(rect.width, rect.height),
                        blendMode = BlendMode.Clear,
                    )

                    // Draw selection border with animated glow
                    drawRect(
                        color = primaryColor,
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(rect.width, rect.height),
                        style = Stroke(width = 3.dp.toPx()),
                    )

                    // Draw corner handles
                    val handleSize = 16.dp.toPx()
                    val corners = listOf(
                        Offset(rect.left, rect.top),
                        Offset(rect.right, rect.top),
                        Offset(rect.left, rect.bottom),
                        Offset(rect.right, rect.bottom),
                    )
                    corners.forEach { corner ->
                        drawCircle(
                            color = primaryColor,
                            radius = handleSize / 2,
                            center = corner,
                        )
                        drawCircle(
                            color = Color.White,
                            radius = handleSize / 3,
                            center = corner,
                        )
                    }
                }
            }
        }

        // Top bar with mode selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                tonalElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Full Page Mode
                    ModeChip(
                        selected = selectionMode == SelectionMode.FULL_PAGE,
                        onClick = {
                            selectionMode = SelectionMode.FULL_PAGE
                            selectionRect = null
                        },
                        icon = Icons.Default.Fullscreen,
                        label = "Página completa",
                    )

                    // Region Mode
                    ModeChip(
                        selected = selectionMode == SelectionMode.REGION,
                        onClick = { selectionMode = SelectionMode.REGION },
                        icon = Icons.Default.CropFree,
                        label = "Seleccionar área",
                    )
                }
            }
        }

        // Instruction text
        AnimatedVisibility(
            visible = selectionMode == SelectionMode.REGION && selectionRect == null,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
            ) {
                Text(
                    text = "Arrastra para seleccionar un área",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        // Bottom action bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Cancel button
            FilledIconButton(
                onClick = onCancel,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancelar",
                    modifier = Modifier.size(28.dp),
                )
            }

            // Confirm button
            FilledIconButton(
                onClick = {
                    val resultBitmap = when {
                        selectionMode == SelectionMode.REGION && selectionRect != null -> {
                            cropBitmap(bitmap, selectionRect!!, containerSize)
                        }
                        else -> bitmap
                    }
                    onConfirm(resultBitmap)
                },
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Confirmar",
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}

@Composable
private fun ModeChip(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private enum class SelectionMode {
    FULL_PAGE,
    REGION,
}

/**
 * Crop the bitmap based on the selection rectangle.
 * Handles coordinate mapping from screen space to bitmap space.
 */
private fun cropBitmap(bitmap: Bitmap, rect: Rect, containerSize: IntSize): Bitmap {
    // Calculate scaling factors
    val scaleX = bitmap.width.toFloat() / containerSize.width
    val scaleY = bitmap.height.toFloat() / containerSize.height

    // Map selection rect to bitmap coordinates
    val x = (rect.left * scaleX).toInt().coerceIn(0, bitmap.width - 1)
    val y = (rect.top * scaleY).toInt().coerceIn(0, bitmap.height - 1)
    val width = (rect.width * scaleX).toInt().coerceIn(1, bitmap.width - x)
    val height = (rect.height * scaleY).toInt().coerceIn(1, bitmap.height - y)

    return Bitmap.createBitmap(bitmap, x, y, width, height)
}
