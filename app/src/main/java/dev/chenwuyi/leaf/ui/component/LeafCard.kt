package dev.chenwuyi.leaf.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.ui.theme.LeafMotion
import dev.chenwuyi.leaf.ui.theme.LeafShape

// Paperleaf's LeafCard states: at rest the leaf corner (radius-l / radius-leaf-small), pressed all four
// corners close in to 18dp, selected all four are radius-l. Only for content objects (essay cards,
// scribbles); controls keep Material's shapes.
private val Big = 24.dp
private val Small = 8.dp
private val Pressed = 18.dp

/**
 * The leaf corner of a card or scribble in its current state. The corners move on [LeafMotion.livelyFast]:
 * these objects live in the lively zone (home, the wall), where a press may give a little.
 */
@Composable
fun animatedLeafShape(pressed: Boolean, selected: Boolean = false): Shape {
    val big by animateDpAsState(if (pressed && !selected) Pressed else Big, LeafMotion.livelyFast(), label = "leaf big")
    val small by animateDpAsState(
        when {
            selected -> Big
            pressed -> Pressed
            else -> Small
        },
        LeafMotion.livelyFast(),
        label = "leaf small",
    )
    return LeafShape(big, small)
}

/** How far a pressed leaf object sinks, on the same spring as its corners. Read in the layer, so it never recomposes. */
@Composable
fun rememberLeafPressScale(pressed: Boolean): State<Float> =
    animateFloatAsState(if (pressed) 0.98f else 1f, LeafMotion.livelyFast(), label = "leaf press")

/** Applies [scale] from [rememberLeafPressScale] in the draw layer. */
fun Modifier.leafPress(scale: State<Float>): Modifier = graphicsLayer {
    scaleX = scale.value
    scaleY = scale.value
}
