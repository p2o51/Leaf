package dev.chenwuyi.leaf.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.Scribble

/** Home: search, the scribble strip, then essays as a Keep-style staggered grid. No title over the strip. */
@Composable
fun HomePane(
    scribbles: List<Scribble>,
    essays: List<Essay>,
    query: String,
    activeEssayId: String?,
    onQuery: (String) -> Unit,
    onOpenWall: (focusId: String?, near: Set<String>) -> Unit,
    onOpenEssay: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            item(key = "search", span = StaggeredGridItemSpan.FullLine) { SearchBar(query, onQuery, onOpenSettings) }
            item(key = "strip", span = StaggeredGridItemSpan.FullLine) { ScribbleStrip(scribbles, onOpenWall) }
            item(key = "essays-title", span = StaggeredGridItemSpan.FullLine) {
                Text("随笔", Modifier.padding(start = 6.dp, top = 8.dp), style = MaterialTheme.typography.headlineMedium)
            }
            if (essays.isEmpty()) {
                item(key = "empty", span = StaggeredGridItemSpan.FullLine) {
                    Text(
                        if (query.isBlank()) "还没有随笔。点右下角的 + 写第一篇。" else "没有匹配的随笔",
                        Modifier.padding(6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(essays, key = { it.id }) { e ->
                EssayCard(e, active = e.id == activeEssayId, tall = e.cover != null && e.id.hashCode() % 2 == 0, onClick = { onOpenEssay(e.id) }, modifier = Modifier.animateItem())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onOpenSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 18.dp, end = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("搜索随笔和涂鸦", color = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                query,
                onQuery,
                Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
        // Leaf's mark, the cookie shape, where an avatar would sit. Settings open from here.
        androidx.compose.foundation.layout.Box(
            Modifier.size(34.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(onClickLabel = "设置", onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            Text("L", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
