package dev.chenwuyi.leaf.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.SampleData
import dev.chenwuyi.leaf.ui.essay.EditorStart
import dev.chenwuyi.leaf.ui.essay.EssayEditor
import dev.chenwuyi.leaf.ui.essay.ToolPanel
import dev.chenwuyi.leaf.ui.theme.LeafTheme
import java.io.File
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the editor's tools for review, written to build/screenshots (CI uploads them). They check
 * nothing beyond rendering without a crash; the point is to see the phone bar and panels and the tablet panel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class EditorScreenshots {
    private val essay = Essay(
        "e1",
        "东京第三天",
        listOf(
            EssayBlock.Paragraph("h", "早上", "heading_2"),
            EssayBlock.Paragraph("p1", "在住处附近的路口站了一会儿，橙色的施工围栏把一块空地圈起来，像一个还没写完的段落。"),
            EssayBlock.Paragraph("q", "停顿不是阅读的失败，它是阅读的一部分。", "quote"),
            EssayBlock.Paragraph("l1", "便利店的灯光", "bulleted_list_item"),
            EssayBlock.Paragraph("l2", "排队的麻辣烫", "bulleted_list_item"),
            EssayBlock.Paragraph("n1", "回去写完这一篇", "numbered_list_item"),
            EssayBlock.Paragraph("n2", "把照片挑出来", "numbered_list_item"),
            EssayBlock.Paragraph("c", "晚上的风比白天温柔。", color = NotionColor.Yellow),
        ),
        NotionColor.Default,
        null,
        false,
        Instant.parse("2026-10-24T09:00:00Z"),
    )

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun phone_bar() = shoot("phone-1-bar") { editor(EditorStart()) }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun phone_insert() = shoot("phone-2-insert") { editor(EditorStart(panel = ToolPanel.Insert, focus = "p1")) }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun phone_style() = shoot("phone-3-style") { editor(EditorStart(panel = ToolPanel.Style, focus = "p1")) }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun phone_style_dark() = shoot("phone-4-style-dark") { editor(EditorStart(panel = ToolPanel.Style, focus = "h")) }

    @Test
    @Config(qualifiers = "w870dp-h800dp-land-xhdpi")
    fun tablet_insert() = shoot("tablet-1-insert") { editor(EditorStart()) }

    @Test
    @Config(qualifiers = "w870dp-h800dp-land-xhdpi")
    fun tablet_style() = shoot("tablet-2-style") { editor(EditorStart(focus = "p1", styleTab = true)) }

    @Composable
    private fun editor(start: EditorStart) = LeafTheme {
        EssayEditor(essay, SampleData.scribbles, "已同步", {}, {}, {}, start = start)
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
