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

/** A block out of sight during its undo window: [after] is the block it sat behind (null when first), [index] its place then. */
private data class HiddenBlock(val block: EssayBlock, val after: String?, val index: Int)

@OptIn(DelicateCoroutinesApi::class)
class LeafViewModel(private val repository: LeafRepository, seed: suspend () -> Unit = {}) : ViewModel() {
    val scribbles: StateFlow<List<Scribble>> = repository.scribbles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Essays deleted from the list but still inside their undo window; they go for good when it ends. */
    private val deletingState = MutableStateFlow<Set<String>>(emptySet())
    private val deleteJobs = mutableMapOf<String, Job>()

    /** Photos taken out of an essay but still inside their undo window, by essay id, in the order they went. */
    private val hiddenState = MutableStateFlow<Map<String, List<HiddenBlock>>>(emptyMap())
    private val hideJobs = mutableMapOf<String, Job>()

    /** The essays as stored, photos in their undo window included: block moves are counted against these. */
    private val storedEssays: StateFlow<List<Essay>> = repository.essays.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val essays: StateFlow<List<Essay>> = combine(storedEssays, deletingState, hiddenState) { all, gone, hidden ->
        if (gone.isEmpty() && hidden.isEmpty()) {
            all
        } else {
            all.filterNot { it.id in gone }.map { e ->
                val ids = hidden[e.id].orEmpty().map { it.block.id }.toSet()
                if (ids.isEmpty()) e else e.copy(blocks = e.blocks.filterNot { it.id in ids })
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    /** The photo's bytes for saving to the gallery; null when it cannot be had (offline, sample art). */
    suspend fun photoBytes(block: EssayBlock.Photo): ByteArray? = repository.photoBytes(block.id)

    /** Opens an attached file with whatever app handles it, using a freshly signed Notion link. */
    fun openFile(block: EssayBlock.Attachment, open: (String) -> Unit) {
        viewModelScope.launch { (repository.fileUrl(block.id) ?: block.url)?.let(open) }
    }

    fun updateEssay(essay: Essay) {
        // The editor's draft no longer has a photo in its undo window; it goes back in before the write, so
        // an edit made meanwhile neither deletes it nor shifts the positions of the blocks around it.
        val full = withHidden(essay)
        viewModelScope.launch { repository.updateEssay(full) }
    }

    fun removeBlock(essayId: String, blockId: String) {
        viewModelScope.launch { repository.removeBlocks(essayId, setOf(blockId)) }
    }

    fun moveBlock(essayId: String, blockId: String, toIndex: Int) {
        // [toIndex] counts the blocks the editor shows; the stored essay still holds any photo in its undo
        // window, so the index is translated to the stored list: the block goes in front of the shown block
        // it lands before, and a hidden photo stays where it was.
        val hiddenIds = hiddenState.value[essayId].orEmpty().map { it.block.id }.toSet()
        val stored = storedEssays.value.firstOrNull { it.id == essayId }?.blocks
        val target = if (hiddenIds.isEmpty() || stored == null) {
            toIndex
        } else {
            val rest = stored.filterNot { it.id == blockId }
            rest.filterNot { it.id in hiddenIds }.getOrNull(toIndex)?.let { before -> rest.indexOfFirst { it.id == before.id } } ?: rest.size
        }
        viewModelScope.launch { repository.moveBlock(essayId, blockId, target) }
    }

    /**
     * Takes a photo out of the essay at once and out of Room and Notion only after [undoMillis], unless
     * [undoRemovePhoto] comes first; the same window as an essay's delete, so 撤销 costs nothing in Notion.
     */
    fun removePhoto(essayId: String, blockId: String, undoMillis: Long = UndoWindowMillis) {
        val blocks = essays.value.firstOrNull { it.id == essayId }?.blocks ?: return
        val at = blocks.indexOfFirst { it.id == blockId }
        if (at < 0) return
        val hidden = HiddenBlock(blocks[at], after = blocks.getOrNull(at - 1)?.id, index = at)
        hiddenState.update { it + (essayId to (it[essayId].orEmpty() + hidden)) }
        hideJobs.remove(blockId)?.cancel()
        hideJobs[blockId] = viewModelScope.launch {
            delay(undoMillis)
            repository.removeBlocks(essayId, setOf(blockId))
            hideJobs.remove(blockId)
            // Only once Room has dropped it, so the photo never flashes back in between.
            unhide(essayId, blockId)
        }
    }

    fun undoRemovePhoto(essayId: String, blockId: String) {
        hideJobs.remove(blockId)?.cancel()
        unhide(essayId, blockId)
    }

    private fun unhide(essayId: String, blockId: String) = hiddenState.update { all ->
        val left = all[essayId].orEmpty().filterNot { it.block.id == blockId }
        if (left.isEmpty()) all - essayId else all + (essayId to left)
    }

    /**
     * [essay] with its hidden photos put back where they sat. The last one hidden goes back first: a photo
     * hidden earlier is then already in place for a later one that sat right behind it.
     */
    private fun withHidden(essay: Essay): Essay {
        val hidden = hiddenState.value[essay.id] ?: return essay
        var blocks = essay.blocks
        hidden.asReversed().forEach { h ->
            if (blocks.any { it.id == h.block.id }) return@forEach
            val at = if (h.after == null) 0 else blocks.indexOfFirst { it.id == h.after }.let { if (it >= 0) it + 1 else h.index.coerceAtMost(blocks.size) }
            blocks = blocks.take(at) + h.block + blocks.drop(at)
        }
        return essay.copy(blocks = blocks)
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
        // The same for photos still in their undo window.
        val photos = hiddenState.value
        if (photos.isNotEmpty()) {
            hideJobs.values.forEach { it.cancel() }
            GlobalScope.launch(NonCancellable) { photos.forEach { (essayId, hidden) -> repository.removeBlocks(essayId, hidden.map { it.block.id }.toSet()) } }
        }
    }

    companion object {
        /** A little longer than a short snackbar, so 撤销 never lands after the delete went out. */
        const val UndoWindowMillis = 5_000L
    }
}
