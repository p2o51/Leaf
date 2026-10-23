package dev.chenwuyi.leaf.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chenwuyi.leaf.data.PaneLayout
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.WallRules
import dev.chenwuyi.leaf.data.paneLayout
import dev.chenwuyi.leaf.ui.capture.Capture
import dev.chenwuyi.leaf.ui.capture.CaptureSheet
import dev.chenwuyi.leaf.ui.capture.VoiceSession
import dev.chenwuyi.leaf.ui.capture.canRecord
import dev.chenwuyi.leaf.data.voice.VoiceRecognizers
import dev.chenwuyi.leaf.ui.component.LocalSharedTransitionScope
import dev.chenwuyi.leaf.ui.component.LocalWallVisibilityScope
import dev.chenwuyi.leaf.ui.essay.EssayEditor
import dev.chenwuyi.leaf.ui.home.AddKind
import dev.chenwuyi.leaf.ui.home.AddMenu
import dev.chenwuyi.leaf.ui.home.HomePane
import dev.chenwuyi.leaf.ui.theme.LeafMotion
import dev.chenwuyi.leaf.ui.settings.SettingsScreen
import dev.chenwuyi.leaf.ui.settings.SettingsViewModel
import dev.chenwuyi.leaf.ui.settings.syncLine
import dev.chenwuyi.leaf.ui.wall.WallActions
import dev.chenwuyi.leaf.ui.wall.WallScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LeafApp(vm: LeafViewModel, settings: SettingsViewModel) {
    val allScribbles by vm.scribbles.collectAsStateWithLifecycle()
    val allEssays by vm.essays.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val openId by vm.openEssayId.collectAsStateWithLifecycle()
    val wall by vm.wall.collectAsStateWithLifecycle()
    val connection by settings.connection.collectAsStateWithLifecycle()
    val syncStatus by settings.status.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String, (() -> Unit)?) -> Unit = { message, undo ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, actionLabel = if (undo != null) "撤销" else null, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<Capture?>(null) }
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
            AddKind.Text -> capture = Capture.Text
            AddKind.Essay -> vm.newEssay()
        }
    }

    BackHandler(settingsOpen) { settingsOpen = false }
    BackHandler(capture != null) { capture = null }
    BackHandler(menuOpen) { menuOpen = false }
    BackHandler(wall != null && capture == null) { if (wall?.selecting == true) vm.stopSelecting() else vm.closeWall() }
    BackHandler(wall == null && openId != null) { vm.closeEssay() }

    val scribbles = WallRules.search(allScribbles, query)
    val essays = WallRules.searchEssays(allEssays, query)
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

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        SharedTransitionLayout {
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                AnimatedContent(
                    targetState = wall != null,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
                    label = "wall",
                ) { wallOpen ->
                    CompositionLocalProvider(LocalWallVisibilityScope provides this) {
                        if (wallOpen) {
                            WallScreen(lastWall, allScribbles, allEssays, wallActions)
                        } else {
                            Panes(
                                listContent = { m ->
                                    Box(m) {
                                        HomePane(scribbles, essays, query, openId, vm::setQuery, vm::openWall, vm::openEssay, { settingsOpen = true }, Modifier.fillMaxSize())
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
                                editor = openEssay?.let { e ->
                                    { m -> EssayEditor(e, allScribbles, syncLine(connection, syncStatus), vm::closeEssay, vm::updateEssay, m) }
                                },
                            )
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
                vm.addScribble(text, color, photo) { s -> notify("记下了") { vm.delete(setOf(s.id)) } }
            },
        )
        AnimatedVisibility(settingsOpen, enter = slideInHorizontally(LeafMotion.expressive()) { it }, exit = slideOutHorizontally(LeafMotion.tool()) { it }) {
            SettingsScreen(settings, onClose = { settingsOpen = false })
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 100.dp))
    }
}

/**
 * Wide screens start as one full-width list; opening an essay slides the list to 410 dp and puts the
 * editor beside it. Narrow screens swap between the list and the editor.
 */
@Composable
private fun Panes(listContent: @Composable (Modifier) -> Unit, editor: (@Composable (Modifier) -> Unit)?) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = paneLayout(maxWidth.value.toInt(), essayOpen = true) == PaneLayout.Split
        if (wide) {
            val listWidth by animateDpAsState(if (editor != null) 410.dp else maxWidth, LeafMotion.expressive(), label = "list")
            Row(Modifier.fillMaxSize()) {
                listContent(Modifier.width(listWidth).fillMaxHeight())
                if (editor != null) {
                    editor(Modifier.weight(1f).fillMaxHeight().padding(10.dp).clip(RoundedCornerShape(32.dp)))
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
                if (e == null) listContent(Modifier.fillMaxSize()) else e(Modifier.fillMaxSize())
            }
        }
    }
}
