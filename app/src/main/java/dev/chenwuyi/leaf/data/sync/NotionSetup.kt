package dev.chenwuyi.leaf.data.sync

import dev.chenwuyi.leaf.data.local.LeafDao
import dev.chenwuyi.leaf.data.notion.DataSourceInfo
import dev.chenwuyi.leaf.data.notion.LeafNotion
import dev.chenwuyi.leaf.data.notion.LeafSchema
import dev.chenwuyi.leaf.data.notion.NotionCredential
import dev.chenwuyi.leaf.data.notion.NotionException
import dev.chenwuyi.leaf.data.notion.NotionFailure
import dev.chenwuyi.leaf.data.notion.NotionOAuthRelay
import dev.chenwuyi.leaf.data.settings.Connection
import dev.chenwuyi.leaf.data.settings.ConnectionStore

/** Connecting and disconnecting a Notion database (plan: 首次启动选一个数据库，缺什么字段就补什么). */
class NotionSetup(
    private val notion: LeafNotion,
    private val store: ConnectionStore,
    private val dao: LeafDao,
    private val engine: SyncEngine,
    private val oauth: NotionOAuthRelay? = null,
) {
    /** 用 Notion 登录 is offered only when there is a relay to exchange the code. */
    val signInAvailable: Boolean get() = oauth != null

    /** After 用 Notion 登录: trades the callback's code for a token pair, through the relay. */
    suspend fun exchange(code: String): NotionCredential.OAuth {
        val relay = oauth ?: throw NotionException(NotionFailure.Unauthorized)
        val tokens = relay.exchange(code)
        return NotionCredential.OAuth(tokens.accessToken, tokens.refreshToken)
    }

    /** Step 1: check the token (pasted, or from signing in) and list the databases shared with it. */
    suspend fun discover(token: String): Pair<String, List<DataSourceInfo>> {
        val clean = token.trim()
        val workspace = notion.workspaceName(clean)
        return workspace to notion.dataSources(clean)
    }

    /**
     * Step 2: add any missing fields, find the 涂鸦墙 row (or create it), save the connection, drop
     * the sample data and pull everything.
     */
    suspend fun connect(credential: NotionCredential, workspace: String, picked: DataSourceInfo) {
        val clean = credential.accessToken.trim()
        val source = notion.ensureSchema(clean, notion.dataSource(clean, picked.id))
        val titleProperty = source.titleProperty ?: throw NotionException(NotionFailure.InvalidResponse)
        val wall = notion.rows(clean, source).filter { it.isWall }.minByOrNull { it.editedAt }?.pageId
            ?: notion.createPage(clean, source, LeafSchema.WallTitle, LeafSchema.TypeWall)
        dao.clearAll()
        store.save(Connection(workspace, source.id, source.name, titleProperty, wall), credential)
        engine.run()
    }

    /** Forgets the credential and every local copy; Notion keeps everything. */
    suspend fun disconnect() {
        store.clear()
        dao.clearAll()
    }
}
