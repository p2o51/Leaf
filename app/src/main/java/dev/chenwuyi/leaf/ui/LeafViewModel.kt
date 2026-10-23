package dev.chenwuyi.leaf.ui

import androidx.lifecycle.ViewModel
import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.LeafRepository
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.data.WallSort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the full wall shows: which scribble was tapped (ringed, centred), its likely mis-tap neighbours, selection. */
data class WallState(
    val focusId: String? = null,
    val nearIds: Set<String> = emptySet(),
    val sort: WallSort = WallSort.Newest,
    val selecting: Boolean = false,
    val selected: Set<String> = emptySet(),
)

class LeafViewModel(private val repository: LeafRepository) : ViewModel() {
    val scribbles: StateFlow<List<Scribble>> = repository.scribbles
    val essays: StateFlow<List<Essay>> = repository.essays

    private val queryState = MutableStateFlow("")
    val query: StateFlow<String> = queryState.asStateFlow()

    private val openEssayState = MutableStateFlow<String?>(null)
    val openEssayId: StateFlow<String?> = openEssayState.asStateFlow()

    /** Null while the wall is closed. */
    private val wallState = MutableStateFlow<WallState?>(null)
    val wall: StateFlow<WallState?> = wallState.asStateFlow()

    fun setQuery(q: String) = queryState.update { q }

    fun openWall(focusId: String?, nearIds: Set<String>) {
        wallState.update { WallState(focusId = focusId, nearIds = nearIds, sort = it?.sort ?: WallSort.Newest) }
    }

    fun closeWall() = wallState.update { null }

    fun setSort(sort: WallSort) = wallState.update { it?.copy(sort = sort) }

    fun startSelecting(firstId: String? = null) =
        wallState.update { it?.copy(selecting = true, selected = setOfNotNull(firstId), focusId = null, nearIds = emptySet()) }

    fun stopSelecting() = wallState.update { it?.copy(selecting = false, selected = emptySet()) }

    fun toggleSelected(id: String) = wallState.update { w ->
        w?.copy(selected = if (id in w.selected) w.selected - id else w.selected + id)
    }

    fun selectAll() = wallState.update { it?.copy(selected = scribbles.value.map(Scribble::id).toSet()) }

    fun addScribble(text: String, color: NotionColor, photo: Picture? = null): Scribble? {
        if (text.isBlank() && photo == null) return null
        return repository.addScribble(text, color, photo)
    }

    fun recolor(ids: Set<String>, color: NotionColor) = repository.recolor(ids, color)

    fun delete(ids: Set<String>): List<Scribble> {
        val removed = repository.deleteScribbles(ids)
        wallState.update { it?.copy(selected = it.selected - ids) }
        return removed
    }

    fun restore(scribbles: List<Scribble>) = repository.restoreScribbles(scribbles)

    /** Copies scribbles into an essay (new when [essayId] is null), leaves the wall and opens that essay. */
    fun copyToEssay(ids: Set<String>, essayId: String?) {
        if (ids.isEmpty()) return
        val id = repository.copyToEssay(ids, essayId)
        wallState.update { null }
        openEssayState.update { id }
    }

    fun newEssay() {
        val e = repository.newEssay()
        wallState.update { null }
        openEssayState.update { e.id }
    }

    fun openEssay(id: String) = openEssayState.update { id }

    fun closeEssay() = openEssayState.update { null }

    fun updateEssay(essay: Essay) = repository.updateEssay(essay)
}
