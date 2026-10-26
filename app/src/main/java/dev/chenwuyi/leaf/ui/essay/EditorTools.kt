package dev.chenwuyi.leaf.ui.essay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.HorizontalRule
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.ui.component.ColorDots
import dev.chenwuyi.leaf.ui.component.PictureView
import dev.chenwuyi.leaf.ui.component.scribbleShape
import dev.chenwuyi.leaf.ui.theme.LeafMotion
import dev.chenwuyi.leaf.ui.theme.LocalNotionTones

/** Which of the phone's two panels is up: the 插入 grid in place of the keyboard, or the 样式 card above it. */
enum class ToolPanel { None, Insert, Style }

/**
 * The phone's floating bar above the keyboard, after Craft's: + opens the insert grid, 样式 the style
 * card for the block being edited, then photo and scribble straight in, and a key to put the keyboard away.
 */
@Composable
fun EditorToolbar(
    panel: ToolPanel,
    canStyle: Boolean,
    keyboardUp: Boolean,
    onPanel: (ToolPanel) -> Unit,
    onPhoto: () -> Unit,
    onScribble: () -> Unit,
    onHideKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val inserting = panel == ToolPanel.Insert
        val corner by animateDpAsState(if (inserting) 14.dp else 22.dp, LeafMotion.fast(), label = "plus")
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(corner))
                .background(if (inserting) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                .clickable(role = Role.Button) { onPanel(if (inserting) ToolPanel.None else ToolPanel.Insert) }
                .semantics { contentDescription = "插入"; selected = inserting },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, null, tint = if (inserting) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(4.dp))
        ToolButton(Icons.Rounded.TextFields, "样式", on = panel == ToolPanel.Style, enabled = canStyle) {
            onPanel(if (panel == ToolPanel.Style) ToolPanel.None else ToolPanel.Style)
        }
        ToolButton(Icons.Rounded.Image, "插入照片", onClick = onPhoto)
        ToolButton(Icons.Rounded.Gesture, "插入涂鸦", onClick = onScribble)
        // Grows out of the bar's end with the keyboard rather than popping in: the bar widens on the same spring.
        AnimatedVisibility(
            keyboardUp,
            enter = expandHorizontally(LeafMotion.expressive(), expandFrom = Alignment.Start) + fadeIn(LeafMotion.tool()) + scaleIn(LeafMotion.expressive(), initialScale = 0.6f),
            exit = shrinkHorizontally(LeafMotion.tool(), shrinkTowards = Alignment.Start) + fadeOut(LeafMotion.tool()) + scaleOut(LeafMotion.tool(), targetScale = 0.6f),
        ) {
            ToolButton(Icons.Rounded.KeyboardHide, "收起键盘", onClick = onHideKeyboard)
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        on -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label; selected = on },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint) }
}

/**
 * Craft's insert grid: big-letter tiles for text styles, then lists, then photos and the wall's newest
 * scribbles. The phone shows it in place of the keyboard; the tablet keeps it in the side panel.
 */
@Composable
fun InsertGrid(
    scribbles: List<Scribble>,
    onPick: (InsertKind) -> Unit,
    onScribble: (Scribble) -> Unit,
    modifier: Modifier = Modifier,
    tile: Dp = 64.dp,
    scribblesStacked: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Section("文字") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InsertKind.TextStyles.forEach { kind -> Tile(kind.label, tile, { onPick(kind) }) { Glyph(kind.glyph) } }
            }
        }
        Section("列表与照片") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(InsertKind.Bullet.label, tile, { onPick(InsertKind.Bullet) }) { Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, null) }
                Tile(InsertKind.Numbered.label, tile, { onPick(InsertKind.Numbered) }) { Icon(Icons.Rounded.FormatListNumbered, null) }
                Tile(InsertKind.Photo.label, tile, { onPick(InsertKind.Photo) }) { Icon(Icons.Rounded.Image, null) }
                Tile(InsertKind.Divider.label, tile, { onPick(InsertKind.Divider) }) { Icon(Icons.Rounded.HorizontalRule, null) }
                Tile("全部涂鸦", tile, { onPick(InsertKind.Scribble) }) { Icon(Icons.Rounded.Gesture, null) }
            }
        }
        if (scribbles.isNotEmpty()) {
            Section("最近的涂鸦") {
                if (scribblesStacked) {
                    // The tablet panel is tall and narrow: the wall reads top to bottom, as it does on the left.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        scribbles.take(30).forEach { s -> ScribbleTile(s, Modifier.fillMaxWidth()) { onScribble(s) } }
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(scribbles.take(12), key = { it.id }) { s -> ScribbleTile(s, Modifier.widthIn(max = 200.dp)) { onScribble(s) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun Glyph(glyph: String) {
    Text(glyph, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
}

@Composable
private fun Tile(label: String, size: Dp, onClick: () -> Unit, face: @Composable () -> Unit) {
    Column(
        Modifier.width(size).clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest),
            contentAlignment = Alignment.Center,
        ) { face() }
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** A scribble as it looks on the wall, small enough to sit in a row or a side panel. */
@Composable
fun ScribbleTile(s: Scribble, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tones = LocalNotionTones.current
    Row(
        modifier.clip(scribbleShape(s)).background(tones.fill(s.color)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        s.photo?.let { PictureView(it, Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))) }
        Text(s.text.ifBlank { "照片" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The block kinds 样式 can turn a text block into, in Craft's order: the headings, body, then lists and quote. */
private val Headings = listOf("heading_1" to "大标题", "heading_2" to "标题", "heading_3" to "小标题", "paragraph" to "正文")
private val Shapes = listOf(
    Triple("bulleted_list_item", "列表", Icons.AutoMirrored.Rounded.FormatListBulleted),
    Triple("numbered_list_item", "编号", Icons.Rounded.FormatListNumbered),
    Triple("quote", "引用", Icons.Rounded.FormatQuote),
)

/**
 * 样式 for the block being edited, after Craft's style card: a row of text kinds, the list and quote
 * kinds, and the Notion colours. Tapping the list kind a block already is turns it back into body text.
 */
@Composable
fun StyleControls(block: EssayBlock.Paragraph?, onType: (String) -> Unit, onColor: (NotionColor) -> Unit, modifier: Modifier = Modifier) {
    AnimatedContent(block == null, modifier, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "style") { none ->
        if (none || block == null) {
            Text("点一下正文里的一段，再来改它的样式", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@AnimatedContent
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Headings.forEach { (type, label) ->
                    val on = block.type == type
                    Text(
                        label,
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable(role = Role.Tab) { onType(type) }
                            .semantics { selected = on }
                            .padding(vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Shapes.forEach { (type, label, icon) ->
                    val on = block.type == type
                    Row(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable(role = Role.Checkbox) { onType(if (on) "paragraph" else type) }
                            .semantics { selected = on },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, null, Modifier.size(18.dp))
                        Text(" $label", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            // One row that scrolls, rather than nine dots and a lone tenth on the next line.
            ColorDots(block.color, onColor, Modifier.horizontalScroll(rememberScrollState()))
        }
    }
}
