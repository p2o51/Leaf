package dev.chenwuyi.leaf.ui.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/** Motion tokens. Every spatial change is a spring; tween is only for colour and alpha. */
object LeafMotion {
    /** Hero moves: the strip opening into the wall, a card opening into the editor. */
    fun <T> expressive(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)

    /** Small expressive details: press morphs, selection, menu items popping in. */
    fun <T> fast(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)

    /** Tool surfaces (menus, bars): critically damped, no bounce. */
    fun <T> tool(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 520f)
}
