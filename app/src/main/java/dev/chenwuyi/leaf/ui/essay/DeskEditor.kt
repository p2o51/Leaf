package dev.chenwuyi.leaf.ui.essay

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chenwuyi.leaf.LeafApplication
import dev.chenwuyi.leaf.R
import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.ui.component.PictureView
import dev.chenwuyi.leaf.ui.theme.LeafMotion
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 专注: off, the sentence being written, or its paragraph; everything else fades. */
enum class DeskFocusMode(val label: String) { Off("全文"), Sentence("句"), Paragraph("段") }

/** The 写作台's three writing faces, all IBM Plex (iA's own fonts ask not to be built into editors). Chinese falls back to the system's Noto CJK. */
enum class DeskFont(val label: String) { Serif("Serif"), Sans("Sans"), Mono("Mono") }

/** The 写作台's switches, kept on this device so the desk opens the way it was left. */
class DeskPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("desk", Context.MODE_PRIVATE)
    var focus: DeskFocusMode
        get() = DeskFocusMode.entries.firstOrNull { it.name == prefs.getString("focus", null) } ?: DeskFocusMode.Sentence
        set(v) = prefs.edit().putString("focus", v.name).apply()
    var typewriter: Boolean
        get() = prefs.getBoolean("typewriter", true)
        set(v) = prefs.edit().putBoolean("typewriter", v).apply()
    var font: DeskFont
        get() = DeskFont.entries.firstOrNull { it.name == prefs.getString("font", null) } ?: DeskFont.Serif
        set(v) = prefs.edit().putString("font", v.name).apply()
    var style: Boolean
        get() = prefs.getBoolean("style", false)
        set(v) = prefs.edit().putBoolean("style", v).apply()
    var origins: Boolean
        get() = prefs.getBoolean("origins", false)
        set(v) = prefs.edit().putBoolean("origins", v).apply()
    var rules: Set<StyleRule>
        get() = prefs.getStringSet("rules", null)?.mapNotNull { n -> StyleRule.entries.firstOrNull { it.name == n } }?.toSet() ?: StyleRule.entries.toSet()
        set(v) = prefs.edit().putStringSet("rules", v.map { it.name }.toSet()).apply()
}

/** 来源 marks per essay, in the app's own files: they describe 51's writing, so they never go to Notion. */
private class DeskOriginStore(context: Context) {
    private val dir = File(context.filesDir, "desk-origins")
    private fun file(essayId: String) = File(dir, essayId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".txt")
    suspend fun load(essayId: String): Map<String, List<OriginSpan>> = withContext(Dispatchers.IO) {
        runCatching { DeskOrigins.decode(file(essayId).readText()) }.getOrDefault(emptyMap())
    }
    suspend fun save(essayId: String, spans: Map<String, List<OriginSpan>>) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val text = DeskOrigins.encode(spans)
            if (text.isEmpty()) file(essayId).delete() else file(essayId).writeText(text)
        }
    }
}

/** One text run on the page: its blocks as last written to Room, and the text being edited now. */
private data class DeskRun(
    val key: String,
    val blocks: List<EssayBlock.Paragraph>,
    val value: TextFieldValue,
    val origins: List<Origin>,
    val written: String,
    val hits: List<StyleHit> = emptyList(),
)

private val PlexSerif = FontFamily(Font(R.font.ibm_plex_serif_400), Font(R.font.ibm_plex_serif_600, FontWeight.SemiBold))
private val PlexSans = FontFamily(Font(R.font.ibm_plex_sans_400), Font(R.font.ibm_plex_sans_600, FontWeight.SemiBold))
private val PlexMono = FontFamily(Font(R.font.ibm_plex_mono_400), Font(R.font.ibm_plex_mono_600, FontWeight.SemiBold))

private fun deskStyle(font: DeskFont, color: Color): TextStyle = when (font) {
    DeskFont.Serif -> TextStyle(fontFamily = PlexSerif, fontSize = 19.sp, lineHeight = 34.sp, color = color)
    DeskFont.Sans -> TextStyle(fontFamily = PlexSans, fontSize = 18.sp, lineHeight = 32.sp, color = color)
    DeskFont.Mono -> TextStyle(fontFamily = PlexMono, fontSize = 17.sp, lineHeight = 31.sp, color = color)
}

/** Edits waiting this long after the last keystroke are written to Room (and so queued for Notion) together. */
private const val WriteAfterMillis = 600L

/** The open essay is fetched again this often while the window is in front and the pen has rested. */
private const val RefreshEveryMillis = 30_000L
private const val RefreshRestMillis = 5_000L

/** How faded the words outside the focus are, as iA does it: still legible, clearly not now. */
private const val FadedAlpha = 0.28f

/**
 * The 写作台: Leaf's editor in 电脑模式 (a hardware keyboard attached, 51's rule of 2026-11-08). The
 * essay is one Markdown text in a single centred column, with photos, files and synced scribbles shown
 * between runs of text. 专注 fades everything but the sentence or paragraph being written, 打字机 keeps
 * the line being written at the middle of the window, 文风 strikes through words to look at twice, 来源
 * colours what was pasted in. Local first: keystrokes stay in the editor, are written to Room once the
 * pen rests, and only then go out to Notion; while the window is open the same page is fetched again
 * every half minute, and nothing else is.
 */
@Composable
internal fun DeskEditor(
    essay: Essay,
    scribbles: List<Scribble>,
    sync: EditorSync,
    onBack: () -> Unit,
    onChange: (Essay) -> Unit,
    onRemoveBlock: (String) -> Unit,
    onOpenFile: (EssayBlock.Attachment) -> Unit,
    onSavePhoto: (EssayBlock.Photo) -> Unit,
    onTabletMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { DeskPrefs(context) }
    val originStore = remember { DeskOriginStore(context) }
    val repository = remember { runCatching { (context.applicationContext as LeafApplication).container.repository }.getOrNull() }
    var focusMode by remember { mutableStateOf(prefs.focus) }
    var typewriter by remember { mutableStateOf(prefs.typewriter) }
    var font by remember { mutableStateOf(prefs.font) }
    var styleOn by remember { mutableStateOf(prefs.style) }
    var originsOn by remember { mutableStateOf(prefs.origins) }
    var rules by remember { mutableStateOf(prefs.rules) }

    var title by remember(essay.id) { mutableStateOf(essay.title) }
    var parts by remember(essay.id) { mutableStateOf(DeskText.parts(essay.blocks)) }
    val runs = remember(essay.id) { mutableStateMapOf<String, DeskRun>() }
    var stored by remember(essay.id) { mutableStateOf<Map<String, List<OriginSpan>>?>(null) }
    var active by remember(essay.id) { mutableStateOf<String?>(null) }
    var lastEdit by remember(essay.id) { mutableLongStateOf(0L) }
    var lastWrite by remember(essay.id) { mutableLongStateOf(0L) }
    var pasting by remember { mutableStateOf(false) }
    var barShown by remember { mutableStateOf(true) }
    var viewing by remember(essay.id) { mutableStateOf<String?>(null) }
    val newId = { UUID.randomUUID().toString() }

    // Lays the page out from [from]: a run whose text is unchanged keeps its cursor and marks.
    fun adopt(from: Essay, spans: Map<String, List<OriginSpan>>) {
        val next = DeskText.parts(from.blocks)
        val keep = runs.toMap()
        runs.clear()
        next.filterIsInstance<DeskPart.Text>().forEach { part ->
            val rendered = DeskText.render(part.blocks)
            val old = keep[part.key]
            runs[part.key] = if (old != null && old.value.text == rendered.text) {
                old.copy(blocks = part.blocks, written = rendered.text)
            } else {
                val laid = DeskOrigins.lay(rendered.text.length, rendered.starts, part.blocks.map { spans[it.id].orEmpty() })
                DeskRun(part.key, part.blocks, TextFieldValue(rendered.text, TextRange(rendered.text.length)), laid, rendered.text)
            }
        }
        parts = next
    }

    LaunchedEffect(essay.id) {
        // The words show at once; 来源 marks follow when the file is read, on runs not yet typed into.
        adopt(essay, emptyMap())
        val spans = originStore.load(essay.id)
        runs.values.toList().forEach { run ->
            if (run.value.text != run.written) return@forEach
            val rendered = DeskText.render(run.blocks)
            runs[run.key] = run.copy(origins = DeskOrigins.lay(rendered.text.length, rendered.starts, run.blocks.map { spans[it.id].orEmpty() }))
        }
        stored = spans
        // Words typed before the marks were read are written now (writing waits for the marks).
        if (runs.values.any { it.value.text != it.written }) lastEdit = System.currentTimeMillis()
    }

    // Writes every run whose text moved since the last write: blocks to Room (and the outbox), marks to the device.
    val write: () -> Unit = write@{
        if (stored == null) return@write
        var changed = false
        val gone = mutableSetOf<String>()
        val nextParts = parts.map { part ->
            if (part !is DeskPart.Text) return@map part
            val run = runs[part.key] ?: return@map part
            if (run.value.text == run.written) return@map part.copy(blocks = run.blocks)
            val (blocks, removed) = DeskText.update(run.blocks, run.value.text, newId)
            gone += removed
            changed = true
            runs[part.key] = run.copy(blocks = blocks, written = run.value.text)
            part.copy(blocks = blocks)
        }
        if (!changed && title == essay.title) return@write
        parts = nextParts
        onChange(essay.copy(title = title, blocks = DeskText.blocks(nextParts)))
        gone.forEach(onRemoveBlock)
        lastWrite = System.currentTimeMillis()
        // Marks follow the blocks: each block's words keep the origins of the characters they came from.
        val marks = HashMap<String, List<OriginSpan>>()
        runs.values.forEach { run ->
            DeskText.parse(run.value.text).zip(run.blocks).forEach { (line, block) ->
                marks[block.id] = DeskOrigins.spans(run.origins, line.textStart, line.text.length)
            }
        }
        stored = marks
    }
    val writeNow by rememberUpdatedState(write)
    LaunchedEffect(lastEdit) {
        if (lastEdit == 0L) return@LaunchedEffect
        delay(WriteAfterMillis)
        writeNow()
    }
    LaunchedEffect(stored) { stored?.let { originStore.save(essay.id, it) } }
    DisposableEffect(essay.id) { onDispose { writeNow() } }

    // Changes from elsewhere (Notion, another device, a photo copied in) are taken once the pen has rested.
    LaunchedEffect(essay, lastEdit) {
        val spans = stored ?: return@LaunchedEffect
        val wait = 1_500L - (System.currentTimeMillis() - lastEdit)
        if (wait > 0) delay(wait)
        val mine = DeskText.blocks(parts)
        if (mine != essay.blocks && runs.values.all { it.value.text == it.written }) adopt(essay, spans)
        if (title != essay.title && System.currentTimeMillis() - lastEdit > 1_500L) title = essay.title
    }

    // 资源集中在当前这篇: while this window is in front and resting, fetch this one page again now and then.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(essay.id, repository) {
        val repo = repository ?: return@LaunchedEffect
        while (true) {
            delay(RefreshEveryMillis)
            if (windowInfo.isWindowFocused && System.currentTimeMillis() - lastEdit > RefreshRestMillis) {
                runCatching { repo.refreshEssayBody(essay.id) }
            }
        }
    }

    // Style marks are worked out off the main thread, a beat after typing, for the run being written.
    val activeText = active?.let { runs[it]?.value?.text }
    LaunchedEffect(active, activeText, styleOn, rules) {
        if (!styleOn) return@LaunchedEffect
        delay(150)
        val snapshot = runs.values.map { it.key to it.value.text }
        val hits = withContext(Dispatchers.Default) { snapshot.associate { (k, t) -> k to DeskStyle.check(t, rules) } }
        hits.forEach { (k, h) -> runs[k]?.let { if (it.value.text == snapshot.first { s -> s.first == k }.second) runs[k] = it.copy(hits = h) } }
    }

    fun setFocus(m: DeskFocusMode) { focusMode = m; prefs.focus = m }
    fun setFont(f: DeskFont) { font = f; prefs.font = f }
    fun toggleTypewriter() { typewriter = !typewriter; prefs.typewriter = typewriter }
    fun toggleStyle() { styleOn = !styleOn; prefs.style = styleOn }
    fun toggleOrigins() { originsOn = !originsOn; prefs.origins = originsOn }

    val ink = MaterialTheme.colorScheme.onSurface
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val pastedColor = MaterialTheme.colorScheme.tertiary
    val body = deskStyle(font, ink)
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val tops = remember(essay.id) { mutableStateMapOf<String, Float>() }
    val layouts = remember(essay.id) { mutableStateMapOf<String, TextLayoutResult>() }
    var viewport by remember { mutableStateOf(0) }
    var windowTop by remember { mutableStateOf(0f) }
    val barAlpha by animateFloatAsState(if (barShown) 1f else 0f, tween(if (barShown) 160 else 420), label = "desk bar")

    // 打字机: the line being written rests a little above the middle of the window.
    val caretKey = active?.let { k -> runs[k]?.let { Triple(k, it.value.selection.start, it.value.text.length) } }
    val activeLayout = active?.let { layouts[it] }
    LaunchedEffect(caretKey, typewriter, activeLayout) {
        if (!typewriter || caretKey == null || viewport == 0) return@LaunchedEffect
        val (key, caret, _) = caretKey
        val layout = layouts[key] ?: return@LaunchedEffect
        val top = tops[key] ?: return@LaunchedEffect
        val rect = runCatching { layout.getCursorRect(caret.coerceIn(0, layout.layoutInput.text.length)) }.getOrNull() ?: return@LaunchedEffect
        // Field and window tops are both in root coordinates, so the caret's place in the window is their difference.
        val target = (scroll.value + top - windowTop + rect.center.y - viewport * 0.45f).roundToInt().coerceIn(0, scroll.maxValue)
        if (kotlin.math.abs(target - scroll.value) > 2) scroll.animateScrollTo(target, tween(140))
    }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { viewport = it.size.height; windowTop = it.positionInRoot().y }
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .pointerInput(Unit) {
                // Any pointer movement brings the bar back; typing fades it.
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        barShown = true
                    }
                }
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val mod = e.isCtrlPressed || e.isMetaPressed
                if (!mod) return@onPreviewKeyEvent false
                when {
                    // Paste goes on to the field; the flag marks what it inserts as pasted.
                    e.key == Key.V -> { pasting = true; false }
                    e.key == Key.D -> { setFocus(DeskFocusMode.entries[(focusMode.ordinal + 1) % DeskFocusMode.entries.size]); true }
                    e.key == Key.T && !e.isShiftPressed -> { toggleTypewriter(); true }
                    e.key == Key.L && e.isShiftPressed -> { toggleStyle(); true }
                    e.key == Key.O && e.isShiftPressed -> { toggleOrigins(); true }
                    e.key == Key.One -> { setFont(DeskFont.Serif); true }
                    e.key == Key.Two -> { setFont(DeskFont.Sans); true }
                    e.key == Key.Three -> { setFont(DeskFont.Mono); true }
                    e.key == Key.S -> { writeNow(); true }
                    else -> false
                }
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .safeDrawingPadding(),
        ) {
            Spacer(Modifier.height(72.dp))
            Column(
                Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 700.dp).fillMaxWidth().padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                BasicTextField(
                    value = title,
                    onValueChange = {
                        title = it.replace("\n", "")
                        lastEdit = System.currentTimeMillis()
                        barShown = false
                    },
                    textStyle = body.copy(fontSize = body.fontSize * 1.35f, lineHeight = body.lineHeight * 1.2f, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (title.isEmpty()) Text("标题", style = body.copy(fontSize = body.fontSize * 1.35f, fontWeight = FontWeight.SemiBold, color = quiet.copy(alpha = 0.5f)))
                            inner()
                        }
                    },
                )
                if (essay.tags.isNotEmpty()) {
                    Text(essay.tags.joinToString("  ") { "#$it" }, style = MaterialTheme.typography.labelMedium, color = quiet)
                }
                parts.forEach { part ->
                    when (part) {
                        is DeskPart.Text -> {
                            val run = runs[part.key] ?: return@forEach
                            val isActive = active == part.key
                            val transform = remember(run.value.text, run.origins, run.hits, isActive, run.value.selection.start, focusMode, originsOn, styleOn, ink, quiet, pastedColor) {
                                DeskLook(
                                    origins = if (originsOn) run.origins else null,
                                    hits = if (styleOn) run.hits else emptyList(),
                                    focus = when {
                                        focusMode == DeskFocusMode.Off -> null
                                        // Nothing fades until 51 puts the cursor somewhere.
                                        active == null -> null
                                        !isActive -> IntRange.EMPTY
                                        focusMode == DeskFocusMode.Sentence -> DeskFocus.sentence(run.value.text, run.value.selection.start)
                                        else -> DeskFocus.paragraph(run.value.text, run.value.selection.start)
                                    },
                                    ink = ink,
                                    quiet = quiet,
                                    pasted = pastedColor,
                                )
                            }
                            BasicTextField(
                                value = run.value,
                                onValueChange = { next ->
                                    val cur = runs[part.key] ?: return@BasicTextField
                                    if (next.text != cur.value.text) {
                                        // A paste (shortcut or menu) or several characters arriving at once
                                        // outside an IME composition reads as pasted; typing and the IME
                                        // committing a word read as typed.
                                        val many = DeskOrigins.insertedLength(cur.value.text, next.text) > 1 && cur.value.composition == null && next.composition == null
                                        val origin = if (pasting || many) Origin.Pasted else Origin.Typed
                                        pasting = false
                                        runs[part.key] = cur.copy(value = next, origins = DeskOrigins.splice(cur.origins, cur.value.text, next.text, origin))
                                        lastEdit = System.currentTimeMillis()
                                        barShown = false
                                    } else {
                                        runs[part.key] = cur.copy(value = next)
                                    }
                                },
                                textStyle = body,
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                visualTransformation = transform,
                                onTextLayout = { layouts[part.key] = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned { tops[part.key] = it.positionInRoot().y }
                                    .onFocusChanged { if (it.isFocused) active = part.key },
                                decorationBox = { inner ->
                                    Box {
                                        if (run.value.text.isEmpty() && parts.size == 1) Text("开始写…", style = body.copy(color = quiet.copy(alpha = 0.5f)))
                                        inner()
                                    }
                                },
                            )
                        }
                        is DeskPart.Shown -> {
                            val faded = focusMode != DeskFocusMode.Off && active != null
                            Box(Modifier.graphicsLayer { alpha = if (faded) FadedAlpha + 0.2f else 1f }) {
                                ShownBlock(part.block, scribbles, body, quiet, onOpenFile) { viewing = it }
                            }
                        }
                    }
                }
            }
            // Room under the last line so 打字机 can bring it to the middle.
            Spacer(Modifier.height(if (typewriter) with(density) { (viewport * 0.55f).toDp() } else 160.dp))
        }

        DeskBar(
            focus = focusMode,
            typewriter = typewriter,
            font = font,
            styleOn = styleOn,
            originsOn = originsOn,
            rules = rules,
            status = rememberSyncFlash(sync, lastWrite) ?: if (!sync.connected) "只存在本机" else "",
            onBack = { writeNow(); onBack() },
            onFocus = ::setFocus,
            onTypewriter = ::toggleTypewriter,
            onFont = ::setFont,
            onStyle = ::toggleStyle,
            onOrigins = ::toggleOrigins,
            onRule = { r -> rules = if (r in rules) rules - r else rules + r; prefs.rules = rules },
            onTabletMode = { writeNow(); onTabletMode() },
            modifier = Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = barAlpha },
        )
        if (originsOn || styleOn) {
            Legend(originsOn, styleOn, pastedColor, quiet, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 14.dp).graphicsLayer { alpha = barAlpha })
        }
        PhotoViewer(
            photo = viewing?.let { id -> DeskText.blocks(parts).firstOrNull { it.id == id } as? EssayBlock.Photo },
            onDismiss = { viewing = null },
            onCaption = { caption ->
                val id = viewing ?: return@PhotoViewer
                val blocks = DeskText.blocks(parts).map { if (it.id == id && it is EssayBlock.Photo) it.copy(caption = caption.replace("\n", "")) else it }
                parts = DeskText.parts(blocks).let { fresh -> fresh.map { p -> if (p is DeskPart.Text) p.copy(blocks = runs[p.key]?.blocks ?: p.blocks) else p } }
                onChange(essay.copy(title = title, blocks = blocks))
            },
            onSave = { (DeskText.blocks(parts).firstOrNull { it.id == viewing } as? EssayBlock.Photo)?.let(onSavePhoto) },
            onDelete = {
                val id = viewing ?: return@PhotoViewer
                viewing = null
                parts = parts.filter { it !is DeskPart.Shown || it.block.id != id }
                onRemoveBlock(id)
            },
        )
    }
}

/**
 * How a run reads: Markdown marks faint, headings set semibold, pasted words in the accent, words to look
 * at again struck through, and everything outside the focus faded. Only colours and weights change, never
 * the characters, so the cursor and the IME see the text exactly as typed.
 */
private class DeskLook(
    private val origins: List<Origin>?,
    private val hits: List<StyleHit>,
    private val focus: IntRange?,
    private val ink: Color,
    private val quiet: Color,
    private val pasted: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val n = s.length
        val out = buildAnnotatedString {
            append(s)
            // Colour per character: pasted or ink, faded outside the focus. Runs of the same colour become one span.
            var i = 0
            while (i < n) {
                val c = colorAt(i)
                var j = i + 1
                while (j < n && colorAt(j) == c) j++
                if (c != ink) addStyle(SpanStyle(color = c), i, j)
                i = j
            }
            for (line in DeskText.parse(s)) {
                val head = s.lastIndexOf('\n', line.textStart - 1) + 1
                if (line.textStart > head) addStyle(SpanStyle(color = faded(quiet.copy(alpha = 0.55f), head)), head, line.textStart)
                if (line.type.startsWith("heading")) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), line.textStart, line.textStart + line.text.length)
            }
            for (h in hits) {
                val a = h.start.coerceIn(0, n)
                val b = h.end.coerceIn(a, n)
                if (a == b) continue
                if (h.rule == StyleRule.Long) {
                    addStyle(SpanStyle(background = quiet.copy(alpha = 0.08f)), a, b)
                } else {
                    addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = faded(quiet.copy(alpha = 0.6f), a)), a, b)
                }
            }
        }
        return TransformedText(out, OffsetMapping.Identity)
    }

    private fun inFocus(i: Int) = focus == null || i in focus

    private fun faded(c: Color, i: Int) = if (inFocus(i)) c else c.copy(alpha = c.alpha * FadedAlpha)

    private fun colorAt(i: Int): Color {
        val base = if (origins != null && origins.getOrNull(i) == Origin.Pasted) pasted else ink
        return faded(base, i)
    }

    // A new look is a new instance each time its inputs change; equality keeps Compose from re-laying out otherwise.
    override fun equals(other: Any?): Boolean = other is DeskLook && other.origins == origins && other.hits == hits && other.focus == focus &&
        other.ink == ink && other.quiet == quiet && other.pasted == pasted
    override fun hashCode(): Int = listOf(origins, hits, focus, ink, quiet, pasted).hashCode()
}

/** A block the 写作台 shows but does not edit as text. */
@Composable
private fun ShownBlock(
    block: EssayBlock,
    scribbles: List<Scribble>,
    body: TextStyle,
    quiet: Color,
    onOpenFile: (EssayBlock.Attachment) -> Unit,
    onView: (String) -> Unit,
) {
    when (block) {
        is EssayBlock.Photo -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PictureView(block.picture, Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(20.dp)).clickable(onClickLabel = "看大图") { onView(block.id) })
            if (block.caption.isNotBlank()) Text(block.caption, style = MaterialTheme.typography.bodyMedium, color = quiet)
        }
        is EssayBlock.Attachment -> AttachmentBlock(block) { onOpenFile(block) }
        is EssayBlock.SyncedScribble -> SyncedScribbleBlock(scribbles.firstOrNull { it.id == block.scribbleId })
        is EssayBlock.Other -> when (block.type) {
            EssayBlock.Divider -> HorizontalDivider(Modifier.padding(vertical = 8.dp), color = quiet.copy(alpha = 0.25f))
            EssayBlock.TableOfContents -> Unit
            else -> Text("Notion 里的「${block.type}」块，写作台先不改它", style = MaterialTheme.typography.bodySmall, color = quiet)
        }
        is EssayBlock.Paragraph -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(block.text, style = body.copy(color = body.color.copy(alpha = 0.85f)))
            Text("带格式的段落，在平板模式或 Notion 里改", style = MaterialTheme.typography.labelSmall, color = quiet.copy(alpha = 0.7f))
        }
    }
}

/** The quiet bar over the page: back, then 专注 · 打字机 · 字体 · 文风 · 来源, and the sync word. Fades while typing. */
@Composable
private fun DeskBar(
    focus: DeskFocusMode,
    typewriter: Boolean,
    font: DeskFont,
    styleOn: Boolean,
    originsOn: Boolean,
    rules: Set<StyleRule>,
    status: String,
    onBack: () -> Unit,
    onFocus: (DeskFocusMode) -> Unit,
    onTypewriter: () -> Unit,
    onFont: (DeskFont) -> Unit,
    onStyle: () -> Unit,
    onOrigins: () -> Unit,
    onRule: (StyleRule) -> Unit,
    onTabletMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.94f)).safeDrawingPadding().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        Segments(DeskFocusMode.entries, focus, { it.label }, onFocus)
        Toggle("打字机", typewriter, onTypewriter)
        Segments(DeskFont.entries, font, { it.label }, onFont)
        Toggle("文风", styleOn, onStyle)
        Toggle("来源", originsOn, onOrigins)
        Spacer(Modifier.weight(1f))
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            var menu by remember { mutableStateOf(false) }
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "更多") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                StyleRule.entries.forEach { r ->
                    DropdownMenuItem(
                        text = { Text(r.label) },
                        leadingIcon = { Box(Modifier.size(24.dp)) { if (r in rules) Icon(Icons.Rounded.Check, null) } },
                        onClick = { onRule(r) },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("用平板模式编辑这一篇") }, onClick = { menu = false; onTabletMode() })
            }
        }
    }
}

@Composable
private fun <T> Segments(items: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEach { item ->
            val on = item == selected
            Text(
                label(item),
                Modifier
                    .clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent)
                    .clickable { onPick(item) }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    val bg by animateFloatAsState(if (on) 1f else 0f, LeafMotion.tool(), label = "toggle")
    Text(
        label,
        Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = bg))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Legend(origins: Boolean, style: Boolean, pasted: Color, quiet: Color, modifier: Modifier) {
    Row(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (origins) {
            Text("● 手打", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("● 粘贴", style = MaterialTheme.typography.labelMedium, color = pasted)
        }
        if (style) Text("划掉的词：再看一眼，不会替你改", style = MaterialTheme.typography.labelMedium, color = quiet)
    }
}
