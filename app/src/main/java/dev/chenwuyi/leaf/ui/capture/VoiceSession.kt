package dev.chenwuyi.leaf.ui.capture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.chenwuyi.leaf.data.voice.VoiceCopy
import dev.chenwuyi.leaf.data.voice.VoiceEvent
import dev.chenwuyi.leaf.data.voice.VoiceException
import dev.chenwuyi.leaf.data.voice.VoiceNoteRecognizer
import dev.chenwuyi.leaf.data.voice.VoiceRoute
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface VoiceState {
    /** Nothing recording. [line] says how the last take went, or what holding does. */
    data class Idle(val line: String = VoiceCopy.Ready) : VoiceState

    /** The finger is down. [text] is what the system recognizer heard so far (Scribe shows none). */
    data class Listening(val text: String = "") : VoiceState

    /** Lifted; waiting for the final words. */
    data class Finishing(val text: String) : VoiceState

    /** A take's words, for the sheet to put into its field once; [take] tells two takes apart. */
    data class Heard(val text: String, val line: String, val take: Int) : VoiceState
}

/**
 * One hold-to-talk take at a time, for the capture sheet and the + button's long press. [press] when
 * the finger comes down, [lift] when it comes up, [cancel] when the sheet closes. The words land in
 * [VoiceState.Heard]; the sheet adds them to whatever is typed and calls [taken].
 */
class VoiceSession(
    private val recognizer: VoiceNoteRecognizer,
    private val scope: CoroutineScope,
    private val language: () -> String = { Locale.getDefault().toLanguageTag() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    var state: VoiceState by mutableStateOf(VoiceState.Idle())
        private set

    private var job: Job? = null
    private var release: CompletableDeferred<Unit>? = null
    private var pressedAt = 0L
    private var takes = 0

    fun route(): VoiceRoute = recognizer.route()

    fun press() {
        if (job?.isActive == true) return
        if (recognizer.route() == VoiceRoute.None) {
            state = VoiceState.Idle(VoiceCopy.Unavailable)
            return
        }
        val lifted = CompletableDeferred<Unit>()
        release = lifted
        pressedAt = now()
        state = VoiceState.Listening()
        job = scope.launch {
            var heard = ""
            try {
                recognizer.listen(language(), lifted).collect { event ->
                    when (event) {
                        VoiceEvent.Listening -> Unit
                        is VoiceEvent.Partial -> {
                            heard = event.text
                            state = if (lifted.isCompleted) VoiceState.Finishing(heard) else VoiceState.Listening(heard)
                        }
                        is VoiceEvent.Final -> heard = event.text
                    }
                }
                state = if (heard.isBlank()) VoiceState.Idle(VoiceCopy.NothingHeard) else VoiceState.Heard(heard, VoiceCopy.Transcribed, ++takes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: VoiceException) {
                // Words already shown are kept; the line says why the take stopped.
                state = if (heard.isNotBlank()) VoiceState.Heard(heard, VoiceCopy.partial(failure.kind), ++takes)
                else VoiceState.Idle(VoiceCopy.stop(failure.kind))
            }
        }
    }

    fun lift() {
        val lifted = release ?: return
        release = null
        // A tap is not a take: say that it needs holding instead of sending a blip to be transcribed.
        if (now() - pressedAt < MinHoldMillis) {
            job?.cancel()
            state = VoiceState.Idle(VoiceCopy.HoldToTalk)
            return
        }
        lifted.complete(Unit)
        (state as? VoiceState.Listening)?.let { state = VoiceState.Finishing(it.text) }
    }

    /** The sheet has put [VoiceState.Heard]'s words in its field. */
    fun taken() {
        (state as? VoiceState.Heard)?.let { state = VoiceState.Idle(it.line) }
    }

    fun denied() {
        state = VoiceState.Idle(VoiceCopy.Denied)
    }

    fun cancel() {
        job?.cancel()
        job = null
        release = null
        state = VoiceState.Idle()
    }

    companion object {
        const val MinHoldMillis = 300L
    }
}
