package dev.chenwuyi.leaf.ui.component

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * What a keyboard shortcut asks the app to do on a desktop-class Android device (Googlebook, a tablet
 * with a keyboard, a resizable window). Back is not here: Esc goes through the activity's back
 * dispatcher, so it closes whatever the BackHandlers already say is on top.
 */
enum class DesktopCommand { NewEssay, NewScribble, Settings, CloseEssay }

/** The shortcuts, in one place so the keyboard-shortcuts helper (Meta + /) lists exactly what works. */
object DesktopKeys {
    data class Shortcut(val label: String, val keyCode: Int, val shift: Boolean, val command: DesktopCommand)

    val shortcuts = listOf(
        Shortcut("新随笔", KeyEvent.KEYCODE_N, shift = false, DesktopCommand.NewEssay),
        Shortcut("新涂鸦", KeyEvent.KEYCODE_N, shift = true, DesktopCommand.NewScribble),
        Shortcut("设置", KeyEvent.KEYCODE_COMMA, shift = false, DesktopCommand.Settings),
        Shortcut("关闭随笔", KeyEvent.KEYCODE_W, shift = false, DesktopCommand.CloseEssay),
    )

    /** The command for Ctrl + [keyCode] (with or without Shift), or null when Leaf has none for it. */
    fun command(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean): DesktopCommand? {
        if (!ctrl || alt) return null
        return shortcuts.firstOrNull { it.keyCode == keyCode && it.shift == shift }?.command
    }

    /** Esc on its own means back, as on every desktop; with a modifier it is someone else's shortcut. */
    fun isBack(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean): Boolean =
        keyCode == KeyEvent.KEYCODE_ESCAPE && !ctrl && !shift && !alt
}

/**
 * 51's rule (2026-11-08): a hardware keyboard attached = 电脑模式 (keyboard first, Markdown and shortcuts),
 * none = 平板模式 (dragging first, Markdown second). Phones are 平板模式 too unless a keyboard is plugged in.
 */
enum class InputMode { Tablet, Desktop }

object InputModes {
    fun from(keyboard: Int, hardKeyboardHidden: Int): InputMode =
        if (keyboard == Configuration.KEYBOARD_QWERTY && hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO) InputMode.Desktop
        else InputMode.Tablet
}

/**
 * The current [InputMode]. Read from the configuration, so plugging or unplugging a keyboard recomposes
 * (the activity handles keyboard config changes itself instead of being recreated).
 */
val inputMode: InputMode
    @Composable @ReadOnlyComposable
    get() = LocalInputModeOverride.current ?: LocalConfiguration.current.let { InputModes.from(it.keyboard, it.hardKeyboardHidden) }

/** Tests and previews pin a mode here; null = follow the hardware. */
val LocalInputModeOverride = staticCompositionLocalOf<InputMode?> { null }

/**
 * Carries shortcuts from the activity, which sees every key the focused text field did not take, to the
 * composition. Extra buffer so a press while the app is recomposing is not lost.
 */
class DesktopCommands {
    private val flow = MutableSharedFlow<DesktopCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<DesktopCommand> = flow.asSharedFlow()
    fun send(command: DesktopCommand): Boolean = flow.tryEmit(command)
}

/**
 * Right click (mouse or touchpad two-finger click) runs [onClick], so whatever a long press opens on a
 * phone opens the desktop way too. Read on the Initial pass and consumed, so the card's own clickable
 * underneath never sees the press as a tap.
 */
fun Modifier.onSecondaryClick(onClick: (() -> Unit)?): Modifier =
    if (onClick == null) {
        this
    } else {
        pointerInput(onClick) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                        event.changes.forEach { it.consume() }
                        onClick()
                    }
                }
            }
        }
    }
