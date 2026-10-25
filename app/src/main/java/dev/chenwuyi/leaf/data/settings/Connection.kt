package dev.chenwuyi.leaf.data.settings

import android.content.Context
import dev.chenwuyi.leaf.data.notion.NotionCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which database Leaf writes to. Non-secret, so plain preferences; the token lives in [TokenVault]. */
data class Connection(
    val workspace: String,
    val dataSourceId: String,
    val databaseName: String,
    val titleProperty: String,
    val wallPageId: String,
)

interface ConnectionStore {
    val connection: StateFlow<Connection?>
    /** Null when there is none, or when it can no longer be read (restored backup, broken key). */
    fun credential(): NotionCredential?
    fun save(connection: Connection, credential: NotionCredential)
    /**
     * A refresh rotated the OAuth pair: swap what the vault holds, but only while it still holds [stale].
     * A disconnect or a new sign-in during the refresh wins; false then, and nothing is written.
     */
    fun replaceCredential(stale: NotionCredential, fresh: NotionCredential): Boolean
    fun clear()

    fun token(): String? = credential()?.accessToken
    fun save(connection: Connection, token: String) = save(connection, NotionCredential.Integration(token))
}

class PrefsConnectionStore(context: Context, private val vault: KeystoreTokenVault = KeystoreTokenVault(context)) : ConnectionStore {
    private val prefs = context.applicationContext.getSharedPreferences("leaf_connection", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    override val connection: StateFlow<Connection?> = state.asStateFlow()

    override fun credential(): NotionCredential? = runCatching { vault.read(ACCOUNT)?.let(NotionCredential::decode) }.getOrNull()

    @Synchronized
    override fun replaceCredential(stale: NotionCredential, fresh: NotionCredential): Boolean {
        if (state.value == null || credential() != stale) return false
        vault.write(ACCOUNT, fresh.encode())
        return true
    }

    @Synchronized
    override fun save(connection: Connection, credential: NotionCredential) {
        vault.write(ACCOUNT, credential.encode())
        prefs.edit()
            .putString("workspace", connection.workspace)
            .putString("dataSourceId", connection.dataSourceId)
            .putString("databaseName", connection.databaseName)
            .putString("titleProperty", connection.titleProperty)
            .putString("wallPageId", connection.wallPageId)
            .apply()
        state.value = connection
    }

    @Synchronized
    override fun clear() {
        vault.delete(ACCOUNT)
        prefs.edit().clear().apply()
        state.value = null
    }

    private fun load(): Connection? {
        val ds = prefs.getString("dataSourceId", null) ?: return null
        return Connection(
            workspace = prefs.getString("workspace", "Notion").orEmpty(),
            dataSourceId = ds,
            databaseName = prefs.getString("databaseName", "").orEmpty(),
            titleProperty = prefs.getString("titleProperty", "Name").orEmpty(),
            wallPageId = prefs.getString("wallPageId", null) ?: return null,
        )
    }

    private companion object {
        const val ACCOUNT = "notion"
    }
}

/** For tests and previews. */
class MemoryConnectionStore(initial: Connection? = null, private var secret: NotionCredential? = null) : ConnectionStore {
    constructor(initial: Connection?, token: String) : this(initial, NotionCredential.Integration(token))

    private val state = MutableStateFlow(initial)
    override val connection: StateFlow<Connection?> = state.asStateFlow()
    override fun credential(): NotionCredential? = secret
    @Synchronized
    override fun replaceCredential(stale: NotionCredential, fresh: NotionCredential): Boolean {
        if (state.value == null || secret != stale) return false
        secret = fresh
        return true
    }
    @Synchronized override fun save(connection: Connection, credential: NotionCredential) { secret = credential; state.value = connection }
    @Synchronized override fun clear() { secret = null; state.value = null }
}
