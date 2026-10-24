package dev.chenwuyi.leaf

import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.ui.essay.EditorEdits
import dev.chenwuyi.leaf.ui.essay.InsertKind
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorEditsTest {
    private var n = 0
    private val newId = { "n${++n}" }
    private fun essay(vararg blocks: EssayBlock) = Essay("e", "t", blocks.toList(), NotionColor.Default, null, false, Instant.EPOCH)

    @Test
    fun should_insert_after_the_focused_block_when_one_is_focused() {
        val e = essay(EssayBlock.Paragraph("a", "一"), EssayBlock.Paragraph("b", "二"))
        val out = EditorEdits.insertAfter(e, "a", listOf(EditorEdits.blank(InsertKind.Heading, newId)))
        assertEquals(listOf("a", "n1", "b"), out.blocks.map { it.id })
        assertEquals("heading_2", (out.blocks[1] as EssayBlock.Paragraph).type)
    }

    @Test
    fun should_append_when_nothing_is_focused() {
        val out = EditorEdits.insertAfter(essay(EssayBlock.Paragraph("a", "一")), null, listOf(EssayBlock.Paragraph("x", "")))
        assertEquals(listOf("a", "x"), out.blocks.map { it.id })
    }

    @Test
    fun should_split_the_block_when_enter_is_typed() {
        val e = essay(EssayBlock.Paragraph("a", "早上路口"))
        val (out, focus) = EditorEdits.typed(e, "a", "早上\n路口", newId)
        assertEquals(listOf("早上", "路口"), out.blocks.map { (it as EssayBlock.Paragraph).text })
        assertEquals("n1", focus)
    }

    @Test
    fun should_make_a_block_of_each_pasted_line_when_text_has_several_line_breaks() {
        val e = essay(EssayBlock.Paragraph("a", ""))
        val (out, focus) = EditorEdits.typed(e, "a", "one\ntwo\n\nthree", newId)
        assertEquals(listOf("one", "two", "", "three"), out.blocks.map { (it as EssayBlock.Paragraph).text })
        assertEquals("n3", focus)
    }

    @Test
    fun should_continue_a_list_and_end_it_on_an_empty_item() {
        val e = essay(EssayBlock.Paragraph("a", "牛奶", "bulleted_list_item"))
        val (cont, _) = EditorEdits.typed(e, "a", "牛奶\n", newId)
        assertEquals("bulleted_list_item", (cont.blocks[1] as EssayBlock.Paragraph).type)
        val (ended, focus) = EditorEdits.typed(cont, "n1", "\n", newId)
        assertEquals("paragraph", (ended.blocks[1] as EssayBlock.Paragraph).type)
        assertNull(focus)
        assertEquals(2, ended.blocks.size)
    }

    @Test
    fun should_copy_a_scribble_in_its_colour_and_keep_local_photos() {
        val text = Scribble("s", "便利店灯光", NotionColor.Orange, Instant.EPOCH)
        assertEquals(EssayBlock.Paragraph("n1", "便利店灯光", color = NotionColor.Orange), EditorEdits.fromScribble(text, newId))
        val photo = Scribble("p", "晚霞", NotionColor.Default, Instant.EPOCH, Picture.Local("file:///x.jpg"))
        assertEquals(EssayBlock.Photo("n2", Picture.Local("file:///x.jpg"), "晚霞"), EditorEdits.fromScribble(photo, newId))
        val remoteOnly = Scribble("r", "", NotionColor.Default, Instant.EPOCH, Picture.Remote("https://x/y.jpg"))
        assertNull(EditorEdits.fromScribble(remoteOnly, newId))
        assertEquals(listOf("p", "s"), EditorEdits.pickable(listOf(text, remoteOnly, photo.copy(createdAt = Instant.ofEpochSecond(5)))).map { it.id })
    }
}
