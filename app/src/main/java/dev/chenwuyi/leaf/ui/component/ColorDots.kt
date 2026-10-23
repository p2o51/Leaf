package dev.chenwuyi.leaf.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.ui.theme.LeafMotion
import dev.chenwuyi.leaf.ui.theme.LocalNotionTones

/** The ten Notion colours as dots. The chosen one morphs from a circle into a rounded square. */
@Composable
fun ColorDots(selected: NotionColor?, onPick: (NotionColor) -> Unit, modifier: Modifier = Modifier) {
    val tones = LocalNotionTones.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NotionColor.entries.forEach { c ->
            val on = c == selected
            val corner by animateDpAsState(if (on) 9.dp else 15.dp, LeafMotion.fast(), label = "dot")
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(corner))
                    .background(if (c == NotionColor.Default) MaterialTheme.colorScheme.surfaceContainerHighest else tones.fill(c))
                    .then(if (on) Modifier.background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)) else Modifier)
                    .clickable { onPick(c) }
                    .semantics { contentDescription = c.label; this.selected = on },
            )
        }
    }
}
