package dev.chenwuyi.leaf.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import dev.chenwuyi.leaf.data.HomeFilter
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.SampleData
import dev.chenwuyi.leaf.data.TagRules
import dev.chenwuyi.leaf.ui.home.HomeDrawer
import dev.chenwuyi.leaf.ui.home.HomePane
import dev.chenwuyi.leaf.ui.theme.LeafTheme
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the drawer and of a filtered home for review, written to build/screenshots next to the
 * editor's. Like those, they only check that it renders; the point is to look at it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class HomeScreenshots {
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun phone_drawer() = shoot("home-phone-1-drawer") { home(HomeFilter.All, drawerOpen = true) }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun phone_drawer_dark() = shoot("home-phone-2-drawer-dark") { home(HomeFilter.ByTag("东京"), drawerOpen = true) }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun phone_tag() = shoot("home-phone-3-tag") { home(HomeFilter.ByTag("东京"), drawerOpen = false) }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun phone_colour() = shoot("home-phone-4-colour") { home(HomeFilter.ByColor(NotionColor.Green), drawerOpen = false) }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun tablet_drawer() = shoot("home-tablet-1-drawer") { home(HomeFilter.ByTag("写作"), drawerOpen = true) }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-night-xhdpi")
    fun tablet_tag() = shoot("home-tablet-2-tag-dark") { home(HomeFilter.ByTag("写作"), drawerOpen = false) }

    @Composable
    private fun home(filter: HomeFilter, drawerOpen: Boolean) = LeafTheme {
        val scribbles = SampleData.scribbles
        val essays = SampleData.essays
        val drawer = rememberDrawerState(if (drawerOpen) DrawerValue.Open else DrawerValue.Closed)
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = { HomeDrawer(drawer, filter, TagRules.colorsInUse(scribbles, essays), TagRules.all(scribbles, essays), {}, {}) },
        ) {
            HomePane(
                scribbles.filter { TagRules.matches(it, filter) },
                essays.filter { TagRules.matches(it, filter) },
                filter, scribbles, essays, null,
                { _, _ -> }, {}, {}, {},
            )
        }
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { content() }
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400)) }
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
