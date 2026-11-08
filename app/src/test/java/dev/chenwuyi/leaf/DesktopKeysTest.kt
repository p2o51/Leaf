package dev.chenwuyi.leaf

import android.view.KeyEvent
import dev.chenwuyi.leaf.ui.component.DesktopCommand
import dev.chenwuyi.leaf.ui.component.DesktopKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopKeysTest {
    private fun ctrl(code: Int, shift: Boolean = false) = DesktopKeys.command(code, ctrl = true, shift = shift, alt = false)

    @Test
    fun should_map_ctrl_shortcuts_to_commands_when_ctrl_is_held() {
        assertEquals(DesktopCommand.NewEssay, ctrl(KeyEvent.KEYCODE_N))
        assertEquals(DesktopCommand.NewScribble, ctrl(KeyEvent.KEYCODE_N, shift = true))
        assertEquals(DesktopCommand.Settings, ctrl(KeyEvent.KEYCODE_COMMA))
        assertEquals(DesktopCommand.CloseEssay, ctrl(KeyEvent.KEYCODE_W))
    }

    @Test
    fun should_ignore_keys_when_ctrl_is_missing_or_alt_is_held() {
        assertNull(DesktopKeys.command(KeyEvent.KEYCODE_N, ctrl = false, shift = false, alt = false))
        assertNull(DesktopKeys.command(KeyEvent.KEYCODE_N, ctrl = true, shift = false, alt = true))
        assertNull(ctrl(KeyEvent.KEYCODE_B))
        assertNull(ctrl(KeyEvent.KEYCODE_W, shift = true))
    }

    @Test
    fun should_treat_plain_escape_as_back_when_no_modifier_is_held() {
        assertTrue(DesktopKeys.isBack(KeyEvent.KEYCODE_ESCAPE, ctrl = false, shift = false, alt = false))
        assertFalse(DesktopKeys.isBack(KeyEvent.KEYCODE_ESCAPE, ctrl = true, shift = false, alt = false))
        assertFalse(DesktopKeys.isBack(KeyEvent.KEYCODE_BACK, ctrl = false, shift = false, alt = false))
    }

    @Test
    fun should_list_each_shortcut_once_when_the_helper_reads_them() {
        val keys = DesktopKeys.shortcuts.map { it.keyCode to it.shift }
        assertEquals(keys.size, keys.toSet().size)
    }
}
