package dev.chenwuyi.leaf.voice

import android.app.Application
import dev.chenwuyi.leaf.data.settings.ElevenLabsKey
import dev.chenwuyi.leaf.data.voice.ScribeTranscriber
import dev.chenwuyi.leaf.data.voice.VoiceException
import dev.chenwuyi.leaf.data.voice.joinTranscript
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ScribeTest {
    private val key = "sk_dev_local_not_secret_0000"

    @Test
    fun should_accept_only_key_shaped_text_when_parsing_a_pasted_key() {
        assertEquals(key, ElevenLabsKey.parse("  $key\n"))
        assertNull(ElevenLabsKey.parse("https://relay.example.workers.dev/#token=$key"))
        assertNull(ElevenLabsKey.parse("sk_short"))
        assertNull(ElevenLabsKey.parse("sk_dev local not secret 0000"))
        assertNull(ElevenLabsKey.parse(null))
        assertEquals("sk_…0000", ElevenLabsKey.masked(key))
    }

    @Test
    fun should_send_pcm_to_elevenlabs_with_the_key_when_transcribing() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"text":" 好的工具应该安静 "}"""))
            assertEquals("好的工具应该安静", ScribeTranscriber(baseUrl = "http://127.0.0.1:${server.port}").transcribe(ByteArray(32_000), key))
            val request = server.takeRequest()
            assertEquals("/v1/speech-to-text", request.path)
            assertEquals(key, request.getHeader("xi-api-key"))
            assertNull(request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("scribe_v2"))
            assertTrue(body.contains("pcm_s16le_16"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun should_report_busy_when_elevenlabs_is_rate_limited() = runBlocking {
        assertEquals(VoiceException.Kind.Busy, failureFor(429))
    }

    @Test
    fun should_report_a_refused_key_when_elevenlabs_answers_401() = runBlocking {
        assertEquals(VoiceException.Kind.Unauthorized, failureFor(401))
    }

    private suspend fun failureFor(code: Int): VoiceException.Kind {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(code).setBody("{}"))
            ScribeTranscriber(baseUrl = "http://127.0.0.1:${server.port}").transcribe(ByteArray(32_000), key)
            fail("expected a failure")
            error("unreachable")
        } catch (failure: VoiceException) {
            return failure.kind
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun should_join_chinese_without_spaces_and_english_with_one() {
        assertEquals("早上在路口站了一会儿", joinTranscript("早上在路口", "站了一会儿"))
        assertEquals("quiet tools", joinTranscript("quiet", "tools"))
        assertEquals("记一下", joinTranscript("", "记一下"))
    }
}
