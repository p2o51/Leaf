package dev.chenwuyi.leaf.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.ui.theme.LeafMotion
import dev.chenwuyi.leaf.ui.theme.LocalNotionTones

/**
 * The ten Notion colours as dots. Each dot sits in its own 48dp touch target, so a thumb never lands on
 * the neighbour; the chosen one morphs from a circle into a rounded square inside an ink ring, which reads
 * on every fill (a tint over the dot did not on the pale ones).
 *
 * Ten 48dp targets do not fit one line of a phone's sheet, and seven over three looked like a mistake, so
 * they go [perRow] to a row: two even rows by default. A caller that scrolls the dots sideways passes the
 * whole count for a single line.
 */
@Composable
fun ColorDots(selected: NotionColor?, onPick: (NotionColor) -> Unit, modifier: Modifier = Modifier, perRow: Int = 5) {
    val tones = LocalNotionTones.current
    val ink = MaterialTheme.colorScheme.onSurface
    FlowRow(modifier, maxItemsInEachRow = perRow) {
        NotionColor.entries.forEach { c ->
            val on = c == selected
            val corner by animateDpAsState(if (on) 9.dp else 15.dp, LeafMotion.fast(), label = "dot")
            val ring by animateColorAsState(if (on) ink else Color.Transparent, LeafMotion.effects(), label = "dot ring")
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(c) })
                    .semantics { contentDescription = swatchLabel(c) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .border(2.dp, ring, RoundedCornerShape(corner + 5.dp))
                        .padding(5.dp)
                        .clip(RoundedCornerShape(corner))
                        .background(if (c == NotionColor.Default) MaterialTheme.colorScheme.surfaceContainerHighest else tones.fill(c)),
                )
            }
        }
    }
}

/** What TalkBack says for a dot: the colour by name, and 默认 as what it is, the page's own colour. */
internal fun swatchLabel(c: NotionColor): String = if (c == NotionColor.Default) "默认颜色" else "${c.label}色"
