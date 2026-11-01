package dev.chenwuyi.leaf.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.WallRules
import dev.chenwuyi.leaf.ui.component.animatedLeafShape
import dev.chenwuyi.leaf.ui.component.leafPress
import dev.chenwuyi.leaf.ui.component.rememberLeafPressScale
import dev.chenwuyi.leaf.ui.theme.EssayPreviewStyle
import dev.chenwuyi.leaf.ui.theme.EssayTitleStyle
import dev.chenwuyi.leaf.ui.theme.LeafMotion
import dev.chenwuyi.leaf.ui.theme.LocalNotionTones
import java.time.ZoneId

/**
 * Keep-style card: a cover picture on top when there is one, the whole card tinted by 卡片颜色. Only a date, no counts.
 *
 * Paperleaf's LeafCard: the leaf corner on paper-container, flat. Pressed, the corners close in; the open
 * essay ([active], beside the editor on a tablet) rounds all four and, when it has no colour of its own,
 * turns leaf-green-container. A Notion colour is the writer's, so it stays while the essay is open.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EssayCard(essay: Essay, active: Boolean, tall: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    val tones = LocalNotionTones.current
    val plain = essay.color == NotionColor.Default
    val fill by animateColorAsState(
        when {
            !plain -> tones.fill(essay.color)
            active -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        LeafMotion.effects(),
        label = "card fill",
    )
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = animatedLeafShape(pressed, selected = active)
    val press = rememberLeafPressScale(pressed)
    Column(
        modifier
            .fillMaxWidth()
            .leafPress(press)
            .clip(shape)
            .background(fill)
            .combinedClickable(
                interactionSource = source,
                indication = LocalIndication.current,
                onLongClickLabel = if (onLongClick != null) "管理" else null,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
    ) {
        essay.cover?.let { cover ->
            Box(Modifier.fillMaxWidth().height(if (tall) 220.dp else 140.dp)) {
                dev.chenwuyi.leaf.ui.component.PictureView(cover, Modifier.fillMaxWidth().height(if (tall) 220.dp else 140.dp))
            }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
            val day = essay.editedAt.atZone(ZoneId.systemDefault()).toLocalDate()
            Text(
                WallRules.dayLabel(day) + if (essay.pinned) " · 置顶" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (essay.title.isNotBlank()) {
                Text(essay.title, Modifier.padding(top = 6.dp), style = EssayTitleStyle)
            }
            if (essay.previewText.isNotBlank()) {
                Text(
                    essay.previewText,
                    Modifier.padding(top = 6.dp),
                    style = EssayPreviewStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (essay.title.isBlank() && essay.previewText.isBlank()) {
                Text("空白随笔", Modifier.padding(top = 6.dp), style = EssayTitleStyle.copy(fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Tags sit at the foot of the card like Keep's labels, quiet enough to read past.
            if (essay.tags.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    essay.tags.forEach { tag ->
                        Text(
                            tag,
                            // Tags are chips: full round. On plain paper they take paper-high; on a tinted card a faint
                            // ink wash, so they read on any of the nine tones.
                            Modifier.clip(CircleShape).background(if (plain && !active) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)).padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
