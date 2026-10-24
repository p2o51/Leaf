package dev.chenwuyi.leaf.ui.essay

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.data.WallRules
import dev.chenwuyi.leaf.ui.component.PictureView
import dev.chenwuyi.leaf.ui.component.scribbleShape
import dev.chenwuyi.leaf.ui.theme.EditorBodyStyle
import dev.chenwuyi.leaf.ui.theme.EditorTitleStyle
import dev.chenwuyi.leaf.ui.theme.LeafMotion
import dev.chenwuyi.leaf.ui.theme.LocalNotionTones
import java.time.ZoneId
import java.util.UUID

/**
 * The essay editor: title and blocks, edited in place. Enter splits a block, Backspace on an empty one
 * removes it, and the 插入 bar above the keyboard adds headings, lists, quotes, photos and scribbles
 * after the block being edited. Drag to reorder and the tablet's drag-in rail are M2.
 */
@Composable
fun EssayEditor(
    essay: Essay,
    scribbles: List<Scribble>,
    status: String,
    onBack: () -> Unit,
    onChange: (Essay) -> Unit,
    onRemoveBlock: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Typing goes to a local draft first: waiting for Room's round trip would drop fast keystrokes.
    var draft by remember(essay.id) { mutableStateOf(essay) }
    var lastEdit by remember(essay.id) { mutableLongStateOf(0L) }
    LaunchedEffect(essay) {
        // Changes from elsewhere (a body loaded from Notion, a photo copied in) are taken once typing pauses.
        if (!EditorEdits.sameContent(essay, draft) && System.currentTimeMillis() - lastEdit > AdoptAfterMillis) draft = essay
    }
    val edit: (Essay) -> Unit = { next ->
        draft = next
        lastEdit = System.currentTimeMillis()
        onChange(next)
    }

    var focused by remember(essay.id) { mutableStateOf<String?>(null) }
    var focusNext by remember(essay.id) { mutableStateOf<String?>(null) }
    val requesters = remember(essay.id) { mutableStateMapOf<String, FocusRequester>() }
    var menuOpen by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val newId = { UUID.randomUUID().toString() }

    fun insert(block: EssayBlock) {
        edit(EditorEdits.insertAfter(draft, focused, listOf(block)))
        if (block is EssayBlock.Paragraph) focusNext = block.id
        menuOpen = false
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) insert(EssayBlock.Photo(newId(), Picture.Local(uri.toString())))
    }
    LaunchedEffect(focusNext, draft.blocks.size) {
        val id = focusNext ?: return@LaunchedEffect
        requesters[id]?.let { runCatching { it.requestFocus() }; focusNext = null }
    }

    val byId = scribbles.associateBy { it.id }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                Text(
                    status,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Box(Modifier.size(48.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    Modifier.widthIn(max = 720.dp).fillMaxSize(),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    draft.cover?.let { cover ->
                        item(key = "cover") {
                            PictureView(cover, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(30.dp, 30.dp, 30.dp, 10.dp)))
                        }
                    }
                    item(key = "title") {
                        PlainField(draft.title, "标题", EditorTitleStyle, Modifier.onFocusChanged { if (it.isFocused) focused = null }) {
                            edit(draft.copy(title = it.replace("\n", "")))
                        }
                    }
                    items(draft.blocks, key = { it.id }) { block ->
                        val requester = remember(block.id) { FocusRequester().also { requesters[block.id] = it } }
                        val track = Modifier.focusRequester(requester).onFocusChanged { if (it.isFocused) focused = block.id }
                        when (block) {
                            is EssayBlock.Paragraph -> TextBlock(
                                block,
                                track.onPreviewKeyEvent { key ->
                                    // Backspace on an empty block removes it and steps back, as in Craft.
                                    val removing = key.type == KeyEventType.KeyDown && key.key == Key.Backspace && block.text.isEmpty() && draft.blocks.size > 1
                                    if (removing) {
                                        focusNext = EditorEdits.before(draft, block.id)
                                        draft = EditorEdits.remove(draft, block.id)
                                        lastEdit = System.currentTimeMillis()
                                        onRemoveBlock(block.id)
                                    }
                                    removing
                                },
                            ) { text ->
                                val (next, split) = EditorEdits.typed(draft, block.id, text, newId)
                                edit(next)
                                if (split != null) focusNext = split
                            }
                            is EssayBlock.Photo -> PhotoBlock(
                                block,
                                track,
                                onCaption = { caption -> edit(draft.copy(blocks = draft.blocks.map { if (it.id == block.id) block.copy(caption = caption.replace("\n", "")) else it })) },
                                onRemove = {
                                    draft = EditorEdits.remove(draft, block.id)
                                    lastEdit = System.currentTimeMillis()
                                    onRemoveBlock(block.id)
                                },
                            )
                            is EssayBlock.SyncedScribble -> SyncedScribbleBlock(byId[block.scribbleId])
                            is EssayBlock.Other -> Text(
                                "Notion 里的「${block.type}」块，Leaf 先不改它",
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            InsertBar(
                open = menuOpen,
                onToggle = { menuOpen = !menuOpen },
                onPick = { kind ->
                    when (kind) {
                        InsertKind.Photo -> {
                            menuOpen = false
                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        InsertKind.Scribble -> {
                            menuOpen = false
                            picking = true
                        }
                        else -> insert(EditorEdits.blank(kind, newId))
                    }
                },
            )
        }
        ScribblePicker(
            open = picking,
            scribbles = EditorEdits.pickable(scribbles),
            onDismiss = { picking = false },
            onPick = { s ->
                picking = false
                EditorEdits.fromScribble(s, newId)?.let(::insert)
            },
        )
    }
}

private const val AdoptAfterMillis = 1_500L

@Composable
private fun TextBlock(block: EssayBlock.Paragraph, modifier: Modifier, onText: (String) -> Unit) {
    val tones = LocalNotionTones.current
    val tinted = block.color != NotionColor.Default
    val style = when (block.type) {
        "heading_1" -> EditorTitleStyle
        "heading_2", "heading_3" -> EditorTitleStyle.copy(fontSize = EditorTitleStyle.fontSize * 0.8f, lineHeight = EditorTitleStyle.lineHeight * 0.85f)
        else -> EditorBodyStyle
    }
    val frame = if (tinted) Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tones.fill(block.color)).padding(horizontal = 14.dp, vertical = 8.dp) else Modifier.fillMaxWidth()
    Row(frame, verticalAlignment = Alignment.Top) {
        when (block.type) {
            "bulleted_list_item" -> Text("•", style = style, modifier = Modifier.width(22.dp))
            "numbered_list_item" -> Text("–", style = style, modifier = Modifier.width(22.dp))
            "quote" -> Box(Modifier.padding(top = 4.dp, end = 14.dp).width(3.dp).height(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
        Box(Modifier.weight(1f)) {
            PlainField(block.text, placeholderFor(block.type), style, modifier, onText)
        }
    }
}

private fun placeholderFor(type: String): String = when (type) {
    "heading_1", "heading_2", "heading_3" -> "小标题"
    "bulleted_list_item", "numbered_list_item" -> "列表"
    "quote" -> "引用"
    else -> ""
}

@Composable
private fun PlainField(value: String, placeholder: String, style: TextStyle, modifier: Modifier = Modifier, onValue: (String) -> Unit) {
    Box {
        if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        BasicTextField(
            value,
            onValue,
            modifier.fillMaxWidth(),
            textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun PhotoBlock(block: EssayBlock.Photo, modifier: Modifier, onCaption: (String) -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            PictureView(block.picture, Modifier.fillMaxWidth().heightIn(min = 160.dp).height(220.dp).clip(RoundedCornerShape(24.dp)))
            IconButton(
                onClick = onRemove,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
            ) { Icon(Icons.Rounded.Close, "移除这张照片", Modifier.size(18.dp)) }
        }
        PlainField(block.caption, "配一句话（可以不写）", MaterialTheme.typography.bodyMedium, modifier, onCaption)
    }
}

/** Above the keyboard: one quiet 插入 button; tapping it shows what can go in after the current block. */
@Composable
private fun InsertBar(open: Boolean, onToggle: () -> Unit, onPick: (InsertKind) -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(LeafMotion.expressive()), exit = fadeOut() + shrinkVertically(LeafMotion.tool())) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InsertKind.entries.forEach { kind ->
                    Text(
                        kind.label,
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (kind == InsertKind.Scribble) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer)
                            .clickable { onPick(kind) }
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (kind == InsertKind.Scribble) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        FilledTonalButton(onClick = onToggle) {
            Icon(if (open) Icons.Rounded.Close else Icons.Rounded.Add, null, Modifier.size(18.dp))
            Text(if (open) " 收起" else " 插入")
        }
    }
}

/** The wall's scribbles to drop into the essay, newest first; tapping one inserts a copy in its colour. */
@Composable
private fun ScribblePicker(open: Boolean, scribbles: List<Scribble>, onDismiss: () -> Unit, onPick: (Scribble) -> Unit) {
    val tones = LocalNotionTones.current
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim).clickable(interactionSource = null, indication = null, onClick = onDismiss))
        }
        AnimatedVisibility(
            open,
            Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + expandVertically(LeafMotion.expressive(), expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(LeafMotion.tool(), shrinkTowards = Alignment.Bottom),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .navigationBarsPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("插入涂鸦", style = MaterialTheme.typography.titleMedium)
                if (scribbles.isEmpty()) {
                    Text("涂鸦墙还是空的", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(scribbles, key = { it.id }) { s ->
                        Row(
                            Modifier.fillMaxWidth().clip(scribbleShape(s)).background(tones.fill(s.color)).clickable { onPick(s) }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            s.photo?.let { PictureView(it, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))) }
                            Text(s.text.ifBlank { "照片" }, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
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
