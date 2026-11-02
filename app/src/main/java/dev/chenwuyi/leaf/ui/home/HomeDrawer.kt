package dev.chenwuyi.leaf.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.R
import dev.chenwuyi.leaf.data.HomeFilter
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.ui.component.scrollEdgeFade
import dev.chenwuyi.leaf.ui.theme.LocalNotionTones

/**
 * Keep's drawer, in Leaf's colours: everything and the wall, then the colors in use, then the tags in use,
 * and 设置 at the foot. No counts, no hints; a group with nothing in it is left out. The sheet itself
 * follows the back gesture.
 *
 * Settings live here rather than behind the small mark in the search bar (51, 2026-11-01): the bar's
 * mark read as an avatar, and the drawer is where Leaf's other places already are. The Leaf mark at the
 * top opens them too, since that is where people look for an app's settings first; the tag list fades
 * out above the pinned 设置 rather than being cut through.
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
    onOpenSettings: () -> Unit,
) {
    // Paperleaf: a panel sits on paper-container, flat.
    ModalDrawerSheet(drawerState = state, drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer) {
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f).scrollEdgeFade(scroll).verticalScroll(scroll).padding(horizontal = 12.dp)) {
            Row(
                Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "打开设置", role = Role.Button, onClick = onOpenSettings)
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(32.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    // The mark is decoration; the row reads as "Leaf", which opens settings.
                    Text("L", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.size(12.dp))
                Text("Leaf", style = MaterialTheme.typography.titleLarge)
            }
            // Unfiltered home is the drawer's "home"; its selected state takes the filled twin.
            val all = filter == HomeFilter.All
            Item("全部", all, { Icon(painterResource(if (all) R.drawable.ic_sym_home_filled else R.drawable.ic_sym_home), null) }) { onPick(HomeFilter.All) }
            Item("涂鸦墙", false, { Icon(painterResource(R.drawable.ic_sym_wall), null) }, onClick = onOpenWall)
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
                    Item(t, filter == HomeFilter.ByTag(t), { Icon(painterResource(R.drawable.ic_sym_label), null) }) { onPick(HomeFilter.ByTag(t)) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        // Pinned under the list, so a long tag list never pushes it out of reach.
        Item(
            "设置",
            false,
            { Icon(painterResource(R.drawable.ic_sym_settings), null) },
            Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun Item(label: String, selected: Boolean, icon: @Composable () -> Unit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    NavigationDrawerItem(label = { Text(label) }, selected = selected, onClick = onClick, icon = icon, modifier = modifier.height(52.dp))
}

/** The 24dp break between groups and a title-s heading; Paperleaf groups by space, not by rules. */
@Composable
private fun Group(title: String) {
    Text(
        title,
        Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
