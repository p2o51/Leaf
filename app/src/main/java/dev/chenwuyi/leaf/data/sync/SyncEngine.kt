package dev.chenwuyi.leaf.data.sync

import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.local.EssayBlockEntity
import dev.chenwuyi.leaf.data.local.EssayEntity
import dev.chenwuyi.leaf.data.local.LeafDao
import dev.chenwuyi.leaf.data.local.OutboxEntity
import dev.chenwuyi.leaf.data.local.OutboxOp
import dev.chenwuyi.leaf.data.local.ScribbleEntity
import dev.chenwuyi.leaf.data.local.tagColumn
import dev.chenwuyi.leaf.data.notion.DataSourceInfo
import dev.chenwuyi.leaf.data.notion.LeafNotion
import dev.chenwuyi.leaf.data.notion.LeafSchema
import dev.chenwuyi.leaf.data.notion.NotionException
import dev.chenwuyi.leaf.data.notion.NotionFailure
import dev.chenwuyi.leaf.data.notion.NotionJson
import dev.chenwuyi.leaf.data.notion.PropertyInfo
import dev.chenwuyi.leaf.data.settings.Connection
import dev.chenwuyi.leaf.data.settings.ConnectionStore
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Running : SyncStatus
    /** Will retry by itself (offline, rate limited). */
    data class Waiting(val message: String) : SyncStatus
    /** Needs the person: the token stopped working or the database is no longer shared. */
    data class NeedsAttention(val message: String) : SyncStatus
}

enum class RunResult { Done, RetryLater, Stopped }

/**
 * Moves changes between Room and Notion. Push drains the outbox in order; pull mirrors the wall page
 * and the essay rows, never overwriting anything that still has a change waiting in the outbox.
 */
class SyncEngine(
    private val dao: LeafDao,
    private val notion: LeafNotion,
    private val store: ConnectionStore,
    private val readPhoto: (String) -> ByteArray? = { null },
    private val downloadPhoto: suspend (String) -> ByteArray? = { null },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val auth: NotionAuth = NotionAuth(store, relay = null),
) {
    private val mutex = Mutex()
    private val statusState = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = statusState.asStateFlow()

    /** Push then pull. Safe to call from several places; runs one at a time. */
    suspend fun run(pull: Boolean = true): RunResult = mutex.withLock {
        val connection = store.connection.value ?: return@withLock RunResult.Done
        val token = store.token() ?: return@withLock stop("连接凭据不可用，请重新连接 Notion")
        statusState.value = SyncStatus.Running
        try {
            // An OAuth token that expired mid-run is refreshed once; the outbox keeps whatever had not gone out.
            auth.withToken(token) { current ->
                push(current, connection)
                if (pull) pull(current, connection)
            }
            statusState.value = SyncStatus.Idle
            RunResult.Done
        } catch (failure: NotionException) {
            when (failure.reason) {
                NotionFailure.RateLimited, NotionFailure.Unavailable -> {
                    statusState.value = SyncStatus.Waiting(failure.userMessage)
                    RunResult.RetryLater
                }
                else -> stop(failure.userMessage)
            }
        }
    }

    private fun stop(message: String): RunResult {
        statusState.value = SyncStatus.NeedsAttention(message)
        return RunResult.Stopped
    }

    private fun source(c: Connection) = DataSourceInfo(c.dataSourceId, c.databaseName, listOf(PropertyInfo("title", c.titleProperty, "title")))

    // ---- push -------------------------------------------------------------------------------

    private suspend fun push(token: String, c: Connection) {
        while (true) {
            val op = dao.nextOp() ?: return
            val finished = try {
                applyOp(token, c, op)
            } catch (failure: NotionException) {
                // A change Notion rejects for good (400, or the target is gone) must not block the queue.
                if (failure.reason in setOf(NotionFailure.InvalidInput, NotionFailure.NotFound) && op.attempts >= 2) {
                    true
                } else {
                    if (failure.reason in setOf(NotionFailure.InvalidInput, NotionFailure.NotFound)) dao.failedOnce(op.seq)
                    throw failure
                }
            }
            if (finished) dao.done(op.seq)
        }
    }

    /** Returns false when the local row changed while it was being pushed, so the op runs again. */
    private suspend fun applyOp(token: String, c: Connection, op: OutboxEntity): Boolean = when (op.op) {
        OutboxOp.SaveScribble -> {
            val row = dao.scribble(op.targetId)
            if (row == null) true else pushScribble(token, c, row).let { dao.scribble(row.id)?.sameContent(row) ?: true }
        }
        OutboxOp.DeleteScribble, OutboxOp.DeleteBlock -> { notion.deleteBlock(token, op.targetId); true }
        OutboxOp.SaveEssay -> {
            val row = dao.essay(op.targetId)
            if (row == null) true else pushEssay(token, c, row).let { dao.essay(row.id)?.let { it.title == row.title && it.color == row.color && it.pinned == row.pinned } ?: true }
        }
        OutboxOp.SaveBlock -> {
            val row = dao.block(op.targetId)
            if (row == null) true else pushBlock(token, row).let { dao.block(row.id)?.let { it.text == row.text && it.color == row.color && it.type == row.type } ?: true }
        }
        OutboxOp.SaveCover -> {
            val row = dao.essay(op.targetId)
            if (row == null) true else pushCover(token, row).let { dao.essay(row.id)?.coverUrl == row.coverUrl }
        }
        OutboxOp.DeleteEssay -> { notion.trashPage(token, op.targetId); true }
        OutboxOp.MoveBlock -> moveBlock(token, op)
        else -> true
    }

    private fun ScribbleEntity.sameContent(other: ScribbleEntity) = text == other.text && color == other.color

    private suspend fun pushScribble(token: String, c: Connection, row: ScribbleEntity) {
        val color = NotionColor.fromApi(row.color)
        val blockId = row.blockId
        val isPhoto = row.localPhoto != null || row.remotePhoto != null
        if (blockId == null) {
            val block = if (isPhoto) {
                // A restored photo from Notion has only its file URL; Notion's own URLs expire, so the
                // image goes up again as a new upload instead of being linked.
                val bytes = row.localPhoto?.let(readPhoto) ?: row.remotePhoto?.let { downloadPhoto(it) } ?: return
                NotionJson.image(notion.uploadImage(token, "leaf-${row.id}.jpg", "image/jpeg", bytes), row.text)
            } else {
                NotionJson.paragraph(row.text, color)
            }
            val created = notion.append(token, c.wallPageId, listOf(block)).firstOrNull() ?: return
            val now = dao.scribble(row.id) ?: return notion.deleteBlock(token, created.first)
            dao.upsertScribbles(listOf(now.copy(blockId = created.first)))
        } else if (isPhoto) {
            notion.updateBlock(token, blockId, NotionJson.captionUpdate(row.text))
        } else {
            notion.updateBlock(token, blockId, NotionJson.textUpdate("paragraph", row.text, color))
        }
    }

    private suspend fun pushEssay(token: String, c: Connection, row: EssayEntity) {
        val pageId = row.pageId ?: notion.createPage(token, source(c), row.title, LeafSchema.TypeEssay).also { id ->
            dao.essay(row.id)?.let { dao.upsertEssays(listOf(it.copy(pageId = id))) }
        }
        notion.updateEssay(token, source(c), pageId, row.title, NotionColor.fromApi(row.color), row.pinned)
    }

    /** A cover picked in Leaf goes up as a file upload; none clears Notion's. A Notion URL is already there. */
    private suspend fun pushCover(token: String, row: EssayEntity) {
        // The essay's own SaveEssay op is earlier in the queue, so it has a page by now.
        val pageId = row.pageId ?: throw NotionException(NotionFailure.InvalidInput)
        val cover = row.coverUrl
        when {
            cover == null -> notion.setCover(token, pageId, null)
            cover.startsWith("file:") -> {
                val bytes = readPhoto(cover) ?: return
                notion.setCover(token, pageId, notion.uploadImage(token, "leaf-cover-${row.id}.jpg", "image/jpeg", bytes))
            }
        }
    }

    private suspend fun pushBlock(token: String, row: EssayBlockEntity) {
        val essay = dao.essay(row.essayId) ?: return
        // The essay's own SaveEssay op is always earlier in the queue, so it has a page by now.
        val pageId = essay.pageId ?: throw NotionException(NotionFailure.InvalidInput)
        val isImage = row.type == "image"
        val isDivider = row.type == EssayBlock.Divider
        if (row.type !in NotionJson.TEXT_TYPES && !isImage && !isDivider) return
        val color = NotionColor.fromApi(row.color)
        val blockId = row.blockId
        if (blockId == null) {
            val block = if (isImage) {
                // Inserted in Leaf, the photo is a local copy (PhotoStore).
                val bytes = row.photo?.let(readPhoto) ?: return
                NotionJson.image(notion.uploadImage(token, "leaf-${row.id}.jpg", "image/jpeg", bytes), row.text)
            } else if (isDivider) {
                NotionJson.divider()
            } else {
                NotionJson.textBlock(row.type, row.text, color)
            }
            appendInPlace(token, pageId, row, block)
        } else if (isImage) {
            notion.updateBlock(token, blockId, NotionJson.captionUpdate(row.text))
        } else if (!isDivider) {
            notion.updateBlock(token, blockId, NotionJson.textUpdate(row.type, row.text, color))
        }
    }

    /** Appends [block] after [row]'s nearest synced neighbour and links it to the row; returns its ID. */
    private suspend fun appendInPlace(token: String, pageId: String, row: EssayBlockEntity, block: JSONObject): String? {
        val siblings = dao.blocksOf(row.essayId).filter { it.blockId != null && it.id != row.id }
        val after = siblings.filter { it.position < row.position }.maxByOrNull { it.position }?.blockId
        // Nothing synced before it but something after: it is the new first block (a restyled opening line).
        val atStart = after == null && siblings.any { it.position > row.position }
        val created = notion.append(token, pageId, listOf(block), after, atStart).firstOrNull() ?: return null
        // Removed, or restyled into another type, while it was being created: the local row has no
        // use for this block, so delete it here; a restyled row is sent again as its new type.
        val now = dao.block(row.id)?.takeIf { it.type == row.type } ?: return null.also { notion.deleteBlock(token, created.first) }
        dao.upsertBlocks(listOf(now.copy(blockId = created.first)))
        return created.first
    }

    /**
     * Writes a dragged block again after its new neighbour, and only then deletes the old one, so nothing
     * is lost when the copy cannot be made. A photo is uploaded again because Notion's file URLs expire.
     */
    private suspend fun moveBlock(token: String, op: OutboxEntity): Boolean {
        val row = dao.block(op.targetId) ?: return true
        val old = row.blockId ?: return true.also { pushBlock(token, row) }
        val pageId = dao.essay(row.essayId)?.pageId ?: throw NotionException(NotionFailure.InvalidInput)
        val block = when (row.type) {
            "image" -> {
                val bytes = row.photo?.let { downloadPhoto(it) } ?: notion.block(token, old)?.imageUrl?.let { downloadPhoto(it) }
                if (bytes == null) {
                    // A download that keeps failing leaves the photo where Notion has it rather than lose it.
                    if (op.attempts >= 2) return true
                    dao.failedOnce(op.seq)
                    throw NotionException(NotionFailure.Unavailable)
                }
                NotionJson.image(notion.uploadImage(token, "leaf-${row.id}.jpg", "image/jpeg", bytes), row.text)
            }
            EssayBlock.Divider -> NotionJson.divider()
            in NotionJson.TEXT_TYPES -> if (row.fixed) return true else NotionJson.textBlock(row.type, row.text, NotionColor.fromApi(row.color))
            else -> return true
        }
        val created = appendInPlace(token, pageId, row, block)
        // Removed meanwhile, the row's own DeleteBlock (queued after this op) takes the old block away.
        if (created != null) notion.deleteBlock(token, old)
        return true
    }

    // ---- pull -------------------------------------------------------------------------------

    private suspend fun pending(): Set<String> = dao.pendingOps().map { it.targetId }.toSet()

    suspend fun pull(token: String, c: Connection) {
        pullWall(token, c)
        pullEssays(token, c)
    }

    private suspend fun pullWall(token: String, c: Connection) {
        val remote = notion.wallScribbles(token, c.wallPageId)
        val waiting = pending()
        val local = dao.allScribbles().filter { !it.sample }
        val byBlock = local.filter { it.blockId != null }.associateBy { it.blockId }
        val upserts = remote.mapNotNull { r ->
            val mine = byBlock[r.blockId]
            when {
                mine == null -> ScribbleEntity(newId(), r.blockId, r.text, r.color.apiName, r.createdAt.toEpochMilli(), remotePhoto = r.imageUrl)
                mine.id in waiting -> null
                // Local time is kept: a restored scribble is a new block in Notion but the same thought.
                else -> mine.copy(text = r.text, color = r.color.apiName, remotePhoto = r.imageUrl ?: mine.remotePhoto)
            }
        }
        dao.upsertScribbles(upserts)
        val remoteIds = remote.map { it.blockId }.toSet()
        val gone = local.filter { it.blockId != null && it.blockId !in remoteIds && it.id !in waiting }.map { it.id }
        if (gone.isNotEmpty()) dao.deleteScribbles(gone)
    }

    private suspend fun pullEssays(token: String, c: Connection) {
        val rows = notion.rows(token, source(c)).filter { !it.isWall && it.pageId != c.wallPageId }
        val waiting = pending()
        val local = dao.allEssays().filter { !it.sample }
        val byPage = local.filter { it.pageId != null }.associateBy { it.pageId }
        for (r in rows) {
            // Deleted in Leaf and not yet trashed in Notion: pulling it back would undo the delete.
            if (r.pageId in waiting) continue
            val mine = byPage[r.pageId]
            if (mine != null && mine.id in waiting) continue
            val changed = mine == null || mine.editedAt != r.editedAt.toEpochMilli()
            val preview = if (changed) {
                notion.children(token, r.pageId, limit = 8).mapNotNull(NotionJson::bodyBlock).filter { it.editable }
                    .joinToString(" ") { it.text }.trim().take(400)
            } else mine!!.preview
            dao.upsertEssays(listOf(EssayEntity(
                id = mine?.id ?: newId(),
                pageId = r.pageId,
                title = r.title,
                color = r.color.apiName,
                pinned = r.pinned,
                editedAt = r.editedAt.toEpochMilli(),
                coverUrl = r.coverUrl,
                preview = preview,
                bodyLoaded = mine?.bodyLoaded == true && !changed,
                tags = tagColumn(r.tags),
                createdAt = r.createdAt?.toEpochMilli() ?: mine?.createdAt,
            )))
        }
        val remoteIds = rows.map { it.pageId }.toSet()
        val gone = local.filter { it.pageId != null && it.pageId !in remoteIds && it.id !in waiting }.map { it.id }
        if (gone.isNotEmpty()) {
            dao.deleteBlocksOf(gone)
            dao.deleteEssays(gone)
        }
    }

    /** Notion's current link to a file or image block, signed afresh; null when offline or unconnected. */
    suspend fun fileUrl(blockId: String): String? {
        store.connection.value ?: return null
        val token = store.token() ?: return null
        return try { auth.withToken(token) { notion.block(it, blockId) }?.imageUrl } catch (_: java.io.IOException) { null }
    }

    /** Loads one essay's body unless it is loaded already or has edits still waiting to go out. */
    suspend fun loadBody(essayId: String): Unit = mutex.withLock {
        val c = store.connection.value ?: return@withLock
        val token = store.token() ?: return@withLock
        val essay = dao.essay(essayId) ?: return@withLock
        val pageId = essay.pageId ?: return@withLock
        if (essay.bodyLoaded) return@withLock
        val waiting = pending()
        if (essayId in waiting || dao.blocksOf(essayId).any { it.id in waiting }) return@withLock
        val before = dao.blocksOf(essayId)
        val blocks = try { auth.withToken(token) { notion.essayBody(it, pageId) } } catch (_: NotionException) { return@withLock }
        // Blocks already known keep their local ID, so an edit made from the old list still lands on them.
        val known = before.filter { it.blockId != null }.associate { it.blockId to it.id }
        val rows = blocks.mapIndexed { i, b ->
            EssayBlockEntity(known[b.blockId] ?: newId(), essayId, i, b.blockId, b.type, b.text, b.color.apiName, photo = b.imageUrl, fixed = b.fixed)
        }
        // The editor writes without this mutex, so typing during the fetch must win over the fetched body.
        dao.replaceBodyIfUnchanged(essayId, before, rows)
    }
}
