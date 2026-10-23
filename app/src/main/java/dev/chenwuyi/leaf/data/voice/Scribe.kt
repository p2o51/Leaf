package dev.chenwuyi.leaf.data.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.chenwuyi.leaf.LeafApplication
import dev.chenwuyi.leaf.data.settings.RelayConnection
import dev.chenwuyi.leaf.data.settings.RelayStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

/** Records one hold as raw 16 kHz mono 16-bit little-endian PCM, until [release] completes. */
interface PcmRecorder {
    /** Throws [VoiceException] when the microphone cannot be opened. Cancelling stops it at once. */
    suspend fun record(release: Deferred<Unit>): ByteArray
}

class AudioRecordPcmRecorder(private val io: CoroutineDispatcher = Dispatchers.IO) : PcmRecorder {
    // The sheet asks for RECORD_AUDIO before any take; a grant revoked meanwhile lands in the catch.
    @SuppressLint("MissingPermission")
    override suspend fun record(release: Deferred<Unit>): ByteArray = withContext(io) {
        val minBuffer = AudioRecord.getMinBufferSize(SampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw VoiceException("no 16 kHz microphone", VoiceException.Kind.Unavailable)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4)
        } catch (_: SecurityException) {
            throw VoiceException("RECORD_AUDIO missing", VoiceException.Kind.NoPermission)
        }
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) throw VoiceException("microphone busy", VoiceException.Kind.Busy)
            record.startRecording()
            val out = ByteArrayOutputStream(SampleRate * 2 * 10)
            val buffer = ByteArray(minBuffer)
            while (isActive && !release.isCompleted && out.size() < MaxBytes) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) throw VoiceException("AudioRecord error $read", VoiceException.Kind.Failed)
                out.write(buffer, 0, read)
            }
            ensureActive()
            out.toByteArray()
        } finally {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            record.release()
        }
    }

    companion object {
        const val SampleRate = 16_000

        /** Five minutes, the most the Relay takes (MAX_TRANSCRIBE_BODY_BYTES in server/src/speech.ts). */
        const val MaxBytes = SampleRate * 2 * 300
    }
}

/**
 * ElevenLabs Scribe v2 through Sheaf's Relay, which adds the Worker's key and the saved keyterms. No
 * language is sent: Scribe detects it, mixed Chinese and English included.
 */
class ScribeTranscriber(client: OkHttpClient = OkHttpClient()) {
    private val http = client.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun transcribe(pcm: ByteArray, relay: RelayConnection): String {
        val url = "${relay.baseUrl}/v1/speech/elevenlabs/v1/speech-to-text".toHttpUrlOrNull()
            ?: throw VoiceException("bad relay address", VoiceException.Kind.Unavailable)
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model_id", Model)
            // Raw PCM skips decoding on their side, which ElevenLabs says answers sooner than a file.
            .addFormDataPart("file_format", "pcm_s16le_16")
            .addFormDataPart("tag_audio_events", "false")
            .addFormDataPart("file", "scribble.pcm", pcm.toRequestBody(OctetStream))
            .build()
        val request = Request.Builder().url(url).post(form)
            .header("Authorization", "Bearer ${relay.token}")
            .header("Accept", "application/json")
            .build()
        val (code, raw) = execute(request)
        if (code !in 200..299) throw VoiceException("speech-to-text answered $code", kindOf(code))
        return try {
            JSONObject(raw).optString("text").trim()
        } catch (_: JSONException) {
            throw VoiceException("speech-to-text answer unreadable", VoiceException.Kind.Failed)
        }
    }

    /** Enqueued, so closing the sheet cancels the upload at once. */
    private suspend fun execute(request: Request): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(VoiceException(e.message ?: "network", VoiceException.Kind.Network)))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { it.code to it.body.string() } }
                    .recoverCatching { throw VoiceException(it.message ?: "network", VoiceException.Kind.Network) }
                continuation.resumeWith(result)
            }
        })
    }

    companion object {
        const val Model = "scribe_v2"
        private val OctetStream = "application/octet-stream".toMediaType()

        // A rate limit or a gateway timeout is a wait; anything else the next hold would meet too.
        internal fun kindOf(code: Int): VoiceException.Kind = when (code) {
            429, 502, 504 -> VoiceException.Kind.Busy
            else -> VoiceException.Kind.Failed
        }
    }
}

/**
 * 按住说 through Scribe: records while held, transcribes after the lift. No words show while holding
 * (Scribe answers once, in about a second), so the take is one [VoiceEvent.Final].
 */
class ScribeVoiceRecognizer(
    private val relay: RelayStore,
    private val transcriber: ScribeTranscriber,
    private val recorder: PcmRecorder = AudioRecordPcmRecorder(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : VoiceNoteRecognizer {
    val ready: Boolean get() = relay.host.value != null

    override fun route(): VoiceRoute = if (ready) VoiceRoute.Cloud else VoiceRoute.None

    override fun listen(languageTag: String, release: Deferred<Unit>): Flow<VoiceEvent> = flow {
        // The Keystore is on disk, so the connection is read off the main thread.
        val connection = withContext(io) { relay.read() } ?: throw VoiceException("no relay", VoiceException.Kind.Unavailable)
        emit(VoiceEvent.Listening)
        val pcm = recorder.record(release)
        currentCoroutineContext().ensureActive()
        // Under 100 ms ElevenLabs refuses the file; a hold that short heard nothing anyway.
        if (pcm.size < MinBytes) {
            emit(VoiceEvent.Final(""))
            return@flow
        }
        emit(VoiceEvent.Final(transcriber.transcribe(pcm, connection)))
    }

    companion object {
        /** 0.3 s of 16 kHz 16-bit audio. */
        const val MinBytes = AudioRecordPcmRecorder.SampleRate * 2 * 3 / 10
    }
}

fun interface NetworkStatus {
    fun isOffline(): Boolean
}

class SystemNetworkStatus(context: Context) : NetworkStatus {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun isOffline(): Boolean {
        val manager = connectivity ?: return false
        val network = manager.activeNetwork ?: return true
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/**
 * Scribe when a Relay is saved and the device is online, else the system recognizer. Chosen at each
 * hold, so the permission line ([route]) and the take always agree. Offline the system one still works
 * on the device; Scribe would only lose the recording.
 */
class CloudFirstVoiceRecognizer(
    private val cloud: ScribeVoiceRecognizer,
    private val system: VoiceNoteRecognizer,
    private val network: NetworkStatus,
) : VoiceNoteRecognizer {
    private fun useCloud(): Boolean = cloud.ready && !network.isOffline()

    override fun route(): VoiceRoute = if (useCloud()) VoiceRoute.Cloud else system.route()

    override fun listen(languageTag: String, release: Deferred<Unit>): Flow<VoiceEvent> =
        if (useCloud()) cloud.listen(languageTag, release) else system.listen(languageTag, release)
}

object VoiceRecognizers {
    /** Scribe through the Relay when one is saved, else the system recognizer; just the latter without the app's container (tests). */
    fun forScribbles(context: Context): VoiceNoteRecognizer {
        val system = SystemVoiceNoteRecognizer(context)
        val container = (context.applicationContext as? LeafApplication)?.container ?: return system
        return CloudFirstVoiceRecognizer(ScribeVoiceRecognizer(container.relay, container.transcriber), system, SystemNetworkStatus(context))
    }
}
