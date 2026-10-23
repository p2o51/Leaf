package dev.chenwuyi.leaf.ui.capture

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.ui.component.ColorDots
import dev.chenwuyi.leaf.ui.component.PictureView
import dev.chenwuyi.leaf.ui.theme.LeafMotion

/** What the bottom sheet is capturing. Voice is typed for now: transcription arrives in M1 with Relay. */
sealed interface Capture {
    data object Text : Capture
    data object Voice : Capture
    data class Photo(val picture: Picture) : Capture
}

/** One sheet for every way of adding a scribble: a line of text, a colour, and for photos the picture and a caption. */
@Composable
fun CaptureSheet(capture: Capture?, onDismiss: () -> Unit, onSave: (text: String, color: NotionColor, photo: Picture?) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(capture != null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim).clickable(interactionSource = null, indication = null, onClick = onDismiss))
        }
        AnimatedVisibility(
            capture != null,
            Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(LeafMotion.expressive()) { it },
            exit = slideOutVertically(LeafMotion.tool()) { it },
        ) {
            // Keep the last capture while the sheet slides away.
            var shown by remember { mutableStateOf<Capture>(Capture.Text) }
            if (capture != null) shown = capture
            SheetBody(shown, onSave)
        }
    }
}

@Composable
private fun SheetBody(capture: Capture, onSave: (String, NotionColor, Picture?) -> Unit) {
    var text by rememberSaveable(capture) { mutableStateOf("") }
    var color by rememberSaveable(capture) { mutableStateOf(NotionColor.Default) }
    val focus = remember { FocusRequester() }
    val photo = (capture as? Capture.Photo)?.picture
    val canSave = text.isNotBlank() || photo != null
    val save = { if (canSave) onSave(text, color, photo) }
    LaunchedEffect(capture) { if (photo == null) focus.requestFocus() }

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
                Capture.Voice -> "说一句（转写在下一步接上，先打字）"
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
            if (text.isEmpty()) {
                Text(if (photo != null) "配一句话（可以不写）" else "想到什么写什么", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BasicTextField(
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = save, enabled = canSave) { Text("记下") }
        }
    }
}
