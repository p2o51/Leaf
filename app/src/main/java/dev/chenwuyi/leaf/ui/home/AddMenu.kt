package dev.chenwuyi.leaf.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.R
import dev.chenwuyi.leaf.ui.capture.holdToTalk
import dev.chenwuyi.leaf.ui.theme.LeafMotion

// No hint under the labels: menus carry no explanations (51's Do/Don't, 2026-10-24). The colour already
// tells the scribbles (primary) from the essay (tertiary). The names are the ones the launcher shortcuts,
// the widget and the quick settings tile use too, so each way in is called the same everywhere.
enum class AddKind(val label: String, @DrawableRes val icon: Int) {
    Photo("拍照", R.drawable.ic_sym_photo_camera),
    Voice("说一句", R.drawable.ic_sym_mic),
    Text("写一句", R.drawable.ic_sym_short_text),
    Essay("随笔", R.drawable.ic_sym_essay),
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
        AnimatedVisibility(expanded, enter = fadeIn(LeafMotion.effects()), exit = fadeOut(LeafMotion.effects())) {
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
                    // Home is the lively zone: the pills spring in; every fade is on effects.
                    enter = fadeIn(LeafMotion.effects()) + scaleIn(LeafMotion.livelyFast(), initialScale = 0.8f) + slideInVertically(LeafMotion.lively()) { it / 2 * (AddKind.entries.size - index) },
                    exit = fadeOut(LeafMotion.effects()) + scaleOut(LeafMotion.livelyFast(), targetScale = 0.8f) + slideOutVertically(LeafMotion.lively()) { it / 2 },
                ) {
                    MenuItem(kind) { onExpandedChange(false); onPick(kind) }
                }
            }
            val haptics = LocalHapticFeedback.current
            val rotation by animateFloatAsState(if (expanded) 45f else 0f, LeafMotion.fast(), label = "plus")
            val corner by androidx.compose.animation.core.animateDpAsState(if (expanded) 38.dp else 26.dp, LeafMotion.fast(), label = "corner")
            // The MD3E morph (round to rounded-square, a 45° turn) stays; the colour now blends on effects
            // instead of swapping while the shape is still moving.
            val container by animateColorAsState(if (expanded) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.tertiaryContainer, LeafMotion.effects(), label = "plus fill")
            val content by animateColorAsState(if (expanded) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onTertiaryContainer, LeafMotion.effects(), label = "plus ink")
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
                        onLongClick(AddKind.Voice.label) { onExpandedChange(false); onPick(AddKind.Voice); true }
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
                Icon(painterResource(R.drawable.ic_sym_add), contentDescription = "新建：点一下选，长按直接说", Modifier.size(34.dp).rotate(rotation), tint = content)
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
        Icon(painterResource(kind.icon), contentDescription = null, tint = fg)
        Text(kind.label, style = MaterialTheme.typography.titleMedium, color = fg)
    }
}
