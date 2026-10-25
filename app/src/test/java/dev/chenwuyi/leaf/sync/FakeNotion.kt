package dev.chenwuyi.leaf.sync

import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

/** Just enough of the Notion API for Leaf's sync: pages hold top-level blocks, rows live in one data source. */
class FakeNotion : Dispatcher() {
    data class Block(val id: String, var json: JSONObject)

    val children = mutableMapOf<String, MutableList<Block>>()
    val rows = mutableListOf<JSONObject>()
    val log = CopyOnWriteArrayList<String>()
    private var next = 1

    /** Runs while an append is in flight, before Notion answers: for edits that race a push. */
    var duringAppend: () -> Unit = {}

    fun paragraph(id: String, text: String, color: String = "default", created: String = "2026-10-20T01:00:00.000Z") = Block(id, JSONObject()
        .put("object", "block").put("id", id).put("type", "paragraph").put("created_time", created)
        .put("paragraph", JSONObject().put("color", color).put("rich_text", JSONArray().put(JSONObject().put("plain_text", text)))))

    fun image(id: String, url: String, caption: String = "") = Block(id, JSONObject()
        .put("object", "block").put("id", id).put("type", "image").put("created_time", "2026-10-20T01:00:00.000Z")
        .put("image", JSONObject().put("type", "file").put("file", JSONObject().put("url", url))
            .put("caption", JSONArray().apply { if (caption.isNotEmpty()) put(JSONObject().put("plain_text", caption)) })))

    fun row(id: String, title: String, type: String?, edited: String = "2026-10-21T00:00:00.000Z", tags: List<String>? = null): JSONObject {
        val props = JSONObject().put("Name", JSONObject().put("type", "title").put("title", JSONArray().put(JSONObject().put("plain_text", title))))
        if (type != null) props.put("类型", JSONObject().put("type", "select").put("select", JSONObject().put("name", type)))
        if (tags != null) props.put("标签", JSONObject().put("type", "multi_select").put("multi_select", JSONArray(tags.map { JSONObject().put("name", it) })))
        return JSONObject().put("object", "page").put("id", id).put("last_edited_time", edited).put("properties", props)
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath.removePrefix("/v1/")
        val raw = request.body.readUtf8()
        val body = raw.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        log += "${request.method} $path${body?.let { " $it" } ?: ""}"
        val parts = path.split('/')
        return when {
            request.method == "GET" && parts[0] == "blocks" && parts.getOrNull(2) == "children" ->
                ok(JSONObject().put("results", JSONArray(children[parts[1]].orEmpty().map { it.json })).put("has_more", false))
            request.method == "PATCH" && parts[0] == "blocks" && parts.getOrNull(2) == "children" -> {
                duringAppend()
                val list = children.getOrPut(parts[1]) { mutableListOf() }
                val created = (0 until body!!.getJSONArray("children").length()).map { i ->
                    val json = body.getJSONArray("children").getJSONObject(i)
                    val id = "new-${next++}"
                    Block(id, JSONObject(json.toString()).put("id", id).put("created_time", "2026-10-23T09:00:00.000Z"))
                }
                val after = body.optJSONObject("position")?.optJSONObject("after_block")?.optString("id")
                val atStart = body.optJSONObject("position")?.optString("type") == "start"
                val at = after?.let { a -> list.indexOfFirst { it.id == a } + 1 } ?: if (atStart) 0 else list.size
                list.addAll(at, created)
                ok(JSONObject().put("results", JSONArray(created.map { it.json })))
            }
            request.method == "PATCH" && parts[0] == "blocks" -> {
                children.values.flatten().firstOrNull { it.id == parts[1] }?.let { b -> body!!.keys().forEach { k -> b.json.put(k, body.get(k)) } }
                ok(JSONObject().put("id", parts[1]))
            }
            request.method == "DELETE" && parts[0] == "blocks" -> {
                children.values.forEach { list -> list.removeAll { it.id == parts[1] } }
                ok(JSONObject().put("id", parts[1]))
            }
            parts[0] == "data_sources" && parts.getOrNull(2) == "query" -> ok(JSONObject().put("results", JSONArray(rows)).put("has_more", false))
            request.method == "POST" && path == "pages" -> {
                val id = "page-${next++}"
                rows += JSONObject(body.toString()).put("object", "page").put("id", id).put("last_edited_time", "2026-10-23T09:00:00.000Z")
                ok(JSONObject().put("id", id))
            }
            request.method == "POST" && path == "file_uploads" -> ok(JSONObject().put("id", "up-${next++}").put("status", "pending"))
            request.method == "POST" && parts[0] == "file_uploads" && parts.getOrNull(2) == "send" -> ok(JSONObject().put("id", parts[1]).put("status", "uploaded"))
            request.method == "PATCH" && parts[0] == "pages" -> ok(JSONObject().put("id", parts[1]))
            else -> MockResponse().setResponseCode(404).setBody("""{"object":"error"}""")
        }
    }

    private fun ok(json: JSONObject) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(json.toString())
}
