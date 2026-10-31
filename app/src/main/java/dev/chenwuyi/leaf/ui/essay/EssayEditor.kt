package dev.chenwuyi.leaf.ui.essay

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.chenwuyi.leaf.data.BlockOrder
import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.data.WallRules
import dev.chenwuyi.leaf.data.sync.SyncStatus
import dev.chenwuyi.leaf.ui.component.PictureView
import dev.chenwuyi.leaf.ui.component.scribbleShape
import dev.chenwuyi.leaf.ui.theme.EditorBodyStyle
import dev.chenwuyi.leaf.ui.theme.EditorTitleStyle
import dev.chenwuyi.leaf.ui.theme.EssayHeadlineStyle
import dev.chenwuyi.leaf.ui.theme.LeafMotion
import dev.chenwuyi.leaf.ui.theme.LocalNotionTones
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the editor needs to know about Notion: whether it is connected at all, and the sync engine's state. */
data class EditorSync(val connected: Boolean, val status: SyncStatus = SyncStatus.Idle)

/**
 * The essay editor, after Craft, laid out like a magazine page: the cover runs edge to edge under the
 * status bar, then the title set large, then a quiet line with the day, the tags and a small sync mark.
 * The bar over it is clear until the title scrolls away, then fills in and takes the title over; it
 * says 同步中 / 已同步到 Notion for a moment after an edit and is otherwise quiet.
 *
 * Title and blocks are edited in place. Enter splits a block, Backspace on an empty one removes it. A
 * block is moved by its handle (the text block being edited shows one in the margin) or, for photos and
 * rules, by a long press; a long press on a photo that does not move opens its menu instead. On a phone
 * a floating bar sits above the keyboard; a wide pane keeps the same tools in a side panel.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EssayEditor(
    essay: Essay,
    scribbles: List<Scribble>,
    sync: EditorSync,
    onBack: () -> Unit,
    onChange: (Essay) -> Unit,
    onRemoveBlock: (String) -> Unit,
    modifier: Modifier = Modifier,
    onMoveBlock: (blockId: String, toIndex: Int) -> Unit = { _, _ -> },
    onCover: (Picture?) -> Unit = {},
    start: EditorStart = EditorStart(),
    onOpenFile: (EssayBlock.Attachment) -> Unit = {},
    onSavePhoto: (EssayBlock.Photo) -> Unit = {},
) {
    // Typing goes to a local draft first: waiting for Room's round trip would drop fast keystrokes.
    var draft by remember(essay.id) { mutableStateOf(essay) }
    var lastEdit by remember(essay.id) { mutableLongStateOf(0L) }
    val list = rememberLazyListState()
    val drag = remember(list) { BlockDrag(list) }
    LaunchedEffect(essay, lastEdit, drag.draggingId) {
        // Changes from elsewhere (a body loaded from Notion, a photo copied in) are taken once typing
        // pauses; each keystroke restarts the wait, so an update that arrived mid-typing is not lost. A
        // block being dragged keeps the draft's order until it is dropped.
        if (drag.draggingId != null) return@LaunchedEffect
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
    var outlineOpen by remember { mutableStateOf(start.outline) }
    var captioning by remember(essay.id) { mutableStateOf<String?>(null) }
    // The photo open full screen, by block id.
    var viewing by remember(essay.id) { mutableStateOf(start.photo) }
    val setCaption = { id: String, caption: String ->
        edit(draft.copy(blocks = draft.blocks.map { if (it.id == id && it is EssayBlock.Photo) it.copy(caption = caption.replace("\n", "")) else it }))
    }
    val removePhoto = { id: String ->
        if (viewing == id) viewing = null
        draft = EditorEdits.remove(draft, id)
        lastEdit = System.currentTimeMillis()
        onRemoveBlock(id)
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardUp = WindowInsets.isImeVisible
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val newId = { UUID.randomUUID().toString() }

    fun insert(blocks: List<EssayBlock>, focus: String?) {
        edit(EditorEdits.insertAfter(draft, focused, blocks))
        if (focus != null) focusNext = focus
        panel = ToolPanel.None
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) insert(listOf(EssayBlock.Photo(newId(), Picture.Local(uri.toString()))), null)
    }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onCover(Picture.Local(uri.toString()))
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
            InsertKind.Divider -> {
                // A rule, then a line to keep writing on, as Craft does.
                val line = EssayBlock.Paragraph(newId(), "")
                insert(listOf(EssayBlock.Other(newId(), EssayBlock.Divider), line), line.id)
            }
            else -> EditorEdits.blank(kind, newId).let { insert(listOf(it), it.id) }
        }
    }
    val onScribble: (Scribble) -> Unit = { s -> EditorEdits.fromScribble(s, newId)?.let { insert(listOf(it), (it as? EssayBlock.Paragraph)?.id) } }
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
    BackHandler(outlineOpen) { outlineOpen = false }

    val flash = rememberSyncFlash(sync, lastEdit)
    val styling = draft.blocks.firstOrNull { it.id == focused } as? EssayBlock.Paragraph
    val restyle: (String) -> Unit = { type -> styling?.let { edit(EditorEdits.restyle(draft, it.id, type)) } }
    val recolor: (NotionColor) -> Unit = { color -> styling?.let { edit(EditorEdits.recolor(draft, it.id, color)) } }
    val pickable = EditorEdits.pickable(scribbles)
    val byId = scribbles.associateBy { it.id }
    val headings = EditorEdits.headings(draft)
    val cover = essay.cover
    // The cover (or the space the bar takes without one), the title and the date line come before the blocks.
    val headerItems = 3

    // Drag to reorder: the draft moves live under the finger; Room and Notion hear of it once, on the drop.
    val blockIds = draft.blocks.map { it.id }
    val dragBy by rememberUpdatedState<(Float) -> Unit> { dy ->
        drag.drag(dy, blockIds) { to ->
            val id = drag.draggingId ?: return@drag
            draft = draft.copy(blocks = BlockOrder.move(draft.blocks, id, to))
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    val startDrag by rememberUpdatedState<(String) -> Unit> { id ->
        drag.start(id, draft.blocks.indexOfFirst { it.id == id })
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val endDrag by rememberUpdatedState<() -> Boolean> {
        // True when the block really moved.
        val id = drag.draggingId
        val to = draft.blocks.indexOfFirst { it.id == id }
        val moved = id != null && to >= 0 && to != drag.startIndex
        if (moved) {
            lastEdit = System.currentTimeMillis()
            onMoveBlock(id!!, to)
        }
        drag.end()
        moved
    }
    val density = LocalDensity.current
    val barHeight = 64.dp
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barPx = with(density) { (barHeight + statusTop).toPx() }
    LaunchedEffect(drag.draggingId) {
        if (drag.draggingId == null) return@LaunchedEffect
        val edge = with(density) { 72.dp.toPx() }
        val speed = with(density) { 14.dp.toPx() }
        while (drag.draggingId != null) {
            withFrameNanos { }
            val step = drag.edgeSpeed(edge, barPx, speed)
            if (step != 0f) {
                val moved = list.scrollBy(step)
                if (moved != 0f) dragBy(moved)
            }
        }
    }

    // The bar fills in once the cover (or, without one, the first lines) has gone under it, and takes
    // the title over once the page's own title has scrolled away.
    val solid by remember(cover != null) {
        derivedStateOf {
            val pastTop = if (cover != null) list.firstVisibleItemIndex > 0 else list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 8
            if (pastTop) 1f else 0f
        }
    }
    val showTitle by remember { derivedStateOf { list.firstVisibleItemIndex >= 2 } }
    val fill by animateFloatAsState(solid, LeafMotion.tool(), label = "bar")

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        // A wide editor pane (a tablet's, beside the list) keeps Craft's side panel instead of the floating bar.
        val wide = maxWidth >= SidePanelFrom
        Row(Modifier.fillMaxSize()) {
            // Edge to edge: only the keyboard pushes the page up; the status and navigation bars lie over it.
            Column(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(WindowInsets.ime)) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val navBottom = if (keyboardUp) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = list,
                        contentPadding = PaddingValues(bottom = navBottom + if (wide) 40.dp else 120.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (cover != null) {
                            item(key = "cover") {
                                CoverImage(cover, statusTop + CoverHeight, onPick = { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, onRemove = { onCover(null) })
                            }
                        } else {
                            item(key = "top") { Spacer(Modifier.height(statusTop + barHeight - 10.dp)) }
                        }
                        item(key = "title") {
                            Readable(Modifier.padding(top = if (cover != null) 14.dp else 0.dp)) {
                                PlainField(draft.title, "标题", EssayHeadlineStyle, Modifier.onFocusChanged { if (it.isFocused) focused = null }) {
                                    edit(draft.copy(title = it.replace("\n", "")))
                                }
                            }
                        }
                        item(key = "meta") {
                            Readable(Modifier.padding(bottom = 14.dp)) {
                                MetaLine(essay, sync, flash)
                            }
                        }
                        items(draft.blocks, key = { it.id }) { block ->
                            val requester = remember(block.id) { FocusRequester().also { requesters[block.id] = it } }
                            val track = Modifier.focusRequester(requester).onFocusChanged {
                                if (it.isFocused) focused = block.id
                                if (!it.isFocused && captioning == block.id && (block as? EssayBlock.Photo)?.caption.isNullOrEmpty()) captioning = null
                            }
                            val dragging = drag.draggingId == block.id
                            val lift = if (dragging) {
                                Modifier.zIndex(1f).graphicsLayer {
                                    translationY = drag.offset
                                    scaleX = 1.02f
                                    scaleY = 1.02f
                                    shadowElevation = 12.dp.toPx()
                                    shape = RoundedCornerShape(18.dp)
                                    clip = false
                                }
                            } else {
                                Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = LeafMotion.expressive())
                            }
                            // Photos and rules move on a long press; text keeps its long press for selecting words.
                            val longPressDrag = if (block is EssayBlock.Photo || (block is EssayBlock.Other && BlockOrder.movable(block))) {
                                Modifier.pointerInput(block.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { startDrag(block.id) },
                                        onDragEnd = { if (!endDrag() && block is EssayBlock.Photo) captioning = MenuFor + block.id },
                                        onDragCancel = { endDrag() },
                                    ) { change, amount ->
                                        change.consume()
                                        dragBy(amount.y)
                                    }
                                }
                            } else {
                                Modifier
                            }
                            Readable(lift) {
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
                                        captionOpen = captioning == block.id || block.caption.isNotEmpty(),
                                        menuOpen = captioning == MenuFor + block.id,
                                        pictureModifier = longPressDrag.pointerInput(block.id) {
                                            // Only a short tap opens it: a held press belongs to the drag and the menu.
                                            detectTapGestures(onPress = {
                                                val at = System.currentTimeMillis()
                                                if (tryAwaitRelease() && System.currentTimeMillis() - at < viewConfiguration.longPressTimeoutMillis) viewing = block.id
                                            })
                                        },
                                        captionModifier = track,
                                        onMenuDismiss = { captioning = null },
                                        onCaptionStart = {
                                            captioning = block.id
                                            focusNext = block.id
                                        },
                                        onCaption = { caption -> setCaption(block.id, caption) },
                                        onSave = {
                                            captioning = null
                                            onSavePhoto(block)
                                        },
                                        onRemove = {
                                            captioning = null
                                            removePhoto(block.id)
                                        },
                                    )
                                    is EssayBlock.SyncedScribble -> SyncedScribbleBlock(byId[block.scribbleId])
                                    is EssayBlock.Attachment -> AttachmentBlock(block) { onOpenFile(block) }
                                    is EssayBlock.Other -> when (block.type) {
                                        EssayBlock.Divider -> HorizontalDivider(
                                            longPressDrag.fillMaxWidth().padding(vertical = 14.dp),
                                            thickness = 1.dp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
                                        )
                                        EssayBlock.TableOfContents -> OutlineKey { outlineOpen = true }
                                        else -> Text(
                                            "Notion 里的「${block.type}」块，Leaf 先不改它",
                                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                // The text block being edited shows its handle in the margin; dragging it moves the block.
                                // One holding more in Notion than Leaf can write back (children, formatting) has none.
                                if (block is EssayBlock.Paragraph && BlockOrder.movable(block)) {
                                    val handle by animateFloatAsState(if (focused == block.id || dragging) 1f else 0f, LeafMotion.tool(), label = "handle")
                                    if (handle > 0f) {
                                        Box(
                                            Modifier
                                                .align(Alignment.TopStart)
                                                .offset(x = (-26).dp, y = if (block.type.startsWith("heading")) 10.dp else 4.dp)
                                                .size(24.dp, 28.dp)
                                                .graphicsLayer { alpha = handle }
                                                .pointerInput(block.id) {
                                                    detectDragGestures(
                                                        onDragStart = { startDrag(block.id) },
                                                        onDragEnd = { endDrag() },
                                                        onDragCancel = { endDrag() },
                                                    ) { change, amount ->
                                                        change.consume()
                                                        dragBy(amount.y)
                                                    }
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(Icons.Rounded.DragIndicator, "拖动这一段", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    EditorTopBar(
                        title = draft.title,
                        flash = flash,
                        showTitle = showTitle,
                        fill = fill,
                        overCover = cover != null,
                        hasOutline = headings.isNotEmpty(),
                        hasCover = cover != null,
                        onBack = onBack,
                        onOutline = { outlineOpen = true },
                        onPickCover = { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onRemoveCover = { onCover(null) },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    if (!wide) {
                        Column(
                            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
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
                                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .navigationBarsPadding()
                                .heightIn(max = 340.dp)
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
        PhotoViewer(
            photo = viewing?.let { id -> draft.blocks.firstOrNull { it.id == id } as? EssayBlock.Photo },
            onDismiss = { viewing = null },
            onCaption = { caption -> viewing?.let { setCaption(it, caption) } },
            onSave = { (draft.blocks.firstOrNull { it.id == viewing } as? EssayBlock.Photo)?.let(onSavePhoto) },
            onDelete = { viewing?.let(removePhoto) },
        )
        OutlinePanel(
            open = outlineOpen,
            headings = headings,
            onDismiss = { outlineOpen = false },
            onPick = { id ->
                outlineOpen = false
                val at = draft.blocks.indexOfFirst { it.id == id }
                if (at >= 0) {
                    scope.launch {
                        list.animateScrollToItem(headerItems + at)
                        // Clear of the bar lying over the top of the list.
                        list.animateScrollBy(-barPx)
                    }
                }
            },
        )
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
data class EditorStart(val panel: ToolPanel = ToolPanel.None, val focus: String? = null, val styleTab: Boolean = false, val outline: Boolean = false, val photo: String? = null)

private enum class SideTab(val label: String) { Insert("插入"), Style("样式") }

// The photo whose menu is open is kept in the same slot as the one being captioned, marked with this.
private const val MenuFor = "menu:"

private val CoverHeight = 248.dp

/** One reading column: full width on a phone, a centred 720dp measure on a tablet, the page's side margins. */
@Composable
private fun Readable(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 28.dp), content = content)
}

/**
 * 同步中 after an edit, then 已同步到 Notion for three seconds once a sync run has finished, then
 * nothing. A run that never starts (offline with nothing to say) clears it too. Null when quiet.
 */
@Composable
private fun rememberSyncFlash(sync: EditorSync, lastEdit: Long): String? {
    var flash by remember { mutableStateOf<String?>(null) }
    var sawRun by remember { mutableStateOf(false) }
    val status by rememberUpdatedState(sync.status)
    LaunchedEffect(lastEdit) {
        if (lastEdit == 0L || !sync.connected) return@LaunchedEffect
        flash = Syncing
        delay(SyncStartsWithinMillis)
        if (!sawRun && status !is SyncStatus.Running) flash = null
    }
    LaunchedEffect(sync.status) {
        if (flash == null && !sawRun) return@LaunchedEffect
        when (val s = sync.status) {
            SyncStatus.Running -> {
                sawRun = true
                flash = Syncing
            }
            SyncStatus.Idle -> if (sawRun) {
                sawRun = false
                flash = "已同步到 Notion"
                delay(FlashMillis)
                flash = null
            }
            is SyncStatus.Waiting, is SyncStatus.NeedsAttention -> {
                sawRun = false
                flash = if (s is SyncStatus.Waiting) "稍后再同步" else "同步出了问题，去设置看看"
                delay(FlashMillis)
                flash = null
            }
        }
    }
    return flash
}

private const val Syncing = "同步中…"
private const val FlashMillis = 3_000L
private const val SyncStartsWithinMillis = 15_000L

/** Clear over the cover, filling in with the page's colour as [fill] goes to 1; back, then a quiet middle, then 目录 and ⋮. */
@Composable
private fun EditorTopBar(
    title: String,
    flash: String?,
    showTitle: Boolean,
    fill: Float,
    overCover: Boolean,
    hasOutline: Boolean,
    hasCover: Boolean,
    onBack: () -> Unit,
    onOutline: () -> Unit,
    onPickCover: () -> Unit,
    onRemoveCover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surfaceContainerLowest
    // Over a picture the keys sit on small discs so they read on any cover; the discs fade as the bar fills.
    val disc = if (overCover) surface.copy(alpha = 0.78f * (1f - fill)) else Color.Transparent
    Box(modifier.fillMaxWidth().drawBehind { drawRect(surface.copy(alpha = fill)) }) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BarKey(disc, onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
            Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                val line = flash ?: if (showTitle) title.ifBlank { "无标题" } else ""
                AnimatedContent(
                    targetState = line to (flash != null),
                    transitionSpec = {
                        (fadeIn(LeafMotion.tool()) + slideInVertically(LeafMotion.expressive()) { it / 2 }) togetherWith
                            (fadeOut(LeafMotion.tool()) + slideOutVertically(LeafMotion.tool()) { -it / 2 })
                    },
                    contentAlignment = Alignment.Center,
                    label = "bar line",
                ) { (text, isFlash) ->
                    if (isFlash) {
                        Text(
                            text,
                            Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)).padding(horizontal = 12.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    } else if (text.isNotEmpty()) {
                        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (hasOutline) BarKey(disc, onOutline) { Icon(Icons.AutoMirrored.Rounded.List, "目录") }
            Box {
                var menu by remember { mutableStateOf(false) }
                BarKey(disc, { menu = true }) { Icon(Icons.Rounded.MoreVert, "更多") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (hasCover) "换封面" else "添加封面") },
                        leadingIcon = { Icon(Icons.Rounded.Image, null) },
                        onClick = {
                            menu = false
                            onPickCover()
                        },
                    )
                    if (hasCover) {
                        DropdownMenuItem(
                            text = { Text("移除封面") },
                            leadingIcon = { Icon(Icons.Rounded.HideImage, null) },
                            onClick = {
                                menu = false
                                onRemoveCover()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BarKey(disc: Color, onClick: () -> Unit, icon: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.padding(2.dp).clip(CircleShape).background(disc)) { icon() }
}

/** The cover, edge to edge and under the status bar; a tap offers to change or remove it. */
@Composable
private fun CoverImage(cover: Picture, height: androidx.compose.ui.unit.Dp, onPick: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        PictureView(cover, Modifier.fillMaxWidth().height(height).clickable(onClickLabel = "封面") { menu = true })
        DropdownMenu(menu, onDismissRequest = { menu = false }, modifier = Modifier) {
            DropdownMenuItem(text = { Text("换封面") }, leadingIcon = { Icon(Icons.Rounded.Image, null) }, onClick = { menu = false; onPick() })
            DropdownMenuItem(text = { Text("移除封面") }, leadingIcon = { Icon(Icons.Rounded.HideImage, null) }, onClick = { menu = false; onRemove() })
        }
    }
}

/** Under the title: the day it was written, its tags, and a small mark for where it stands with Notion. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaLine(essay: Essay, sync: EditorSync, flash: String?) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val day = (essay.createdAt ?: essay.editedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        Text(fullDay(day), Modifier.padding(vertical = 3.dp), style = MaterialTheme.typography.labelMedium, color = quiet)
        essay.tags.forEach { tag ->
            Text(
                tag,
                Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)).padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium,
                color = quiet,
            )
        }
        val (icon, label) = when {
            !sync.connected -> Icons.Rounded.CloudOff to "示例数据"
            sync.status is SyncStatus.NeedsAttention -> Icons.Rounded.ErrorOutline to "需要处理"
            sync.status is SyncStatus.Running || flash == Syncing -> Icons.Rounded.Sync to "同步中"
            sync.status is SyncStatus.Waiting -> Icons.Rounded.Sync to "稍后同步"
            else -> Icons.Rounded.CloudDone to "已同步到 Notion"
        }
        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(13.dp), tint = quiet.copy(alpha = 0.8f))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = quiet.copy(alpha = 0.8f))
        }
    }
}

private fun fullDay(day: LocalDate): String =
    if (day.year == LocalDate.now().year) WallRules.dayLabel(day) else "${day.year}年${WallRules.dayLabel(day)}"

/** Notion's table of contents block in the text: a small key that opens the outline from the right. */
@Composable
private fun OutlineKey(onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClickLabel = "打开目录", onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.List, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text("目录", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The outline, a sheet pulled in from the right edge over a scrim; a heading takes the page to it. */
@Composable
private fun OutlinePanel(open: Boolean, headings: List<EssayBlock.Paragraph>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(LeafMotion.tool()), exit = fadeOut(LeafMotion.tool())) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim).clickable(interactionSource = null, indication = null, onClick = onDismiss))
        }
        AnimatedVisibility(
            open,
            Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(LeafMotion.expressive()) { it },
            exit = slideOutHorizontally(LeafMotion.tool()) { it },
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.8f)
                    .widthIn(max = 340.dp)
                    .clip(RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(vertical = 16.dp),
            ) {
                Text("目录", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                if (headings.isEmpty()) {
                    Text("这篇还没有标题", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(headings, key = { it.id }) { h ->
                        val level = when (h.type) { "heading_1" -> 0; "heading_2" -> 1; else -> 2 }
                        Text(
                            h.text,
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onPick(h.id) }
                                .padding(start = 16.dp + 16.dp * level, end = 16.dp, top = 11.dp, bottom = 11.dp),
                            style = if (level == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                            color = if (level == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

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
@Composable
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

/**
 * A photo as it reads: just the picture, and its caption under it when it has one. A long press either
 * drags it ([pictureModifier]) or, let go in place, opens its menu (PhotoMenuItems); a tap opens it full screen.
 */
@Composable
private fun PhotoBlock(
    block: EssayBlock.Photo,
    captionOpen: Boolean,
    menuOpen: Boolean,
    pictureModifier: Modifier,
    captionModifier: Modifier,
    onMenuDismiss: () -> Unit,
    onCaptionStart: () -> Unit,
    onCaption: (String) -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            PictureView(block.picture, pictureModifier.fillMaxWidth().heightIn(min = 160.dp).height(220.dp).clip(RoundedCornerShape(24.dp)))
            DropdownMenu(menuOpen, onDismissRequest = onMenuDismiss) {
                PhotoMenuItems(block.caption.isNotEmpty(), onCaption = onCaptionStart, onSave = onSave, onDelete = onRemove)
            }
        }
        AnimatedVisibility(captionOpen, enter = fadeIn(LeafMotion.tool()) + expandVertically(LeafMotion.expressive()), exit = fadeOut(LeafMotion.tool()) + shrinkVertically(LeafMotion.tool())) {
            PlainField(block.caption, "写一句标注", MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant), captionModifier, onCaption)
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
/** A Notion file, PDF, video or audio block: its name and kind; a tap opens it in whatever app handles it. */
@Composable
private fun AttachmentBlock(block: EssayBlock.Attachment, onOpen: () -> Unit) {
    val icon = when (block.type) {
        "pdf" -> Icons.Rounded.PictureAsPdf
        "video" -> Icons.Rounded.Movie
        "audio" -> Icons.Rounded.Audiotrack
        else -> Icons.Rounded.Description
    }
    val kind = when (block.type) {
        "pdf" -> "PDF"
        "video" -> "视频"
        "audio" -> "音频"
        else -> block.name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 && it != block.name }?.uppercase() ?: "文件"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(block.name.ifBlank { "附件" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, "打开", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

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
