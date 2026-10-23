package dev.chenwuyi.leaf.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chenwuyi.leaf.data.notion.DataSourceInfo
import dev.chenwuyi.leaf.data.notion.NotionException
import dev.chenwuyi.leaf.data.settings.Connection
import dev.chenwuyi.leaf.data.sync.NotionSetup
import dev.chenwuyi.leaf.data.sync.SyncStatus
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The connect flow: paste a token, pick a database, done. */
sealed interface ConnectStep {
    data class Token(val busy: Boolean = false, val error: String? = null) : ConnectStep
    data class Pick(val token: String, val workspace: String, val sources: List<DataSourceInfo>, val busy: Boolean = false, val error: String? = null) : ConnectStep
}

class SettingsViewModel(
    private val setup: NotionSetup,
    val connection: StateFlow<Connection?>,
    val status: StateFlow<SyncStatus>,
    private val syncNow: () -> Unit,
    private val afterDisconnect: suspend () -> Unit,
) : ViewModel() {
    private val stepState = MutableStateFlow<ConnectStep>(ConnectStep.Token())
    val step: StateFlow<ConnectStep> = stepState.asStateFlow()

    fun discover(token: String) {
        if (token.isBlank()) return
        stepState.value = ConnectStep.Token(busy = true)
        viewModelScope.launch {
            stepState.value = try {
                val (workspace, sources) = setup.discover(token)
                if (sources.isEmpty()) ConnectStep.Token(error = "这个集成还看不到任何数据库：在 Notion 里打开数据库，右上角 ··· → 连接 → 选你的集成")
                else ConnectStep.Pick(token.trim(), workspace, sources)
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
                setup.connect(pick.token, pick.workspace, source)
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
