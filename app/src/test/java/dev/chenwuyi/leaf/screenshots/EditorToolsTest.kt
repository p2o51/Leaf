package dev.chenwuyi.leaf.screenshots

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.ui.essay.EditorSync
import dev.chenwuyi.leaf.ui.essay.EssayEditor
import dev.chenwuyi.leaf.ui.theme.LeafTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone bar's 样式 key stays on with nothing picked and then says how to pick a paragraph; the
 * outline has one way in, the bar's 目录 key.
 */
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
        settleFrames()
        val texts = unmergedNodes(activity).flatMap { it.texts() }
        val styleOn = semanticsNodes(activity).single { "样式" in it.descriptions() }.config.getOrNull(SemanticsProperties.Selected)
        assertTrue("the hint shows (样式 selected=$styleOn): $texts", texts.any { "点一下正文里的一段" in it })
    }

    @Test
    fun should_offer_one_outline_key_when_the_essay_has_a_table_of_contents_block() {
        val withToc = essay.copy(blocks = listOf<EssayBlock>(EssayBlock.Other("toc", EssayBlock.TableOfContents)) + essay.blocks)
        val activity = probe("phone-6-one-outline-key") {
            LeafTheme { EssayEditor(withToc, emptyList(), EditorSync(connected = true), {}, {}, {}) }
        }
        val keys = semanticsNodes(activity).filter { "目录" in it.descriptions() || "目录" in it.texts() || it.clickLabel() == "打开目录" }
        assertEquals("only the bar's key: ${keys.map { it.descriptions() + it.texts() }}", 1, keys.size)
    }
}
