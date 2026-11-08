package dev.chenwuyi.leaf

import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.SampleArt
import dev.chenwuyi.leaf.ui.essay.DeskFocus
import dev.chenwuyi.leaf.ui.essay.DeskOrigins
import dev.chenwuyi.leaf.ui.essay.DeskPart
import dev.chenwuyi.leaf.ui.essay.DeskStyle
import dev.chenwuyi.leaf.ui.essay.DeskText
import dev.chenwuyi.leaf.ui.essay.Origin
import dev.chenwuyi.leaf.ui.essay.OriginSpan
import dev.chenwuyi.leaf.ui.essay.StyleRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskTextTest {
    private var n = 0
    private val newId = { "n${++n}" }
    private fun p(id: String, text: String, type: String = "paragraph") = EssayBlock.Paragraph(id, text, type)

    @Test
    fun should_renderMarkdown_when_blocksHaveTypes() {
        val r = DeskText.render(listOf(p("a", "标题", "heading_2"), p("b", "正文"), p("c", "一", "bulleted_list_item"), p("d", "二", "bulleted_list_item"), p("e", "x", "numbered_list_item"), p("f", "y", "numbered_list_item")))
        assertEquals("## 标题\n\n正文\n\n- 一\n- 二\n1. x\n2. y", r.text)
        assertEquals(listOf(3, 7, 13, 17, 22, 27), r.starts)
    }

    @Test
    fun should_roundTrip_when_textIsUnchanged() {
        val blocks = listOf(p("a", "标题", "heading_1"), p("b", "第一段\n软换行"), p("c", "引用", "quote"), p("d", "一", "bulleted_list_item"))
        val (out, gone) = DeskText.update(blocks, DeskText.render(blocks).text, newId)
        assertEquals(blocks, out)
        assertTrue(gone.isEmpty())
    }

    @Test
    fun should_keepBlockId_when_aParagraphIsEdited() {
        val blocks = listOf(p("a", "早上很安静。"), p("b", "下雨了。"))
        val (out, gone) = DeskText.update(blocks, "早上很安静，也很冷。\n\n下雨了。", newId)
        assertEquals(listOf("a", "b"), out.map { it.id })
        assertEquals("早上很安静，也很冷。", out[0].text)
        assertTrue(gone.isEmpty())
    }

    @Test
    fun should_addNewBlock_when_aBlankLineSplitsAParagraph() {
        val blocks = listOf(p("a", "一二三"))
        val (out, _) = DeskText.update(blocks, "一二三\n\n四五", newId)
        assertEquals(listOf("a", "n1"), out.map { it.id })
        assertEquals("四五", out[1].text)
    }

    @Test
    fun should_reportGoneBlock_when_aParagraphIsDeleted() {
        val blocks = listOf(p("a", "一"), p("b", "二"), p("c", "三"))
        val (out, gone) = DeskText.update(blocks, "一\n\n三", newId)
        assertEquals(listOf("a", "c"), out.map { it.id })
        assertEquals(setOf("b"), gone)
    }

    @Test
    fun should_changeType_when_aMarkerIsTyped() {
        val (out, _) = DeskText.update(listOf(p("a", "标题")), "## 标题", newId)
        assertEquals("heading_2", out.single().type)
        assertEquals("a", out.single().id)
    }

    @Test
    fun should_keepHashtagAsText_when_noSpaceFollowsHash() {
        assertEquals("paragraph", DeskText.parse("#东京 下雨").single().type)
    }

    @Test
    fun should_splitListItems_when_onConsecutiveLines() {
        val lines = DeskText.parse("- 一\n- 二\n继续二")
        assertEquals(listOf("一", "二\n继续二"), lines.map { it.text })
    }

    @Test
    fun should_keepColour_when_aColouredParagraphIsEdited() {
        val blocks = listOf(EssayBlock.Paragraph("a", "涂鸦", color = NotionColor.Yellow))
        val (out, _) = DeskText.update(blocks, "涂鸦改了", newId)
        assertEquals(NotionColor.Yellow, out.single().color)
    }

    @Test
    fun should_showPhotoBetweenRuns_when_pageHasAPhoto() {
        val photo = EssayBlock.Photo("ph", Picture.Sample(SampleArt.Moss))
        val parts = DeskText.parts(listOf(p("a", "一"), photo, p("b", "二")))
        assertEquals(3, parts.size)
        assertTrue(parts[1] is DeskPart.Shown)
        assertEquals(listOf("a", "ph", "b"), DeskText.blocks(parts).map { it.id })
    }

    @Test
    fun should_endWithAnEmptyRun_when_pageEndsWithAPhoto() {
        val photo = EssayBlock.Photo("ph", Picture.Sample(SampleArt.Moss))
        val parts = DeskText.parts(listOf(photo))
        assertEquals(2, parts.size)
        assertTrue((parts.last() as DeskPart.Text).blocks.isEmpty())
    }

    @Test
    fun should_showFixedParagraph_when_notionHoldsMore() {
        val parts = DeskText.parts(listOf(EssayBlock.Paragraph("a", "粗体", fixed = true)))
        assertTrue(parts.first() is DeskPart.Shown)
    }

    @Test
    fun should_showParagraphAsIs_when_itsWordsLookLikeMarkdown() {
        val parts = DeskText.parts(listOf(p("a", "# 不是标题"), p("b", "正文"), p("c", "引用\n> 还是引用", "quote")))
        assertTrue(parts[0] is DeskPart.Shown)
        assertEquals(listOf("b"), (parts[1] as DeskPart.Text).blocks.map { it.id })
        assertTrue(parts[2] is DeskPart.Shown)
    }

    @Test
    fun should_findChineseSentence_when_caretIsInside() {
        val t = "早上很安静。窗外下雨了！"
        assertEquals("窗外下雨了！", t.substring(DeskFocus.sentence(t, 8)))
        assertEquals("早上很安静。", t.substring(DeskFocus.sentence(t, 2)))
    }

    @Test
    fun should_notEndSentence_when_dotIsInsideANumber() {
        val t = "圆周率是 3.14 左右。"
        assertEquals(t, t.substring(DeskFocus.sentence(t, 2)))
    }

    @Test
    fun should_findParagraph_when_caretIsInSecondBlock() {
        val t = "第一段。\n\n第二段，还有。"
        assertEquals("第二段，还有。", t.substring(DeskFocus.paragraph(t, 8)))
    }

    @Test
    fun should_markFillersAndHalfPunct_when_styleCheckIsOn() {
        val hits = DeskStyle.check("我其实想写,但是非常忙。", StyleRule.entries.toSet())
        assertEquals(listOf(StyleRule.Filler, StyleRule.Punct, StyleRule.Degree), hits.map { it.rule })
    }

    @Test
    fun should_markEveryDe_when_aSentenceHasTooMany() {
        val hits = DeskStyle.check("我的书的封面的颜色的样子。", setOf(StyleRule.ManyDe))
        assertEquals(4, hits.size)
    }

    @Test
    fun should_markNothing_when_noRuleIsOn() {
        assertTrue(DeskStyle.check("其实非常", emptySet()).isEmpty())
    }

    @Test
    fun should_markPastedCharacters_when_textIsPastedInTheMiddle() {
        val before = "开头结尾"
        val after = "开头引用结尾"
        val origins = DeskOrigins.splice(List(before.length) { Origin.Typed }, before, after, Origin.Pasted)
        assertEquals(listOf(OriginSpan(2, 4, Origin.Pasted)), DeskOrigins.spans(origins, 0, after.length))
    }

    @Test
    fun should_roundTripSpans_when_encoded() {
        val map = mapOf("a" to listOf(OriginSpan(1, 3, Origin.Pasted)), "b" to emptyList())
        assertEquals(mapOf("a" to listOf(OriginSpan(1, 3, Origin.Pasted))), DeskOrigins.decode(DeskOrigins.encode(map)))
    }

    @Test
    fun should_layStoredSpans_when_runIsRendered() {
        val laid = DeskOrigins.lay(6, listOf(0, 4), listOf(emptyList(), listOf(OriginSpan(0, 2, Origin.Pasted))))
        assertEquals(listOf(Origin.Typed, Origin.Typed, Origin.Typed, Origin.Typed, Origin.Pasted, Origin.Pasted), laid)
    }
}
