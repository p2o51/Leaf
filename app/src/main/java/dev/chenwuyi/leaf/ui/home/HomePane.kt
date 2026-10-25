package dev.chenwuyi.leaf.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.HomeFilter
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.data.WallRules
import dev.chenwuyi.leaf.ui.component.ColorDots
import dev.chenwuyi.leaf.ui.component.ScribbleChip
import dev.chenwuyi.leaf.ui.theme.LeafMotion
import dev.chenwuyi.leaf.ui.theme.LocalNotionTones
import kotlinx.coroutines.launch

/**
 * Home: search, the scribble strip, then essays as a Keep-style staggered grid. No title over the strip.
 * Search is M3's own search bar (the same pair Sheaf's library uses): tapping it opens the full-screen
 * search page, which lists matching scribbles and essays, so home itself never reflows while typing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePane(
    scribbles: List<Scribble>,
    essays: List<Essay>,
    filter: HomeFilter,
    allScribbles: List<Scribble>,
    allEssays: List<Essay>,
    activeEssayId: String?,
    onOpenWall: (focusId: String?, near: Set<String>) -> Unit,
    onOpenEssay: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    actions: EssayActions = EssayActions(),
) {
    val scope = rememberCoroutineScope()
    val bar = rememberSearchBarState()
    val text = rememberTextFieldState()
    // Leaving the search page ends the search, so the next one starts from an empty field.
    LaunchedEffect(bar) {
        snapshotFlow { bar.currentValue }.collect { if (it == SearchBarValue.Collapsed) text.setTextAndPlaceCursorAtEnd("") }
    }
    val sorted = WallRules.searchEssays(essays, "")
    val inputField: @Composable () -> Unit = { SearchField(text, bar, onOpenSettings, onOpenDrawer) }

    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surface)) {
        val columns = when {
            maxWidth < 600.dp -> 2
            maxWidth < 840.dp -> 3
            else -> 4
        }
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(columns),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = top + 10.dp, bottom = 130.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalItemSpacing = 10.dp,
        ) {
            item(key = "search", span = StaggeredGridItemSpan.FullLine) {
                SearchBar(
                    state = bar,
                    inputField = inputField,
                    colors = SearchBarDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val all = filter == HomeFilter.All
            // A filter names itself where the page starts, the way Keep titles a label, and back clears it.
            if (!all) {
                item(key = "filter", span = StaggeredGridItemSpan.FullLine) { FilterTitle(filter, Modifier.animateItem()) }
            }
            // The strip shows only when there is something on it: no scribbles, no strip and no placeholder.
            if (scribbles.isNotEmpty()) {
                item(key = "strip", span = StaggeredGridItemSpan.FullLine) { ScribbleStrip(scribbles, onOpenWall, bleed = 12.dp) }
            }
            if (all || sorted.isNotEmpty()) {
                item(key = "essays-title", span = StaggeredGridItemSpan.FullLine) {
                    // A quiet section label, not a page title: the cards are what the eye should land on.
                    Text("随笔", Modifier.padding(start = 6.dp, top = 6.dp).animateItem(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (all && sorted.isEmpty()) {
                item(key = "empty", span = StaggeredGridItemSpan.FullLine) {
                    Text("还没有随笔。点右下角的 + 写第一篇。", Modifier.padding(6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!all && scribbles.isEmpty() && sorted.isEmpty()) {
                item(key = "empty-filter", span = StaggeredGridItemSpan.FullLine) {
                    Text("这里现在是空的", Modifier.padding(6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(sorted, key = { it.id }) { e ->
                ManagedEssayCard(e, active = e.id == activeEssayId, onOpen = { onOpenEssay(e.id) }, actions = actions, modifier = Modifier.animateItem())
            }
        }
    }

    ExpandedFullScreenSearchBar(
        state = bar,
        inputField = inputField,
        colors = SearchBarDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        SearchResults(
            query = text.text.toString(),
            // Search looks through everything, whatever the drawer is filtering.
            scribbles = allScribbles,
            essays = allEssays,
            onOpenScribble = { id ->
                // The search page is its own window above home, so it folds away before the wall flies in.
                scope.launch { bar.snapTo(0f) }
                onOpenWall(id, emptySet())
            },
            onOpenEssay = { id ->
                scope.launch { bar.snapTo(0f) }
                onOpenEssay(id)
            },
        )
    }
}

/** M3's input field. Collapsed it carries Leaf's mark (settings) at the end; open, the magnifier turns into back. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchField(text: TextFieldState, bar: SearchBarState, onOpenSettings: () -> Unit, onOpenDrawer: () -> Unit) {
    val scope = rememberCoroutineScope()
    val expanded = bar.targetValue == SearchBarValue.Expanded
    val turn by animateFloatAsState(if (expanded) 1f else 0f, LeafMotion.fast(), label = "lead")
    SearchBarDefaults.InputField(
        textFieldState = text,
        searchBarState = bar,
        onSearch = {},
        placeholder = { Text("搜索随笔和涂鸦") },
        leadingIcon = {
            // Collapsed, the leading icon opens the drawer, as in Keep; open, it turns into back.
            IconButton(onClick = { if (expanded) scope.launch { bar.animateToCollapsed() } else onOpenDrawer() }) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Menu, if (expanded) null else "分类", Modifier.graphicsLayer { alpha = 1f - turn; rotationZ = 90f * turn })
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, if (expanded) "关闭搜索" else null, Modifier.graphicsLayer { alpha = turn; rotationZ = -90f * (1f - turn) })
                }
            }
        },
        trailingIcon = {
            if (expanded) {
                if (text.text.isNotEmpty()) IconButton(onClick = { text.setTextAndPlaceCursorAtEnd("") }) { Icon(Icons.Rounded.Close, "清空") }
            } else {
                // Leaf's mark, the cookie shape, where an avatar would sit. Settings open from here.
                Box(
                    Modifier.padding(end = 4.dp).size(34.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(onClickLabel = "设置", onClick = onOpenSettings),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("L", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchResults(query: String, scribbles: List<Scribble>, essays: List<Essay>, onOpenScribble: (String) -> Unit, onOpenEssay: (String) -> Unit) {
    if (query.isBlank()) return
    val hitScribbles = WallRules.search(scribbles, query).sortedByDescending { it.createdAt }
    val hitEssays = WallRules.searchEssays(essays, query)
    // A centred reading width on tablets, rather than results stretched across the whole screen.
    LazyColumn(Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (hitScribbles.isEmpty() && hitEssays.isEmpty()) {
            item(key = "none") { Text("没有找到「${query.trim()}」", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (hitScribbles.isNotEmpty()) {
            item(key = "scribbles-title") { ResultTitle("涂鸦") }
            item(key = "scribbles") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    hitScribbles.forEach { s -> ScribbleChip(s, singleLine = true, onClick = { onOpenScribble(s.id) }) }
                }
            }
        }
        if (hitEssays.isNotEmpty()) {
            item(key = "essays-title") { ResultTitle("随笔") }
            items(hitEssays, key = { it.id }) { e -> EssayCard(e, active = false, tall = false, onClick = { onOpenEssay(e.id) }, modifier = Modifier.animateItem()) }
        }
    }
}

/** The drawer's pick as the page's heading: a color with its swatch, or a tag. */
@Composable
private fun FilterTitle(filter: HomeFilter, modifier: Modifier = Modifier) {
    val tones = LocalNotionTones.current
    Row(modifier.padding(start = 6.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        when (filter) {
            is HomeFilter.ByColor -> {
                Box(Modifier.size(22.dp).clip(CircleShape).background(tones.fill(filter.color)))
                Spacer(Modifier.width(12.dp))
                Text("${filter.color.label}色", style = MaterialTheme.typography.headlineMedium)
            }
            is HomeFilter.ByTag -> Text("#${filter.name}", style = MaterialTheme.typography.headlineMedium)
            HomeFilter.All -> Unit
        }
    }
}

@Composable
private fun ResultTitle(label: String) {
    Text(label, Modifier.padding(start = 4.dp, top = 14.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What a long press on an essay card can do. */
data class EssayActions(
    val setPinned: (Essay, Boolean) -> Unit = { _, _ -> },
    val recolor: (Essay, NotionColor) -> Unit = { _, _ -> },
    val delete: (Essay) -> Unit = {},
)

/**
 * An essay card that a long press manages, as Keep's do: the card lifts a little and a menu opens on it
 * with the card colours, 置顶 and, last, 删除.
 */
@Composable
private fun ManagedEssayCard(essay: Essay, active: Boolean, onOpen: () -> Unit, actions: EssayActions, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val lift by animateFloatAsState(if (menu) 1.03f else 1f, LeafMotion.fast(), label = "lift")
    Box(modifier) {
        EssayCard(
            essay,
            active = active,
            tall = essay.cover != null && essay.id.hashCode() % 2 == 0,
            onClick = onOpen,
            modifier = Modifier.graphicsLayer { scaleX = lift; scaleY = lift },
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                menu = true
            },
        )
        DropdownMenu(menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(20.dp)) {
            ColorDots(
                essay.color,
                { c ->
                    menu = false
                    if (c != essay.color) actions.recolor(essay, c)
                },
                Modifier.widthIn(max = 232.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            )
            DropdownMenuItem(
                text = { Text(if (essay.pinned) "取消置顶" else "置顶") },
                leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                onClick = {
                    menu = false
                    actions.setPinned(essay, !essay.pinned)
                },
            )
            DropdownMenuItem(
                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    menu = false
                    actions.delete(essay)
                },
            )
        }
    }
}
