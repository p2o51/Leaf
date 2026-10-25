package dev.chenwuyi.leaf.voice

import android.app.Application
import dev.chenwuyi.leaf.data.settings.RelayConnection
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
class RelayAndScribeTest {
    private val token = "dev-local-not-secret-token"

    @Test
    fun should_accept_only_well_formed_connection_strings_when_parsing() {
        val ok = RelayConnection.parse("  https://relay.example.workers.dev/#token=$token ")!!
        assertEquals("https://relay.example.workers.dev", ok.baseUrl)
        assertEquals("relay.example.workers.dev", ok.host)
        assertNull(RelayConnection.parse("https://relay.example.workers.dev/"))
        assertNull(RelayConnection.parse("http://relay.example.workers.dev/#token=$token"))
        assertNull(RelayConnection.parse("https://relay.example.workers.dev/x#token=$token"))
        assertNull(RelayConnection.parse("https://relay.example.workers.dev/#token=short"))
        assertTrue(RelayConnection.parse("http://127.0.0.1:8787/#token=$token") != null)
    }

    @Test
    fun should_send_pcm_to_the_relay_with_its_token_when_transcribing() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"text":" 好的工具应该安静 "}"""))
            val relay = RelayConnection("http://127.0.0.1:${server.port}", token)
            assertEquals("好的工具应该安静", ScribeTranscriber().transcribe(ByteArray(32_000), relay))
            val request = server.takeRequest()
            assertEquals("/v1/speech/elevenlabs/v1/speech-to-text", request.path)
            assertEquals("Bearer $token", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("scribe_v2"))
            assertTrue(body.contains("pcm_s16le_16"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun should_report_busy_when_the_relay_is_rate_limited() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
            try {
                ScribeTranscriber().transcribe(ByteArray(32_000), RelayConnection("http://127.0.0.1:${server.port}", token))
                fail("expected a failure")
            } catch (busy: VoiceException) {
                assertEquals(VoiceException.Kind.Busy, busy.kind)
            }
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
