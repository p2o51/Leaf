package dev.chenwuyi.leaf

import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.HomeFilter
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.data.TagRules
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagRulesTest {
    private fun s(text: String, color: NotionColor = NotionColor.Default) = Scribble(text, text, color, Instant.EPOCH)
    private fun e(tags: List<String>, color: NotionColor = NotionColor.Default) = Essay("e", "", emptyList(), color, null, false, Instant.EPOCH, tags = tags)

    @Test
    fun should_read_hash_words_as_tags_when_parsing_scribble_text() {
        assertEquals(listOf("东京", "夜晚"), TagRules.inText("便利店灯光 #东京 #夜晚"))
        assertEquals(listOf("东京"), TagRules.inText("灯光很暖#东京，明天再去"))
        assertEquals(listOf("read-later"), TagRules.inText("#read-later #read-later"))
    }

    @Test
    fun should_not_take_hashes_inside_words_or_links_when_parsing_scribble_text() {
        assertEquals(emptyList<String>(), TagRules.inText("学 C# 的第一天"))
        assertEquals(emptyList<String>(), TagRules.inText("https://example.com/#token"))
        assertEquals(emptyList<String>(), TagRules.inText("# 不是标签"))
    }

    @Test
    fun should_list_each_tag_once_from_scribbles_and_essays_when_collecting() {
        val tags = TagRules.all(listOf(s("a #写作"), s("b #东京")), listOf(e(listOf("写作", "Sheaf"))))
        assertEquals(setOf("写作", "东京", "Sheaf"), tags.toSet())
        assertEquals(3, tags.size)
    }

    @Test
    fun should_skip_plain_and_unused_colours_when_listing_colours() {
        val used = TagRules.colorsInUse(listOf(s("a", NotionColor.Yellow), s("b")), listOf(e(emptyList(), NotionColor.Blue)))
        assertEquals(listOf(NotionColor.Yellow, NotionColor.Blue), used)
    }

    @Test
    fun should_match_by_text_for_scribbles_and_by_field_for_essays_when_filtering_by_tag() {
        val tag = HomeFilter.ByTag("东京")
        assertTrue(TagRules.matches(s("灯光 #东京"), tag))
        assertFalse(TagRules.matches(s("东京的灯光"), tag))
        assertTrue(TagRules.matches(e(listOf("东京")), tag))
        assertFalse(TagRules.matches(e(listOf("写作")), tag))
        assertTrue(TagRules.matches(s("x", NotionColor.Red), HomeFilter.ByColor(NotionColor.Red)))
        assertTrue(TagRules.matches(e(emptyList()), HomeFilter.All))
    }
}
