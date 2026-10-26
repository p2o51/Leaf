package dev.chenwuyi.leaf.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The ElevenLabs API key 按住说 calls Scribe with, straight from the phone. It replaced Sheaf's Relay
 * connection string: the Relay no longer carries speech, so Leaf holds its own key.
 */
object ElevenLabsKey {
    private val Pattern = Regex("^[A-Za-z0-9_-]{20,200}$")

    /** The key as pasted, trimmed; null when it cannot be one (spaces inside, a URL, too short). */
    fun parse(input: String?): String? = input?.trim()?.takeIf { Pattern.matches(it) }

    /** What settings shows instead of the key: its first characters and last four. */
    fun masked(key: String): String = if (key.length <= 10) "…" else key.take(3) + "…" + key.takeLast(4)
}

interface SpeechKeyStore {
    /** The masked key while one is saved; readable without touching the Keystore. */
    val masked: StateFlow<String?>
    fun read(): String?
    fun save(key: String)
    fun clear()
}

class VaultSpeechKeyStore(context: Context, private val vault: KeystoreTokenVault = KeystoreTokenVault(context)) : SpeechKeyStore {
    private val prefs = context.applicationContext.getSharedPreferences("leaf_speech", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getString("masked", null))
    override val masked: StateFlow<String?> = state.asStateFlow()

    init {
        // The Relay connection string the previous version kept is no use any more; drop it.
        val old = context.applicationContext.getSharedPreferences("leaf_relay", Context.MODE_PRIVATE)
        if (old.contains("host")) {
            runCatching { vault.delete("relay") }
            old.edit().clear().apply()
        }
    }

    override fun read(): String? = runCatching { vault.read(ACCOUNT) }.getOrNull()

    override fun save(key: String) {
        vault.write(ACCOUNT, key)
        prefs.edit().putString("masked", ElevenLabsKey.masked(key)).apply()
        state.value = ElevenLabsKey.masked(key)
    }

    override fun clear() {
        vault.delete(ACCOUNT)
        prefs.edit().clear().apply()
        state.value = null
    }

    private companion object {
        const val ACCOUNT = "elevenlabs"
    }
}

class MemorySpeechKeyStore(private var saved: String? = null) : SpeechKeyStore {
    private val state = MutableStateFlow(saved?.let(ElevenLabsKey::masked))
    override val masked: StateFlow<String?> = state.asStateFlow()
    override fun read(): String? = saved
    override fun save(key: String) { saved = key; state.value = ElevenLabsKey.masked(key) }
    override fun clear() { saved = null; state.value = null }
}
