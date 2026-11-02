package dev.chenwuyi.leaf.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The leaf corner from the brand mark's folded page, for content objects (essay cards, scribbles,
 * covers): top-start and bottom-end big, top-end and bottom-start small. Controls keep Material's
 * own shapes; this is only for things you read.
 */
fun LeafShape(big: Dp = 24.dp, small: Dp = 8.dp): RoundedCornerShape =
    RoundedCornerShape(topStart = big, topEnd = small, bottomEnd = big, bottomStart = small)

/** Paperleaf's corner scale (radius-inner, -s, -m, -l, -xl), shared with Sheaf. */
internal val LeafShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
