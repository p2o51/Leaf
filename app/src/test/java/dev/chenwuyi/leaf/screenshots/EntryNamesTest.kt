package dev.chenwuyi.leaf.screenshots

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dev.chenwuyi.leaf.R
import dev.chenwuyi.leaf.data.settings.VoiceHint
import dev.chenwuyi.leaf.data.voice.VoiceRecognizers
import dev.chenwuyi.leaf.ui.capture.Capture
import dev.chenwuyi.leaf.ui.capture.CaptureSheet
import dev.chenwuyi.leaf.ui.capture.VoiceSession
import dev.chenwuyi.leaf.ui.home.AddKind
import dev.chenwuyi.leaf.ui.home.AddMenu
import dev.chenwuyi.leaf.ui.theme.LeafTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The four ways to add something carry one set of names everywhere (51's review, round 1): 拍照 / 说一句 /
 * 写一句 / 随笔 in the + menu, and the same 写一句 / 说一句 on the shortcuts, the widget and the tile. The
 * hold-to-talk hint shows once, in the voice sheet, after a few picks of 说一句 from the menu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class EntryNamesTest {
    @Test
    fun should_list_photo_say_write_and_essay_when_the_add_menu_opens() {
        val activity = probe("home-phone-8-add-menu") { LeafTheme { AddMenu(true, {}, {}, {}, {}) } }
        val labels = AddKind.entries.map { it.label }
        assertEquals(listOf("拍照", "说一句", "写一句", "随笔"), labels)
        val shown = semanticsNodes(activity).flatMap { it.texts() }.filter { it in labels }
        assertEquals(labels, shown)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun should_draw_the_open_add_menu_when_dark() {
        probe("home-phone-9-add-menu-dark") { LeafTheme { AddMenu(true, {}, {}, {}, {}) } }
    }

    @Test
    fun should_use_the_menu_names_when_the_shortcuts_widget_and_tile_are_labelled() {
        val context = RuntimeEnvironment.getApplication()
        // The launcher shortcuts, the widget's buttons and the quick settings tile all read these two.
        assertEquals(AddKind.Text.label, context.getString(R.string.quick_write))
        assertEquals(AddKind.Voice.label, context.getString(R.string.quick_speak))
    }

    @Test
    fun should_show_the_hold_hint_once_when_say_a_line_is_picked_from_the_menu_a_few_times() {
        val hint = VoiceHint(RuntimeEnvironment.getApplication())
        val shown = (1..VoiceHint.PicksBeforeHint + 3).map { hint.pickedFromMenu() }
        assertEquals("only the ${VoiceHint.PicksBeforeHint}th pick: $shown", listOf(VoiceHint.PicksBeforeHint), shown.withIndex().filter { it.value }.map { it.index + 1 })
        // Kept across launches: a new reader of the same prefs never shows it again.
        assertFalse(VoiceHint(RuntimeEnvironment.getApplication()).pickedFromMenu())
    }

    @Test
    fun should_never_show_the_hold_hint_when_plus_was_already_held() {
        val hint = VoiceHint(RuntimeEnvironment.getApplication())
        hint.heldToTalk()
        assertTrue((1..VoiceHint.PicksBeforeHint * 2).none { hint.pickedFromMenu() })
    }

    @Test
    fun should_say_the_hold_hint_in_the_voice_sheet_when_it_is_given() {
        val activity = probe("quick-5-voice-hint") {
            LeafTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val voice = remember { VoiceSession(VoiceRecognizers.forScribbles(context), scope) }
                CaptureSheet(Capture.Voice, voice, {}, { _, _, _ -> }, hint = VoiceHint.Text)
            }
        }
        val texts = semanticsNodes(activity).flatMap { it.texts() }
        assertTrue("说一句 is the sheet's title: $texts", "说一句" in texts)
        assertTrue("the hint is under it: $texts", VoiceHint.Text in texts)
    }
}
