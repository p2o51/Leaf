package dev.chenwuyi.leaf.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
}
