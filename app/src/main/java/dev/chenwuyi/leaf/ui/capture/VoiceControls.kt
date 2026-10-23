package dev.chenwuyi.leaf.ui.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.chenwuyi.leaf.data.voice.VoiceCopy
import dev.chenwuyi.leaf.ui.theme.LeafMotion

fun Context.canRecord(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** The voice sheet's bottom row: one quiet line, the big hold-to-talk mic, and 记下. */
@Composable
fun VoiceControls(voice: VoiceSession, canSave: Boolean, onSave: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) voice.denied()
    }
    val state = voice.state
    val holding = state is VoiceState.Listening
    val line = when (state) {
        is VoiceState.Idle -> if (!context.canRecord() && state.line == VoiceCopy.Ready) VoiceCopy.permission(voice.route()) else state.line
        is VoiceState.Listening -> VoiceCopy.Holding
        is VoiceState.Finishing -> VoiceCopy.Finishing
        is VoiceState.Heard -> state.line
    }
    val scale by animateFloatAsState(if (holding) 1.12f else 1f, LeafMotion.fast(), label = "mic")
    val corner by animateDpAsState(if (holding) 30.dp else 46.dp, LeafMotion.fast(), label = "micShape")
    val container by animateColorAsState(
        if (holding) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primaryContainer,
        LeafMotion.fast(),
        label = "micColor",
    )
    val content = if (holding) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimaryContainer

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            Box(
                Modifier
                    .size(92.dp)
                    .scale(scale)
                    .clip(RoundedCornerShape(corner))
                    .background(container)
                    .semantics {
                        role = Role.Button
                        contentDescription = "按住说话，松开结束"
                    }
                    .holdToTalk(
                        onHoldStart = {
                            if (context.canRecord()) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                voice.press()
                            } else {
                                ask.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onHoldEnd = voice::lift,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Mic, contentDescription = null, Modifier.size(38.dp), tint = content)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Button(onClick = onSave, enabled = canSave && state !is VoiceState.Listening && state !is VoiceState.Finishing) { Text("记下") }
            }
        }
    }
}
