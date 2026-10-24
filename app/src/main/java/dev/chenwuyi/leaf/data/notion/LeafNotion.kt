package dev.chenwuyi.leaf.data.notion

import dev.chenwuyi.leaf.data.NotionColor
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** The Notion calls Leaf makes, in Leaf's terms. Every call takes the token so nothing here holds a secret. */
class LeafNotion(private val client: NotionClient = NotionClient()) {

    /** Validates a token; returns the workspace name. */
    suspend fun workspaceName(token: String): String {
        val me = client.get(token, "users/me")
        return me.optJSONObject("bot")?.optString("workspace_name")?.takeIf { it.isNotBlank() } ?: me.optString("name", "Notion")
    }

    /** Every database (data source) shared with the integration. */
    suspend fun dataSources(token: String): List<DataSourceInfo> {
        val out = linkedMapOf<String, DataSourceInfo>()
        paginate { cursor ->
            client.post(token, "search", JSONObject()
                .put("filter", JSONObject().put("property", "object").put("value", "data_source"))
                .put("page_size", 100).apply { if (cursor != null) put("start_cursor", cursor) })
        }.forEach { if (it.optString("object") == "data_source") NotionJson.dataSource(it).let { ds -> out[ds.id] = ds } }
        return out.values.toList()
    }

    suspend fun dataSource(token: String, id: String): DataSourceInfo = NotionJson.dataSource(client.get(token, "data_sources/$id"))

    /** Adds 类型 / 卡片颜色 / 置顶 if missing. Needs the integration's "Update content" capability. */
    suspend fun ensureSchema(token: String, source: DataSourceInfo): DataSourceInfo {
        val missing = LeafSchema.missingProperties(source) ?: return source
        return NotionJson.dataSource(client.patch(token, "data_sources/${source.id}", JSONObject().put("properties", missing)))
    }

    suspend fun rows(token: String, source: DataSourceInfo): List<RemoteRow> =
        paginate { cursor ->
            client.post(token, "data_sources/${source.id}/query", JSONObject().put("page_size", 100)
                .put("sorts", JSONArray().put(JSONObject().put("timestamp", "last_edited_time").put("direction", "descending")))
                .apply { if (cursor != null) put("start_cursor", cursor) })
        }.filter { it.optString("object") == "page" && !it.optBoolean("in_trash") }.map { NotionJson.row(it, source.titleProperty) }

    /** Creates a row; returns its page ID. */
    suspend fun createPage(token: String, source: DataSourceInfo, title: String, type: String): String {
        val titleProperty = source.titleProperty ?: throw NotionException(NotionFailure.InvalidResponse)
        return client.post(token, "pages", NotionJson.newPage(source.id, titleProperty, title, type)).getString("id")
    }

    suspend fun updateEssay(token: String, source: DataSourceInfo, pageId: String, title: String, color: NotionColor, pinned: Boolean) {
        val titleProperty = source.titleProperty ?: throw NotionException(NotionFailure.InvalidResponse)
        client.patch(token, "pages/$pageId", NotionJson.essayProperties(titleProperty, title, color, pinned))
    }

    /** Top-level children of a page; Leaf does not descend into nested blocks. */
    suspend fun children(token: String, blockId: String, limit: Int? = null): List<JSONObject> {
        if (limit != null) {
            val page = client.get(token, "blocks/$blockId/children", mapOf("page_size" to limit.coerceIn(1, 100).toString()))
            return page.optJSONArray("results").objects()
        }
        return paginate { cursor -> client.get(token, "blocks/$blockId/children", mapOf("page_size" to "100", "start_cursor" to cursor)) }
    }

    suspend fun wallScribbles(token: String, wallPageId: String): List<RemoteScribble> =
        children(token, wallPageId).mapNotNull(NotionJson::wallScribble)

    suspend fun essayBody(token: String, pageId: String): List<RemoteBlock> =
        children(token, pageId).mapNotNull(NotionJson::bodyBlock)

    /** Appends blocks and returns the created blocks (id + created_time), in order. */
    suspend fun append(token: String, parentId: String, blocks: List<JSONObject>, afterBlockId: String? = null, atStart: Boolean = false): List<Pair<String, Instant>> {
        val response = client.patch(token, "blocks/$parentId/children", NotionJson.children(blocks, afterBlockId, atStart))
        return response.optJSONArray("results").objects().map { it.getString("id") to Instant.parse(it.getString("created_time")) }
    }

    suspend fun updateBlock(token: String, blockId: String, body: JSONObject) {
        client.patch(token, "blocks/$blockId", body)
    }

    suspend fun deleteBlock(token: String, blockId: String) {
        try {
            client.delete(token, "blocks/$blockId")
        } catch (gone: NotionException) {
            // Already deleted in Notion: the goal is reached.
            if (gone.reason != NotionFailure.NotFound) throw gone
        }
    }

    /** Uploads an image with the File Upload API; returns the upload ID to put in an image block. */
    suspend fun uploadImage(token: String, filename: String, contentType: String, bytes: ByteArray): String {
        val created = client.post(token, "file_uploads", JSONObject().put("mode", "single_part").put("filename", filename).put("content_type", contentType))
        val id = created.getString("id")
        val sent = client.sendFile(token, id, filename, contentType, bytes)
        if (sent.optString("status") != "uploaded") throw NotionException(NotionFailure.InvalidResponse)
        return id
    }

    private suspend fun paginate(fetch: suspend (cursor: String?) -> JSONObject): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = fetch(cursor)
            out += page.optJSONArray("results").objects()
            cursor = if (page.optBoolean("has_more")) page.optString("next_cursor").takeIf { it.isNotBlank() && seen.add(it) } else null
        } while (cursor != null)
        return out
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
