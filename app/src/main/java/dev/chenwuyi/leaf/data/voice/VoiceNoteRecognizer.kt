package dev.chenwuyi.leaf.data.voice

import java.io.IOException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow

/** What one hold-to-talk take reports, in order: [Listening], any number of [Partial]s, one [Final]. */
sealed interface VoiceEvent {
    /** The microphone is open; may repeat when a long hold spans several recognizer segments. */
    data object Listening : VoiceEvent

    /**
     * The best guess so far for the whole take, segments the recognizer already finished included;
     * each one replaces the previous. A failure keeps the last one, so it must hold every word.
     */
    data class Partial(val text: String) : VoiceEvent

    /** The transcript once the finger has lifted. Empty means nothing was heard. */
    data class Final(val text: String) : VoiceEvent
}

/** Where a take's audio may go on this device right now; the permission line is chosen by it. */
enum class VoiceRoute {
    /** Only the platform's on-device recognizer (API 31+) may be used: audio stays on the device. */
    OnDevice,

    /**
     * The default system recognizer may be used, asked to prefer offline; it may use the network.
     * An on-device recognizer, if any, still goes first and hands over a language it lacks.
     */
    System,

    /** ElevenLabs Scribe: the recording leaves the device (ScribeVoiceRecognizer). */
    Cloud,

    /** No usable recognizer, or only a networked one while that is not allowed. */
    None,
}

class VoiceException(message: String, val kind: Kind) : IOException(message) {
    enum class Kind {
        /** RECORD_AUDIO is missing or was revoked mid-take. */
        NoPermission,

        /** No recognizer at all, or the one we have cannot do this language. */
        Unavailable,

        /** The recognizer needs the network and cannot reach it, or its server failed. */
        Network,

        /** The recognizer is still busy (with an earlier segment or another app), or rate limited. */
        Busy,

        /** The speech service refused the saved key (wrong, revoked or out of quota). */
        Unauthorized,

        /** Anything else the recognizer reports (audio error, client error, a dead service). */
        Failed,
    }
}

/**
 * Speech-to-text for scribbles (按住说), ported from Sheaf. One take per hold: collecting [listen] opens the microphone,
 * completing [release] (the finger lifts) asks for the final words, and cancelling the collection
 * discards the take. Failures end the flow with a [VoiceException]; the words heard until then are
 * the last [VoiceEvent.Partial], which the caller keeps.
 */
interface VoiceNoteRecognizer {
    /**
     * Where the audio of the next take may go. Never [VoiceRoute.OnDevice] while [listen] could
     * still hand the audio to a networked recognizer: the permission line promises what this says.
     */
    fun route(): VoiceRoute

    /** [languageTag] is a BCP 47 tag such as "zh-CN"; see Locale.getDefault(). */
    fun listen(languageTag: String, release: Deferred<Unit>): Flow<VoiceEvent>
}
