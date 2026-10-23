package dev.chenwuyi.leaf.data.notion

import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

enum class NotionFailure { Unauthorized, Forbidden, NotFound, InvalidInput, RateLimited, Unavailable, InvalidResponse }

class NotionException(val reason: NotionFailure, val status: Int? = null) : IOException("Notion: $reason${status?.let { " ($it)" } ?: ""}") {
    /** What to tell the person. Never includes the token or a request body. */
    val userMessage: String
        get() = when (reason) {
            NotionFailure.Unauthorized -> "Notion 不认这个 token，请重新连接"
            NotionFailure.Forbidden, NotionFailure.NotFound -> "Leaf 看不到这个页面：在 Notion 里把数据库分享给你的集成"
            NotionFailure.InvalidInput -> "Notion 拒绝了这次修改"
            NotionFailure.RateLimited, NotionFailure.Unavailable -> "Notion 暂时连不上，稍后会自动重试"
            NotionFailure.InvalidResponse -> "Notion 返回了看不懂的内容"
        }
}

/**
 * Thin HTTP layer for the Notion API. One instance serializes every call and keeps them about 350 ms
 * apart (Notion allows about three a second), and retries 429 / 5xx with Retry-After or backoff.
 */
class NotionClient(
    client: OkHttpClient = OkHttpClient(),
    private val baseUrl: HttpUrl = "https://api.notion.com/v1/".toHttpUrl(),
    private val requestIntervalMillis: Long = 350,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    init {
        // The token only ever goes to api.notion.com, or to a loopback server in tests.
        require((baseUrl.isHttps && baseUrl.host == "api.notion.com") || baseUrl.host in setOf("localhost", "127.0.0.1", "::1"))
    }

    suspend fun get(token: String, path: String, query: Map<String, String?> = emptyMap()) = call(token, "GET", path, null, query)
    suspend fun post(token: String, path: String, body: JSONObject) = call(token, "POST", path, body.toJsonBody())
    suspend fun patch(token: String, path: String, body: JSONObject) = call(token, "PATCH", path, body.toJsonBody())
    suspend fun delete(token: String, path: String) = call(token, "DELETE", path, null)

    /** File Upload API's send step: multipart with the bytes in a part named "file". */
    suspend fun sendFile(token: String, uploadId: String, filename: String, contentType: String, bytes: ByteArray): JSONObject {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, bytes.toRequestBody(contentType.toMediaType()))
            .build()
        return call(token, "POST", "file_uploads/$uploadId/send", body)
    }

    private fun JSONObject.toJsonBody() = toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    private suspend fun call(token: String, method: String, path: String, body: RequestBody?, query: Map<String, String?> = emptyMap()): JSONObject =
        mutex.withLock {
            if (token.isBlank() || token.any { it == '\n' || it == '\r' }) throw NotionException(NotionFailure.Unauthorized)
            val url = baseUrl.newBuilder().addEncodedPathSegments(path).apply {
                query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
            }.build()
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer ${token.trim()}")
                .header("Notion-Version", API_VERSION)
                .header("Accept", "application/json")
                .method(method, body ?: if (method == "PATCH" || method == "POST") ByteArray(0).toRequestBody() else null)
                .build()
            for (attempt in 0..MAX_RETRIES) {
                val wait = requestIntervalMillis - (System.nanoTime() / 1_000_000 - lastRequestAt)
                if (wait > 0) pause(wait)
                lastRequestAt = System.nanoTime() / 1_000_000
                val response = try {
                    http.newCall(request).await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    if (attempt == MAX_RETRIES) throw NotionException(NotionFailure.Unavailable)
                    pause(backoff(attempt))
                    continue
                }
                var retryIn: Long? = null
                response.use {
                    val json = try { JSONObject(it.body.string()) } catch (_: JSONException) { null }
                    if (it.isSuccessful) return@withLock json ?: throw NotionException(NotionFailure.InvalidResponse, it.code)
                    val retryable = it.code in setOf(429, 500, 502, 503, 504, 529)
                    if (!retryable || attempt == MAX_RETRIES) throw NotionException(failureFor(it.code), it.code)
                    retryIn = it.header("Retry-After")?.toLongOrNull()?.takeIf { s -> s in 0..600 }?.let { s -> s * 1_000 } ?: backoff(attempt)
                }
                retryIn?.let { pause(it) }
            }
            throw NotionException(NotionFailure.Unavailable)
        }

    private fun backoff(attempt: Int) = minOf(1_000L shl attempt, 30_000L) + Random.nextLong(0, 251)

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
        })
    }

    companion object {
        const val API_VERSION = "2026-03-11"
        private const val MAX_RETRIES = 4

        fun failureFor(code: Int) = when (code) {
            400 -> NotionFailure.InvalidInput
            401 -> NotionFailure.Unauthorized
            403 -> NotionFailure.Forbidden
            404 -> NotionFailure.NotFound
            429, 529 -> NotionFailure.RateLimited
            else -> NotionFailure.Unavailable
        }

        private val ID = Regex("[0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /** Accepts a Notion URL or a bare ID; returns the dashed form. */
        fun extractId(value: String): String {
            val raw = ID.findAll(value.substringBefore('?')).lastOrNull()?.value ?: throw NotionException(NotionFailure.InvalidInput)
            val hex = raw.replace("-", "").lowercase()
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
        }
    }
}
