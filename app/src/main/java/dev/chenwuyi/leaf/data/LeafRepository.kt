package dev.chenwuyi.leaf.data

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The app's single source of truth. Writes land locally first and return at once; the Room
 * implementation then queues them for Notion.
 */
interface LeafRepository {
    val scribbles: Flow<List<Scribble>>
    val essays: Flow<List<Essay>>

    suspend fun addScribble(text: String, color: NotionColor, photo: Picture? = null): Scribble
    suspend fun recolor(ids: Set<String>, color: NotionColor)
    suspend fun deleteScribbles(ids: Set<String>): List<Scribble>
    suspend fun restoreScribbles(scribbles: List<Scribble>)
    suspend fun newEssay(): Essay
    suspend fun updateEssay(essay: Essay)
    /** Copies scribbles into [essayId], or a new essay when null. Returns the essay's id. */
    suspend fun copyToEssay(scribbleIds: Set<String>, essayId: String?): String
    /** Fetches an essay's body from Notion when it is not loaded yet (no-op when offline or unconnected). */
    suspend fun loadEssayBody(essayId: String) {}

    suspend fun removeBlocks(essayId: String, blockIds: Set<String>)

    /** Moves one block to [toIndex] of the essay's blocks (drag to reorder). */
    suspend fun moveBlock(essayId: String, blockId: String, toIndex: Int)

    /** Sets the essay's cover to a picked photo, or removes it when null. */
    suspend fun setCover(essayId: String, cover: Picture?)

    /** Removes the essay; in Notion its page goes to the trash. */
    suspend fun deleteEssay(essayId: String)
}

class InMemoryLeafRepository(
    scribbles: List<Scribble> = emptyList(),
    essays: List<Essay> = emptyList(),
    private val clock: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : LeafRepository {
    private val scribbleState = MutableStateFlow(scribbles)
    private val essayState = MutableStateFlow(essays)
    override val scribbles: StateFlow<List<Scribble>> = scribbleState.asStateFlow()
    override val essays: StateFlow<List<Essay>> = essayState.asStateFlow()

    override suspend fun addScribble(text: String, color: NotionColor, photo: Picture?): Scribble {
        val s = Scribble(newId(), text.trim(), color, clock(), photo)
        scribbleState.update { it + s }
        return s
    }

    override suspend fun recolor(ids: Set<String>, color: NotionColor) {
        scribbleState.update { list -> list.map { if (it.id in ids) it.copy(color = color) else it } }
    }

    override suspend fun deleteScribbles(ids: Set<String>): List<Scribble> {
        val removed = scribbleState.value.filter { it.id in ids }
        scribbleState.update { list -> list.filterNot { it.id in ids } }
        return removed
    }

    override suspend fun restoreScribbles(scribbles: List<Scribble>) {
        scribbleState.update { list -> (list + scribbles).distinctBy { it.id } }
    }

    override suspend fun newEssay(): Essay {
        val e = Essay(newId(), "", listOf(EssayBlock.Paragraph(newId(), "")), NotionColor.Default, null, false, clock(), createdAt = clock())
        essayState.update { it + e }
        return e
    }

    override suspend fun moveBlock(essayId: String, blockId: String, toIndex: Int) {
        essayState.update { list -> list.map { e -> if (e.id == essayId) e.copy(blocks = BlockOrder.move(e.blocks, blockId, toIndex)) else e } }
    }

    override suspend fun setCover(essayId: String, cover: Picture?) {
        essayState.update { list -> list.map { if (it.id == essayId) it.copy(cover = cover) else it } }
    }

    override suspend fun deleteEssay(essayId: String) {
        essayState.update { list -> list.filterNot { it.id == essayId } }
    }

    override suspend fun updateEssay(essay: Essay) {
        essayState.update { list -> list.map { if (it.id == essay.id) essay.copy(editedAt = clock()) else it } }
    }

    override suspend fun removeBlocks(essayId: String, blockIds: Set<String>) {
        essayState.update { list -> list.map { e -> if (e.id == essayId) e.copy(blocks = e.blocks.filter { it.id !in blockIds }) else e } }
    }

    override suspend fun copyToEssay(scribbleIds: Set<String>, essayId: String?): String {
        val picked = scribbleState.value.filter { it.id in scribbleIds }
        val target = essayId?.let { id -> essayState.value.firstOrNull { it.id == id } }
        val result = WallRules.copyInto(target, picked, clock(), newId)
        essayState.update { list -> if (target == null) list + result else list.map { if (it.id == result.id) result else it } }
        return result.id
    }
}

/** Reordering, kept pure so the editor's live drag and the stored order agree. */
object BlockOrder {
    /** [items] with the block [id] taken out and put back at [toIndex] (clamped). */
    fun move(items: List<EssayBlock>, id: String, toIndex: Int): List<EssayBlock> = moveAt(items, items.indexOfFirst { it.id == id }, toIndex)

    /** [items] with the one at [from] taken out and put back at [toIndex] (clamped); unchanged when [from] is out of range. */
    fun <T> moveAt(items: List<T>, from: Int, toIndex: Int): List<T> {
        if (from !in items.indices) return items
        val out = items.toMutableList()
        val moved = out.removeAt(from)
        out.add(toIndex.coerceIn(0, out.size), moved)
        return out
    }

    /**
     * Whether a block can be dragged: Leaf can only move what it can write again in Notion, which has no
     * move call, so a moved block is deleted and created anew. Tables, embeds and other blocks Leaf does
     * not know stay where they are.
     */
    fun movable(block: EssayBlock): Boolean = when (block) {
        is EssayBlock.Paragraph, is EssayBlock.Photo -> true
        is EssayBlock.Other -> block.type == EssayBlock.Divider
        is EssayBlock.SyncedScribble -> false
    }
}
