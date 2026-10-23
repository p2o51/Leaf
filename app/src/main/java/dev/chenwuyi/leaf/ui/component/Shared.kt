package dev.chenwuyi.leaf.ui.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import dev.chenwuyi.leaf.ui.theme.LeafMotion

/**
 * The strip and the full wall show the same scribble chips. Wrapping both in one shared transition makes
 * opening the wall one continuous motion: each chip flies from its place in the strip to its place in the
 * list, and back on close.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalWallVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedScribble(id: String): Modifier = sharedKey("scribble-$id", zIndex = 1f)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedKey(key: String, zIndex: Float = 0f): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalWallVisibilityScope.current ?: return this
    return with(shared) {
        this@sharedKey.sharedBounds(
            rememberSharedContentState(key),
            visibility,
            boundsTransform = { _, _ -> LeafMotion.expressive() },
            zIndexInOverlay = zIndex,
        )
    }
}
