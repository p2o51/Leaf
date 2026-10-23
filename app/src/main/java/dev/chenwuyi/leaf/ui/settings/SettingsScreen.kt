package dev.chenwuyi.leaf.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chenwuyi.leaf.data.notion.DataSourceInfo

/** Quiet tool page: the Notion connection and where 按住说 transcribes. */
@Composable
fun SettingsScreen(vm: SettingsViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val step by vm.step.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "关闭") }
            Text("设置", style = MaterialTheme.typography.headlineSmall)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { Text("Notion", style = MaterialTheme.typography.titleLarge) }
                val c = connection
                if (c != null) {
                    item {
                        Section {
                            Text("${c.workspace} · ${c.databaseName}", style = MaterialTheme.typography.titleMedium)
                            Text(syncLine(c, status), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = vm::sync) { Text("立即同步") }
                                TextButton(onClick = vm::disconnect) { Text("断开连接") }
                            }
                            Text("断开只清掉这台设备上的副本，Notion 里的内容都在。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else when (val s = step) {
                    is ConnectStep.Token -> item { TokenStep(s, vm::discover) }
                    is ConnectStep.Pick -> {
                        item {
                            Text("选一个数据库（${s.workspace}）", style = MaterialTheme.typography.titleMedium)
                            Text("Leaf 会补上缺的字段（类型、卡片颜色、置顶），并在里面找或新建一行「涂鸦墙」。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(s.sources, key = { it.id }) { source -> SourceRow(source, enabled = !s.busy) { vm.connect(source) } }
                        item {
                            if (s.error != null) Text(s.error, color = MaterialTheme.colorScheme.error)
                            if (s.busy) Text("正在连接…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = vm::back, enabled = !s.busy) { Text("换一个 token") }
                        }
                    }
                }
                item { Text("语音转写", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) }
                item { RelaySection(vm) }
            }
        }
    }
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun TokenStep(step: ConnectStep.Token, onSubmit: (String) -> Unit) {
    var token by rememberSaveable { mutableStateOf("") }
    Section {
        Text("连接你的 Notion 数据库", style = MaterialTheme.typography.titleMedium)
        Text(
            "1. 在 notion.so/profile/integrations 新建一个内部集成，打开「读取内容」「更新内容」「插入内容」。\n" +
                "2. 在要用的数据库右上角 ··· → 连接，选这个集成。\n" +
                "3. 把集成的密钥贴在下面。它只存在这台设备的加密存储里。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 16.dp, vertical = 14.dp)) {
            if (token.isEmpty()) Text("ntn_…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                token,
                { token = it },
                Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
        if (step.error != null) Text(step.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Button(onClick = { onSubmit(token) }, enabled = token.isNotBlank() && !step.busy) { Text(if (step.busy) "正在检查…" else "下一步") }
    }
}

@Composable
private fun SourceRow(source: DataSourceInfo, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.titleMedium)
            Text("${source.properties.size} 个字段", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RelaySection(vm: SettingsViewModel) {
    val host by vm.relayHost.collectAsStateWithLifecycle()
    val error by vm.relayError.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    Section {
        val saved = host
        if (saved != null) {
            Text("经 Relay（$saved）", style = MaterialTheme.typography.titleMedium)
            Text("按住说的录音经 Relay 交给 ElevenLabs 转写，密钥只在 Relay 上。断网时改用这台设备的系统识别。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = vm::clearRelay) { Text("不用 Relay") }
        } else {
            Text("现在用这台设备的系统识别", style = MaterialTheme.typography.titleMedium)
            Text(
                "想用 ElevenLabs 转写（中英混说更准），把 Sheaf「接力」里的连接串贴在下面。它只存在这台设备的加密存储里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 16.dp, vertical = 14.dp)) {
                if (text.isEmpty()) Text("https://…/#token=…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    text,
                    { text = it },
                    Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { if (vm.saveRelay(text)) text = "" }, enabled = text.isNotBlank()) { Text("保存") }
        }
    }
}
