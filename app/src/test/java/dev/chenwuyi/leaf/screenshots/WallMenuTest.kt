package dev.chenwuyi.leaf.screenshots

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.SampleArt
import dev.chenwuyi.leaf.data.SampleData
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.ui.WallState
import dev.chenwuyi.leaf.ui.component.swatchLabel
import dev.chenwuyi.leaf.ui.theme.LeafTheme
import dev.chenwuyi.leaf.ui.wall.ScribbleMenu
import dev.chenwuyi.leaf.ui.wall.WallActions
import dev.chenwuyi.leaf.ui.wall.WallScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A long press on one scribble on the wall opens the essay card's menu (round 2 #3): its colour dots
 * recolour that scribble at once, 选择 still leads into picking several, and 删除 is last. While picking,
 * a long press keeps toggling.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class WallMenuTest {
    private val selecting = mutableListOf<String?>()
    private val toggled = mutableListOf<String>()
    private val recoloured = mutableListOf<Pair<Set<String>, NotionColor>>()
    private val deleted = mutableListOf<Set<String>>()

    private val actions = WallActions(
        close = {},
        setSort = {},
        startSelecting = { selecting += it },
        stopSelecting = {},
        toggle = { toggled += it },
        selectAll = {},
        recolor = { ids, c -> recoloured += ids to c },
        delete = { deleted += it },
        copyToEssay = { _, _ -> },
        tapScribble = {},
    )

    private fun wall(name: String, state: WallState = WallState()): ComponentActivity = probe(name) {
        LeafTheme { WallScreen(state, SampleData.scribbles, emptyList<Essay>(), actions) }
    }

    /** The newest text scribble's chip: it is at the top of the wall. */
    private fun newest(): Scribble = SampleData.scribbles.filter { it.photo == null }.maxBy { it.createdAt }

    private fun chip(activity: ComponentActivity, s: Scribble): SemanticsNode =
        semanticsNodes(activity).first { s.text in it.texts() && it.config.getOrNull(SemanticsActions.OnLongClick) != null }

    private fun SemanticsNode.longPress() {
        val action = config.getOrNull(SemanticsActions.OnLongClick)?.action ?: error("no long press: $this")
        action()
        settleFrames()
    }

    /** Every Compose root built while a test runs; a popup (the menu) builds its own. */
    private val roots = mutableListOf<ViewRootForTest>()

    @Before
    fun watchRoots() {
        ViewRootForTest.onViewCreatedCallback = { roots += it }
    }

    @After
    fun stopWatching() {
        ViewRootForTest.onViewCreatedCallback = null
    }

    /**
     * Nodes in Compose roots outside the activity's own view tree: the menu is a popup window. Found the way
     * compose-ui-test finds them, through the root-created callback, since Robolectric does not reliably list
     * popup windows in WindowManagerGlobal.
     */
    private fun popupNodes(activity: ComponentActivity): List<SemanticsNode> {
        val own = activity.window.decorView
        fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }
        val popups = roots.filter { it.view.rootView !== own }
        return popups.flatMap { it.semanticsOwner.rootSemanticsNode.all() }
    }

    /** What the roots were, for a failure message. */
    private fun rootsSeen(activity: ComponentActivity): String =
        roots.joinToString { "${it.view.javaClass.simpleName}(own=${it.view.rootView === activity.window.decorView}, attached=${it.view.isAttachedToWindow})" }

    @Test
    fun should_open_the_colour_menu_instead_of_selecting_when_a_scribble_is_long_pressed() {
        val activity = wall("wall-menu-1-open")
        val s = newest()
        chip(activity, s).longPress()
        assertTrue("no selection started: $selecting", selecting.isEmpty())
        val menu = popupNodes(activity)
        val names = menu.flatMap { it.descriptions() }
        assertTrue("the ten colour dots: $names; roots: ${rootsSeen(activity)}", NotionColor.entries.all { swatchLabel(it) in names })
        val texts = menu.flatMap { it.texts() }
        assertTrue("选择 and 删除 in the menu: $texts", "选择" in texts && "删除" in texts)
    }

    @Test
    fun should_recolour_that_scribble_at_once_when_a_dot_in_its_menu_is_tapped() {
        val activity = wall("wall-menu-2-recolour")
        val s = newest()
        chip(activity, s).longPress()
        val target = if (s.color == NotionColor.Blue) NotionColor.Green else NotionColor.Blue
        popupNodes(activity).first { "${target.label}色" in it.descriptions() }.tap()
        assertEquals(listOf(setOf(s.id) to target), recoloured)
        assertTrue(selecting.isEmpty())
    }

    @Test
    fun should_start_selecting_from_that_scribble_when_select_is_picked_in_its_menu() {
        val activity = wall("wall-menu-3-select")
        val s = newest()
        chip(activity, s).longPress()
        popupNodes(activity).first { "选择" in it.texts() }.tap()
        assertEquals(listOf<String?>(s.id), selecting)
    }

    @Test
    fun should_delete_only_that_scribble_when_delete_is_picked_in_its_menu() {
        val activity = wall("wall-menu-4-delete")
        val s = newest()
        chip(activity, s).longPress()
        popupNodes(activity).first { "删除" in it.texts() }.tap()
        assertEquals(listOf(setOf(s.id)), deleted)
    }

    @Test
    fun should_keep_toggling_when_a_scribble_is_long_pressed_while_selecting() {
        val s = newest()
        val activity = wall("wall-menu-5-selecting", WallState(selecting = true))
        chip(activity, s).longPress()
        assertEquals(listOf(s.id), toggled)
        assertTrue("no menu while picking", popupNodes(activity).none { "删除" in it.texts() })
    }

    @Test
    fun should_draw_the_scribble_menu_when_it_opens() {
        val s = newest()
        val activity = probe("wall-menu-6-items") {
            LeafTheme {
                Column(Modifier.width(280.dp).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 8.dp)) {
                    ScribbleMenu(s, {}, {}, {})
                }
            }
        }
        val texts = semanticsNodes(activity).flatMap { it.texts() }
        assertEquals(listOf("选择", "删除"), texts.filter { it == "选择" || it == "删除" })
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun should_draw_the_scribble_menu_when_it_opens_in_dark() {
        val s = newest()
        probe("wall-menu-7-items-dark") {
            LeafTheme {
                Column(Modifier.width(280.dp).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 8.dp)) {
                    ScribbleMenu(s, {}, {}, {})
                }
            }
        }
    }

    @Test
    fun should_leave_out_the_dots_when_the_scribble_is_a_photo() {
        val photo = Scribble("p1", "", NotionColor.Default, newest().createdAt, photo = Picture.Sample(SampleArt.Moss))
        val activity = probe("wall-menu-8-photo") {
            LeafTheme { Column { ScribbleMenu(photo, {}, {}, {}) } }
        }
        val names = semanticsNodes(activity).flatMap { it.descriptions() }
        assertTrue("no colour dots for a photo: $names", names.none { it.endsWith("色") })
    }
}
