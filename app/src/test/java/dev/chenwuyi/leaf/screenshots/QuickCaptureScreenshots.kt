package dev.chenwuyi.leaf.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.chenwuyi.leaf.data.voice.VoiceRecognizers
import dev.chenwuyi.leaf.quick.LeafWidget
import dev.chenwuyi.leaf.ui.capture.Capture
import dev.chenwuyi.leaf.ui.capture.CaptureSheet
import dev.chenwuyi.leaf.ui.capture.VoiceSession
import dev.chenwuyi.leaf.ui.theme.LeafTheme
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pictures of the home-screen widget and of the capture sheet as sharing opens it, for review (see EditorScreenshots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class QuickCaptureScreenshots {
    @Test
    fun widget() = widget("quick-1-widget", listOf("好的工具应该安静", "照片 · 晚霞", "今天想清楚了：Leaf 不做 AI 代写，也不做摘要和推荐"))

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun widget_dark() = widget("quick-2-widget-dark", listOf("好的工具应该安静", "Relay 这个名字像接力"))

    @Test
    fun widget_empty() = widget("quick-3-widget-empty", emptyList())

    @Test
    fun shared_link() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            LeafTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val voice = remember { VoiceSession(VoiceRecognizers.forScribbles(context), scope) }
                CaptureSheet(Capture.Text("好的工具应该安静\nhttps://example.com/quiet-tools"), voice, {}, { _, _, _ -> })
            }
        }
        save("quick-4-shared-link", activity.window.decorView)
    }

    private fun widget(name: String, lines: List<String>) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val frame = FrameLayout(activity).apply { setPadding(32, 32, 32, 32) }
        val density = activity.resources.displayMetrics.density
        frame.addView(LeafWidget.views(activity, lines).apply(activity, frame), FrameLayout.LayoutParams((340 * density).toInt(), (230 * density).toInt()))
        activity.setContentView(frame)
        save(name, frame)
    }

    private fun save(name: String, view: View) {
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400)) }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
