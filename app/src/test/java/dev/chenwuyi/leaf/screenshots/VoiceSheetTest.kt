package dev.chenwuyi.leaf.screenshots

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dev.chenwuyi.leaf.data.settings.VoiceHint
import dev.chenwuyi.leaf.data.voice.VoiceCopy
import dev.chenwuyi.leaf.data.voice.VoiceRecognizers
import dev.chenwuyi.leaf.ui.capture.Capture
import dev.chenwuyi.leaf.ui.capture.CaptureSheet
import dev.chenwuyi.leaf.ui.capture.ConfirmLabel
import dev.chenwuyi.leaf.ui.capture.VoiceSession
import dev.chenwuyi.leaf.ui.theme.LeafTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 说一句 as Sheaf's thought sheet has it (round 2 #1, #12): the confirm button says 完成 on Leaf's own
 * primary, the sheet carries no line about where the audio goes, and the one-time hint spells the plus out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class VoiceSheetTest {
    @Composable
    private fun Sheet(capture: Capture, hint: String? = null) {
        LeafTheme {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val voice = remember { VoiceSession(VoiceRecognizers.forScribbles(context), scope) }
            CaptureSheet(capture, voice, {}, { _, _, _ -> }, hint = hint)
        }
    }

    @Test
    fun should_say_done_on_the_confirm_button_when_the_voice_sheet_opens() {
        val activity = probe("voice-sheet-1") { Sheet(Capture.Voice, hint = VoiceHint.Text) }
        val texts = semanticsNodes(activity).flatMap { it.texts() }
        assertEquals("完成", ConfirmLabel)
        assertTrue("the button says 完成: $texts", ConfirmLabel in texts)
        assertFalse("no 记下 left: $texts", "记下" in texts)
    }

    @Test
    fun should_leave_out_the_recording_explanation_when_the_microphone_is_not_granted_yet() {
        // Robolectric grants no permission, so this is the sheet before the first hold.
        val activity = probe("voice-sheet-2-no-mic") { Sheet(Capture.Voice) }
        val texts = unmergedNodes(activity).flatMap { it.texts() }
        assertTrue("the quiet line says what holding does: $texts", VoiceCopy.Ready in texts)
        assertTrue("no line about where the audio goes: $texts", texts.none { it.startsWith("只在按住时收音") })
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun should_say_done_when_the_voice_sheet_opens_in_dark() {
        val activity = probe("voice-sheet-3-dark") { Sheet(Capture.Voice, hint = VoiceHint.Text) }
        assertTrue(ConfirmLabel in semanticsNodes(activity).flatMap { it.texts() })
    }

    @Test
    fun should_say_done_when_the_write_a_line_sheet_opens() {
        val activity = probe("voice-sheet-4-text") { Sheet(Capture.Text()) }
        val texts = semanticsNodes(activity).flatMap { it.texts() }
        assertTrue("写一句 confirms with the same word: $texts", ConfirmLabel in texts)
        assertFalse("记下" in texts)
    }

    @Test
    fun should_spell_out_the_plus_when_the_hold_hint_is_worded() {
        assertEquals("下次可以长按加号直接说", VoiceHint.Text)
        assertFalse("a bare + read as 和", "+" in VoiceHint.Text)
    }

    @Test
    fun should_not_ask_to_save_with_the_old_word_when_a_take_is_transcribed() {
        assertFalse(VoiceCopy.Transcribed, "记下" in VoiceCopy.Transcribed)
    }
}
