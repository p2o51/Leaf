package dev.chenwuyi.leaf.ui.capture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Press-and-hold for 按住说. With [onTap], a press shorter than the long-press timeout is a tap and
 * only a longer one starts holding (the + button); without it, holding starts on touch (the sheet's
 * mic). The hold lasts until every finger is up, wherever it moved, and ends if the gesture is torn
 * down (the button left the screen), so a take never keeps recording by itself.
 */
@Composable
fun Modifier.holdToTalk(onHoldStart: () -> Unit, onHoldEnd: () -> Unit, onTap: (() -> Unit)? = null): Modifier {
    val start by rememberUpdatedState(onHoldStart)
    val end by rememberUpdatedState(onHoldEnd)
    val tap by rememberUpdatedState(onTap)
    val delayed = onTap != null
    return pointerInput(delayed) {
        awaitEachGesture {
            awaitFirstDown().consume()
            if (delayed) {
                var cancelled = false
                val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    waitForUpOrCancellation().also { if (it == null) cancelled = true }
                }
                if (up != null) {
                    up.consume()
                    tap?.invoke()
                    return@awaitEachGesture
                }
                if (cancelled) return@awaitEachGesture
            }
            start()
            try {
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            } finally {
                end()
            }
        }
    }
}
