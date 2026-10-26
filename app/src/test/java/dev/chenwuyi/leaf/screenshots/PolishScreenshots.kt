package dev.chenwuyi.leaf.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.HomeFilter
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.SampleArt
import dev.chenwuyi.leaf.data.SampleData
import dev.chenwuyi.leaf.data.local.LeafDatabase
import dev.chenwuyi.leaf.data.notion.LeafNotion
import dev.chenwuyi.leaf.data.settings.Appearance
import dev.chenwuyi.leaf.data.settings.Connection
import dev.chenwuyi.leaf.data.settings.MemoryAppearanceStore
import dev.chenwuyi.leaf.data.settings.MemoryConnectionStore
import dev.chenwuyi.leaf.data.settings.MemorySpeechKeyStore
import dev.chenwuyi.leaf.data.settings.ReadingFont
import dev.chenwuyi.leaf.data.sync.NotionSetup
import dev.chenwuyi.leaf.data.sync.SyncEngine
import dev.chenwuyi.leaf.data.sync.SyncStatus
import dev.chenwuyi.leaf.ui.essay.EditorStart
import dev.chenwuyi.leaf.ui.essay.EditorSync
import dev.chenwuyi.leaf.ui.essay.EssayEditor
import dev.chenwuyi.leaf.ui.home.HomePane
import dev.chenwuyi.leaf.ui.settings.SettingsScreen
import dev.chenwuyi.leaf.ui.settings.SettingsViewModel
import dev.chenwuyi.leaf.ui.theme.LeafTheme
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the 2026-10-25 polish for review: the essay page as a magazine page (cover under the
 * status bar, big title, date / tags / sync mark), its outline, settings with 外观 and the ElevenLabs key,
 * and home without scribbles. Like the others they only check that it renders.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class PolishScreenshots {
    private val essay = Essay(
        "e1",
        "东京第三天",
        listOf(
            EssayBlock.Other("toc", EssayBlock.TableOfContents),
            EssayBlock.Paragraph("h", "早上", "heading_2"),
            EssayBlock.Paragraph("p1", "在住处附近的路口站了一会儿，橙色的施工围栏把一块空地圈起来，像一个还没写完的段落。"),
            EssayBlock.Photo("i", Picture.Sample(SampleArt.Street), "路口的围栏"),
            EssayBlock.Other("d", EssayBlock.Divider),
            EssayBlock.Paragraph("h2", "晚上", "heading_2"),
            EssayBlock.Paragraph("q", "停顿不是阅读的失败，它是阅读的一部分。", "quote"),
            EssayBlock.Paragraph("c", "晚上的风比白天温柔。", color = NotionColor.Yellow),
        ),
        NotionColor.Default,
        Picture.Sample(SampleArt.Dusk),
        false,
        Instant.parse("2026-10-24T09:00:00Z"),
        tags = listOf("东京", "旅行"),
        createdAt = Instant.parse("2026-10-22T09:00:00Z"),
    )

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun magazine() = shoot("polish-1-essay-cover") { editor(essay) }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun magazine_dark_sans() = shoot("polish-2-essay-cover-dark-sans") { editor(essay, font = ReadingFont.Sans) }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun no_cover() = shoot("polish-3-essay-no-cover") { editor(essay.copy(cover = null)) }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun outline() = shoot("polish-4-essay-outline") { editor(essay, EditorStart(outline = true)) }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun handle() = shoot("polish-5-essay-handle") { editor(essay.copy(cover = null), EditorStart(focus = "p1")) }

    @Test
    @Config(qualifiers = "w870dp-h800dp-land-xhdpi")
    fun tablet() = shoot("polish-6-essay-tablet") { editor(essay) }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun settings() = shoot("polish-7-settings") { settingsPage() }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun settings_dark() = shoot("polish-8-settings-dark") { settingsPage() }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun home_without_scribbles() = shoot("polish-9-home-no-scribbles") {
        LeafTheme {
            HomePane(emptyList(), SampleData.essays, HomeFilter.All, emptyList(), SampleData.essays, null, { _, _ -> }, {}, {}, {})
        }
    }

    @Composable
    private fun editor(e: Essay, start: EditorStart = EditorStart(), font: ReadingFont = ReadingFont.Serif) = LeafTheme(font = font) {
        EssayEditor(e, SampleData.scribbles, EditorSync(connected = true, status = SyncStatus.Idle), {}, {}, {}, start = start)
    }

    @Composable
    private fun settingsPage() = LeafTheme {
        val vm = remember { settingsViewModel() }
        SettingsScreen(vm, onClose = {})
    }

    private fun settingsViewModel(): SettingsViewModel {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LeafDatabase::class.java).allowMainThreadQueries().build()
        val store = MemoryConnectionStore(Connection("51 的工作区", "ds", "随笔库", "Name", "wall"), "dev-local-not-secret")
        val notion = LeafNotion()
        val engine = SyncEngine(db.dao(), notion, store)
        return SettingsViewModel(
            setup = NotionSetup(notion, store, db.dao(), engine),
            connection = store.connection,
            status = MutableStateFlow(SyncStatus.Idle),
            syncNow = {},
            afterDisconnect = {},
            speechKey = MemorySpeechKeyStore("sk_dev_local_not_secret_0000"),
            appearanceStore = MemoryAppearanceStore(Appearance(font = ReadingFont.Serif)),
        )
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
