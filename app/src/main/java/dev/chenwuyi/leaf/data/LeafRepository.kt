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
        val e = Essay(newId(), "", listOf(EssayBlock.Paragraph(newId(), "")), NotionColor.Default, null, false, clock())
        essayState.update { it + e }
        return e
    }

    override suspend fun updateEssay(essay: Essay) {
        essayState.update { list -> list.map { if (it.id == essay.id) essay.copy(editedAt = clock()) else it } }
    }

    override suspend fun copyToEssay(scribbleIds: Set<String>, essayId: String?): String {
        val picked = scribbleState.value.filter { it.id in scribbleIds }
        val target = essayId?.let { id -> essayState.value.firstOrNull { it.id == id } }
        val result = WallRules.copyInto(target, picked, clock(), newId)
        essayState.update { list -> if (target == null) list + result else list.map { if (it.id == result.id) result else it } }
        return result.id
    }
}
