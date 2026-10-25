package dev.chenwuyi.leaf.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.chenwuyi.leaf.data.BlockOrder
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.local.EssayBlockEntity
import dev.chenwuyi.leaf.data.local.EssayEntity
import dev.chenwuyi.leaf.data.local.LeafDatabase
import dev.chenwuyi.leaf.data.local.RoomLeafRepository
import dev.chenwuyi.leaf.data.notion.LeafNotion
import dev.chenwuyi.leaf.data.notion.NotionClient
import dev.chenwuyi.leaf.data.settings.Connection
import dev.chenwuyi.leaf.data.settings.MemoryConnectionStore
import dev.chenwuyi.leaf.data.sync.SyncEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SyncEngineTest {
    private val fake = FakeNotion()
    private val server = MockWebServer().apply { dispatcher = fake }
    private lateinit var db: LeafDatabase
    private lateinit var engine: SyncEngine
    private lateinit var repo: RoomLeafRepository
    private val store = MemoryConnectionStore(Connection("WS", "ds", "随笔库", "Name", "wall"), "ntn_test")

    @Before
    fun setUp() {
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LeafDatabase::class.java).allowMainThreadQueries().build()
        val notion = LeafNotion(NotionClient(baseUrl = "http://127.0.0.1:${server.port}/v1/".toHttpUrl(), requestIntervalMillis = 0))
        engine = SyncEngine(db.dao(), notion, store)
        repo = RoomLeafRepository(db.dao(), isConnected = { true }, requestSync = {})
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test
    fun should_append_a_coloured_paragraph_and_keep_its_block_id_when_a_scribble_is_added() = runBlocking {
        val s = repo.addScribble("好的工具应该安静", NotionColor.Yellow)
        engine.run(pull = false)
        val sent = fake.children.getValue("wall").single().json
        assertEquals("yellow_background", sent.getJSONObject("paragraph").getString("color"))
        assertEquals("好的工具应该安静", sent.getJSONObject("paragraph").getJSONArray("rich_text").getJSONObject(0).getJSONObject("text").getString("content"))
        assertEquals(sent.getString("id"), db.dao().scribble(s.id)?.blockId)
        assertTrue(db.dao().pendingOps().isEmpty())
    }

    @Test
    fun should_mirror_the_wall_but_keep_waiting_edits_when_pulling() = runBlocking {
        fake.children["wall"] = mutableListOf(fake.paragraph("b1", "一", "red_background"), fake.paragraph("b2", "二"))
        engine.run()
        assertEquals(setOf("一", "二"), repo.scribbles.first().map { it.text }.toSet())
        assertEquals(NotionColor.Red, repo.scribbles.first().first { it.text == "一" }.color)

        // A local recolour that has not gone out yet survives a pull; a block deleted in Notion disappears.
        val two = repo.scribbles.first().first { it.text == "二" }
        repo.recolor(setOf(two.id), NotionColor.Blue)
        fake.children.getValue("wall").removeAll { it.id == "b1" }
        engine.pull("ntn_test", store.connection.value!!)
        val after = repo.scribbles.first()
        assertEquals(listOf("二"), after.map { it.text })
        assertEquals(NotionColor.Blue, after.single().color)

        engine.run()
        assertEquals("blue_background", fake.children.getValue("wall").single().json.getJSONObject("paragraph").getString("color"))
    }

    @Test
    fun should_delete_the_notion_block_when_a_scribble_is_deleted() = runBlocking {
        fake.children["wall"] = mutableListOf(fake.paragraph("b1", "一"))
        engine.run()
        val one = repo.scribbles.first().single()
        repo.deleteScribbles(setOf(one.id))
        engine.run()
        assertTrue(fake.children.getValue("wall").isEmpty())
        assertTrue(fake.log.any { it.startsWith("DELETE blocks/b1") })
    }

    @Test
    fun should_list_essays_with_a_preview_but_not_the_wall_row_when_pulling() = runBlocking {
        fake.rows += fake.row("wall", "涂鸦墙", "涂鸦墙")
        fake.rows += fake.row("p1", "东京第三天", "随笔")
        fake.children["p1"] = mutableListOf(fake.paragraph("x1", "早上在路口站了一会儿"))
        engine.run()
        val essays = repo.essays.first()
        assertEquals(listOf("东京第三天"), essays.map { it.title })
        assertEquals("早上在路口站了一会儿", essays.single().previewText)
    }

    @Test
    fun should_keep_an_essays_notion_tags_when_pulling() = runBlocking {
        fake.rows += fake.row("p1", "东京第三天", "随笔", tags = listOf("东京", "旅行"))
        engine.run()
        assertEquals(listOf("东京", "旅行"), repo.essays.first().single().tags)
        fake.rows[0] = fake.row("p1", "东京第三天", "随笔", edited = "2026-10-22T00:00:00.000Z", tags = listOf("东京"))
        engine.run()
        assertEquals(listOf("东京"), repo.essays.first().single().tags)
    }

    @Test
    fun should_append_copies_after_the_last_block_when_copying_into_an_essay() = runBlocking {
        db.dao().upsertEssays(listOf(EssayEntity("e1", "p1", "东京第三天", "default", false, 0, bodyLoaded = true)))
        db.dao().upsertBlocks(listOf(EssayBlockEntity("k1", "e1", 0, "x1", "paragraph", "早上", "default")))
        fake.children["p1"] = mutableListOf(fake.paragraph("x1", "早上"), fake.paragraph("x2", "Notion 里后加的"))
        val s = repo.addScribble("便利店灯光", NotionColor.Orange)
        repo.copyToEssay(setOf(s.id), "e1")
        engine.run(pull = false)

        val texts = fake.children.getValue("p1").map { it.json.getJSONObject("paragraph").getJSONArray("rich_text").optJSONObject(0)?.optJSONObject("text")?.optString("content") ?: it.json.getJSONObject("paragraph").getJSONArray("rich_text").optJSONObject(0)?.optString("plain_text") }
        assertEquals(listOf("早上", "便利店灯光", "", "Notion 里后加的"), texts.map { it ?: "" })
        val local = repo.essays.first().single().blocks
        assertEquals(3, local.size)
        assertEquals(NotionColor.Orange, (local[1] as EssayBlock.Paragraph).color)
        assertNotNull(db.dao().blocksOf("e1")[2].blockId)
    }

    @Test
    fun should_upload_the_image_again_when_a_deleted_notion_photo_is_restored() = runBlocking {
        val withDownload = SyncEngine(db.dao(), LeafNotion(NotionClient(baseUrl = "http://127.0.0.1:${server.port}/v1/".toHttpUrl(), requestIntervalMillis = 0)), store,
            downloadPhoto = { byteArrayOf(1, 2, 3) })
        fake.children["wall"] = mutableListOf(fake.image("i1", "https://files.example/a.jpg"))
        withDownload.run()
        val photo = repo.scribbles.first().single()
        val removed = repo.deleteScribbles(setOf(photo.id))
        repo.restoreScribbles(removed)
        withDownload.run(pull = false)

        val wall = fake.children.getValue("wall")
        assertEquals(listOf("image"), wall.map { it.json.getString("type") })
        assertEquals("file_upload", wall.single().json.getJSONObject("image").getString("type"))
    }

    @Test
    fun should_keep_local_typing_when_the_body_changed_during_a_fetch() = runBlocking {
        db.dao().upsertEssays(listOf(EssayEntity("e1", "p1", "东京第三天", "default", false, 0)))
        val before = listOf(EssayBlockEntity("k1", "e1", 0, "x1", "paragraph", "早上", "default"))
        db.dao().upsertBlocks(before)
        db.dao().upsertBlocks(listOf(before.single().copy(text = "早上，路口")))
        val replaced = db.dao().replaceBodyIfUnchanged("e1", before, listOf(EssayBlockEntity("n1", "e1", 0, "x1", "paragraph", "早上", "default")))
        assertEquals(false, replaced)
        assertEquals("早上，路口", db.dao().blocksOf("e1").single().text)
    }

    @Test
    fun should_keep_local_block_ids_when_loading_a_body_again() = runBlocking {
        db.dao().upsertEssays(listOf(EssayEntity("e1", "p1", "东京第三天", "default", false, 0)))
        db.dao().upsertBlocks(listOf(EssayBlockEntity("k1", "e1", 0, "x1", "paragraph", "早上", "default")))
        fake.children["p1"] = mutableListOf(fake.paragraph("x1", "早上，路口"), fake.paragraph("x2", "晚上"))
        engine.loadBody("e1")
        val blocks = db.dao().blocksOf("e1")
        assertEquals(listOf("早上，路口", "晚上"), blocks.map { it.text })
        assertEquals("k1", blocks.first().id)
        assertTrue(db.dao().essay("e1")!!.bodyLoaded)
    }

    @Test
    fun should_create_typed_blocks_and_upload_photos_when_inserted_in_the_editor() = runBlocking {
        val withPhotos = SyncEngine(db.dao(), LeafNotion(NotionClient(baseUrl = "http://127.0.0.1:${server.port}/v1/".toHttpUrl(), requestIntervalMillis = 0)), store,
            readPhoto = { byteArrayOf(1, 2, 3) })
        db.dao().upsertEssays(listOf(EssayEntity("e1", "p1", "东京第三天", "default", false, 0, bodyLoaded = true)))
        db.dao().upsertBlocks(listOf(EssayBlockEntity("k1", "e1", 0, "x1", "paragraph", "早上", "default")))
        fake.children["p1"] = mutableListOf(fake.paragraph("x1", "早上"))
        val essay = repo.essays.first().single()
        repo.updateEssay(essay.copy(blocks = essay.blocks + EssayBlock.Paragraph("h", "路口", "heading_2") + EssayBlock.Photo("i", dev.chenwuyi.leaf.data.Picture.Local("file:///photo.jpg"), "晚霞")))
        withPhotos.run(pull = false)

        val sent = fake.children.getValue("p1").map { it.json.getString("type") }
        assertEquals(listOf("paragraph", "heading_2", "image"), sent)
        assertEquals("file_upload", fake.children.getValue("p1")[2].json.getJSONObject("image").getString("type"))
        assertTrue(db.dao().blocksOf("e1").all { it.blockId != null })
    }

    @Test
    fun should_delete_the_notion_block_when_a_block_is_removed_in_the_editor() = runBlocking {
        db.dao().upsertEssays(listOf(EssayEntity("e1", "p1", "东京第三天", "default", false, 0, bodyLoaded = true)))
        db.dao().upsertBlocks(listOf(EssayBlockEntity("k1", "e1", 0, "x1", "paragraph", "早上", "default"), EssayBlockEntity("k2", "e1", 1, "x2", "paragraph", "", "default")))
        fake.children["p1"] = mutableListOf(fake.paragraph("x1", "早上"), fake.paragraph("x2", ""))
        repo.removeBlocks("e1", setOf("k2"))
        engine.run(pull = false)
        assertEquals(listOf("x1"), fake.children.getValue("p1").map { it.id })
        assertEquals(listOf("k1"), db.dao().blocksOf("e1").map { it.id })
    }

    @Test
    fun should_delete_the_new_notion_block_when_it_is_removed_while_being_created() = runBlocking {
        db.dao().upsertEssays(listOf(EssayEntity("e1", "p1", "东京第三天", "default", false, 0, bodyLoaded = true)))
        fake.children["p1"] = mutableListOf()
        val essay = repo.essays.first().single()
        repo.updateEssay(essay.copy(blocks = essay.blocks + EssayBlock.Paragraph("k1", "写错了")))
        fake.duringAppend = { runBlocking { repo.removeBlocks("e1", setOf("k1")) } }
        engine.run(pull = false)
        assertEquals(emptyList<String>(), fake.children.getValue("p1").map { it.id })
        assertEquals(emptyList<String>(), db.dao().blocksOf("e1").map { it.id })
    }

    @Test
    fun should_import_a_picked_photo_once_when_the_editor_keeps_sending_its_content_uri() = runBlocking {
        var imports = 0
        val photos = RoomLeafRepository(db.dao(), isConnected = { true }, requestSync = {}, importPhoto = { imports++; "file:///copy-$imports.jpg" })
        db.dao().upsertEssays(listOf(EssayEntity("e1", "p1", "东京第三天", "default", false, 0, bodyLoaded = true)))
        val essay = photos.essays.first().single()
        val picked = EssayBlock.Photo("i", dev.chenwuyi.leaf.data.Picture.Local("content://media/1"))
        photos.updateEssay(essay.copy(blocks = listOf(picked)))
        photos.updateEssay(essay.copy(title = "东京", blocks = listOf(picked)))
        assertEquals(1, imports)
        assertEquals("file:///copy-1.jpg", db.dao().blocksOf("e1").single().photo)
    }

    @Test
    fun should_replace_the_notion_block_when_a_block_is_restyled() = runBlocking {
        db.dao().upsertEssays(listOf(EssayEntity("e1", "p1", "东京第三天", "default", false, 0, bodyLoaded = true)))
        db.dao().upsertBlocks(listOf(EssayBlockEntity("k1", "e1", 0, "x1", "paragraph", "早上", "default"), EssayBlockEntity("k2", "e1", 1, "x2", "paragraph", "路口", "default")))
        fake.children["p1"] = mutableListOf(fake.paragraph("x1", "早上"), fake.paragraph("x2", "路口"))
        val essay = repo.essays.first().single()
        repo.updateEssay(essay.copy(blocks = essay.blocks.map { if (it.id == "k1") (it as EssayBlock.Paragraph).copy(type = "heading_2") else it }))
        engine.run(pull = false)
        val sent = fake.children.getValue("p1")
        assertEquals(listOf("heading_2", "paragraph"), sent.map { it.json.getString("type") })
        assertEquals("x2", sent[1].id)
        assertEquals(sent[0].id, db.dao().blocksOf("e1").first { it.id == "k1" }.blockId)
    }

    @Test
    fun should_write_the_block_again_after_its_new_neighbour_when_a_block_is_moved() = runBlocking {
        db.dao().upsertEssays(listOf(EssayEntity("e1", "p1", "东京第三天", "default", false, 0, bodyLoaded = true)))
        db.dao().upsertBlocks(listOf(
            EssayBlockEntity("k1", "e1", 0, "x1", "paragraph", "一", "default"),
            EssayBlockEntity("k2", "e1", 1, "x2", "paragraph", "二", "default"),
            EssayBlockEntity("k3", "e1", 2, "x3", "paragraph", "三", "default"),
        ))
        fake.children["p1"] = mutableListOf(fake.paragraph("x1", "一"), fake.paragraph("x2", "二"), fake.paragraph("x3", "三"))
        repo.moveBlock("e1", "k1", 2)
        engine.run(pull = false)

        val sent = fake.children.getValue("p1")
        assertEquals(listOf("x2", "x3"), sent.take(2).map { it.id })
        assertEquals(3, sent.size)
        val local = db.dao().blocksOf("e1")
        assertEquals(listOf("k2", "k3", "k1"), local.map { it.id })
        assertEquals(sent[2].id, local.last().blockId)
        assertTrue(fake.log.any { it.startsWith("DELETE blocks/x1") })
    }

    @Test
    fun should_keep_a_moved_photo_in_notion_until_its_copy_is_uploaded() = runBlocking {
        var online = false
        val photos = SyncEngine(db.dao(), LeafNotion(NotionClient(baseUrl = "http://127.0.0.1:${server.port}/v1/".toHttpUrl(), requestIntervalMillis = 0)), store,
            downloadPhoto = { url -> if (online && url == "https://fresh") byteArrayOf(1, 2, 3) else null })
        db.dao().upsertEssays(listOf(EssayEntity("e1", "p1", "东京第三天", "default", false, 0, bodyLoaded = true)))
        db.dao().upsertBlocks(listOf(
            EssayBlockEntity("k1", "e1", 0, "x1", "image", "路口", "default", photo = "https://expired"),
            EssayBlockEntity("k2", "e1", 1, "x2", "paragraph", "二", "default"),
        ))
        fake.children["p1"] = mutableListOf(fake.image("x1", "https://fresh", "路口"), fake.paragraph("x2", "二"))
        repo.moveBlock("e1", "k1", 1)

        // The download fails: the original stays and the move waits for another run.
        photos.run(pull = false)
        assertEquals(listOf("x1", "x2"), fake.children.getValue("p1").map { it.id })
        assertTrue(fake.log.none { it.startsWith("DELETE blocks/x1") })
        assertEquals(1, db.dao().pendingOps().size)

        // Back online, the expired URL is swapped for Notion's fresh one and the copy goes after 二.
        online = true
        photos.run(pull = false)
        val sent = fake.children.getValue("p1")
        assertEquals(listOf("x2", "image"), sent.map { if (it.id == "x2") it.id else it.json.getString("type") })
        assertEquals(sent[1].id, db.dao().block("k1")?.blockId)
        assertTrue(db.dao().pendingOps().isEmpty())
    }

    @Test
    fun should_not_offer_to_move_a_block_with_content_leaf_did_not_load() = runBlocking {
        db.dao().upsertEssays(listOf(EssayEntity("e1", "p1", "东京第三天", "default", false, 0)))
        fake.children["p1"] = mutableListOf(fake.paragraph("x1", "一"), fake.paragraph("x2", "二").apply { json.put("has_children", true) })
        engine.loadBody("e1")
        val blocks = repo.essays.first().single().blocks
        assertEquals(listOf(true, false), blocks.map { BlockOrder.movable(it) })
    }

    @Test
    fun should_upload_a_picked_cover_and_clear_a_removed_one() = runBlocking {
        val withPhotos = SyncEngine(db.dao(), LeafNotion(NotionClient(baseUrl = "http://127.0.0.1:${server.port}/v1/".toHttpUrl(), requestIntervalMillis = 0)), store,
            readPhoto = { byteArrayOf(1, 2, 3) })
        db.dao().upsertEssays(listOf(EssayEntity("e1", "p1", "东京第三天", "default", false, 0, bodyLoaded = true)))
        repo.setCover("e1", dev.chenwuyi.leaf.data.Picture.Local("file:///cover.jpg"))
        assertEquals(dev.chenwuyi.leaf.data.Picture.Local("file:///cover.jpg"), repo.essays.first().single().cover)
        withPhotos.run(pull = false)
        assertTrue(fake.log.any { it.startsWith("PATCH pages/p1") && it.contains("file_upload") })

        repo.setCover("e1", null)
        withPhotos.run(pull = false)
        assertTrue(fake.log.last { it.startsWith("PATCH pages/p1") }.contains("\"cover\":null"))
    }

    @Test
    fun should_trash_the_page_and_not_pull_it_back_when_an_essay_is_deleted() = runBlocking {
        fake.rows += fake.row("p1", "东京第三天", "随笔")
        engine.run()
        val essay = repo.essays.first().single()
        repo.deleteEssay(essay.id)
        engine.pull("ntn_test", store.connection.value!!)
        assertTrue(repo.essays.first().isEmpty())
        engine.run(pull = false)
        assertTrue(fake.log.any { it.startsWith("PATCH pages/p1") && it.contains("in_trash") })
    }

    @Test
    fun should_send_a_divider_when_one_is_inserted() = runBlocking {
        db.dao().upsertEssays(listOf(EssayEntity("e1", "p1", "东京第三天", "default", false, 0, bodyLoaded = true)))
        db.dao().upsertBlocks(listOf(EssayBlockEntity("k1", "e1", 0, "x1", "paragraph", "早上", "default")))
        fake.children["p1"] = mutableListOf(fake.paragraph("x1", "早上"))
        val essay = repo.essays.first().single()
        repo.updateEssay(essay.copy(blocks = essay.blocks + EssayBlock.Other("d", EssayBlock.Divider) + EssayBlock.Paragraph("k2", "晚上")))
        engine.run(pull = false)
        assertEquals(listOf("paragraph", "divider", "paragraph"), fake.children.getValue("p1").map { it.json.getString("type") })
    }

    @Test
    fun should_keep_the_day_an_essay_was_created_when_pulling() = runBlocking {
        fake.rows += fake.row("p1", "东京第三天", "随笔").put("created_time", "2026-10-20T08:00:00.000Z")
        engine.run()
        assertEquals(java.time.Instant.parse("2026-10-20T08:00:00Z"), repo.essays.first().single().createdAt)
    }
}
