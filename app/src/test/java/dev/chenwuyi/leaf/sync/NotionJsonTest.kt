package dev.chenwuyi.leaf.sync

import android.app.Application
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.notion.DataSourceInfo
import dev.chenwuyi.leaf.data.notion.LeafSchema
import dev.chenwuyi.leaf.data.notion.NotionClient
import dev.chenwuyi.leaf.data.notion.NotionJson
import dev.chenwuyi.leaf.data.notion.PropertyInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class NotionJsonTest {
    @Test
    fun should_add_only_missing_fields_when_preparing_the_schema() {
        val complete = DataSourceInfo("ds", "x", listOf(
            PropertyInfo("t", "Name", "title"), PropertyInfo("a", "类型", "select"), PropertyInfo("b", "卡片颜色", "select"), PropertyInfo("c", "置顶", "checkbox"),
        ))
        assertNull(LeafSchema.missingProperties(complete))
        val bare = DataSourceInfo("ds", "x", listOf(PropertyInfo("t", "Name", "title"), PropertyInfo("c", "置顶", "checkbox")))
        val add = LeafSchema.missingProperties(bare)!!
        assertEquals(setOf("类型", "卡片颜色"), add.keys().asSequence().toSet())
        assertEquals(10, add.getJSONObject("卡片颜色").getJSONObject("select").getJSONArray("options").length())
    }

    @Test
    fun should_treat_an_untyped_row_titled_wall_as_the_wall_when_reading_rows() {
        val json = JSONObject("""{"id":"p","last_edited_time":"2026-10-20T00:00:00.000Z","properties":{"Name":{"type":"title","title":[{"plain_text":"涂鸦墙"}]}}}""")
        assertTrue(NotionJson.row(json, "Name").isWall)
        val essay = JSONObject("""{"id":"p","last_edited_time":"2026-10-20T00:00:00.000Z","properties":{"Name":{"type":"title","title":[{"plain_text":"涂鸦墙"}]},"类型":{"type":"select","select":{"name":"随笔"}}}}""")
        assertFalse(NotionJson.row(essay, "Name").isWall)
    }

    @Test
    fun should_read_tags_from_the_tag_field_or_any_multi_select_when_reading_rows() {
        val tagged = JSONObject("""{"id":"p","last_edited_time":"2026-10-20T00:00:00.000Z","properties":{"Name":{"type":"title","title":[]},"标签":{"type":"multi_select","multi_select":[{"name":"东京"},{"name":"写作"}]}}}""")
        assertEquals(listOf("东京", "写作"), NotionJson.row(tagged, "Name").tags)
        val ownField = JSONObject("""{"id":"p","last_edited_time":"2026-10-20T00:00:00.000Z","properties":{"Name":{"type":"title","title":[]},"Tags":{"type":"multi_select","multi_select":[{"name":"Sheaf"}]}}}""")
        assertEquals(listOf("Sheaf"), NotionJson.row(ownField, "Name").tags)
        val authorsFirst = JSONObject("""{"id":"p","last_edited_time":"2026-10-20T00:00:00.000Z","properties":{"Name":{"type":"title","title":[]},"作者":{"type":"multi_select","multi_select":[{"name":"51"}]},"tags":{"type":"multi_select","multi_select":[{"name":"东京"}]}}}""")
        assertEquals(listOf("东京"), NotionJson.row(authorsFirst, "Name").tags)
        val none = JSONObject("""{"id":"p","last_edited_time":"2026-10-20T00:00:00.000Z","properties":{"Name":{"type":"title","title":[]}}}""")
        assertEquals(emptyList<String>(), NotionJson.row(none, "Name").tags)
    }

    @Test
    fun should_read_card_colour_from_the_option_colour_before_its_name() {
        assertEquals(NotionColor.Blue, NotionJson.optionColor(JSONObject("""{"name":"随便叫","color":"blue"}""")))
        assertEquals(NotionColor.Red, NotionJson.optionColor(JSONObject("""{"name":"红","color":"default"}""")))
    }

    @Test
    fun should_split_long_text_into_2000_character_pieces_when_writing() {
        assertEquals(3, NotionJson.richText("字".repeat(4001)).length())
    }

    @Test
    fun should_use_the_position_object_when_appending_after_a_block() {
        val body = NotionJson.children(listOf(NotionJson.paragraph("a", NotionColor.Default)), "abc")
        assertEquals("after_block", body.getJSONObject("position").getString("type"))
        assertEquals("abc", body.getJSONObject("position").getJSONObject("after_block").getString("id"))
        assertFalse(body.has("after"))
    }

    @Test
    fun should_skip_empty_paragraphs_and_read_image_captions_on_the_wall() {
        val empty = JSONObject("""{"id":"b","type":"paragraph","created_time":"2026-10-20T00:00:00.000Z","paragraph":{"rich_text":[],"color":"default"}}""")
        assertNull(NotionJson.wallScribble(empty))
        val image = JSONObject("""{"id":"i","type":"image","created_time":"2026-10-20T00:00:00.000Z","image":{"type":"file","file":{"url":"https://x/y.jpg"},"caption":[{"plain_text":"晚霞"}]}}""")
        val s = NotionJson.wallScribble(image)!!
        assertEquals("晚霞", s.text)
        assertEquals("https://x/y.jpg", s.imageUrl)
    }

    @Test
    fun should_extract_a_dashed_id_when_given_a_notion_url() {
        assertEquals("1234abcd-0000-1111-2222-333344445555", NotionClient.extractId("https://www.notion.so/ws/Leaf-1234abcd000011112222333344445555?v=99"))
    }

    @Test
    fun should_mark_blocks_leaf_cannot_rewrite_as_fixed_when_reading_a_body() {
        fun block(type: String, value: JSONObject, children: Boolean = false) = NotionJson.bodyBlock(JSONObject()
            .put("object", "block").put("id", "b").put("type", type).put("has_children", children).put(type, value))!!
        fun words(text: String, bold: Boolean = false, link: String? = null) = org.json.JSONArray().put(JSONObject()
            .put("type", "text").put("plain_text", text)
            .put("text", JSONObject().put("content", text).put("link", link?.let { JSONObject().put("url", it) } ?: JSONObject.NULL))
            .put("annotations", JSONObject().put("bold", bold).put("color", "default")))

        assertFalse(block("paragraph", JSONObject().put("rich_text", words("早上"))).fixed)
        assertFalse(block("to_do", JSONObject().put("rich_text", words("买菜")).put("checked", false)).fixed)
        assertTrue(block("paragraph", JSONObject().put("rich_text", words("早上")), children = true).fixed)
        assertTrue(block("paragraph", JSONObject().put("rich_text", words("早上", bold = true))).fixed)
        assertTrue(block("paragraph", JSONObject().put("rich_text", words("早上", link = "https://example.com"))).fixed)
        assertTrue(block("to_do", JSONObject().put("rich_text", words("买菜")).put("checked", true)).fixed)
        assertTrue(block("callout", JSONObject().put("rich_text", words("提示"))).fixed)
    }
}
