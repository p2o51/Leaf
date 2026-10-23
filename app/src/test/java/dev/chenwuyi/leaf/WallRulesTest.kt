package dev.chenwuyi.leaf

import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.PaneLayout
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.data.WallRules
import dev.chenwuyi.leaf.data.WallSort
import dev.chenwuyi.leaf.data.paneLayout
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallRulesTest {
    private fun s(id: String, text: String, hour: Long, color: NotionColor = NotionColor.Default) =
        Scribble(id, text, color, Instant.parse("2026-10-20T00:00:00Z").plusSeconds(hour * 3600))

    @Test
    fun should_keep_strip_rows_about_equally_long_when_dealing_scribbles() {
        val list = (1..30).map { s("s$it", "x".repeat(it % 15 + 1), it.toLong()) }
        val widths = WallRules.stripRows(list).map { row -> row.sumOf { WallRules.estimatedWidth(it) } }
        assertEquals(3, widths.size)
        assertTrue("rows differ too much: $widths", widths.max() - widths.min() <= 18 * 14 + 40)
        assertEquals(30, WallRules.stripRows(list).sumOf { it.size })
    }

    @Test
    fun should_group_by_day_newest_first_when_sorting_by_time() {
        val list = listOf(s("a", "a", 1), s("b", "b", 30), s("c", "c", 2))
        val groups = WallRules.groups(list, WallSort.Newest, ZoneOffset.UTC)
        assertEquals(listOf("10月21日", "10月20日"), groups.map { it.label })
        assertEquals(listOf("c", "a"), groups[1].scribbles.map { it.id })
    }

    @Test
    fun should_group_by_colour_in_notion_order_when_sorting_by_colour() {
        val list = listOf(s("a", "a", 1, NotionColor.Red), s("b", "b", 2, NotionColor.Yellow), s("c", "c", 3, NotionColor.Red))
        val groups = WallRules.groups(list, WallSort.Color, ZoneOffset.UTC)
        assertEquals(listOf("黄", "红"), groups.map { it.label })
        assertEquals(listOf("c", "a"), groups[1].scribbles.map { it.id })
    }

    @Test
    fun should_append_synced_blocks_oldest_first_with_blank_paragraphs_when_copying_into_an_essay() {
        var n = 0
        val essay = Essay("e", "t", listOf(EssayBlock.Paragraph("p0", "hello")), NotionColor.Default, null, false, Instant.EPOCH)
        val out = WallRules.copyInto(essay, listOf(s("late", "l", 5), s("early", "e", 1)), Instant.EPOCH) { "id${n++}" }
        assertEquals("e", out.id)
        assertEquals(5, out.blocks.size)
        assertEquals("p0", out.blocks[0].id)
        assertEquals("early", (out.blocks[1] as EssayBlock.SyncedScribble).scribbleId)
        assertEquals("", (out.blocks[2] as EssayBlock.Paragraph).text)
        assertEquals("late", (out.blocks[3] as EssayBlock.SyncedScribble).scribbleId)
    }

    @Test
    fun should_start_a_new_untitled_essay_when_copying_with_no_target() {
        var n = 0
        val out = WallRules.copyInto(null, listOf(s("a", "a", 1)), Instant.EPOCH) { "id${n++}" }
        assertEquals("", out.title)
        assertEquals(2, out.blocks.size)
    }

    @Test
    fun should_put_pinned_essays_first_when_searching_with_empty_query() {
        val old = Essay("old", "旧的", emptyList(), NotionColor.Default, null, pinned = true, editedAt = Instant.EPOCH)
        val new = Essay("new", "新的", emptyList(), NotionColor.Default, null, pinned = false, editedAt = Instant.EPOCH.plusSeconds(10))
        assertEquals(listOf("old", "new"), WallRules.searchEssays(listOf(new, old), " ").map { it.id })
        assertEquals(listOf("new"), WallRules.searchEssays(listOf(new, old), "新").map { it.id })
    }

    @Test
    fun should_split_only_on_wide_screens_when_an_essay_is_open() {
        assertEquals(PaneLayout.ListOnly, paneLayout(1280, essayOpen = false))
        assertEquals(PaneLayout.Split, paneLayout(1280, essayOpen = true))
        assertEquals(PaneLayout.EditorOnly, paneLayout(411, essayOpen = true))
    }

    @Test
    fun should_map_both_notion_colour_names_when_reading_from_the_api() {
        assertEquals(NotionColor.Yellow, NotionColor.fromApi("yellow_background"))
        assertEquals(NotionColor.Yellow, NotionColor.fromApi("yellow"))
        assertEquals(NotionColor.Default, NotionColor.fromApi("brand_new_colour"))
        assertEquals("blue_background", NotionColor.Blue.backgroundApiName)
    }
}
