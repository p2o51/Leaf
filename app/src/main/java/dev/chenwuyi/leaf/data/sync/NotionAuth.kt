package dev.chenwuyi.leaf.data.sync

import dev.chenwuyi.leaf.data.notion.NotionCredential
import dev.chenwuyi.leaf.data.notion.NotionException
import dev.chenwuyi.leaf.data.notion.NotionFailure
import dev.chenwuyi.leaf.data.notion.NotionOAuthRelay
import dev.chenwuyi.leaf.data.settings.ConnectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps an OAuth connection's access token fresh. Notion turns an expired one down with 401; the relay
 * then trades the refresh token for a new pair. Each refresh rotates the refresh token too, so only one
 * runs at a time, and a caller holding a token that was already replaced just gets the new one.
 */
class NotionAuth(private val store: ConnectionStore, private val relay: NotionOAuthRelay?) {
    private val mutex = Mutex()

    /**
     * A token to retry with after [stale] was turned down, or null when there is none: a pasted
     * integration token cannot be refreshed, and a refresh token Notion no longer takes means signing in again.
     */
    suspend fun refreshed(stale: String): String? = mutex.withLock {
        val current = withContext(Dispatchers.IO) { store.credential() } ?: return@withLock null
        if (current.accessToken != stale) return@withLock current.accessToken
        val oauth = current as? NotionCredential.OAuth ?: return@withLock null
        val tokens = try {
            relay?.refresh(oauth.refreshToken) ?: return@withLock null
        } catch (failure: NotionException) {
            if (failure.reason == NotionFailure.Unauthorized) return@withLock null
            throw failure
        }
        withContext(Dispatchers.IO) { store.replaceCredential(NotionCredential.OAuth(tokens.accessToken, tokens.refreshToken)) }
        tokens.accessToken
    }

    /** Runs [block] with the current token, and once more with a refreshed one if Notion answers 401. */
    suspend fun <T> withToken(token: String, block: suspend (String) -> T): T = try {
        block(token)
    } catch (failure: NotionException) {
        if (failure.reason != NotionFailure.Unauthorized) throw failure
        block(refreshed(token) ?: throw failure)
    }
}
