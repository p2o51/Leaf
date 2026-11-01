package dev.chenwuyi.leaf.screenshots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.SampleArt
import dev.chenwuyi.leaf.data.SampleData
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.ui.theme.LeafTheme
import dev.chenwuyi.leaf.ui.wall.ScribbleMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the scribble menu draws (round 2 #3), rendered on its own for the screenshots. Opening it from the
 * wall and what its items do are in [WallMenuPopupTest], which can reach the popup window.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class WallMenuTest {
    /** The newest text scribble's chip: it is at the top of the wall. */
    private fun newest(): Scribble = SampleData.scribbles.filter { it.photo == null }.maxBy { it.createdAt }

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
