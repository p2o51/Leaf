package dev.chenwuyi.leaf.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.ui.capture.holdToTalk
import dev.chenwuyi.leaf.ui.theme.LeafMotion

// No hint under the labels: menus carry no explanations (51's Do/Don't, 2026-10-24). The colour already
// tells the scribbles (primary) from the essay (tertiary).
enum class AddKind(val label: String, val icon: ImageVector) {
    Photo("拍照", Icons.Rounded.PhotoCamera),
    Voice("语音", Icons.Rounded.Mic),
    Text("文本", Icons.AutoMirrored.Rounded.ShortText),
    Essay("随笔", Icons.Rounded.Edit),
}

/**
 * The one + button. Tap opens the choice of what to make (photo, voice and text all become scribbles;
 * 随笔 is a whole essay). Holding it skips the menu and records until the finger lifts: [onHoldStart]
 * opens the voice sheet and starts listening, [onHoldEnd] ends the take.
 */
@Composable
fun AddMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPick: (AddKind) -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(expanded, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim)
                    .clickable(interactionSource = null, indication = null) { onExpandedChange(false) },
            )
        }
        Column(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AddKind.entries.forEachIndexed { index, kind ->
                AnimatedVisibility(
                    expanded,
                    enter = fadeIn() + scaleIn(LeafMotion.fast(), initialScale = 0.8f) + slideInVertically(LeafMotion.expressive()) { it / 2 * (AddKind.entries.size - index) },
                    exit = fadeOut() + scaleOut(targetScale = 0.8f) + slideOutVertically { it / 2 },
                ) {
                    MenuItem(kind) { onExpandedChange(false); onPick(kind) }
                }
            }
            val haptics = LocalHapticFeedback.current
            val rotation by animateFloatAsState(if (expanded) 45f else 0f, LeafMotion.fast(), label = "plus")
            val corner by androidx.compose.animation.core.animateDpAsState(if (expanded) 38.dp else 26.dp, LeafMotion.fast(), label = "corner")
            val container = if (expanded) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.tertiaryContainer
            val content = if (expanded) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onTertiaryContainer
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(76.dp)
                    .clip(RoundedCornerShape(corner))
                    .background(container)
                    .semantics {
                        role = Role.Button
                        onClick("新建") { onExpandedChange(!expanded); true }
                        // Without a finger to hold, a long press only opens the voice sheet; its mic does the rest.
                        onLongClick("语音") { onExpandedChange(false); onPick(AddKind.Voice); true }
                    }
                    // Keyboard and D-pad: the custom gesture below handles touch only, so Enter opens the menu.
                    .onKeyEvent { event ->
                        val activates = event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.DirectionCenter || event.key == Key.Spacebar
                        if (activates && event.type == KeyEventType.KeyUp) onExpandedChange(!expanded)
                        activates
                    }
                    .focusable()
                    .holdToTalk(
                        onHoldStart = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onExpandedChange(false)
                            onHoldStart()
                        },
                        onHoldEnd = onHoldEnd,
                        onTap = { onExpandedChange(!expanded) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "新建：点一下选，长按直接说", Modifier.size(34.dp).rotate(rotation), tint = content)
            }
        }
    }
}

@Composable
private fun MenuItem(kind: AddKind, onClick: () -> Unit) {
    val essay = kind == AddKind.Essay
    val bg: Color = if (essay) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
    val fg: Color = if (essay) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Row(
        Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(kind.icon, contentDescription = null, tint = fg)
        Text(kind.label, style = MaterialTheme.typography.titleMedium, color = fg)
    }
}
