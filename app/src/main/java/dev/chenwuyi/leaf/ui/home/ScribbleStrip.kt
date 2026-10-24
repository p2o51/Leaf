package dev.chenwuyi.leaf.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.data.WallRules
import dev.chenwuyi.leaf.ui.component.ScribbleChip
import dev.chenwuyi.leaf.ui.component.sharedKey
import dev.chenwuyi.leaf.ui.component.sharedScribble

private const val DriftPxPerFrame = 0.5f
private const val PauseAfterTouchMillis = 2500L
private val EdgeFade = 28.dp

/**
 * Home's scribble strip: three rows that drift sideways on their own, never more than a third of the
 * screen. Tapping a chip or the blank space opens the full wall; [onOpen] gets the tapped chip (if any)
 * and the chips around it that the finger may have meant.
 *
 * No panel behind it (51, 2026-10-24: "无缝的就好"): the strip reaches past the grid's [bleed] to the
 * screen edges and its chips fade out there, so they drift in and out instead of being cut at a border.
 */
@Composable
fun ScribbleStrip(scribbles: List<Scribble>, onOpen: (focusId: String?, near: Set<String>) -> Unit, modifier: Modifier = Modifier, bleed: Dp = 0.dp) {
    val scroll = rememberScrollState()
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    val pausedUntil = remember { mutableLongStateOf(0L) }
    val bounds = remember { HashMap<String, Rect>() }
    val pad = with(LocalDensity.current) { 22.dp.toPx() }
    val rows = remember(scribbles) { WallRules.stripRows(scribbles.sortedByDescending { it.createdAt }) }
    val isDragged by rememberUpdatedState(dragged)

    LaunchedEffect(scroll) {
        var direction = 1
        while (true) {
            val now = withFrameNanos { it / 1_000_000 }
            if (isDragged) {
                pausedUntil.longValue = now + PauseAfterTouchMillis
                continue
            }
            if (now < pausedUntil.longValue || scroll.maxValue == 0) continue
            if (scroll.value >= scroll.maxValue) direction = -1 else if (scroll.value <= 0) direction = 1
            scroll.scrollBy(direction * DriftPxPerFrame)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val extra = (bleed * 2).roundToPx()
                val width = constraints.maxWidth + extra
                val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
            }
            .sharedKey("wall-surface")
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fade = (EdgeFade.toPx() / size.width).coerceIn(0f, 0.5f)
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, fade to Color.Black, 1f - fade to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            }
            .clickable(interactionSource = null, indication = null) { onOpen(null, emptySet()) }
            .horizontalScroll(scroll)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (scribbles.isEmpty()) {
            Text("还没有涂鸦", Modifier.padding(horizontal = bleed + 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        rows.forEachIndexed { index, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(bleed + 10.dp + 22.dp * index))
                row.forEach { s ->
                    ScribbleChip(
                        s,
                        Modifier
                            .sharedScribble(s.id)
                            .onGloballyPositioned { bounds[s.id] = it.boundsInRoot() },
                        singleLine = true,
                        onClick = { onOpen(s.id, neighboursOf(s.id, bounds, pad)) },
                    )
                }
                Spacer(Modifier.width(bleed + 10.dp))
            }
        }
    }
}
