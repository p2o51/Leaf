package dev.chenwuyi.leaf.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.HomeFilter
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.ui.theme.LocalNotionTones

/**
 * Keep's drawer, in Leaf's colours: everything and the wall, then the colors in use, then the tags in use.
 * No counts, no hints; a group with nothing in it is left out. The sheet itself follows the back gesture.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeDrawer(
    state: DrawerState,
    filter: HomeFilter,
    colors: List<NotionColor>,
    tags: List<String>,
    onPick: (HomeFilter) -> Unit,
    onOpenWall: () -> Unit,
) {
    ModalDrawerSheet(drawerState = state) {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Row(Modifier.padding(start = 16.dp, top = 20.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(32.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("L", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.size(12.dp))
                Text("Leaf", style = MaterialTheme.typography.titleLarge)
            }
            Item("全部", filter == HomeFilter.All, { Icon(Icons.AutoMirrored.Rounded.Notes, null) }) { onPick(HomeFilter.All) }
            Item("涂鸦墙", false, { Icon(Icons.Rounded.Gesture, null) }, onOpenWall)
            if (colors.isNotEmpty()) {
                Group("颜色")
                val tones = LocalNotionTones.current
                colors.forEach { c ->
                    Item("${c.label}色", filter == HomeFilter.ByColor(c), { Box(Modifier.size(20.dp).clip(CircleShape).background(tones.fill(c))) }) {
                        onPick(HomeFilter.ByColor(c))
                    }
                }
            }
            if (tags.isNotEmpty()) {
                Group("标签")
                tags.forEach { t ->
                    Item(t, filter == HomeFilter.ByTag(t), { Icon(Icons.AutoMirrored.Outlined.Label, null) }) { onPick(HomeFilter.ByTag(t)) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Item(label: String, selected: Boolean, icon: @Composable () -> Unit, onClick: () -> Unit) {
    NavigationDrawerItem(label = { Text(label) }, selected = selected, onClick = onClick, icon = icon, modifier = Modifier.height(52.dp))
}

/** A divider and a small heading, the 24dp break between groups. */
@Composable
private fun Group(title: String) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        Text(
            title,
            Modifier.padding(start = 16.dp, bottom = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
