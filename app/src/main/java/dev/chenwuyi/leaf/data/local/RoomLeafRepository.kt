package dev.chenwuyi.leaf.data.local

import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.LeafRepository
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.SampleData
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.data.WallRules
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

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
        val stored = (photo as? Picture.Local)?.let { importPhoto(it.uri)?.let(Picture::Local) } ?: photo
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
        val e = Essay(newId(), "", listOf(EssayBlock.Paragraph(newId(), "")), NotionColor.Default, null, false, clock())
        dao.upsertEssays(listOf(e.toEntity()))
        dao.upsertBlocks(e.blocks.mapIndexed { i, b -> b.toEntity(e.id, i, null) })
        queue(OutboxOp.SaveEssay, e.id)
        e.blocks.forEach { queue(OutboxOp.SaveBlock, it.id) }
        return e
    }

    override suspend fun updateEssay(essay: Essay) {
        val stored = dao.essay(essay.id) ?: return
        val now = clock().toEpochMilli()
        if (stored.title != essay.title || stored.color != essay.color.apiName || stored.pinned != essay.pinned) {
            dao.upsertEssays(listOf(stored.copy(title = essay.title, color = essay.color.apiName, pinned = essay.pinned, editedAt = now)))
            queue(OutboxOp.SaveEssay, essay.id)
        }
        val old = dao.blocksOf(essay.id).associateBy { it.id }
        val rows = essay.blocks.mapIndexed { i, b -> b.toEntity(essay.id, i, old[b.id]?.blockId) }
        val changed = rows.filter { row -> old[row.id]?.let { it.text != row.text || it.color != row.color } ?: true }
        if (changed.isNotEmpty()) {
            dao.upsertBlocks(rows)
            dao.upsertEssays(listOf((dao.essay(essay.id) ?: stored).copy(editedAt = now)))
            changed.forEach { queue(OutboxOp.SaveBlock, it.id) }
        }
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

    private suspend fun queue(op: String, target: String) {
        if (!isConnected()) return
        if (op != OutboxOp.DeleteScribble && dao.pendingOps().any { it.op == op && it.targetId == target }) return
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
)
