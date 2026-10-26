package dev.chenwuyi.leaf.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chenwuyi.leaf.data.notion.DataSourceInfo
import dev.chenwuyi.leaf.data.notion.NotionCredential
import dev.chenwuyi.leaf.data.notion.NotionException
import dev.chenwuyi.leaf.data.notion.NotionOAuthResult
import dev.chenwuyi.leaf.data.notion.NotionOAuthSession
import dev.chenwuyi.leaf.data.settings.Appearance
import dev.chenwuyi.leaf.data.settings.AppearanceStore
import dev.chenwuyi.leaf.data.settings.Connection
import dev.chenwuyi.leaf.data.settings.ElevenLabsKey
import dev.chenwuyi.leaf.data.settings.MemoryAppearanceStore
import dev.chenwuyi.leaf.data.settings.MemorySpeechKeyStore
import dev.chenwuyi.leaf.data.settings.SpeechKeyStore
import dev.chenwuyi.leaf.data.sync.NotionSetup
import dev.chenwuyi.leaf.data.sync.SyncStatus
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The connect flow: sign in with Notion (or paste a token), pick a database, done. */
sealed interface ConnectStep {
    data class Token(val busy: Boolean = false, val error: String? = null) : ConnectStep
    data class Pick(val credential: NotionCredential, val workspace: String, val sources: List<DataSourceInfo>, val busy: Boolean = false, val error: String? = null) : ConnectStep
}

class SettingsViewModel(
    private val setup: NotionSetup,
    val connection: StateFlow<Connection?>,
    val status: StateFlow<SyncStatus>,
    private val syncNow: () -> Unit,
    private val afterDisconnect: suspend () -> Unit,
    private val speechKey: SpeechKeyStore = MemorySpeechKeyStore(),
    private val oauth: NotionOAuthSession = NotionOAuthSession(),
    private val appearanceStore: AppearanceStore = MemoryAppearanceStore(),
) : ViewModel() {
    private val stepState = MutableStateFlow<ConnectStep>(ConnectStep.Token())
    val step: StateFlow<ConnectStep> = stepState.asStateFlow()

    /** Step 1 leads with 用 Notion 登录; the integration token stays as the fallback. */
    val signInAvailable: Boolean get() = setup.signInAvailable

    init {
        // The browser comes back through NotionOAuthActivity, which leaves the callback in [oauth].
        viewModelScope.launch { oauth.result.collect { if (it != null) finishSignIn() } }
    }

    /** The relay's start page, for settings to open in the browser. */
    fun signInUrl(): String {
        stepState.value = ConnectStep.Token()
        return oauth.start()
    }

    private fun finishSignIn() {
        when (val result = oauth.consume()) {
            null, NotionOAuthResult.Cancelled -> Unit
            is NotionOAuthResult.Failed -> stepState.value = ConnectStep.Token(error = result.message)
            is NotionOAuthResult.Code -> {
                stepState.value = ConnectStep.Token(busy = true)
                viewModelScope.launch {
                    stepState.value = try {
                        val credential = setup.exchange(result.code)
                        val (workspace, sources) = setup.discover(credential.accessToken)
                        if (sources.isEmpty()) ConnectStep.Token(error = "这次授权没有勾选数据库：再登录一次，在 Notion 的授权页里勾上要用的数据库")
                        else ConnectStep.Pick(credential, workspace, sources)
                    } catch (failure: NotionException) {
                        ConnectStep.Token(error = failure.userMessage)
                    } catch (_: IOException) {
                        ConnectStep.Token(error = "连不上 Notion，检查一下网络")
                    }
                }
            }
        }
    }

    /** The ElevenLabs key 按住说 uses, masked; null when none is saved. */
    val speechKeyMasked: StateFlow<String?> = speechKey.masked
    private val speechKeyErrorState = MutableStateFlow<String?>(null)
    val speechKeyError: StateFlow<String?> = speechKeyErrorState.asStateFlow()

    /** Takes a pasted ElevenLabs API key; [onSaved] runs only once it is in the vault. */
    fun saveSpeechKey(text: String, onSaved: () -> Unit) {
        val key = ElevenLabsKey.parse(text)
        if (key == null) {
            speechKeyErrorState.value = "这不像 ElevenLabs 的密钥：在 elevenlabs.io › Developers › API Keys 里复制，形如 sk_…"
            return
        }
        speechKeyErrorState.value = null
        viewModelScope.launch {
            // The vault writes to disk through the Keystore; a full disk or a broken key is a line, not a crash.
            val saved = withContext(Dispatchers.IO) { runCatching { speechKey.save(key) } }
            if (saved.isSuccess) {
                onSaved()
            } else {
                speechKeyErrorState.value = "没存上，稍后再试一次"
            }
        }
    }

    fun clearSpeechKey() {
        speechKeyErrorState.value = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { speechKey.clear() } }
        }
    }

    val appearance: StateFlow<Appearance> = appearanceStore.appearance

    fun setAppearance(next: Appearance) = appearanceStore.set(next)

    fun discover(token: String) {
        if (token.isBlank()) return
        stepState.value = ConnectStep.Token(busy = true)
        viewModelScope.launch {
            stepState.value = try {
                val (workspace, sources) = setup.discover(token)
                if (sources.isEmpty()) ConnectStep.Token(error = "这个集成还看不到任何数据库：在 Notion 里打开数据库，右上角 ··· → 连接 → 选你的集成")
                else ConnectStep.Pick(NotionCredential.Integration(token.trim()), workspace, sources)
            } catch (failure: NotionException) {
                ConnectStep.Token(error = failure.userMessage)
            } catch (_: IOException) {
                ConnectStep.Token(error = "连不上 Notion，检查一下网络")
            }
        }
    }

    fun connect(source: DataSourceInfo) {
        val pick = stepState.value as? ConnectStep.Pick ?: return
        stepState.value = pick.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                setup.connect(pick.credential, pick.workspace, source)
                stepState.value = ConnectStep.Token()
            } catch (failure: NotionException) {
                stepState.update { pick.copy(busy = false, error = failure.userMessage) }
            } catch (_: IOException) {
                stepState.update { pick.copy(busy = false, error = "连不上 Notion，检查一下网络") }
            }
        }
    }

    fun back() { stepState.value = ConnectStep.Token() }

    fun sync() = syncNow()

    fun disconnect() {
        viewModelScope.launch {
            setup.disconnect()
            afterDisconnect()
        }
    }
}

/** One line for the editor's top bar and the settings page. */
fun syncLine(connection: Connection?, status: SyncStatus): String = when {
    connection == null -> "示例数据，还没连 Notion"
    status is SyncStatus.Running -> "同步中…"
    status is SyncStatus.Waiting -> status.message
    status is SyncStatus.NeedsAttention -> status.message
    else -> "已同步到 Notion"
}
