package dev.chenwuyi.leaf.data.notion

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

/**
 * Ported from Sheaf. Notion's public-connection OAuth goes through notion-oauth-relay (the Worker shared
 * with Sheaf, 墨记 and CronPaw), because exchanging and refreshing need the client secret, which must never
 * ship in the APK. The relay sends Notion's callback on to [CallbackUri]; the code is then exchanged by a
 * POST from the app, so it is never left in a URL the relay logs.
 */
object NotionOAuthConfig {
    /** Blank turns sign-in off: settings then offers only the integration token, as before. */
    const val RelayBaseUrl = "https://notion-oauth-relay.p2o51willam.workers.dev"
    const val App = "leaf"
    const val Scheme = "leaf"
    const val Host = "notion-oauth"
    const val CallbackUri = "$Scheme://$Host"

    val enabled: Boolean get() = RelayBaseUrl.isNotBlank()
}

/** What the vault keeps: a pasted integration token, or an OAuth pair. */
sealed interface NotionCredential {
    val accessToken: String

    data class Integration(override val accessToken: String) : NotionCredential

    /** Each refresh rotates both tokens (Notion Authorization docs), so the pair is always written together. */
    data class OAuth(override val accessToken: String, val refreshToken: String) : NotionCredential

    fun encode(): String = when (this) {
        // Stored exactly as before, so tokens saved by older builds read back unchanged.
        is Integration -> accessToken
        is OAuth -> JSONObject().put("v", 1).put("kind", "oauth").put("access", accessToken).put("refresh", refreshToken).toString()
    }

    companion object {
        fun decode(stored: String): NotionCredential {
            // An integration token never starts with a brace (ntn_… / secret_…).
            if (!stored.startsWith("{")) return Integration(stored)
            val json = try { JSONObject(stored) } catch (_: JSONException) { throw IOException("连接凭据不可用，请重新连接 Notion") }
            val access = json.optString("access")
            val refresh = json.optString("refresh")
            if (json.optString("kind") != "oauth" || access.isBlank() || refresh.isBlank()) throw IOException("连接凭据不可用，请重新连接 Notion")
            return OAuth(access, refresh)
        }
    }
}

data class NotionOAuthTokens(val accessToken: String, val refreshToken: String)

/** The relay's two POST routes; both return Notion's token response unchanged. */
interface NotionOAuthRelay {
    suspend fun exchange(code: String): NotionOAuthTokens
    suspend fun refresh(refreshToken: String): NotionOAuthTokens
}

class HttpNotionOAuthRelay(
    client: OkHttpClient = OkHttpClient(),
    private val baseUrl: HttpUrl = NotionOAuthConfig.RelayBaseUrl.toHttpUrl(),
    private val app: String = NotionOAuthConfig.App,
) : NotionOAuthRelay {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    init {
        // Tokens only ever travel over https, except to a loopback test server.
        require(baseUrl.isHttps || baseUrl.host in setOf("localhost", "127.0.0.1", "::1"))
    }

    override suspend fun exchange(code: String) = post("token", JSONObject().put("code", code))

    override suspend fun refresh(refreshToken: String) = post("refresh", JSONObject().put("refresh_token", refreshToken))

    private suspend fun post(route: String, body: JSONObject): NotionOAuthTokens {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment(app).addPathSegment(route).build())
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val response = try {
            http.newCall(request).await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            throw NotionException(NotionFailure.Unavailable)
        }
        response.use {
            // Notion answers a spent or revoked code / refresh token with 400 invalid_grant: sign in again.
            if (it.code in 400..499) throw NotionException(NotionFailure.Unauthorized, it.code)
            if (!it.isSuccessful) throw NotionException(NotionFailure.Unavailable, it.code)
            val json = try { JSONObject(it.body.string()) } catch (_: JSONException) { throw NotionException(NotionFailure.InvalidResponse, it.code) }
            val access = json.optString("access_token")
            val refresh = json.optString("refresh_token")
            if (access.isBlank() || refresh.isBlank()) throw NotionException(NotionFailure.InvalidResponse, it.code)
            return NotionOAuthTokens(access, refresh)
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
        })
    }
}

/** What came back to [NotionOAuthConfig.CallbackUri]: a code to exchange, or why there is none. */
sealed interface NotionOAuthResult {
    data class Code(val code: String) : NotionOAuthResult
    /** Cancel on Notion's page: nothing to say, settings just stays where it was. */
    data object Cancelled : NotionOAuthResult
    data class Failed(val message: String) : NotionOAuthResult
}

/**
 * One sign-in at a time, for the whole process: the browser hands the callback to an activity, while
 * settings waits for it here. The state lives only in memory, so a callback that outlived its process
 * (or was never asked for) is turned down rather than exchanged.
 */
class NotionOAuthSession(private val random: SecureRandom = SecureRandom()) {
    private var expectedState: String? = null
    private val mutableResult = MutableStateFlow<NotionOAuthResult?>(null)
    val result: StateFlow<NotionOAuthResult?> = mutableResult.asStateFlow()

    /** The relay's start page for a fresh sign-in; any sign-in still pending is forgotten. */
    @Synchronized
    fun start(): String {
        val bytes = ByteArray(24).also(random::nextBytes)
        val state = bytes.joinToString("") { "%02x".format(it) }
        expectedState = state
        mutableResult.value = null
        val base = NotionOAuthConfig.RelayBaseUrl.trimEnd('/')
        return "$base/${NotionOAuthConfig.App}/start?state=${URLEncoder.encode(state, "UTF-8")}"
    }

    /** Called with the callback activity's data; anything that is not our callback is ignored. */
    @Synchronized
    fun deliver(uri: String?) {
        val parsed = try { uri?.let(::URI) } catch (_: Exception) { null } ?: return
        if (parsed.scheme != NotionOAuthConfig.Scheme || parsed.host != NotionOAuthConfig.Host) return
        val query = parsed.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
            val parts = pair.split('=', limit = 2)
            decode(parts[0]) to decode(parts.getOrElse(1) { "" })
        }
        val expected = expectedState
        val state = query["state"]
        mutableResult.value = when {
            expected == null || state == null || !constantTimeEquals(expected, state) -> NotionOAuthResult.Failed("登录已过期，请再试一次")
            query["error"] == "access_denied" -> NotionOAuthResult.Cancelled
            !query["error"].isNullOrEmpty() || query["code"].isNullOrEmpty() -> NotionOAuthResult.Failed("Notion 没有完成授权，请再试一次")
            else -> NotionOAuthResult.Code(query.getValue("code")!!)
        }
        // A state is good for one callback only.
        expectedState = null
    }

    /** Takes the waiting result, so it is handled once. */
    @Synchronized
    fun consume(): NotionOAuthResult? = mutableResult.value.also { mutableResult.value = null }

    private fun decode(value: String) = try { URLDecoder.decode(value, "UTF-8") } catch (_: IllegalArgumentException) { "" }

    private fun constantTimeEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        /** The process's session: the callback activity and settings meet here. */
        val shared = NotionOAuthSession()
    }
}
