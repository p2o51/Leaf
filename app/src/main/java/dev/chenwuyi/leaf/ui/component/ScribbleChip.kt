package dev.chenwuyi.leaf.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.ui.theme.LeafMotion
import dev.chenwuyi.leaf.ui.theme.LeafShape
import dev.chenwuyi.leaf.ui.theme.LocalNotionTones

enum class ChipMark { None, Focus, Near, Selected }

/**
 * Every scribble has Paperleaf's leaf corner, the same as essay cards, so the strip, the wall and the
 * editor's scribble tiles read as one kind of object. (Before Paperleaf the corners varied per id and long
 * lines had a speech-bubble tail.)
 */
@Suppress("UNUSED_PARAMETER")
fun scribbleShape(s: Scribble): Shape = LeafShape()

/** A scribble's paper on a page: paper-container when it has no Notion colour, its tone when it has one. */
@Composable
fun scribbleFill(s: Scribble): Color =
    if (s.color == NotionColor.Default) MaterialTheme.colorScheme.surfaceContainer else LocalNotionTones.current.fill(s.color)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScribbleChip(
    scribble: Scribble,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    large: Boolean = false,
    mark: ChipMark = ChipMark.None,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    // State is told by shape and paper, not by rings (Paperleaf: no outlines). Near and focused chips
    // round all their corners; the focused one also sits on a deeper paper; a selected one turns
    // leaf-green-container, as LeafCard's selected state does.
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = animatedLeafShape(pressed, selected = mark != ChipMark.None)
    val base = scribbleFill(scribble)
    val fill by animateColorAsState(
        when (mark) {
            ChipMark.Selected -> MaterialTheme.colorScheme.primaryContainer
            ChipMark.Focus -> deeper(base)
            ChipMark.Near, ChipMark.None -> base
        },
        LeafMotion.effects(),
        label = "scribble fill",
    )
    val press = rememberLeafPressScale(pressed)
    val m = modifier.leafPress(press).clip(shape).combinedClickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick, onLongClick = onLongClick)
    val photo = scribble.photo
    if (photo != null) {
        Box(m.size(if (large) 150.dp else 88.dp, if (large) 110.dp else 64.dp), contentAlignment = Alignment.BottomStart) {
            PictureView(photo, Modifier.matchParentSize())
            // A photo has no paper to recolour, so selection washes it in leaf-green-container instead.
            val wash by animateFloatAsState(if (mark == ChipMark.Selected) 0.55f else 0f, LeafMotion.effects(), label = "photo selected")
            if (wash > 0f) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = wash)))
            if (scribble.text.isNotBlank()) {
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0x8C000000))))
                Text(
                    scribble.text,
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else {
        Text(
            scribble.text,
            m.background(fill).widthIn(max = if (singleLine) 250.dp else 560.dp).padding(horizontal = 15.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (mark == ChipMark.Selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One step deeper on the same paper: paper-container to paper-high, a Notion tone darkened (lightened in dark) a touch. */
@Composable
private fun deeper(fill: Color): Color =
    if (fill == MaterialTheme.colorScheme.surfaceContainer) MaterialTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f).compositeOver(fill)
