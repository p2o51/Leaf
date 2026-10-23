package dev.chenwuyi.leaf.ui.essay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.data.WallRules
import dev.chenwuyi.leaf.ui.component.PictureView
import dev.chenwuyi.leaf.ui.component.scribbleShape
import dev.chenwuyi.leaf.ui.theme.EditorBodyStyle
import dev.chenwuyi.leaf.ui.theme.EditorTitleStyle
import dev.chenwuyi.leaf.ui.theme.LocalNotionTones
import java.time.ZoneId

/**
 * M0 editor: title, paragraphs and synced scribbles, all editable in place. The Craft-style block
 * editor (markdown shortcuts, drag to reorder, the insert menu and the drag-in rail) is M2.
 */
@Composable
fun EssayEditor(essay: Essay, scribbles: List<Scribble>, onBack: () -> Unit, onChange: (Essay) -> Unit, modifier: Modifier = Modifier) {
    val byId = scribbles.associateBy { it.id }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
            Text(
                "示例数据，还没连 Notion",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Box(Modifier.size(48.dp))
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 160.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                essay.cover?.let { cover ->
                    item(key = "cover") {
                        PictureView(cover, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(30.dp, 30.dp, 30.dp, 10.dp)))
                    }
                }
                item(key = "title") {
                    PlainField(essay.title, "标题", EditorTitleStyle) { onChange(essay.copy(title = it)) }
                }
                items(essay.blocks, key = { it.id }) { block ->
                    when (block) {
                        is EssayBlock.Paragraph -> PlainField(block.text, "", EditorBodyStyle) { text ->
                            onChange(essay.copy(blocks = essay.blocks.map { if (it.id == block.id) block.copy(text = text) else it }))
                        }
                        is EssayBlock.SyncedScribble -> SyncedScribbleBlock(byId[block.scribbleId])
                    }
                }
            }
        }
    }
}

@Composable
private fun PlainField(value: String, placeholder: String, style: TextStyle, onValue: (String) -> Unit) {
    Box {
        if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            value,
            onValue,
            Modifier.fillMaxWidth(),
            textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}

/** A synced block renders from the live scribble: recolour or edit it on the wall and it changes here too. */
@Composable
private fun SyncedScribbleBlock(scribble: Scribble?) {
    val tones = LocalNotionTones.current
    if (scribble == null) {
        Text(
            "原涂鸦已删除",
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(
        Modifier.fillMaxWidth().clip(scribbleShape(scribble)).background(tones.fill(scribble.color)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        scribble.photo?.let { PictureView(it, Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp))) }
        if (scribble.text.isNotBlank()) Text(scribble.text, style = EditorBodyStyle.copy(fontSize = EditorBodyStyle.fontSize * 0.9f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Sync, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                " 同步块 · 涂鸦墙 " + WallRules.dayLabel(scribble.createdAt.atZone(ZoneId.systemDefault()).toLocalDate()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
