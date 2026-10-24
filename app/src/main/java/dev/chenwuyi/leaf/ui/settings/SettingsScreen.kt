package dev.chenwuyi.leaf.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chenwuyi.leaf.BuildConfig
import dev.chenwuyi.leaf.CrashLog
import dev.chenwuyi.leaf.data.settings.Connection
import dev.chenwuyi.leaf.data.sync.SyncStatus

/**
 * A quiet tool page laid out like Sheaf's settings: back in a bar, 设置 set large at the top (the bar
 * takes the title over once it scrolls away), then labelled groups of segmented rows, each row's value
 * at its end. Notion, 按住说 and 关于.
 */
@Composable
fun SettingsScreen(vm: SettingsViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val step by vm.step.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val handoff = with(LocalDensity.current) { 12.dp.toPx() to 36.dp.toPx() }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 8.dp).column(bleed = 8.dp).padding(end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
            Spacer(Modifier.width(4.dp))
            Text(
                "设置",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    // Follows the scroll position itself, as in Sheaf, so there is no timed motion here.
                    .graphicsLayer { alpha = ((scroll.value - handoff.first) / handoff.second).coerceIn(0f, 1f) }
                    .clearAndSetSemantics {},
            )
        }
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(scroll).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp)) {
            Column(Modifier.column()) {
                Text("设置", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp).semantics { heading() })
                NotionGroup(vm, connection, status, step)
                VoiceGroup(vm)
                AboutGroup()
            }
        }
    }
}

// On wide windows the page keeps one centred 720dp column, as Sheaf's settings do.
private val ContentMaxWidth = 720.dp

private fun Modifier.column(bleed: Dp = 0.dp): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = ContentMaxWidth + bleed * 2).fillMaxWidth()

@Composable
private fun NotionGroup(vm: SettingsViewModel, connection: Connection?, status: SyncStatus, step: ConnectStep) {
    if (connection != null) {
        SettingsSection("Notion") {
            SettingsRow(Icons.Rounded.Storage, connection.databaseName, detail = connection.workspace)
            ActionRow(Icons.Rounded.Sync, "立即同步", value = syncLine(connection, status), onClick = vm::sync)
            ActionRow(Icons.Rounded.LinkOff, "断开连接", titleColor = MaterialTheme.colorScheme.error, onClick = vm::disconnect)
        }
        SettingsHint("断开只清掉这台设备上的副本，Notion 里的内容都在。")
        return
    }
    when (step) {
        is ConnectStep.Token -> {
            var token by rememberSaveable { mutableStateOf("") }
            SettingsSection("Notion") {
                PanelBlock("集成密钥") {
                    SecretField(token, { token = it }, "ntn_…")
                    Button(onClick = { vm.discover(token) }, enabled = token.isNotBlank() && !step.busy) { Text(if (step.busy) "正在检查…" else "下一步") }
                }
            }
            if (step.error != null) SettingsHint(step.error, MaterialTheme.colorScheme.error)
            SettingsHint("在 notion.so/profile/integrations 新建内部集成，在数据库的 ··· › 连接 里选上它，再把密钥贴在这里。密钥只存在这台设备的加密存储里。")
        }
        is ConnectStep.Pick -> {
            SettingsSection("选一个数据库 · ${step.workspace}") {
                step.sources.forEach { source ->
                    ActionRow(Icons.Rounded.Storage, source.name, value = "${source.properties.size} 个字段", enabled = !step.busy) { vm.connect(source) }
                }
                ActionRow(Icons.AutoMirrored.Rounded.ArrowBack, "换一个密钥", enabled = !step.busy, onClick = vm::back)
            }
            if (step.error != null) SettingsHint(step.error, MaterialTheme.colorScheme.error)
            SettingsHint(if (step.busy) "正在连接…" else "Leaf 会补上缺的字段，并在里面找或新建一行「涂鸦墙」。")
        }
    }
}

@Composable
private fun VoiceGroup(vm: SettingsViewModel) {
    val host by vm.relayHost.collectAsStateWithLifecycle()
    val error by vm.relayError.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    val saved = host
    SettingsSection("按住说") {
        SettingsRow(Icons.Rounded.Mic, "转写", value = if (saved != null) "ElevenLabs · 经 Relay" else "本机识别")
        if (saved != null) {
            SettingsRow(Icons.Rounded.CloudSync, "Relay", value = saved)
            ActionRow(Icons.Rounded.LinkOff, "不用 Relay", titleColor = MaterialTheme.colorScheme.error, onClick = vm::clearRelay)
        } else {
            PanelBlock("Sheaf「接力」里的连接串") {
                SecretField(text, { text = it }, "https://…/#token=…")
                Button(onClick = { vm.saveRelay(text) { text = "" } }, enabled = text.isNotBlank()) { Text("保存") }
            }
        }
    }
    error?.let { SettingsHint(it, MaterialTheme.colorScheme.error) }
    SettingsHint(if (saved != null) "断网时改用这台设备的系统识别。" else "贴上连接串后改用 ElevenLabs 转写，中英混说更准。")
}

@Composable
private fun AboutGroup() {
    val context = LocalContext.current
    var crash by remember { mutableStateOf(CrashLog.read(context)) }
    SettingsSection("关于") {
        SettingsRow(Icons.Rounded.Info, "版本", value = "${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_HASH}")
        ActionRow(Icons.Rounded.BugReport, "复制崩溃日志", value = if (crash == null) "没有" else "有一份", enabled = crash != null) {
            crash?.let { copy(context, it) }
            CrashLog.clear(context)
            crash = null
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Leaf crash", text))
}
