package dev.chenwuyi.leaf.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.WallRules
import dev.chenwuyi.leaf.ui.theme.EssayPreviewStyle
import dev.chenwuyi.leaf.ui.theme.EssayTitleStyle
import dev.chenwuyi.leaf.ui.theme.LocalNotionTones
import java.time.ZoneId

/** Keep-style card: a cover picture on top when there is one, the whole card tinted by 卡片颜色. Only a date, no counts. */
@Composable
fun EssayCard(essay: Essay, active: Boolean, tall: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tones = LocalNotionTones.current
    val fill = if (essay.color == NotionColor.Default) MaterialTheme.colorScheme.surfaceContainerHigh else tones.fill(essay.color)
    val shape = if (active) RoundedCornerShape(28.dp, 28.dp, 28.dp, 10.dp) else RoundedCornerShape(28.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(fill).clickable(onClick = onClick)) {
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
        }
    }
}
