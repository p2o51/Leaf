package dev.chenwuyi.leaf.screenshots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.ui.component.ColorDots
import dev.chenwuyi.leaf.ui.theme.LeafTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The colour dots as a sheet shows them, on paper-container: big enough to hit, and every one named. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ColorDotsTest {
    @Test
    fun should_give_every_dot_a_48dp_target_and_a_name_when_the_dots_show() {
        val activity = probe("colour-dots-1") {
            LeafTheme {
                Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp)) {
                    ColorDots(NotionColor.Gray, {})
                }
            }
        }
        val density = activity.resources.displayMetrics.density
        val dots = semanticsNodes(activity).filter { it.config.getOrNull(SemanticsProperties.Role) != null && it.descriptions().isNotEmpty() }
        assertEquals(NotionColor.entries.size, dots.size)
        dots.forEach { dot ->
            val b = dot.boundsInRoot
            assertTrue("${dot.descriptions()} is at least 48dp: $b", b.width >= 48 * density - 1 && b.height >= 48 * density - 1)
        }
        val names = dots.flatMap { it.descriptions() }
        assertTrue("默认 is named: $names", "默认颜色" in names)
        assertEquals(listOf("灰色"), dots.filter { it.config.getOrNull(SemanticsProperties.Selected) == true }.flatMap { it.descriptions() })
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun should_draw_the_ink_ring_when_a_dot_is_picked_in_dark() {
        probe("colour-dots-2-dark") {
            LeafTheme {
                Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp)) {
                    ColorDots(NotionColor.Default, {})
                }
            }
        }
    }
}
