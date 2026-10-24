package dev.chenwuyi.leaf.ui.wall

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.data.WallRules
import dev.chenwuyi.leaf.data.WallSort
import dev.chenwuyi.leaf.ui.WallState
import dev.chenwuyi.leaf.ui.component.ChipMark
import dev.chenwuyi.leaf.ui.component.ColorDots
import dev.chenwuyi.leaf.ui.component.ScribbleChip
import dev.chenwuyi.leaf.ui.component.sharedKey
import dev.chenwuyi.leaf.ui.component.sharedScribble
import dev.chenwuyi.leaf.ui.theme.LeafMotion

class WallActions(
    val close: () -> Unit,
    val setSort: (WallSort) -> Unit,
    val startSelecting: (String?) -> Unit,
    val stopSelecting: () -> Unit,
    val toggle: (String) -> Unit,
    val selectAll: () -> Unit,
    val recolor: (Set<String>, NotionColor) -> Unit,
    val delete: (Set<String>) -> Unit,
    val copyToEssay: (Set<String>, String?) -> Unit,
    val tapScribble: (String) -> Unit,
)

/** The full scribble wall: everything grouped by day (or colour), with sort, select, recolour, delete and 复制到随笔. */
@Composable
fun WallScreen(state: WallState, scribbles: List<Scribble>, essays: List<Essay>, actions: WallActions, modifier: Modifier = Modifier) {
    val groups = remember(scribbles, state.sort) { WallRules.groups(scribbles, state.sort) }
    // Start with the tapped scribble's group in view, so its chip lands near the middle of the screen.
    val focusGroup = state.focusId?.let { id -> groups.indexOfFirst { g -> g.scribbles.any { it.id == id } } } ?: -1
    val listState = rememberLazyStaggeredGridState(initialFirstVisibleItemIndex = (focusGroup - 1).coerceAtLeast(0))

    Box(modifier.fillMaxSize().sharedKey("wall-surface").background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(state, actions)
            // One column on a phone; on wide windows the days sit side by side instead of one thin
            // column with most of the screen empty.
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(340.dp),
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                items(groups, key = { it.key }) { g ->
                    Column(Modifier.padding(top = 14.dp)) {
                        Text(g.label, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            g.scribbles.forEach { s ->
                                val mark = when {
                                    state.selecting && s.id in state.selected -> ChipMark.Selected
                                    state.selecting -> ChipMark.None
                                    s.id == state.focusId -> ChipMark.Focus
                                    s.id in state.nearIds -> ChipMark.Near
                                    else -> ChipMark.None
                                }
                                ScribbleChip(
                                    s,
                                    Modifier.sharedScribble(s.id),
                                    large = true,
                                    mark = mark,
                                    onClick = { if (state.selecting) actions.toggle(s.id) else actions.tapScribble(s.id) },
                                    onLongClick = { if (state.selecting) actions.toggle(s.id) else actions.startSelecting(s.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            state.selecting,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp),
            enter = slideInVertically(LeafMotion.expressive()) { it * 2 },
            exit = slideOutVertically(LeafMotion.tool()) { it * 2 },
        ) {
            SelectionBar(state.selected, essays, actions)
        }
    }
}

@Composable
private fun TopBar(state: WallState, actions: WallActions) {
    // Entering and leaving selection changes the bar in place: the new row settles in from the start edge
    // while the old one goes at once, so the two bars are never on screen together.
    AnimatedContent(
        targetState = state.selecting,
        transitionSpec = { (fadeIn(LeafMotion.tool()) + slideInHorizontally(LeafMotion.expressive()) { -it / 8 }) togetherWith ExitTransition.None },
        label = "wall bar",
    ) { selecting ->
        TopBarRow(state, selecting, actions)
    }
}

@Composable
private fun TopBarRow(state: WallState, selecting: Boolean, actions: WallActions) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selecting) {
            IconButton(onClick = actions.stopSelecting) { Icon(Icons.Rounded.Close, "退出选择") }
            Text("已选 ${state.selected.size} 条", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Pill("全选", null, actions.selectAll)
        } else {
            IconButton(onClick = actions.close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
            Text("涂鸦墙", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            var sortOpen by remember { mutableStateOf(false) }
            Box {
                Pill(state.sort.label, Icons.AutoMirrored.Rounded.Sort) { sortOpen = true }
                DropdownMenu(sortOpen, { sortOpen = false }, shape = RoundedCornerShape(20.dp)) {
                    WallSort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label) },
                            onClick = { sortOpen = false; actions.setSort(s) },
                            trailingIcon = { if (s == state.sort) Icon(Icons.Rounded.Check, null) },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Pill("选择", Icons.Rounded.Check) { actions.startSelecting(null) }
        }
    }
}

@Composable
private fun Pill(label: String, icon: ImageVector?, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.height(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SelectionBar(selected: Set<String>, essays: List<Essay>, actions: WallActions) {
    val enabled = selected.isNotEmpty()
    Row(
        Modifier.clip(RoundedCornerShape(30.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        var colorOpen by remember { mutableStateOf(false) }
        var copyOpen by remember { mutableStateOf(false) }
        Box {
            BarButton("颜色", Icons.Rounded.Palette, enabled) { colorOpen = true }
            DropdownMenu(colorOpen, { colorOpen = false }, shape = RoundedCornerShape(24.dp)) {
                ColorDots(null, { actions.recolor(selected, it); colorOpen = false }, Modifier.width(220.dp).padding(14.dp))
            }
        }
        Box {
            BarButton("复制到随笔", Icons.Rounded.ContentCopy, enabled) { copyOpen = true }
            // Second-level menu: a new essay, or one of the existing ones (appended at the end as synced blocks).
            DropdownMenu(copyOpen, { copyOpen = false }, shape = RoundedCornerShape(20.dp)) {
                Text("复制 ${selected.size} 条到", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DropdownMenuItem(text = { Text("新建一篇随笔") }, onClick = { copyOpen = false; actions.copyToEssay(selected, null) })
                if (essays.isNotEmpty()) HorizontalDivider()
                essays.sortedByDescending { it.editedAt }.take(8).forEach { e ->
                    DropdownMenuItem(
                        text = { Text(e.title.ifBlank { "无标题" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { copyOpen = false; actions.copyToEssay(selected, e.id) },
                    )
                }
            }
        }
        BarButton("删除", Icons.Rounded.Delete, enabled) { actions.delete(selected) }
    }
}

@Composable
private fun BarButton(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
    }
}
