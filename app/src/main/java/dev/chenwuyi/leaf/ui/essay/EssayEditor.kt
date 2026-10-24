package dev.chenwuyi.leaf.ui.essay

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Sync
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
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.util.UUID

/**
 * The essay editor, after Craft: title and blocks edited in place. Enter splits a block, Backspace on an
 * empty one removes it. On a phone a floating bar sits above the keyboard (+ for the insert grid in the
 * keyboard's place, 样式 for the block being edited, photo, scribble); a wide pane keeps the same grid and
 * style controls in a side panel instead. New blocks go after the block being edited. Drag to reorder and
 * dragging in from the side panel are M2.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EssayEditor(
    essay: Essay,
    scribbles: List<Scribble>,
    status: String,
    onBack: () -> Unit,
    onChange: (Essay) -> Unit,
    onRemoveBlock: (String) -> Unit,
    modifier: Modifier = Modifier,
    start: EditorStart = EditorStart(),
) {
    // Typing goes to a local draft first: waiting for Room's round trip would drop fast keystrokes.
    var draft by remember(essay.id) { mutableStateOf(essay) }
    var lastEdit by remember(essay.id) { mutableLongStateOf(0L) }
    LaunchedEffect(essay, lastEdit) {
        // Changes from elsewhere (a body loaded from Notion, a photo copied in) are taken once typing
        // pauses; each keystroke restarts the wait, so an update that arrived mid-typing is not lost.
        val wait = AdoptAfterMillis - (System.currentTimeMillis() - lastEdit)
        if (wait > 0) delay(wait)
        if (!EditorEdits.sameContent(essay, draft)) draft = essay
    }
    val edit: (Essay) -> Unit = { next ->
        draft = next
        lastEdit = System.currentTimeMillis()
        onChange(next)
    }

    var focused by remember(essay.id) { mutableStateOf(start.focus) }
    var focusNext by remember(essay.id) { mutableStateOf<String?>(null) }
    val requesters = remember(essay.id) { mutableStateMapOf<String, FocusRequester>() }
    var panel by remember { mutableStateOf(start.panel) }
    var tab by remember { mutableStateOf(if (start.styleTab) SideTab.Style else SideTab.Insert) }
    var picking by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardUp = WindowInsets.isImeVisible
    val newId = { UUID.randomUUID().toString() }

    fun insert(block: EssayBlock) {
        edit(EditorEdits.insertAfter(draft, focused, listOf(block)))
        if (block is EssayBlock.Paragraph) focusNext = block.id
        panel = ToolPanel.None
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) insert(EssayBlock.Photo(newId(), Picture.Local(uri.toString())))
    }
    val onPick: (InsertKind) -> Unit = { kind ->
        when (kind) {
            InsertKind.Photo -> {
                panel = ToolPanel.None
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            InsertKind.Scribble -> {
                panel = ToolPanel.None
                picking = true
            }
            else -> insert(EditorEdits.blank(kind, newId))
        }
    }
    val onScribble: (Scribble) -> Unit = { s -> EditorEdits.fromScribble(s, newId)?.let(::insert) }
    LaunchedEffect(focusNext, draft.blocks.size) {
        val id = focusNext ?: return@LaunchedEffect
        requesters[id]?.let {
            runCatching { it.requestFocus() }
            keyboard?.show()
            focusNext = null
        }
    }
    // The insert grid stands in for the keyboard: tapping back into the text brings the keyboard and drops the grid.
    LaunchedEffect(keyboardUp) { if (keyboardUp && panel == ToolPanel.Insert) panel = ToolPanel.None }

    val styling = draft.blocks.firstOrNull { it.id == focused } as? EssayBlock.Paragraph
    val restyle: (String) -> Unit = { type -> styling?.let { edit(EditorEdits.restyle(draft, it.id, type)) } }
    val recolor: (NotionColor) -> Unit = { color -> styling?.let { edit(EditorEdits.recolor(draft, it.id, color)) } }
    val pickable = EditorEdits.pickable(scribbles)
    val byId = scribbles.associateBy { it.id }
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        // A wide editor pane (a tablet's, beside the list) keeps Craft's side panel instead of the floating bar.
        val wide = maxWidth >= SidePanelFrom
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().statusBarsPadding().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
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
                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = if (wide) 40.dp else 120.dp),
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
                                    if (block.type == "numbered_list_item") EditorEdits.numberOf(draft, block.id) else 0,
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
                    if (!wide) {
                        Column(
                            Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AnimatedVisibility(
                                panel == ToolPanel.Style,
                                enter = fadeIn() + scaleIn(LeafMotion.expressive(), initialScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 1f)),
                                exit = fadeOut() + scaleOut(LeafMotion.tool(), targetScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 1f)),
                            ) {
                                StyleControls(
                                    styling,
                                    restyle,
                                    recolor,
                                    Modifier
                                        .widthIn(max = 460.dp)
                                        .shadow(8.dp, RoundedCornerShape(28.dp))
                                        .clip(RoundedCornerShape(28.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .padding(16.dp),
                                )
                            }
                            EditorToolbar(
                                panel = panel,
                                canStyle = styling != null,
                                keyboardUp = keyboardUp,
                                onPanel = { next ->
                                    if (next == ToolPanel.Insert) keyboard?.hide()
                                    panel = next
                                },
                                onPhoto = { onPick(InsertKind.Photo) },
                                onScribble = { onPick(InsertKind.Scribble) },
                                onHideKeyboard = {
                                    panel = ToolPanel.None
                                    keyboard?.hide()
                                },
                            )
                        }
                    }
                }
                if (!wide) {
                    AnimatedVisibility(
                        panel == ToolPanel.Insert,
                        enter = expandVertically(LeafMotion.expressive(), expandFrom = Alignment.Top),
                        exit = shrinkVertically(LeafMotion.tool(), shrinkTowards = Alignment.Top),
                    ) {
                        InsertGrid(
                            pickable,
                            onPick,
                            onScribble,
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 340.dp)
                                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp),
                        )
                    }
                }
            }
            if (wide) {
                SidePanel(tab, { tab = it }, Modifier.width(300.dp).fillMaxHeight()) { shown ->
                    when (shown) {
                        SideTab.Insert -> InsertGrid(pickable, onPick, onScribble, scribblesStacked = true)
                        SideTab.Style -> StyleControls(styling, restyle, recolor)
                    }
                }
            }
        }
        ScribblePicker(
            open = picking,
            scribbles = pickable,
            onDismiss = { picking = false },
            onPick = { s ->
                picking = false
                onScribble(s)
            },
        )
    }
}

/** Where the editor's tools start. Always closed in the app; the screenshot tests open a panel straight away. */
data class EditorStart(val panel: ToolPanel = ToolPanel.None, val focus: String? = null, val styleTab: Boolean = false)

private enum class SideTab(val label: String) { Insert("插入"), Style("样式") }

/** The tablet's right-hand panel, Craft's Insert and Format tabs: always there, so nothing covers the page. */
@Composable
private fun SidePanel(tab: SideTab, onTab: (SideTab) -> Unit, modifier: Modifier, content: @Composable (SideTab) -> Unit) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SideTab.entries.forEach { t ->
                val on = t == tab
                Text(
                    t.label,
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent)
                        .clickable(role = Role.Tab) { onTab(t) }
                        .semantics { selected = on }
                        .padding(vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "side") { content(it) }
        }
    }
}

private val SidePanelFrom = 760.dp

private const val AdoptAfterMillis = 1_500L

@Composable
private fun TextBlock(block: EssayBlock.Paragraph, number: Int, modifier: Modifier, onText: (String) -> Unit) {
    val tones = LocalNotionTones.current
    val tinted = block.color != NotionColor.Default
    val style = styleFor(block.type)
    val frame = if (tinted) Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tones.fill(block.color)).padding(horizontal = 14.dp, vertical = 8.dp) else Modifier.fillMaxWidth()
    val bar = MaterialTheme.colorScheme.primary
    Row(frame.padding(top = if (block.type.startsWith("heading")) 8.dp else 0.dp), verticalAlignment = Alignment.Top) {
        when (block.type) {
            "bulleted_list_item" -> Text("•", style = style, modifier = Modifier.width(24.dp), textAlign = TextAlign.Center)
            "numbered_list_item" -> Text("$number.", style = style, modifier = Modifier.width(28.dp))
        }
        val quote = if (block.type == "quote") {
            // Drawn rather than laid out, so the bar is exactly as tall as the quote however many lines it runs.
            Modifier.drawBehind { drawRoundRect(bar, size = Size(3.dp.toPx(), size.height), cornerRadius = CornerRadius(2.dp.toPx())) }.padding(start = 16.dp)
        } else {
            Modifier
        }
        Box(Modifier.weight(1f).then(quote)) {
            PlainField(block.text, placeholderFor(block.type), style, modifier, onText)
        }
    }
}

/** Heading sizes step down from the essay title, so a 大标题 inside the essay never reads as its title. */
private fun styleFor(type: String): TextStyle = when (type) {
    "heading_1" -> EditorTitleStyle.copy(fontSize = 25.sp, lineHeight = 34.sp)
    "heading_2" -> EditorTitleStyle.copy(fontSize = 22.sp, lineHeight = 30.sp)
    "heading_3" -> EditorTitleStyle.copy(fontSize = 19.sp, lineHeight = 28.sp)
    "quote" -> EditorBodyStyle.copy(fontStyle = FontStyle.Italic)
    else -> EditorBodyStyle
}

private fun placeholderFor(type: String): String = when (type) {
    "heading_1" -> "大标题"
    "heading_2" -> "标题"
    "heading_3" -> "小标题"
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
