package dev.chenwuyi.leaf.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.local.LeafDatabase
import dev.chenwuyi.leaf.data.local.RoomLeafRepository
import dev.chenwuyi.leaf.data.notion.HttpNotionOAuthRelay
import dev.chenwuyi.leaf.data.notion.LeafNotion
import dev.chenwuyi.leaf.data.notion.NotionClient
import dev.chenwuyi.leaf.data.notion.NotionCredential
import dev.chenwuyi.leaf.data.notion.NotionException
import dev.chenwuyi.leaf.data.notion.NotionFailure
import dev.chenwuyi.leaf.data.notion.NotionOAuthRelay
import dev.chenwuyi.leaf.data.notion.NotionOAuthResult
import dev.chenwuyi.leaf.data.notion.NotionOAuthSession
import dev.chenwuyi.leaf.data.notion.NotionOAuthTokens
import dev.chenwuyi.leaf.data.settings.Connection
import dev.chenwuyi.leaf.data.settings.MemoryConnectionStore
import dev.chenwuyi.leaf.data.sync.NotionAuth
import dev.chenwuyi.leaf.data.sync.NotionSetup
import dev.chenwuyi.leaf.data.sync.RunResult
import dev.chenwuyi.leaf.data.sync.SyncEngine
import dev.chenwuyi.leaf.data.sync.SyncStatus
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class NotionOAuthTest {
    private val fake = FakeNotion()
    private val server = MockWebServer().apply { dispatcher = fake }
    private lateinit var db: LeafDatabase
    private lateinit var notion: LeafNotion
    private val relay = Relay()
    private val connection = Connection("WS", "ds", "随笔库", "Name", "wall")

    @Before
    fun setUp() {
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LeafDatabase::class.java).allowMainThreadQueries().build()
        notion = LeafNotion(NotionClient(baseUrl = "http://127.0.0.1:${server.port}/v1/".toHttpUrl(), requestIntervalMillis = 0))
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test
    fun should_read_older_tokens_unchanged_when_the_vault_holds_a_plain_integration_token() {
        assertEquals(NotionCredential.Integration("test-only-integration"), NotionCredential.decode("test-only-integration"))
        assertEquals("test-only-integration", NotionCredential.Integration("test-only-integration").encode())
        val pair = NotionCredential.OAuth("test-only-access", "test-only-refresh")
        assertEquals(pair, NotionCredential.decode(pair.encode()))
    }

    @Test
    fun should_only_accept_the_callback_it_asked_for_when_notion_sends_the_browser_back() {
        val session = NotionOAuthSession()
        session.deliver("leaf://notion-oauth?code=c&state=forged")
        assertTrue(session.consume() is NotionOAuthResult.Failed)

        val start = session.start()
        assertTrue(start.startsWith("https://notion-oauth-relay.p2o51willam.workers.dev/leaf/start?state="))
        val state = start.substringAfter("state=")
        session.deliver("daylist://notion-oauth?code=c&state=$state")
        assertNull(session.consume())
        session.deliver("leaf://notion-oauth?code=test-code&state=$state")
        assertEquals(NotionOAuthResult.Code("test-code"), session.consume())
        // A state is spent by its first callback.
        session.deliver("leaf://notion-oauth?code=test-code&state=$state")
        assertTrue(session.consume() is NotionOAuthResult.Failed)

        session.deliver("leaf://notion-oauth?error=access_denied&state=${session.start().substringAfter("state=")}")
        assertEquals(NotionOAuthResult.Cancelled, session.consume())
    }

    @Test
    fun should_post_to_the_leaf_routes_when_exchanging_and_refreshing() = runBlocking {
        MockWebServer().use { relayServer ->
            relayServer.enqueue(MockResponse().setBody("""{"access_token":"a","refresh_token":"r","bot_id":"bot"}"""))
            relayServer.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
            val http = HttpNotionOAuthRelay(baseUrl = relayServer.url("/"))
            assertEquals(NotionOAuthTokens("a", "r"), http.exchange("test-code"))
            val sent = relayServer.takeRequest()
            assertEquals("/leaf/token", sent.path)
            assertEquals("test-code", JSONObject(sent.body.readUtf8()).getString("code"))

            val failure = runCatching { http.refresh("spent") }.exceptionOrNull()
            assertEquals(NotionFailure.Unauthorized, (failure as NotionException).reason)
            assertEquals("/leaf/refresh", relayServer.takeRequest().path)
        }
    }

    @Test
    fun should_save_the_token_pair_when_connecting_after_sign_in() = runBlocking {
        val store = MemoryConnectionStore()
        val setup = NotionSetup(notion, store, db.dao(), SyncEngine(db.dao(), notion, store), relay)
        fake.rows += fake.row("wall", "涂鸦墙", "涂鸦墙")
        fake.validToken = "access-1"

        val credential = setup.exchange("test-code")
        val (workspace, sources) = setup.discover(credential.accessToken)
        setup.connect(credential, workspace, sources.single())

        assertEquals(listOf("test-code"), relay.exchanged)
        assertEquals(NotionCredential.OAuth("access-1", "refresh-1"), store.credential())
        assertEquals("wall", store.connection.value?.wallPageId)
    }

    @Test
    fun should_refresh_once_and_keep_the_rotated_pair_when_notion_turns_the_access_token_down() = runBlocking {
        val store = MemoryConnectionStore(connection, NotionCredential.OAuth("access-1", "refresh-1"))
        val engine = SyncEngine(db.dao(), notion, store, auth = NotionAuth(store, relay))
        val repo = RoomLeafRepository(db.dao(), isConnected = { true }, requestSync = {})
        fake.validToken = "access-2"
        repo.addScribble("过期以后也要发出去", NotionColor.Default)

        assertEquals(RunResult.Done, engine.run())
        assertEquals(listOf("refresh-1"), relay.refreshed)
        assertEquals(NotionCredential.OAuth("access-2", "refresh-2"), store.credential())
        assertEquals(1, fake.children.getValue("wall").size)
        assertTrue(db.dao().pendingOps().isEmpty())

        // The new token is used from now on, without another refresh.
        engine.run()
        assertEquals(1, relay.refreshed.size)
    }

    @Test
    fun should_ask_to_reconnect_when_a_pasted_integration_token_is_turned_down() = runBlocking {
        val store = MemoryConnectionStore(connection, "test-only-integration")
        val engine = SyncEngine(db.dao(), notion, store, auth = NotionAuth(store, relay))
        fake.validToken = "something-else"
        assertEquals(RunResult.Stopped, engine.run())
        assertTrue(engine.status.value is SyncStatus.NeedsAttention)
        assertTrue(relay.refreshed.isEmpty())
    }

    @Test
    fun should_ask_to_reconnect_when_the_refresh_token_is_spent_too() = runBlocking {
        val store = MemoryConnectionStore(connection, NotionCredential.OAuth("access-1", "refresh-1"))
        val spent = object : NotionOAuthRelay by relay {
            override suspend fun refresh(refreshToken: String): NotionOAuthTokens = throw NotionException(NotionFailure.Unauthorized, 400)
        }
        val engine = SyncEngine(db.dao(), notion, store, auth = NotionAuth(store, spent))
        fake.validToken = "access-2"
        assertEquals(RunResult.Stopped, engine.run())
        assertEquals(NotionCredential.OAuth("access-1", "refresh-1"), store.credential())
    }

    private class Relay : NotionOAuthRelay {
        val exchanged = mutableListOf<String>()
        val refreshed = mutableListOf<String>()
        override suspend fun exchange(code: String): NotionOAuthTokens { exchanged += code; return NotionOAuthTokens("access-1", "refresh-1") }
        override suspend fun refresh(refreshToken: String): NotionOAuthTokens {
            refreshed += refreshToken
            val next = refreshed.size + 1
            return NotionOAuthTokens("access-$next", "refresh-$next")
        }
    }
}
