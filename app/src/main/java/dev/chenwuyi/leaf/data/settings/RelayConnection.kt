package dev.chenwuyi.leaf.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Sheaf's Relay (a Cloudflare Worker), reached with the same connection string Sheaf uses:
 * `https://host/#token=…`. Leaf only uses it for speech to text; the ElevenLabs key stays on the Worker.
 */
class RelayConnection(val baseUrl: String, val token: String) {
    val host: String get() = baseUrl.toHttpUrlOrNull()?.host ?: baseUrl

    override fun toString(): String = "RelayConnection($baseUrl, token=…)"

    companion object {
        private val TokenPattern = Regex("^[A-Za-z0-9._~-]{16,256}$")
        private val LoopbackHosts = setOf("127.0.0.1", "localhost", "::1")

        /** Same rules as Sheaf's parser (contracts/relay/reference/connection-string.mjs); null rejects it. */
        fun parse(input: String?): RelayConnection? {
            val text = input?.trim() ?: return null
            val hash = text.indexOf('#')
            if (hash == -1) return null
            val base = text.substring(0, hash)
            val fragment = text.substring(hash + 1)
            if (!fragment.startsWith("token=")) return null
            val token = fragment.removePrefix("token=")
            if (!TokenPattern.matches(token)) return null
            val url = base.toHttpUrlOrNull() ?: return null
            // Plain http only for this machine (wrangler dev); never on a network.
            if (url.scheme == "http" && url.host !in LoopbackHosts) return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            if ('?' in base || url.encodedPath != "/") return null
            return RelayConnection(base.trimEnd('/'), token)
        }
    }
}

interface RelayStore {
    /** The Relay's host while one is saved; readable without touching the Keystore. */
    val host: StateFlow<String?>
    fun read(): RelayConnection?
    fun save(connection: RelayConnection)
    fun clear()
}

class VaultRelayStore(context: Context, private val vault: KeystoreTokenVault = KeystoreTokenVault(context)) : RelayStore {
    private val prefs = context.applicationContext.getSharedPreferences("leaf_relay", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getString("host", null))
    override val host: StateFlow<String?> = state.asStateFlow()

    override fun read(): RelayConnection? =
        runCatching { vault.read(ACCOUNT) }.getOrNull()?.let { saved ->
            val (base, token) = saved.split('\n', limit = 2).takeIf { it.size == 2 } ?: return null
            RelayConnection(base, token)
        }

    override fun save(connection: RelayConnection) {
        vault.write(ACCOUNT, connection.baseUrl + "\n" + connection.token)
        prefs.edit().putString("host", connection.host).apply()
        state.value = connection.host
    }

    override fun clear() {
        vault.delete(ACCOUNT)
        prefs.edit().clear().apply()
        state.value = null
    }

    private companion object {
        const val ACCOUNT = "relay"
    }
}

class MemoryRelayStore(private var saved: RelayConnection? = null) : RelayStore {
    private val state = MutableStateFlow(saved?.host)
    override val host: StateFlow<String?> = state.asStateFlow()
    override fun read(): RelayConnection? = saved
    override fun save(connection: RelayConnection) { saved = connection; state.value = connection.host }
    override fun clear() { saved = null; state.value = null }
}
