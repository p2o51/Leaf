package dev.chenwuyi.leaf.ui.essay

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Drag to reorder inside the editor's LazyColumn, after Craft: the dragged block follows the finger
 * ([offset] from where the list lays it out) and takes a neighbour's place once its middle passes that
 * neighbour's middle; the list then lays it out in the new spot and [offset] is corrected by the same
 * amount, so it never jumps under the finger.
 */
@Stable
class BlockDrag(private val list: LazyListState) {
    var draggingId by mutableStateOf<String?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set

    /** Where the block was when the drag began, so a drop back in place writes nothing. */
    var startIndex = -1
        private set

    // After a swap, the list has not laid the block out in its new place yet; until it has, another
    // swap would be measured from the old place.
    private var staleLazyIndex = -1

    fun start(id: String, index: Int) {
        draggingId = id
        startIndex = index
        offset = 0f
        staleLazyIndex = -1
    }

    /**
     * Moves the block by [dy] px (finger or auto-scroll). When it passes a neighbour among [blockIds],
     * [onSwap] gets the index in [blockIds] it should take now.
     */
    fun drag(dy: Float, blockIds: List<String>, onSwap: (toIndex: Int) -> Unit) {
        val id = draggingId ?: return
        offset += dy
        val items = list.layoutInfo.visibleItemsInfo
        val current = items.firstOrNull { it.key == id } ?: return
        if (current.index == staleLazyIndex) return
        staleLazyIndex = -1
        val middle = current.offset + offset + current.size / 2f
        val target = items.firstOrNull { it.key != id && it.key in blockIds && middle > it.offset && middle < it.offset + it.size } ?: return
        val down = target.index > current.index
        val half = target.offset + target.size / 2f
        if (if (down) middle < half else middle > half) return
        val to = blockIds.indexOf(target.key)
        if (to < 0) return
        // Where the list will put the block: after the target going down, in its place going up.
        val shift = if (down) target.offset + target.size - (current.offset + current.size) else target.offset - current.offset
        offset -= shift
        staleLazyIndex = current.index
        onSwap(to)
    }

    /**
     * How far to scroll this frame while the block is held near the top ([topInset] is the bar over the
     * list) or bottom edge; 0 elsewhere.
     */
    fun edgeSpeed(edge: Float, topInset: Float, maxSpeed: Float): Float {
        val id = draggingId ?: return 0f
        val info = list.layoutInfo
        val current = info.visibleItemsInfo.firstOrNull { it.key == id } ?: return 0f
        val top = current.offset + offset
        val bottom = top + current.size
        val start = info.viewportStartOffset + topInset
        val end = info.viewportEndOffset.toFloat()
        return when {
            bottom > end - edge -> maxSpeed * ((bottom - (end - edge)) / edge).coerceIn(0f, 1f)
            top < start + edge -> -maxSpeed * (((start + edge) - top) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    fun end() {
        draggingId = null
        offset = 0f
        startIndex = -1
        staleLazyIndex = -1
    }
}
