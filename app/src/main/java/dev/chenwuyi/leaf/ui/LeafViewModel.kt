package dev.chenwuyi.leaf.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.HomeFilter
import dev.chenwuyi.leaf.data.LeafRepository
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.Scribble
import dev.chenwuyi.leaf.data.TagRules
import dev.chenwuyi.leaf.data.WallSort
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the full wall shows: which scribble was tapped (ringed, centred), its likely mis-tap neighbours, selection. */
data class WallState(
    val focusId: String? = null,
    val nearIds: Set<String> = emptySet(),
    val sort: WallSort = WallSort.Newest,
    val selecting: Boolean = false,
    val selected: Set<String> = emptySet(),
)

@OptIn(DelicateCoroutinesApi::class)
class LeafViewModel(private val repository: LeafRepository, seed: suspend () -> Unit = {}) : ViewModel() {
    val scribbles: StateFlow<List<Scribble>> = repository.scribbles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Essays deleted from the list but still inside their undo window; they go for good when it ends. */
    private val deletingState = MutableStateFlow<Set<String>>(emptySet())
    private val deleteJobs = mutableMapOf<String, Job>()

    val essays: StateFlow<List<Essay>> = combine(repository.essays, deletingState) { all, gone -> if (gone.isEmpty()) all else all.filterNot { it.id in gone } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val filterState = MutableStateFlow<HomeFilter>(HomeFilter.All)
    /** The drawer's pick. Home, the strip and the wall all show only what matches it. */
    val filter: StateFlow<HomeFilter> = filterState.asStateFlow()

    val visibleScribbles: StateFlow<List<Scribble>> = combine(scribbles, filterState) { all, f -> all.filter { TagRules.matches(it, f) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val visibleEssays: StateFlow<List<Essay>> = combine(essays, filterState) { all, f -> all.filter { TagRules.matches(it, f) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** What the drawer lists: the colors and tags actually in use. */
    val tags: StateFlow<List<String>> = combine(scribbles, essays, TagRules::all).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val colorsInUse: StateFlow<List<NotionColor>> = combine(scribbles, essays, TagRules::colorsInUse).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val queryState = MutableStateFlow("")
    val query: StateFlow<String> = queryState.asStateFlow()

    private val openEssayState = MutableStateFlow<String?>(null)
    val openEssayId: StateFlow<String?> = openEssayState.asStateFlow()

    /** Null while the wall is closed. */
    private val wallState = MutableStateFlow<WallState?>(null)
    val wall: StateFlow<WallState?> = wallState.asStateFlow()

    init {
        viewModelScope.launch { seed() }
        // A scribble recoloured or retagged out of the filter leaves the wall, so it leaves the selection too:
        // nothing out of sight is deleted, copied or recoloured by the selection bar.
        viewModelScope.launch {
            visibleScribbles.collect { shown ->
                val ids = shown.map(Scribble::id).toSet()
                wallState.update { w -> if (w == null || w.selected.all { it in ids }) w else w.copy(selected = w.selected intersect ids) }
            }
        }
    }

    fun setQuery(q: String) = queryState.update { q }

    fun setFilter(filter: HomeFilter) = filterState.update { filter }

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

    // Only what the wall shows: a filtered wall never selects scribbles that are out of sight.
    fun selectAll() = wallState.update { it?.copy(selected = visibleScribbles.value.map(Scribble::id).toSet()) }

    /** Saves a scribble unless it is empty; [onAdded] gets it for the undo snackbar. */
    fun addScribble(text: String, color: NotionColor, photo: Picture? = null, onAdded: (Scribble) -> Unit = {}) {
        if (text.isBlank() && photo == null) return
        viewModelScope.launch { onAdded(repository.addScribble(text, color, photo)) }
    }

    fun recolor(ids: Set<String>, color: NotionColor) {
        viewModelScope.launch { repository.recolor(ids, color) }
    }

    fun delete(ids: Set<String>, onDeleted: (List<Scribble>) -> Unit = {}) {
        wallState.update { it?.copy(selected = it.selected - ids) }
        viewModelScope.launch { onDeleted(repository.deleteScribbles(ids)) }
    }

    fun restore(scribbles: List<Scribble>) {
        viewModelScope.launch { repository.restoreScribbles(scribbles) }
    }

    /** Copies scribbles into an essay (new when [essayId] is null), leaves the wall and opens that essay. */
    fun copyToEssay(ids: Set<String>, essayId: String?) {
        if (ids.isEmpty()) return
        wallState.update { null }
        viewModelScope.launch { openEssay(repository.copyToEssay(ids, essayId)) }
    }

    fun newEssay() {
        wallState.update { null }
        viewModelScope.launch { openEssayState.update { repository.newEssay().id } }
    }

    fun openEssay(id: String) {
        openEssayState.update { id }
        viewModelScope.launch { repository.loadEssayBody(id) }
    }

    fun closeEssay() = openEssayState.update { null }

    /** Opens an attached file with whatever app handles it, using a freshly signed Notion link. */
    fun openFile(block: EssayBlock.Attachment, open: (String) -> Unit) {
        viewModelScope.launch { (repository.fileUrl(block.id) ?: block.url)?.let(open) }
    }

    fun updateEssay(essay: Essay) {
        viewModelScope.launch { repository.updateEssay(essay) }
    }

    fun removeBlock(essayId: String, blockId: String) {
        viewModelScope.launch { repository.removeBlocks(essayId, setOf(blockId)) }
    }

    fun moveBlock(essayId: String, blockId: String, toIndex: Int) {
        viewModelScope.launch { repository.moveBlock(essayId, blockId, toIndex) }
    }

    fun setCover(essayId: String, cover: Picture?) {
        viewModelScope.launch { repository.setCover(essayId, cover) }
    }

    fun setPinned(essay: Essay, pinned: Boolean) = updateEssay(essay.copy(pinned = pinned))

    fun recolorEssay(essay: Essay, color: NotionColor) = updateEssay(essay.copy(color = color))

    /**
     * Takes the essay off the list at once and deletes it for good after [undoMillis], unless
     * [undoDelete] comes first: the snackbar's 撤销 then costs nothing in Notion.
     */
    fun deleteEssay(id: String, undoMillis: Long = UndoWindowMillis) {
        if (openEssayState.value == id) openEssayState.update { null }
        deletingState.update { it + id }
        deleteJobs.remove(id)?.cancel()
        deleteJobs[id] = viewModelScope.launch {
            delay(undoMillis)
            repository.deleteEssay(id)
            deleteJobs.remove(id)
            deletingState.update { it - id }
        }
    }

    fun undoDelete(id: String) {
        deleteJobs.remove(id)?.cancel()
        deletingState.update { it - id }
    }

    override fun onCleared() {
        // Leaving with a delete still in its undo window keeps the delete: the person asked for it.
        val pending = deletingState.value
        if (pending.isNotEmpty()) {
            deleteJobs.values.forEach { it.cancel() }
            GlobalScope.launch(NonCancellable) { pending.forEach { repository.deleteEssay(it) } }
        }
    }

    companion object {
        /** A little longer than a short snackbar, so 撤销 never lands after the delete went out. */
        const val UndoWindowMillis = 5_000L
    }
}
