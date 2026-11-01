package dev.chenwuyi.leaf.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.chenwuyi.leaf.data.HomeFilter
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.SampleData
import dev.chenwuyi.leaf.ui.home.HomeDrawer
import dev.chenwuyi.leaf.ui.home.HomePane
import dev.chenwuyi.leaf.ui.theme.LeafTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings open from the drawer's foot now, not from the mark at the end of the search bar (51,
 * 2026-11-01). These read the Compose semantics tree straight from the root view, since the app has no
 * ui-test dependency, and also write pictures next to the other home shots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class DrawerSettingsTest {
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun should_open_settings_when_the_drawer_settings_item_is_tapped() {
        var opened = 0
        val activity = show("home-phone-5-drawer-settings") { drawer(tags = listOf("东京", "写作")) { opened++ } }
        val settings = nodes(activity).filter { it.hasText("设置") }
        assertEquals("one 设置 item in the drawer", 1, settings.size)
        val click = settings.single().config.getOrNull(SemanticsActions.OnClick)
        assertTrue("设置 is clickable", click?.action != null)
        click!!.action!!.invoke()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, opened)
    }

    @Test
    @Config(qualifiers = "w411dp-h560dp-xhdpi")
    fun should_keep_settings_on_screen_when_the_tag_list_is_longer_than_the_drawer() {
        val tags = (1..24).map { "标签$it" }
        val activity = show("home-phone-6-drawer-short") { drawer(tags = tags) {} }
        val root = composeRoots(activity).single()
        val settings = root.semanticsOwner.rootSemanticsNode.flatten().single { it.hasText("设置") }
        val bounds = settings.boundsInRoot
        assertTrue("设置 starts inside the window: $bounds", bounds.top >= 0f)
        assertTrue("设置 ends inside the window: $bounds", bounds.bottom <= root.view.height.toFloat())
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun should_not_offer_settings_on_home_when_the_drawer_is_closed() {
        val activity = show("home-phone-7-search-bar") {
            LeafTheme {
                HomePane(SampleData.scribbles, SampleData.essays, HomeFilter.All, SampleData.scribbles, SampleData.essays, null, { _, _ -> }, {}, {})
            }
        }
        val offending = nodes(activity).filter { it.hasText("设置") || it.config.getOrNull(SemanticsActions.OnClick)?.label == "设置" }
        assertTrue("home has no settings entry of its own: $offending", offending.isEmpty())
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun should_open_settings_when_the_leaf_mark_at_the_drawer_top_is_tapped() {
        var opened = 0
        val activity = show("home-phone-10-drawer-mark") { drawer(tags = listOf("东京")) { opened++ } }
        val header = nodes(activity).single { it.config.getOrNull(SemanticsActions.OnClick)?.label == "打开设置" }
        assertTrue("the mark's row reads as Leaf", header.hasText("Leaf"))
        header.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, opened)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun should_say_categories_and_settings_when_the_menu_key_is_read_out() {
        val activity = show("home-phone-11-menu-key") {
            LeafTheme {
                HomePane(SampleData.scribbles, SampleData.essays, HomeFilter.All, SampleData.scribbles, SampleData.essays, null, { _, _ -> }, {}, {})
            }
        }
        val described = nodes(activity).flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        assertTrue("menu key: $described", "分类和设置" in described)
        assertTrue("the old name is gone: $described", "分类" !in described)
    }

    @Test
    @Config(qualifiers = "w411dp-h560dp-night-xhdpi")
    fun should_draw_the_tag_list_fading_above_settings_when_it_overflows_a_dark_drawer() {
        // A picture only: the fade over the last tag rows above the pinned 设置, in dark paper.
        show("home-phone-12-drawer-short-dark") { drawer(tags = (1..24).map { "标签$it" }) {} }
    }

    @Composable
    private fun drawer(tags: List<String>, onOpenSettings: () -> Unit) = LeafTheme {
        val state = rememberDrawerState(DrawerValue.Open)
        ModalNavigationDrawer(
            drawerState = state,
            drawerContent = {
                HomeDrawer(state, HomeFilter.All, listOf(NotionColor.Green, NotionColor.Yellow, NotionColor.Blue), tags, {}, {}, onOpenSettings)
            },
        ) {}
    }

    private fun show(name: String, content: @Composable () -> Unit): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { content() }
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400)) }
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return activity
    }

    private fun composeRoots(activity: ComponentActivity): List<ViewRootForTest> {
        fun walk(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { walk(v.getChildAt(it)) } else listOf(v)
        return walk(activity.window.decorView).filterIsInstance<ViewRootForTest>()
    }

    // The merged tree, as TalkBack sees it: a drawer item's label text sits on the item itself.
    private fun nodes(activity: ComponentActivity): List<SemanticsNode> =
        composeRoots(activity).flatMap { it.semanticsOwner.rootSemanticsNode.flatten() }

    private fun SemanticsNode.flatten(): List<SemanticsNode> = listOf(this) + children.flatMap { it.flatten() }

    private fun SemanticsNode.hasText(text: String): Boolean =
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
}
