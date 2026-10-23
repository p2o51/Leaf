package dev.chenwuyi.leaf.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.ui.theme.LocalNotionTones

enum class ChipMark { None, Focus, Near, Selected }

/**
 * Scribbles get slightly irregular corners so the wall reads like notes stuck on a fridge, not a list.
 * Longer lines get a speech-bubble tail; the variant is stable per scribble.
 */
fun scribbleShape(s: Scribble): Shape {
    if (s.photo != null) return RoundedCornerShape(18.dp)
    val v = (s.id.hashCode() and 0x7fffffff) % 4
    return when {
        s.text.length > 12 -> RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)
        v == 0 -> RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
        v == 1 -> RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)
        v == 2 -> RoundedCornerShape(20.dp)
        else -> RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp)
    }
}

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
    val tones = LocalNotionTones.current
    val shape = scribbleShape(scribble)
    val ring: Pair<Dp, Color>? = when (mark) {
        ChipMark.Focus, ChipMark.Selected -> 3.dp to tones.ring
        ChipMark.Near -> 2.dp to tones.near
        ChipMark.None -> null
    }
    var m = modifier.clip(shape)
    if (ring != null) m = m.border(ring.first, ring.second, shape)
    m = m.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    val photo = scribble.photo
    if (photo != null) {
        Box(m.size(if (large) 150.dp else 88.dp, if (large) 110.dp else 64.dp), contentAlignment = Alignment.BottomStart) {
            PictureView(photo, Modifier.matchParentSize())
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
            m.background(tones.fill(scribble.color)).widthIn(max = if (singleLine) 250.dp else 560.dp).padding(horizontal = 15.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
