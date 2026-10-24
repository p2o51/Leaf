package dev.chenwuyi.leaf.ui.component

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import dev.chenwuyi.leaf.ui.theme.LeafMotion
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The predictive-back preview Leaf's pages share, ported from Sheaf's BackPreview (the AOSP
 * cross-activity pose): while the finger is down the page shrinks toward 0.9, moves toward the side the
 * swipe came from, follows the finger vertically and rounds its corners. A cancelled gesture springs it
 * home; a committed one leaves the pose in place so the page's own exit carries on from it instead of
 * snapping back to full size first (that snap is the "闪一下" 51 does not want).
 */
@Stable
class BackPreview {
    var progress by mutableFloatStateOf(0f)
        private set
    var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
        private set
    var touchYDelta by mutableFloatStateOf(0f)
        private set

    private var startY = Float.NaN
    private var generation = 0

    fun follow(event: BackEventCompat) {
        generation++
        if (startY.isNaN()) startY = event.touchY
        swipeEdge = event.swipeEdge
        touchYDelta = event.touchY - startY
        progress = LeafMotion.BackGestureEasing.transform(event.progress.coerceIn(0f, 1f))
    }

    suspend fun cancel() {
        val settling = ++generation
        startY = Float.NaN
        animate(progress, 0f, 0f, LeafMotion.tool()) { v, _ -> if (generation == settling) progress = v }
        if (generation == settling) touchYDelta = 0f
    }

    /** Back to no preview at once, for a page that is shown again after it left in the pose. */
    fun reset() {
        generation++
        startY = Float.NaN
        progress = 0f
        touchYDelta = 0f
    }
}

/**
 * Back for a page shown in the window: the system gesture drives [preview] and a commit calls [onBack],
 * whose exit animation starts from the pose. The pose is cleared the next time the page is entered
 * ([BackPreview.reset] from the page's enter), never while it is leaving.
 */
@Composable
fun PagePredictiveBack(enabled: Boolean, preview: BackPreview, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { preview.follow(it) }
            onBack()
        } catch (cancelled: CancellationException) {
            scope.launch { preview.cancel() }
            throw cancelled
        }
    }
}

@Composable
fun rememberBackPreview(): BackPreview = remember { BackPreview() }

/** Writes [preview]'s pose into this layer. Read in the layer block, so a gesture frame never recomposes the page. */
fun Modifier.backPose(preview: BackPreview): Modifier = graphicsLayer {
    val p = preview.progress
    if (p <= 0f) return@graphicsLayer
    val scale = 1f - (1f - LeafMotion.BackScaleTarget) * p
    scaleX = scale
    scaleY = scale
    val margin = LeafMotion.BackDisplayMargin.toPx()
    translationX = if (preview.swipeEdge == BackEventCompat.EDGE_LEFT) max(0f, size.width * (1f - LeafMotion.BackScaleTarget) / 2f - margin) * p else 0f
    translationY = followY(size.height, scale, preview.touchYDelta, margin)
    shape = RoundedCornerShape(LeafMotion.BackCorner.toPx() * p)
    clip = true
}

// AOSP getYOffset: a decelerated share of the finger's vertical travel, within the room the shrunk page has.
private fun followY(height: Float, scale: Float, rawDy: Float, margin: Float): Float {
    if (rawDy == 0f) return 0f
    val half = height / 2f
    val ratio = min(half, abs(rawDy)) / half
    val decelerated = 1f - (1f - ratio) * (1f - ratio)
    val room = max(0f, height * (1f - scale) / 2f - margin)
    return room * decelerated * (if (rawDy < 0f) -1f else 1f)
}
