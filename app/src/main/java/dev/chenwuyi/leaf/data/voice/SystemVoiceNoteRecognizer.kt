package dev.chenwuyi.leaf.data.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * The platform SpeechRecognizer. On API 31+ the on-device recognizer comes first so a note can stay
 * on the phone; the default recognizer, which may send audio to its server, is used only when
 * [allowNetwork] is true, either because there is no on-device recognizer or because it lacks the
 * language. That hand-over happens mid-take without asking, so [route] already says
 * [VoiceRoute.System] whenever it could happen.
 *
 * The platform ends a segment by itself after a pause, but a hold is one take: while the finger is
 * still down a finished segment is kept and listening starts again, and only the lift ends the take.
 *
 * SpeechRecognizer must be created and called on the main thread, hence [main].
 */
class SystemVoiceNoteRecognizer(
    context: Context,
    private val allowNetwork: Boolean = AllowNetworkFallback,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
) : VoiceNoteRecognizer {
    private val context = context.applicationContext

    /** Languages the on-device recognizer said it has no model for; touched on [main] only. */
    private val missingOnDevice = mutableSetOf<String>()

    // Which languages the on-device recognizer has is only known once it refuses one, so with a
    // networked recognizer allowed nothing can promise the audio stays on the device.
    override fun route(): VoiceRoute = when {
        networkAllowed() -> VoiceRoute.System
        onDeviceAvailable() -> VoiceRoute.OnDevice
        else -> VoiceRoute.None
    }

    override fun listen(languageTag: String, release: Deferred<Unit>): Flow<VoiceEvent> = callbackFlow {
        val network = networkAllowed()
        // On-device first even on the System route; skip it only for a language it already refused.
        var useOnDevice = onDeviceAvailable() && !(network && languageTag in missingOnDevice)
        if (!useOnDevice && !network) throw VoiceException("no usable speech recognizer", VoiceException.Kind.Unavailable)
        val intent = recognizerIntent(languageTag)
        var recognizer: SpeechRecognizer? = null
        var released = false
        var segments = 0
        // Text from segments that ended while the finger was still down, and the newest partial of
        // the segment in progress (kept so a stop that never answers still yields what was shown).
        var committed = ""
        var latest = ""

        fun finish(tail: String) {
            trySend(VoiceEvent.Final(joinTranscript(committed, tail)))
            close()
        }

        fun listenAgain() {
            segments++
            latest = ""
            recognizer?.startListening(intent)
        }

        lateinit var listener: RecognitionListener
        fun open(onDevice: Boolean) {
            recognizer?.destroy()
            recognizer = create(onDevice).also {
                it.setRecognitionListener(listener)
                it.startListening(intent)
            }
        }

        listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(VoiceEvent.Listening)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults.bestText() ?: return
                latest = text
                trySend(VoiceEvent.Partial(joinTranscript(committed, text)))
            }

            override fun onResults(results: Bundle?) {
                val text = results.bestText() ?: latest
                if (!released && segments < MaxSegments) {
                    committed = joinTranscript(committed, text)
                    // A finished segment can differ from its last partial; show it, since a later
                    // failure keeps whatever the last Partial said.
                    if (committed.isNotEmpty()) trySend(VoiceEvent.Partial(committed))
                    listenAgain()
                } else {
                    finish(text)
                }
            }

            override fun onError(error: Int) {
                when {
                    // A pause or silence while held is the speaker thinking, not the end.
                    error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        if (!released && segments < MaxSegments) {
                            // A partial the recognizer then gave up on was on screen already; keep it.
                            committed = joinTranscript(committed, latest)
                            listenAgain()
                        } else {
                            finish(latest)
                        }
                    // Lifting before the recognizer was ready is a stop it rejects, not a failure.
                    error == SpeechRecognizer.ERROR_CLIENT && released -> finish(latest)
                    // The on-device recognizer lacks this language's model: hand over if allowed.
                    // route() said System for this case, so the permission line already told.
                    useOnDevice && error in LanguageErrors && network -> {
                        missingOnDevice += languageTag
                        useOnDevice = false
                        committed = joinTranscript(committed, latest)
                        latest = ""
                        open(onDevice = false)
                        if (released) recognizer?.stopListening()
                    }
                    // Words already heard went out as the last Partial; the session keeps them.
                    else -> close(VoiceException("SpeechRecognizer error $error", voiceErrorKind(error)))
                }
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        open(useOnDevice)
        // Started after the recognizer so a tap shorter than its start-up still reaches stopListening.
        val lift = launch {
            release.await()
            released = true
            recognizer?.stopListening()
            // Some recognizers never answer a stop; keep what was shown rather than hang the sheet.
            delay(FinishTimeoutMillis)
            finish(latest)
        }
        awaitClose {
            lift.cancel()
            recognizer?.destroy()
            recognizer = null
        }
    }.flowOn(main)

    private fun networkAllowed(): Boolean = allowNetwork && SpeechRecognizer.isRecognitionAvailable(context)

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun create(onDevice: Boolean): SpeechRecognizer =
        if (onDevice && Build.VERSION.SDK_INT >= 31) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    private fun recognizerIntent(languageTag: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Asks the default recognizer to stay on the device whenever it has the language.
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

    companion object {
        /**
         * Whether the system's default recognizer, which may use the network, may take a scribble the
         * on-device one cannot. Same answer as Sheaf; the permission line says the audio may go online.
         */
        const val AllowNetworkFallback = true

        /** Restarts allowed in one hold; a stuck recognizer must not loop forever. */
        const val MaxSegments = 20

        /** How long to wait for the final words after the lift. */
        const val FinishTimeoutMillis = 4_000L

        // API 31 codes; they are compile-time constants, and older recognizers never report them.
        private val LanguageErrors = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        )
    }
}

// Each kind picks the line the user reads (VoiceCopy), so a code goes where that line is true.
internal fun voiceErrorKind(error: Int): VoiceException.Kind = when (error) {
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceException.Kind.NoPermission
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    SpeechRecognizer.ERROR_SERVER,
    -> VoiceException.Kind.Network
    // RECOGNIZER_BUSY is often our own recognizer still closing the previous segment, and
    // TOO_MANY_REQUESTS is a rate limit: neither is someone else holding the microphone.
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
    -> VoiceException.Kind.Busy
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
    -> VoiceException.Kind.Unavailable
    // SERVER_DISCONNECTED is the recognition service process going away, on-device ones too, so it
    // is no network trouble; it lands here with audio and client errors.
    else -> VoiceException.Kind.Failed
}

private fun Bundle?.bestText(): String? =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }?.trim()
