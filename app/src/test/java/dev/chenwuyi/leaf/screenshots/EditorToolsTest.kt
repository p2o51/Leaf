package dev.chenwuyi.leaf.screenshots

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.ui.essay.EditorSync
import dev.chenwuyi.leaf.ui.essay.EssayEditor
import dev.chenwuyi.leaf.ui.theme.LeafTheme
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The phone bar's 样式 key stays on with nothing picked and then says how to pick a paragraph. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class EditorToolsTest {
    private val essay = Essay(
        "e1",
        "东京第三天",
        listOf(EssayBlock.Paragraph("h", "早上", "heading_2"), EssayBlock.Paragraph("p1", "在住处附近的路口站了一会儿。")),
        NotionColor.Default,
        null,
        false,
        Instant.parse("2026-10-24T09:00:00Z"),
    )

    @Test
    fun should_open_the_style_hint_when_aa_is_tapped_with_no_paragraph_picked() {
        val activity = probe("phone-5-style-hint") {
            LeafTheme { EssayEditor(essay, emptyList(), EditorSync(connected = true), {}, {}, {}) }
        }
        val aa = semanticsNodes(activity).single { "样式" in it.descriptions() }
        assertFalse("样式 is not greyed out", aa.config.contains(SemanticsProperties.Disabled))
        aa.tap()
        val texts = semanticsNodes(activity).flatMap { it.texts() }
        assertTrue("the hint shows: $texts", "点一下正文里的一段，再来改它的样式" in texts)
    }
}
