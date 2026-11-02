package dev.chenwuyi.leaf.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.ui.component.ColorDots
import dev.chenwuyi.leaf.ui.theme.DarkSurfaceContainer
import dev.chenwuyi.leaf.ui.theme.DarkTones
import dev.chenwuyi.leaf.ui.theme.LeafTheme
import dev.chenwuyi.leaf.ui.theme.LightOutline
import dev.chenwuyi.leaf.ui.theme.LightSurfaceContainer
import dev.chenwuyi.leaf.ui.theme.LightTones
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 默认 and 灰 tell apart (round 2 #2): 默认 is a hollow ring in outline, with the sheet's own paper inside,
 * while 灰 is a filled dot; on the dark drawer and sheets the grey sits a step lighter, so it shows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ColorDotsDefaultTest {
    private fun show(name: String): ComponentActivity = probe(name) {
        LeafTheme {
            Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp)) {
                ColorDots(null, {})
            }
        }
    }

    private fun picture(activity: ComponentActivity): Bitmap {
        val view = activity.window.decorView
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    private fun dot(activity: ComponentActivity, label: String): SemanticsNode =
        semanticsNodes(activity).first { label in it.descriptions() }

    private fun close(a: Int, b: Int, tolerance: Int = 6): Boolean {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        return listOf(16, 8, 0).all { abs(ch(a, it) - ch(b, it)) <= tolerance }
    }

    private fun centre(bitmap: Bitmap, node: SemanticsNode): Int {
        val c = node.boundsInWindow.center
        return bitmap.getPixel(c.x.toInt(), c.y.toInt())
    }

    @Test
    fun should_draw_default_as_a_hollow_ring_when_the_dots_show() {
        val activity = show("colour-dots-3-default-ring")
        val bitmap = picture(activity)
        val density = activity.resources.displayMetrics.density
        val default = dot(activity, "默认颜色")
        val gray = dot(activity, "灰色")
        // Inside the ring is the sheet's own paper, not a fill.
        assertTrue("默认's middle is the paper", close(centre(bitmap, default), LightSurfaceContainer.toArgb()))
        assertTrue("灰's middle is its fill", close(centre(bitmap, gray), LightTones.fill(NotionColor.Gray).toArgb()))
        // Going up from the middle, the ring shows in outline before the dot's 15dp edge.
        val c = default.boundsInWindow.center
        val column = (0..(16 * density).toInt()).map { bitmap.getPixel(c.x.toInt(), (c.y - it).toInt()) }
        val outline = LightOutline.luminance()
        assertTrue(
            "an outline ring around 默认",
            column.any { abs(Color(it).luminance() - outline) < .06f },
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun should_show_the_grey_dot_when_it_sits_on_dark_paper() {
        val activity = show("colour-dots-4-dark-grey")
        val bitmap = picture(activity)
        val gray = DarkTones.fill(NotionColor.Gray)
        assertTrue("灰 is drawn in its dark tone", close(centre(bitmap, dot(activity, "灰色")), gray.toArgb()))
        assertTrue("默认's middle is the dark paper", close(centre(bitmap, dot(activity, "默认颜色")), DarkSurfaceContainer.toArgb()))
        // WCAG-style ratio against the drawer's and sheets' paper: the old 0xFF3A3935 was 1.4.
        val a = gray.luminance()
        val b = DarkSurfaceContainer.luminance()
        val ratio = (max(a, b) + .05f) / (min(a, b) + .05f)
        assertTrue("grey stands off dark paper-container: $ratio", ratio >= 1.6f)
    }
}
