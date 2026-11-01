package dev.chenwuyi.leaf.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.dp

/**
 * Motion tokens. Every spatial change is a spring; tween is only for colour and alpha.
 *
 * Paperleaf's two tempos (shared with Sheaf): the still zone, where you read and write, settles
 * without overshoot ([still], [stillFast]); the lively zone, where you find and arrange things, uses
 * MD3E's bounce ([lively], [livelyFast], [livelySlow]). [effects] is for colour and alpha in both,
 * [control] for switches and segmented choices.
 *
 * The older tokens map onto them: [expressive] is [lively], [fast] is [livelyFast], and [tool] is a
 * slightly stiffer [still]. New code takes the Paperleaf names.
 */
object LeafMotion {
    /** Hero moves: the strip opening into the wall, a card opening into the editor. Same spring as [lively]. */
    fun <T> expressive(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)

    /** Small expressive details: press morphs, selection, menu items popping in. Same spring as [livelyFast]. */
    fun <T> fast(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)

    /** Tool surfaces (menus, bars): critically damped, no bounce. The stiffer cousin of [still]. */
    fun <T> tool(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 520f)

    /** Still zone: position and size where you read or write (toolbars, blocks re-flowing). No overshoot. */
    fun <T> still(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 380f)

    /** Still zone, small controls: options inside a panel, the sentence toolbar. */
    fun <T> stillFast(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 900f)

    /** Switches, segmented choices, play and pause: quick, with only a trace of give. */
    fun <T> control(): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = 1400f)

    /** Every colour and alpha change, in both zones; never overshoots. */
    fun <T> effects(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)

    /** Lively zone scenes: a card opening, panels, a folder opening, container morphs. */
    fun <T> lively(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)

    /** Lively zone details: press morphs, icon nudges, a chip being selected. */
    fun <T> livelyFast(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)

    /** The few hero moments: the day's first breath, finishing a piece. */
    fun <T> livelySlow(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 200f)

    // Predictive back, the same AOSP pose Sheaf's pages use (Sheaf DaylistMotion.Back*).
    const val BackScaleTarget = 0.9f
    val BackCorner = 28.dp
    val BackGestureEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
    val BackDisplayMargin = 8.dp

    /** How long a sheet shows its tick and 完成 words before it slides away (Paw SheetFinisher). */
    const val SheetFinishMillis = 820L
}
