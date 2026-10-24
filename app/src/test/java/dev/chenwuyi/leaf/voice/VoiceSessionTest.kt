package dev.chenwuyi.leaf.voice

import dev.chenwuyi.leaf.data.voice.VoiceCopy
import dev.chenwuyi.leaf.data.voice.VoiceEvent
import dev.chenwuyi.leaf.data.voice.VoiceException
import dev.chenwuyi.leaf.data.voice.VoiceNoteRecognizer
import dev.chenwuyi.leaf.data.voice.VoiceRoute
import dev.chenwuyi.leaf.ui.capture.VoiceSession
import dev.chenwuyi.leaf.ui.capture.VoiceState
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionTest {
    /** Says [partials] while held, then [final] after the lift, or fails with [failure]. */
    private class Scripted(
        val partials: List<String> = emptyList(),
        val final: String = "",
        val failure: VoiceException.Kind? = null,
        val route: VoiceRoute = VoiceRoute.Cloud,
    ) : VoiceNoteRecognizer {
        override fun route() = route
        override fun listen(languageTag: String, release: Deferred<Unit>): Flow<VoiceEvent> = flow {
            emit(VoiceEvent.Listening)
            partials.forEach { emit(VoiceEvent.Partial(it)) }
            release.await()
            failure?.let { throw VoiceException("scripted", it) }
            emit(VoiceEvent.Final(final))
        }
    }

    private var clock = 0L
    private fun TestScope.session(recognizer: VoiceNoteRecognizer) =
        VoiceSession(recognizer, TestScope(UnconfinedTestDispatcher(testScheduler)), language = { "zh-CN" }, now = { clock })

    @Test
    fun should_hand_over_the_words_when_the_finger_lifts() = runTest {
        val voice = session(Scripted(final = "便利店的灯光"))
        voice.press()
        assertTrue(voice.state is VoiceState.Listening)
        clock = 1_200
        voice.lift()
        testScheduler.advanceUntilIdle()
        val heard = voice.state as VoiceState.Heard
        assertEquals("便利店的灯光", heard.text)
        voice.taken()
        assertEquals(VoiceState.Idle(VoiceCopy.Transcribed), voice.state)
    }

    @Test
    fun should_say_it_needs_holding_when_it_was_only_a_tap() = runTest {
        val voice = session(Scripted(final = "嗯"))
        voice.press()
        clock = 120
        voice.lift()
        testScheduler.advanceUntilIdle()
        assertEquals(VoiceState.Idle(VoiceCopy.HoldToTalk), voice.state)
    }

    @Test
    fun should_keep_the_heard_words_when_the_service_drops_mid_take() = runTest {
        val voice = session(Scripted(partials = listOf("早上在路口"), failure = VoiceException.Kind.Network, route = VoiceRoute.System))
        voice.press()
        assertEquals(VoiceState.Listening("早上在路口"), voice.state)
        clock = 2_000
        voice.lift()
        testScheduler.advanceUntilIdle()
        val heard = voice.state as VoiceState.Heard
        assertEquals("早上在路口", heard.text)
        assertEquals(VoiceCopy.partial(VoiceException.Kind.Network), heard.line)
    }

    @Test
    fun should_say_nothing_was_heard_when_the_take_is_empty() = runTest {
        val voice = session(Scripted(final = "  "))
        voice.press()
        clock = 900
        voice.lift()
        testScheduler.advanceUntilIdle()
        assertEquals(VoiceState.Idle(VoiceCopy.NothingHeard), voice.state)
    }

    @Test
    fun should_not_record_when_no_recognizer_is_usable() = runTest {
        val voice = session(Scripted(route = VoiceRoute.None))
        voice.press()
        assertEquals(VoiceState.Idle(VoiceCopy.Unavailable), voice.state)
    }

    @Test
    fun should_drop_the_take_when_the_sheet_closes() = runTest {
        val voice = session(Scripted(final = "不要了"))
        voice.press()
        voice.cancel()
        clock = 1_000
        voice.lift()
        testScheduler.advanceUntilIdle()
        assertEquals(VoiceState.Idle(), voice.state)
    }
}
