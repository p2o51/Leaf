package dev.chenwuyi.leaf.data.settings

import android.content.Context
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
    fun token(): String?
    fun save(connection: Connection, token: String)
    fun clear()
}

class PrefsConnectionStore(context: Context, private val vault: KeystoreTokenVault = KeystoreTokenVault(context)) : ConnectionStore {
    private val prefs = context.applicationContext.getSharedPreferences("leaf_connection", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    override val connection: StateFlow<Connection?> = state.asStateFlow()

    override fun token(): String? = runCatching { vault.read(ACCOUNT) }.getOrNull()

    override fun save(connection: Connection, token: String) {
        vault.write(ACCOUNT, token)
        prefs.edit()
            .putString("workspace", connection.workspace)
            .putString("dataSourceId", connection.dataSourceId)
            .putString("databaseName", connection.databaseName)
            .putString("titleProperty", connection.titleProperty)
            .putString("wallPageId", connection.wallPageId)
            .apply()
        state.value = connection
    }

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
class MemoryConnectionStore(initial: Connection? = null, private var secret: String? = null) : ConnectionStore {
    private val state = MutableStateFlow(initial)
    override val connection: StateFlow<Connection?> = state.asStateFlow()
    override fun token(): String? = secret
    override fun save(connection: Connection, token: String) { secret = token; state.value = connection }
    override fun clear() { secret = null; state.value = null }
}
