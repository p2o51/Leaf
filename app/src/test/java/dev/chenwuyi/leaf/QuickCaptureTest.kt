package dev.chenwuyi.leaf

import android.app.Application
import android.content.Intent
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.quick.QuickIntents
import dev.chenwuyi.leaf.quick.WidgetLines
import dev.chenwuyi.leaf.ui.capture.Capture
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class QuickCaptureTest {
    private fun capture(action: String?, type: String? = null, text: String? = null, subject: String? = null, stream: String? = null) =
        QuickIntents.capture(action, type, text, subject, stream)

    @Test
    fun should_open_text_or_voice_when_the_tile_shortcuts_or_widget_ask() {
        assertEquals(Capture.Text(), capture(QuickIntents.ACTION_WRITE))
        assertEquals(Capture.Voice, capture(QuickIntents.ACTION_SPEAK))
        assertNull(capture(Intent.ACTION_VIEW))
    }

    @Test
    fun should_keep_title_and_link_when_a_web_page_is_shared() {
        val shared = capture(Intent.ACTION_SEND, "text/plain", text = "https://example.com/a", subject = "好的工具应该安静")
        assertEquals(Capture.Text("好的工具应该安静\nhttps://example.com/a"), shared)
        assertEquals(Capture.Text("只有一句"), capture(Intent.ACTION_SEND, "text/plain", text = " 只有一句 ", subject = "只有一句"))
        assertNull(capture(Intent.ACTION_SEND, "text/plain", text = "  "))
    }

    @Test
    fun should_open_a_photo_with_its_caption_when_an_image_is_shared() {
        val shared = capture(Intent.ACTION_SEND, "image/jpeg", text = "晚霞", stream = "content://media/1")
        assertEquals(Capture.Photo(Picture.Local("content://media/1"), "晚霞"), shared)
        assertNull(capture(Intent.ACTION_SEND, "image/jpeg"))
    }

    @Test
    fun should_take_selected_text_when_it_is_sent_from_the_text_menu() {
        assertEquals(Capture.Text("一段选中的话"), capture(Intent.ACTION_PROCESS_TEXT, "text/plain", text = "一段选中的话"))
    }

    @Test
    fun should_show_the_three_newest_scribbles_on_one_line_each_when_the_widget_renders() {
        fun s(id: String, text: String, at: Long, photo: Picture? = null) = Scribble(id, text, NotionColor.Default, Instant.ofEpochSecond(at), photo)
        val wall = listOf(
            s("a", "最早", 1),
            s("b", "第二\n行", 2),
            s("c", "", 3),
            s("d", "", 4, Picture.Local("file:///x.jpg")),
            s("e", "最新", 5),
        )
        assertEquals(listOf("最新", "照片", "第二 行"), WidgetLines.newest(wall))
    }
}
