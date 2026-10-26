package dev.chenwuyi.leaf.data.local

import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.BlockOrder
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.LeafRepository
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.SampleData
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.data.WallRules
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Room-backed repository. Every write is local first; when Notion is connected it also queues an
 * outbox op and asks [requestSync] to push. Without a connection Leaf runs on sample data.
 */
class RoomLeafRepository(
    private val dao: LeafDao,
    private val isConnected: () -> Boolean,
    private val requestSync: () -> Unit,
    private val importPhoto: (String) -> String? = { it },
    private val loadBody: suspend (essayId: String) -> Unit = {},
    private val clock: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : LeafRepository {

    override val scribbles: Flow<List<Scribble>> = dao.scribbles().map { list -> list.map { it.toScribble() } }

    override val essays: Flow<List<Essay>> = combine(dao.essays(), dao.blocks()) { essays, blocks ->
        val byEssay = blocks.groupBy { it.essayId }
        essays.map { it.toEssay(byEssay[it.id].orEmpty()) }
    }

    /** Fills an empty, unconnected install with the demo content so the first launch is not blank. */
    suspend fun seedSamplesIfEmpty() {
        if (isConnected() || dao.allScribbles().isNotEmpty() || dao.allEssays().isNotEmpty()) return
        dao.upsertScribbles(SampleData.scribbles.map { it.toEntity(sample = true) })
        SampleData.essays.forEach { e ->
            dao.upsertEssays(listOf(e.toEntity(sample = true)))
            dao.upsertBlocks(e.blocks.mapIndexed { i, b -> b.toEntity(e.id, i, null) })
        }
    }

    override suspend fun addScribble(text: String, color: NotionColor, photo: Picture?): Scribble {
        val stored = (photo as? Picture.Local)?.let { withContext(Dispatchers.IO) { importPhoto(it.uri) }?.let(Picture::Local) } ?: photo
        val s = Scribble(newId(), text.trim(), color, clock(), stored)
        dao.upsertScribbles(listOf(s.toEntity()))
        queue(OutboxOp.SaveScribble, s.id)
        return s
    }

    override suspend fun recolor(ids: Set<String>, color: NotionColor) {
        val changed = dao.scribbles(ids).filter { it.color != color.apiName }.map { it.copy(color = color.apiName) }
        dao.upsertScribbles(changed)
        changed.forEach { queue(OutboxOp.SaveScribble, it.id) }
    }

    override suspend fun deleteScribbles(ids: Set<String>): List<Scribble> {
        val rows = dao.scribbles(ids)
        dao.deleteScribbles(ids)
        rows.forEach { row -> row.blockId?.let { queue(OutboxOp.DeleteScribble, it) } }
        return rows.map { it.toScribble() }
    }

    /**
     * Undo of a delete. The Notion block may already be gone, so a restored scribble is appended
     * again as a new block; its local time is kept.
     */
    override suspend fun restoreScribbles(scribbles: List<Scribble>) {
        dao.upsertScribbles(scribbles.map { it.toEntity(blockId = null) })
        scribbles.forEach { queue(OutboxOp.SaveScribble, it.id) }
    }

    override suspend fun newEssay(): Essay {
        val e = Essay(newId(), "", listOf(EssayBlock.Paragraph(newId(), "")), NotionColor.Default, null, false, clock(), createdAt = clock())
        dao.upsertEssays(listOf(e.toEntity()))
        dao.upsertBlocks(e.blocks.mapIndexed { i, b -> b.toEntity(e.id, i, null) })
        queue(OutboxOp.SaveEssay, e.id)
        e.blocks.forEach { queue(OutboxOp.SaveBlock, it.id) }
        return e
    }

    // Editor writes are launched per keystroke; one at a time keeps a removal from being written back.
    private val essayWrites = Mutex()

    override suspend fun updateEssay(essay: Essay): Unit = essayWrites.withLock {
        val stored = dao.essay(essay.id) ?: return@withLock
        val now = clock().toEpochMilli()
        if (stored.title != essay.title || stored.color != essay.color.apiName || stored.pinned != essay.pinned) {
            dao.upsertEssays(listOf(stored.copy(title = essay.title, color = essay.color.apiName, pinned = essay.pinned, editedAt = now)))
            queue(OutboxOp.SaveEssay, essay.id)
        }
        val old = dao.blocksOf(essay.id).associateBy { it.id }
        val rows = essay.blocks.mapIndexed { i, b ->
            val before = old[b.id]
            val row = b.storedPhoto(before).toEntity(essay.id, i, before?.blockId)
            // Notion cannot change a block's type in place: a restyled block goes out as a new block
            // in the same spot, and the old one is deleted.
            if (before?.blockId != null && before.type != row.type) row.copy(blockId = null) else row
        }
        val changed = rows.filter { row -> old[row.id]?.let { it.text != row.text || it.color != row.color || it.type != row.type } ?: true }
        if (changed.isNotEmpty()) {
            dao.upsertBlocks(rows)
            dao.upsertEssays(listOf((dao.essay(essay.id) ?: stored).copy(editedAt = now)))
            changed.forEach { row ->
                old[row.id]?.takeIf { it.type != row.type }?.blockId?.let { queue(OutboxOp.DeleteBlock, it) }
                queue(OutboxOp.SaveBlock, row.id)
            }
        }
    }

    /**
     * Removal is its own call rather than read off a missing block in [updateEssay]: an editor still
     * holding an older list (a body just loaded from Notion) must never delete what it has not seen.
     */
    override suspend fun removeBlocks(essayId: String, blockIds: Set<String>): Unit = essayWrites.withLock {
        val gone = dao.blocksOf(essayId).filter { it.id in blockIds }
        if (gone.isEmpty()) return@withLock
        dao.deleteBlocks(gone.map { it.id })
        dao.essay(essayId)?.let { dao.upsertEssays(listOf(it.copy(editedAt = clock().toEpochMilli()))) }
        gone.forEach { row -> row.blockId?.let { queue(OutboxOp.DeleteBlock, it) } }
    }

    /**
     * Notion has no call that moves a block, so the moved block is written again after its new neighbour
     * and the old one deleted; the new one goes out first, so a failure in between never loses the words.
     */
    override suspend fun moveBlock(essayId: String, blockId: String, toIndex: Int): Unit = essayWrites.withLock {
        val rows = dao.blocksOf(essayId)
        val moved = rows.firstOrNull { it.id == blockId } ?: return@withLock
        val reordered = BlockOrder.moveAt(rows, rows.indexOf(moved), toIndex)
        if (reordered.map { it.id } == rows.map { it.id }) return@withLock
        dao.upsertBlocks(reordered.mapIndexed { i, row -> row.copy(position = i) })
        dao.essay(essayId)?.let { dao.upsertEssays(listOf(it.copy(editedAt = clock().toEpochMilli()))) }
        // A block not in Notion yet is created at wherever it sits when its SaveBlock runs.
        if (moved.blockId != null) queue(OutboxOp.MoveBlock, blockId)
    }

    override suspend fun setCover(essayId: String, cover: Picture?) {
        val stored = dao.essay(essayId) ?: return
        val url = when (cover) {
            null -> null
            is Picture.Local -> if (cover.uri.startsWith("content:")) withContext(Dispatchers.IO) { importPhoto(cover.uri) } ?: return else cover.uri
            is Picture.Remote -> cover.url
            is Picture.Sample -> return dao.upsertEssays(listOf(stored.copy(sampleCover = cover.art.name)))
        }
        dao.upsertEssays(listOf(stored.copy(coverUrl = url, sampleCover = if (cover == null) null else stored.sampleCover, editedAt = clock().toEpochMilli())))
        if (stored.sample) return
        queue(OutboxOp.SaveCover, essayId)
    }

    override suspend fun deleteEssay(essayId: String): Unit = essayWrites.withLock {
        val stored = dao.essay(essayId) ?: return@withLock
        dao.deleteBlocksOf(listOf(essayId))
        dao.deleteEssays(listOf(essayId))
        stored.pageId?.let { queue(OutboxOp.DeleteEssay, it) }
    }

    override suspend fun copyToEssay(scribbleIds: Set<String>, essayId: String?): String {
        val picked = dao.scribbles(scribbleIds).map { it.toScribble() }
        val target = essayId?.let { dao.essay(it) }
        if (target == null) {
            val created = newEssay()
            val withCopies = WallRules.copyInto(created, picked, clock(), newId)
            updateEssay(withCopies)
            return created.id
        }
        val existing = target.toEssay(dao.blocksOf(target.id))
        updateEssay(WallRules.copyInto(existing, picked, clock(), newId))
        return target.id
    }

    override suspend fun loadEssayBody(essayId: String) = loadBody(essayId)

    /**
     * A photo picked into an essay arrives as a content URI; keep a copy, like a scribble's. The editor
     * may keep sending the content URI until it adopts the stored row, so a block already stored reuses
     * its copy instead of importing the photo again on every keystroke.
     */
    private suspend fun EssayBlock.storedPhoto(stored: EssayBlockEntity?): EssayBlock {
        val block = this as? EssayBlock.Photo ?: return this
        val local = block.picture as? Picture.Local ?: return this
        if (!local.uri.startsWith("content:")) return this
        stored?.photo?.takeIf { it.startsWith("file:") }?.let { return block.copy(picture = Picture.Local(it)) }
        val copy = withContext(Dispatchers.IO) { importPhoto(local.uri) } ?: return this
        return block.copy(picture = Picture.Local(copy))
    }

    private suspend fun queue(op: String, target: String) {
        if (!isConnected()) return
        if (op != OutboxOp.DeleteScribble && op != OutboxOp.DeleteBlock && dao.pendingOps().any { it.op == op && it.targetId == target }) return
        dao.enqueue(OutboxEntity(op = op, targetId = target))
        requestSync()
    }
}

fun Essay.toEntity(sample: Boolean = false): EssayEntity = EssayEntity(
    id = id,
    pageId = null,
    title = title,
    color = color.apiName,
    pinned = pinned,
    editedAt = editedAt.toEpochMilli(),
    sampleCover = (cover as? Picture.Sample)?.art?.name,
    preview = previewText.take(400),
    bodyLoaded = true,
    sample = sample,
    tags = tagColumn(tags),
    coverUrl = (cover as? Picture.Local)?.uri ?: (cover as? Picture.Remote)?.url,
    createdAt = createdAt?.toEpochMilli(),
)
