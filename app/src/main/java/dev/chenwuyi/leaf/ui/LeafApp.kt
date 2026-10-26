package dev.chenwuyi.leaf.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chenwuyi.leaf.data.HomeFilter
import dev.chenwuyi.leaf.data.PaneLayout
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.paneLayout
import dev.chenwuyi.leaf.ui.capture.Capture
import dev.chenwuyi.leaf.ui.capture.CaptureSheet
import dev.chenwuyi.leaf.ui.capture.VoiceSession
import dev.chenwuyi.leaf.ui.capture.canRecord
import dev.chenwuyi.leaf.data.voice.VoiceRecognizers
import dev.chenwuyi.leaf.ui.component.BackPreview
import dev.chenwuyi.leaf.ui.component.LocalSharedTransitionScope
import dev.chenwuyi.leaf.ui.component.PagePredictiveBack
import dev.chenwuyi.leaf.ui.component.backPose
import dev.chenwuyi.leaf.ui.component.rememberBackPreview
import dev.chenwuyi.leaf.ui.component.LocalWallVisibilityScope
import dev.chenwuyi.leaf.ui.essay.EditorSync
import dev.chenwuyi.leaf.ui.essay.EssayEditor
import dev.chenwuyi.leaf.ui.home.AddKind
import dev.chenwuyi.leaf.ui.home.AddMenu
import dev.chenwuyi.leaf.ui.home.EssayActions
import dev.chenwuyi.leaf.ui.home.HomeDrawer
import dev.chenwuyi.leaf.ui.home.HomePane
import dev.chenwuyi.leaf.ui.theme.LeafMotion
import dev.chenwuyi.leaf.ui.settings.SettingsScreen
import dev.chenwuyi.leaf.ui.settings.SettingsViewModel
import dev.chenwuyi.leaf.ui.wall.WallActions
import dev.chenwuyi.leaf.ui.wall.WallScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LeafApp(vm: LeafViewModel, settings: SettingsViewModel) {
    val allScribbles by vm.scribbles.collectAsStateWithLifecycle()
    val allEssays by vm.essays.collectAsStateWithLifecycle()
    val openId by vm.openEssayId.collectAsStateWithLifecycle()
    val wall by vm.wall.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val shownScribbles by vm.visibleScribbles.collectAsStateWithLifecycle()
    val shownEssays by vm.visibleEssays.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val colors by vm.colorsInUse.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val connection by settings.connection.collectAsStateWithLifecycle()
    val syncStatus by settings.status.collectAsStateWithLifecycle()
    // Saveable, so a theme switch or rotation keeps the page, menu or sheet open instead of dropping to home.
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String, (() -> Unit)?) -> Unit = { message, undo ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, actionLabel = if (undo != null) "撤销" else null, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }

    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var capture by rememberSaveable(stateSaver = CaptureSaver) { mutableStateOf<Capture?>(null) }
    val context = LocalContext.current
    val voice = remember { VoiceSession(VoiceRecognizers.forScribbles(context), scope) }
    // Closing the sheet, or switching it to text or a photo, drops a take still recording.
    LaunchedEffect(capture) { if (capture != Capture.Voice) voice.cancel() }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) capture = Capture.Photo(Picture.Local(uri.toString()))
    }
    val onAdd: (AddKind) -> Unit = { kind ->
        when (kind) {
            AddKind.Photo -> pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            AddKind.Voice -> capture = Capture.Voice
            AddKind.Text -> capture = Capture.Text()
            AddKind.Essay -> vm.newEssay()
        }
    }

    // The page that is drawn on top gets back first, so the handlers go from the bottom page up: the one
    // composed last wins. Pages follow the finger (predictive back); the menu and the sheet just close.
    // Lowest of all: with nothing else open, back on a filtered home goes back to everything.
    BackHandler(filter != HomeFilter.All && wall == null && openId == null) { vm.setFilter(HomeFilter.All) }
    val essayBack = rememberBackPreview()
    val wallBack = rememberBackPreview()
    val settingsBack = rememberBackPreview()
    PagePredictiveBack(wall == null && openId != null, essayBack) { vm.closeEssay() }
    PagePredictiveBack(wall != null && wall?.selecting != true && capture == null, wallBack) { vm.closeWall() }
    BackHandler(wall?.selecting == true && capture == null) { vm.stopSelecting() }
    BackHandler(menuOpen) { menuOpen = false }
    BackHandler(capture != null) { capture = null }
    PagePredictiveBack(settingsOpen, settingsBack) { settingsOpen = false }

    val openEssay = allEssays.firstOrNull { it.id == openId }
    // Keep the wall's last state while it animates away, so the chips have somewhere to fly from.
    var lastWall by remember { mutableStateOf(WallState()) }
    wall?.let { lastWall = it }

    val wallActions = WallActions(
        close = vm::closeWall,
        setSort = vm::setSort,
        startSelecting = vm::startSelecting,
        stopSelecting = vm::stopSelecting,
        toggle = vm::toggleSelected,
        selectAll = vm::selectAll,
        recolor = vm::recolor,
        delete = { ids -> vm.delete(ids) { removed -> notify("删了 ${removed.size} 条") { vm.restore(removed) } } },
        copyToEssay = { ids, essayId ->
            val title = allEssays.firstOrNull { it.id == essayId }?.title?.ifBlank { "无标题" }
            vm.copyToEssay(ids, essayId)
            notify(if (title == null) "${ids.size} 条复制进了一篇新随笔" else "${ids.size} 条复制到了《$title》末尾", null)
        },
        tapScribble = { id -> vm.openWall(id, emptySet()) },
    )

    val essayActions = EssayActions(
        setPinned = vm::setPinned,
        recolor = vm::recolorEssay,
        delete = { e ->
            vm.deleteEssay(e.id)
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val title = e.title.ifBlank { "无标题" }
                // Short, so it is gone before the delete goes out (LeafViewModel.UndoWindowMillis).
                val result = snackbar.showSnackbar("删了《$title》", actionLabel = "撤销", duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) vm.undoDelete(e.id)
            }
        },
    )

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        SharedTransitionLayout {
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                AnimatedContent(
                    targetState = wall != null,
                    // Home never fades: it stays put under the wall, whose surface grows out of the strip
                    // (the shared "wall-surface" bounds) and shrinks back into it. A crossfade of the two
                    // pages showed both at once halfway through.
                    transitionSpec = {
                        if (targetState) {
                            ContentTransform(fadeIn(LeafMotion.tool()), ExitTransition.KeepUntilTransitionsFinished, targetContentZIndex = 1f)
                        } else {
                            ContentTransform(EnterTransition.None, fadeOut(LeafMotion.tool()), targetContentZIndex = -1f)
                        }
                    },
                    label = "wall",
                ) { wallOpen ->
                    CompositionLocalProvider(LocalWallVisibilityScope provides this) {
                        if (wallOpen) {
                            LaunchedEffect(Unit) { wallBack.reset() }
                            WallScreen(lastWall, shownScribbles, allEssays, wallActions, Modifier.backPose(wallBack), title = wallTitle(filter))
                        } else {
                            // Keep's drawer. It opens from the menu icon in the search bar only; an edge swipe
                            // stays the system back gesture.
                            ModalNavigationDrawer(
                                drawerState = drawer,
                                gesturesEnabled = drawer.isOpen,
                                drawerContent = {
                                    HomeDrawer(
                                        drawer, filter, colors, tags,
                                        onPick = { f ->
                                            vm.setFilter(f)
                                            scope.launch { drawer.close() }
                                        },
                                        onOpenWall = {
                                            // The drawer is gone before the wall grows out of the strip, so
                                            // the two never move at once; the wall here means every scribble.
                                            scope.launch {
                                                drawer.close()
                                                vm.setFilter(HomeFilter.All)
                                                vm.openWall(null, emptySet())
                                            }
                                        },
                                    )
                                },
                            ) {
                                Panes(
                                    listContent = { m ->
                                        Box(m) {
                                            HomePane(
                                                shownScribbles, shownEssays, filter, allScribbles, allEssays, openId,
                                                { id, near ->
                                                    // A search hit can lie outside the filter; the wall it opens
                                                    // must still have it, so the filter goes first.
                                                    if (id != null && shownScribbles.none { it.id == id }) vm.setFilter(HomeFilter.All)
                                                    vm.openWall(id, near)
                                                },
                                                vm::openEssay, { settingsOpen = true }, { scope.launch { drawer.open() } },
                                                Modifier.fillMaxSize(),
                                                actions = essayActions,
                                            )
                                            AddMenu(
                                                menuOpen,
                                                { menuOpen = it },
                                                onAdd,
                                                onHoldStart = {
                                                    capture = Capture.Voice
                                                    // Without the permission the sheet opens and its mic asks first.
                                                    if (context.canRecord()) voice.press()
                                                },
                                                onHoldEnd = voice::lift,
                                            )
                                        }
                                    },
                                    editorKey = openEssay?.id,
                                    back = essayBack,
                                    editor = openEssay?.let { e ->
                                        { m ->
                                            EssayEditor(
                                                e, allScribbles, EditorSync(connection != null, syncStatus), vm::closeEssay, vm::updateEssay, { id -> vm.removeBlock(e.id, id) }, m,
                                                onMoveBlock = { id, to -> vm.moveBlock(e.id, id, to) },
                                                onCover = { cover -> vm.setCover(e.id, cover) },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        CaptureSheet(
            capture,
            voice,
            onDismiss = { capture = null },
            onSave = { text, color, photo ->
                capture = null
                vm.addScribble(text, color, photo) { s ->
                    // The sheet says 记下了 itself; the undo waits until it has slid away.
                    scope.launch {
                        delay(LeafMotion.SheetFinishMillis)
                        notify("新涂鸦") { vm.delete(setOf(s.id)) }
                    }
                }
            },
        )
        AnimatedVisibility(settingsOpen, enter = slideInHorizontally(LeafMotion.expressive()) { it }, exit = slideOutHorizontally(LeafMotion.tool()) { it }) {
            LaunchedEffect(Unit) { settingsBack.reset() }
            SettingsScreen(settings, onClose = { settingsOpen = false }, Modifier.backPose(settingsBack))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 100.dp))
    }
}

/**
 * Wide screens start as one full-width list; opening an essay slides the list to 410 dp and the editor
 * comes in beside it, and switching essays there changes the editor in place rather than swapping it at
 * once. Narrow screens swap between the list and the editor. [back] is the editor's back preview.
 */
@Composable
private fun Panes(
    listContent: @Composable (Modifier) -> Unit,
    editorKey: String?,
    back: BackPreview,
    editor: (@Composable (Modifier) -> Unit)?,
) {
    // Keep the last editor while it animates away, so the pane has something to slide out.
    var shown by remember { mutableStateOf(editor) }
    var shownKey by remember { mutableStateOf(editorKey) }
    if (editor != null) {
        shown = editor
        shownKey = editorKey
    }
    LaunchedEffect(editor != null) { if (editor != null) back.reset() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = paneLayout(maxWidth.value.toInt(), essayOpen = true) == PaneLayout.Split
        if (wide) {
            val listWidth by animateDpAsState(if (editor != null) 410.dp else maxWidth, LeafMotion.expressive(), label = "list")
            Row(Modifier.fillMaxSize()) {
                listContent(Modifier.width(listWidth).fillMaxHeight())
                AnimatedVisibility(
                    editor != null,
                    Modifier.weight(1f).fillMaxHeight(),
                    enter = slideInHorizontally(LeafMotion.expressive()) { it / 3 } + fadeIn(LeafMotion.tool()),
                    exit = slideOutHorizontally(LeafMotion.tool()) { it / 3 } + fadeOut(LeafMotion.tool()),
                    label = "editor pane",
                ) {
                    AnimatedContent(
                        targetState = shownKey,
                        // Another essay: the old page leaves at once and the new one settles in, so two
                        // editors are never on screen together.
                        transitionSpec = { (fadeIn(LeafMotion.tool()) + scaleIn(LeafMotion.expressive(), initialScale = 0.96f)) togetherWith ExitTransition.None },
                        label = "editor switch",
                    ) { key ->
                        if (key == shownKey) {
                            shown?.invoke(Modifier.fillMaxSize().padding(10.dp).backPose(back).clip(RoundedCornerShape(32.dp)))
                        }
                    }
                }
            }
        } else {
            AnimatedContent(
                targetState = editor,
                transitionSpec = {
                    if (targetState != null) slideInHorizontally(LeafMotion.expressive()) { it } togetherWith fadeOut()
                    else fadeIn() togetherWith slideOutHorizontally(LeafMotion.tool()) { it }
                },
                contentKey = { it != null },
                label = "editor",
            ) { e ->
                if (e == null) listContent(Modifier.fillMaxSize()) else e(Modifier.fillMaxSize().backPose(back))
            }
        }
    }
}

// Only a picked local photo is worth keeping across recreation; sample and remote pictures never open a sheet.
private val CaptureSaver = Saver<Capture?, String>(
    save = { c ->
        when (c) {
            // A shared or selected text arrives as the sheet's starting line; keep it too.
            is Capture.Text -> "text:" + c.initial
            Capture.Voice -> "voice"
            is Capture.Photo -> (c.picture as? Picture.Local)?.let { "photo:" + it.uri } ?: ""
            null -> ""
        }
    },
    restore = { s ->
        when {
            s.startsWith("text:") -> Capture.Text(s.removePrefix("text:"))
            s == "voice" -> Capture.Voice
            s.startsWith("photo:") -> Capture.Photo(Picture.Local(s.removePrefix("photo:")))
            else -> null
        }
    },
)

/** The wall's heading follows the drawer's pick, so a filtered wall says what it is showing. */
private fun wallTitle(filter: HomeFilter): String = when (filter) {
    HomeFilter.All -> "涂鸦墙"
    is HomeFilter.ByColor -> "${filter.color.label}色"
    is HomeFilter.ByTag -> "#${filter.name}"
}
