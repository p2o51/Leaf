package dev.chenwuyi.leaf.data.notion

import dev.chenwuyi.leaf.data.NotionColor
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class PropertyInfo(val id: String, val name: String, val type: String)

data class DataSourceInfo(val id: String, val name: String, val properties: List<PropertyInfo>) {
    val titleProperty: String? get() = properties.firstOrNull { it.type == "title" }?.name
    fun has(name: String, type: String) = properties.any { it.name == name && it.type == type }
}

/** One line read from the wall page: a coloured paragraph, or an image with an optional caption. */
data class RemoteScribble(val blockId: String, val text: String, val color: NotionColor, val createdAt: Instant, val imageUrl: String?)

data class RemoteRow(
    val pageId: String,
    val title: String,
    val isWall: Boolean,
    val color: NotionColor,
    val pinned: Boolean,
    val coverUrl: String?,
    val editedAt: Instant,
)

/** An essay body block. [type] is Notion's block type; only text-bearing types are editable in Leaf. */
data class RemoteBlock(val blockId: String, val type: String, val text: String, val color: NotionColor, val imageUrl: String? = null) {
    val editable: Boolean get() = type in NotionJson.TEXT_TYPES
}

/**
 * Leaf's database contract (plan: Notion 数据模型). Field names are what Leaf adds when they are missing;
 * the title field keeps whatever name the database already uses.
 */
object LeafSchema {
    const val Type = "类型"
    const val TypeEssay = "随笔"
    const val TypeWall = "涂鸦墙"
    const val CardColor = "卡片颜色"
    const val Pinned = "置顶"
    const val WallTitle = "涂鸦墙"

    /** The properties PATCH body for whatever is missing, or null when the schema is already complete. */
    fun missingProperties(source: DataSourceInfo): JSONObject? {
        val add = JSONObject()
        if (!source.has(Type, "select")) {
            add.put(Type, JSONObject().put("select", JSONObject().put("options", JSONArray()
                .put(JSONObject().put("name", TypeEssay).put("color", "default"))
                .put(JSONObject().put("name", TypeWall).put("color", "yellow")))))
        }
        if (!source.has(CardColor, "select")) {
            val options = JSONArray()
            NotionColor.entries.forEach { options.put(JSONObject().put("name", it.label).put("color", it.apiName)) }
            add.put(CardColor, JSONObject().put("select", JSONObject().put("options", options)))
        }
        if (!source.has(Pinned, "checkbox")) add.put(Pinned, JSONObject().put("checkbox", JSONObject()))
        return if (add.length() == 0) null else add
    }
}

object NotionJson {
    val TEXT_TYPES = setOf("paragraph", "heading_1", "heading_2", "heading_3", "quote", "bulleted_list_item", "numbered_list_item", "to_do", "callout", "toggle")

    fun dataSource(json: JSONObject): DataSourceInfo {
        val props = json.optJSONObject("properties") ?: JSONObject()
        val list = props.keys().asSequence().mapNotNull { key ->
            val p = props.optJSONObject(key) ?: return@mapNotNull null
            PropertyInfo(p.optString("id"), p.optString("name", key), p.optString("type"))
        }.toList()
        return DataSourceInfo(json.getString("id"), plain(json.optJSONArray("title")).ifBlank { "未命名数据库" }, list)
    }

    fun row(json: JSONObject, titleProperty: String?): RemoteRow {
        val props = json.optJSONObject("properties") ?: JSONObject()
        val title = titleProperty?.let { props.optJSONObject(it)?.optJSONArray("title") }?.let(::plain)
            ?: props.keys().asSequence().mapNotNull { props.optJSONObject(it) }.firstOrNull { it.optString("type") == "title" }?.optJSONArray("title")?.let(::plain)
            ?: ""
        val type = props.optJSONObject(LeafSchema.Type)?.optJSONObject("select")?.optString("name")
        val colorOption = props.optJSONObject(LeafSchema.CardColor)?.optJSONObject("select")
        return RemoteRow(
            pageId = json.getString("id"),
            title = title,
            isWall = type == LeafSchema.TypeWall || (type.isNullOrEmpty() && title.trim() == LeafSchema.WallTitle),
            color = colorOption?.let(::optionColor) ?: NotionColor.Default,
            pinned = props.optJSONObject(LeafSchema.Pinned)?.optBoolean("checkbox") == true,
            coverUrl = fileUrl(json.optJSONObject("cover")),
            editedAt = Instant.parse(json.getString("last_edited_time")),
        )
    }

    /** Card colour reads the option's Notion colour first, so renamed options still work, then its name. */
    fun optionColor(option: JSONObject): NotionColor {
        val byColor = NotionColor.fromApi(option.optString("color"))
        if (byColor != NotionColor.Default) return byColor
        return NotionColor.entries.firstOrNull { it.label == option.optString("name") } ?: NotionColor.Default
    }

    fun wallScribble(block: JSONObject): RemoteScribble? {
        if (block.optBoolean("in_trash")) return null
        val type = block.optString("type")
        val value = block.optJSONObject(type) ?: return null
        val created = Instant.parse(block.getString("created_time"))
        return when (type) {
            "paragraph" -> {
                val text = plain(value.optJSONArray("rich_text")).trim()
                if (text.isEmpty()) null else RemoteScribble(block.getString("id"), text, NotionColor.fromApi(value.optString("color")), created, null)
            }
            "image" -> RemoteScribble(block.getString("id"), plain(value.optJSONArray("caption")).trim(), NotionColor.Default, created, fileUrl(value))
            else -> null
        }
    }

    fun bodyBlock(block: JSONObject): RemoteBlock? {
        if (block.optBoolean("in_trash")) return null
        val type = block.optString("type")
        val value = block.optJSONObject(type) ?: JSONObject()
        val text = when (type) {
            in TEXT_TYPES -> plain(value.optJSONArray("rich_text"))
            "image" -> plain(value.optJSONArray("caption"))
            else -> ""
        }
        val image = if (type == "image") fileUrl(value) else null
        return RemoteBlock(block.getString("id"), type, text, NotionColor.fromApi(value.optString("color")), image)
    }

    fun plain(array: JSONArray?): String {
        if (array == null) return ""
        return (0 until array.length()).joinToString("") { array.optJSONObject(it)?.optString("plain_text").orEmpty().ifEmpty {
            array.optJSONObject(it)?.optJSONObject("text")?.optString("content").orEmpty()
        } }
    }

    private fun fileUrl(file: JSONObject?): String? {
        file ?: return null
        return when (file.optString("type")) {
            "external" -> file.optJSONObject("external")?.optString("url")
            "file" -> file.optJSONObject("file")?.optString("url")
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    /** Notion caps one text object at 2000 characters, so long text becomes several. */
    fun richText(text: String): JSONArray {
        val out = JSONArray()
        text.chunked(2000).forEach { out.put(JSONObject().put("type", "text").put("text", JSONObject().put("content", it))) }
        return out
    }

    fun paragraph(text: String, color: NotionColor): JSONObject = textBlock("paragraph", text, color)

    /** Any of [TEXT_TYPES] as a new block; a to-do starts unchecked. */
    fun textBlock(type: String, text: String, color: NotionColor): JSONObject {
        val value = JSONObject().put("rich_text", richText(text)).put("color", color.backgroundApiName)
        if (type == "to_do") value.put("checked", false)
        return JSONObject().put("object", "block").put("type", type).put(type, value)
    }

    fun image(uploadId: String, caption: String): JSONObject =
        JSONObject().put("object", "block").put("type", "image").put("image", JSONObject()
            .put("type", "file_upload").put("file_upload", JSONObject().put("id", uploadId))
            .put("caption", richText(caption)))

    /** PATCH body that rewrites a text block's text, keeping its type. */
    fun textUpdate(type: String, text: String, color: NotionColor? = null): JSONObject {
        val value = JSONObject().put("rich_text", richText(text))
        if (color != null) value.put("color", color.backgroundApiName)
        return JSONObject().put(type, value)
    }

    fun captionUpdate(caption: String): JSONObject = JSONObject().put("image", JSONObject().put("caption", richText(caption)))

    fun children(blocks: List<JSONObject>, afterBlockId: String? = null, atStart: Boolean = false): JSONObject {
        val body = JSONObject().put("children", JSONArray(blocks))
        // 2026-03-11 replaced the top-level "after" with a position object; omitted means the end.
        if (afterBlockId != null) {
            body.put("position", JSONObject().put("type", "after_block").put("after_block", JSONObject().put("id", afterBlockId)))
        } else if (atStart) {
            body.put("position", JSONObject().put("type", "start"))
        }
        return body
    }

    fun newPage(dataSourceId: String, titleProperty: String, title: String, type: String): JSONObject =
        JSONObject()
            .put("parent", JSONObject().put("type", "data_source_id").put("data_source_id", dataSourceId))
            .put("properties", JSONObject()
                .put(titleProperty, JSONObject().put("title", richText(title)))
                .put(LeafSchema.Type, JSONObject().put("select", JSONObject().put("name", type))))

    fun essayProperties(titleProperty: String, title: String, color: NotionColor, pinned: Boolean): JSONObject =
        JSONObject().put("properties", JSONObject()
            .put(titleProperty, JSONObject().put("title", richText(title)))
            .put(LeafSchema.CardColor, if (color == NotionColor.Default) JSONObject().put("select", JSONObject.NULL) else JSONObject().put("select", JSONObject().put("name", color.label)))
            .put(LeafSchema.Pinned, JSONObject().put("checkbox", pinned)))
}
