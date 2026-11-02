package dev.chenwuyi.leaf.screenshots

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.SampleData
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.ui.WallState
import dev.chenwuyi.leaf.ui.component.swatchLabel
import dev.chenwuyi.leaf.ui.theme.LeafTheme
import dev.chenwuyi.leaf.ui.wall.WallActions
import dev.chenwuyi.leaf.ui.wall.WallScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A long press on one scribble on the wall opens the essay card's menu (round 2 #3): its colour dots
 * recolour that scribble at once, 选择 still leads into picking several, and 删除 is last. While picking,
 * a long press keeps toggling. The menu is a popup window, which the compose test rule can see.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class WallMenuPopupTest {
    @get:Rule val compose = createComposeRule()

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

    private fun wall(state: WallState = WallState()) {
        compose.setContent { LeafTheme { WallScreen(state, SampleData.scribbles, emptyList<Essay>(), actions) } }
        compose.waitForIdle()
    }

    /** The newest text scribble: it is at the top of the wall. */
    private fun newest(): Scribble = SampleData.scribbles.filter { it.photo == null }.maxBy { it.createdAt }

    private fun longPress(s: Scribble) {
        compose.onAllNodes(hasText(s.text, substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .onFirst()
            .performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
    }

    private fun inMenu(matcher: SemanticsMatcher): SemanticsNodeInteraction = compose.onNode(matcher and hasAnyAncestor(isPopup()))

    @Test
    fun should_open_the_colour_menu_instead_of_selecting_when_a_scribble_is_long_pressed() {
        wall()
        longPress(newest())

        assertTrue("no selection started: $selecting", selecting.isEmpty())
        NotionColor.entries.forEach { inMenu(hasContentDescription(swatchLabel(it))).assertExists() }
        inMenu(hasText("选择")).assertExists()
        inMenu(hasText("删除")).assertExists()
    }

    @Test
    fun should_recolour_that_scribble_at_once_when_a_dot_in_its_menu_is_tapped() {
        wall()
        val s = newest()
        longPress(s)
        val target = if (s.color == NotionColor.Blue) NotionColor.Green else NotionColor.Blue

        inMenu(hasContentDescription(swatchLabel(target))).performClick()
        compose.waitForIdle()

        assertEquals(listOf(setOf(s.id) to target), recoloured)
        assertTrue(selecting.isEmpty())
    }

    @Test
    fun should_start_selecting_from_that_scribble_when_select_is_picked_in_its_menu() {
        wall()
        val s = newest()
        longPress(s)

        inMenu(hasText("选择")).performClick()
        compose.waitForIdle()

        assertEquals(listOf<String?>(s.id), selecting)
    }

    @Test
    fun should_delete_only_that_scribble_when_delete_is_picked_in_its_menu() {
        wall()
        val s = newest()
        longPress(s)

        inMenu(hasText("删除")).performClick()
        compose.waitForIdle()

        assertEquals(listOf(setOf(s.id)), deleted)
    }

    @Test
    fun should_keep_toggling_when_a_scribble_is_long_pressed_while_selecting() {
        wall(WallState(selecting = true))
        val s = newest()
        longPress(s)

        assertEquals(listOf(s.id), toggled)
        inMenu(hasText("删除")).assertDoesNotExist()
    }
}
