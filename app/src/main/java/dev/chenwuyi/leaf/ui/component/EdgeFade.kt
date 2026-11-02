package dev.chenwuyi.leaf.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Paperleaf's scrollEdgeFade: where scrolled content runs under something pinned, it fades out over
 * [length] instead of being cut through mid-line. Only the edges that have more behind them fade, so a
 * list that fits shows no fade at all. Goes before the scroll modifier, so it fades the viewport.
 */
fun Modifier.scrollEdgeFade(state: ScrollState, length: Dp = 24.dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val edge = length.toPx().coerceAtMost(size.height / 2)
            if (state.canScrollBackward) {
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = edge),
                    size = Size(size.width, edge),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (state.canScrollForward) {
                drawRect(
                    Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - edge, endY = size.height),
                    topLeft = Offset(0f, size.height - edge),
                    size = Size(size.width, edge),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
