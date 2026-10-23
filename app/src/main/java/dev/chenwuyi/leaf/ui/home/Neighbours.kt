package dev.chenwuyi.leaf.ui.home

import androidx.compose.ui.geometry.Rect

/**
 * Chips close enough to [target] that the tap might have been meant for them. They get a lighter ring in
 * the full wall, so a mis-tap is easy to spot and correct.
 */
fun neighboursOf(targetId: String, bounds: Map<String, Rect>, pad: Float): Set<String> {
    val t = bounds[targetId] ?: return emptySet()
    val zone = Rect(t.left - pad, t.top - pad, t.right + pad, t.bottom + pad)
    return bounds.filter { (id, r) -> id != targetId && r.overlaps(zone) }.keys
}
