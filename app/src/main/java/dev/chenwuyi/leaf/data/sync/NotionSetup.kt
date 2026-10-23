package dev.chenwuyi.leaf.data.sync

import dev.chenwuyi.leaf.data.local.LeafDao
import dev.chenwuyi.leaf.data.notion.DataSourceInfo
import dev.chenwuyi.leaf.data.notion.LeafNotion
import dev.chenwuyi.leaf.data.notion.LeafSchema
import dev.chenwuyi.leaf.data.notion.NotionException
import dev.chenwuyi.leaf.data.notion.NotionFailure
import dev.chenwuyi.leaf.data.settings.Connection
import dev.chenwuyi.leaf.data.settings.ConnectionStore

/** Connecting and disconnecting a Notion database (plan: 首次启动选一个数据库，缺什么字段就补什么). */
class NotionSetup(
    private val notion: LeafNotion,
    private val store: ConnectionStore,
    private val dao: LeafDao,
    private val engine: SyncEngine,
) {
    /** Step 1: check the token and list the databases shared with it. */
    suspend fun discover(token: String): Pair<String, List<DataSourceInfo>> {
        val clean = token.trim()
        val workspace = notion.workspaceName(clean)
        return workspace to notion.dataSources(clean)
    }

    /**
     * Step 2: add any missing fields, find the 涂鸦墙 row (or create it), save the connection, drop
     * the sample data and pull everything.
     */
    suspend fun connect(token: String, workspace: String, picked: DataSourceInfo) {
        val clean = token.trim()
        val source = notion.ensureSchema(clean, notion.dataSource(clean, picked.id))
        val titleProperty = source.titleProperty ?: throw NotionException(NotionFailure.InvalidResponse)
        val wall = notion.rows(clean, source).filter { it.isWall }.minByOrNull { it.editedAt }?.pageId
            ?: notion.createPage(clean, source, LeafSchema.WallTitle, LeafSchema.TypeWall)
        dao.clearAll()
        store.save(Connection(workspace, source.id, source.name, titleProperty, wall), clean)
        engine.run()
    }

    /** Forgets the token and every local copy; Notion keeps everything. */
    suspend fun disconnect() {
        store.clear()
        dao.clearAll()
    }
}
