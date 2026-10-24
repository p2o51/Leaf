package dev.chenwuyi.leaf.ui.capture

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.voice.joinTranscript
import dev.chenwuyi.leaf.ui.component.ColorDots
import dev.chenwuyi.leaf.ui.component.PictureView
import dev.chenwuyi.leaf.ui.theme.LeafMotion
import kotlinx.coroutines.delay

/** What the bottom sheet is capturing. Voice is a scribble too: its words land in the same field, to edit before saving. */
sealed interface Capture {
    data object Text : Capture
    data object Voice : Capture
    data class Photo(val picture: Picture) : Capture
}

/** One sheet for every way of adding a scribble: a line of text, a colour, and for photos the picture and a caption. */
@Composable
fun CaptureSheet(capture: Capture?, voice: VoiceSession, onDismiss: () -> Unit, onSave: (text: String, color: NotionColor, photo: Picture?) -> Unit) {
    // After 记下 the sheet stays a moment with a tick and 记下了, then slides away (Paw's SheetFinisher),
    // instead of vanishing the instant the scribble is saved. The scribble itself is saved at once.
    // [finishing] holds the sheet up for the pause; [done] keeps the tick shown through the slide away
    // too, so the form (which would take focus and bring the keyboard back) never returns as it leaves.
    var finishing by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(finishing) {
        if (finishing) {
            delay(LeafMotion.SheetFinishMillis)
            finishing = false
        }
    }
    // A new capture opens on the form, also when it comes during the pause.
    if (capture != null && (done || finishing)) {
        done = false
        finishing = false
    }
    val visible = capture != null || finishing
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim).clickable(interactionSource = null, indication = null, onClick = onDismiss))
        }
        AnimatedVisibility(
            visible,
            // Tablet windows keep the sheet at a writing width instead of stretching it edge to edge.
            Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp),
            enter = slideInVertically(LeafMotion.expressive()) { it },
            exit = slideOutVertically(LeafMotion.tool()) { it },
        ) {
            // Keep the last capture while the sheet slides away.
            var shown by remember { mutableStateOf<Capture>(Capture.Text) }
            if (capture != null) shown = capture
            AnimatedContent(
                targetState = done,
                // The form goes at once and the tick settles in as the sheet shrinks to it, so the two never overlap.
                transitionSpec = { fadeIn(LeafMotion.tool()) togetherWith ExitTransition.None using SizeTransform { _, _ -> LeafMotion.expressive() } },
                label = "sheet finish",
            ) { finished ->
                if (finished) {
                    SheetDone()
                } else {
                    SheetBody(shown, voice) { text, color, photo ->
                        finishing = true
                        done = true
                        onSave(text, color, photo)
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetDone() {
    val keyboard = LocalSoftwareKeyboardController.current
    val pop = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) {
        keyboard?.hide()
        pop.animateTo(1f, LeafMotion.fast())
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        Text("记下了", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SheetBody(capture: Capture, voice: VoiceSession, onSave: (String, NotionColor, Picture?) -> Unit) {
    var text by rememberSaveable(capture) { mutableStateOf("") }
    var color by rememberSaveable(capture) { mutableStateOf(NotionColor.Default) }
    val focus = remember { FocusRequester() }
    val photo = (capture as? Capture.Photo)?.picture
    val canSave = text.isNotBlank() || photo != null
    val save = { if (canSave) onSave(text, color, photo) }
    val isVoice = capture == Capture.Voice
    // Voice keeps the keyboard down: the hands are on the mic, and the field is only for fixing words.
    LaunchedEffect(capture) { if (photo == null && !isVoice) focus.requestFocus() }
    val heard = voice.state as? VoiceState.Heard
    LaunchedEffect(heard?.take) {
        if (heard != null) {
            text = joinTranscript(text, heard.text)
            voice.taken()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .navigationBarsPadding()
            .imePadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            when (capture) {
                Capture.Text -> "记一条涂鸦"
                Capture.Voice -> "说一句"
                is Capture.Photo -> "这张照片记成涂鸦"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        if (photo != null) {
            PictureView(photo, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(24.dp)))
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            val live = (voice.state as? VoiceState.Listening)?.text ?: (voice.state as? VoiceState.Finishing)?.text
            if (isVoice && !live.isNullOrEmpty()) {
                // What the system recognizer hears while held, after anything already in the field.
                Text(joinTranscript(text, live), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (text.isEmpty()) {
                Text(
                    when {
                        photo != null -> "配一句话（可以不写）"
                        isVoice -> "说的话会出现在这里，可以改"
                        else -> "想到什么写什么"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!(isVoice && !live.isNullOrEmpty())) BasicTextField(
                text,
                { text = it },
                Modifier.fillMaxWidth().focusRequester(focus),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )
        }
        if (photo == null) ColorDots(color, { color = it })
        if (isVoice) {
            VoiceControls(voice, canSave, save)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = save, enabled = canSave) { Text("记下") }
            }
        }
    }
}
