package dev.chenwuyi.leaf.ui.essay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.ui.component.PictureView
import dev.chenwuyi.leaf.ui.theme.LeafMotion
import androidx.compose.material3.DropdownMenu

/**
 * The same three actions wherever a photo offers a menu: the ⋯ in the viewer and a long press in the page.
 * 标注 is the photo's caption in Notion.
 */
@Composable
internal fun PhotoMenuItems(hasCaption: Boolean, onCaption: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    DropdownMenuItem(
        text = { Text(if (hasCaption) "编辑标注" else "添加标注") },
        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
        onClick = onCaption,
    )
    DropdownMenuItem(
        text = { Text("保存图片") },
        leadingIcon = { Icon(Icons.Rounded.Download, null) },
        onClick = onSave,
    )
    DropdownMenuItem(
        text = { Text("删除图片", color = MaterialTheme.colorScheme.error) },
        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
        onClick = onDelete,
    )
}

/**
 * A photo on its own, filling the screen: pinch or double tap to zoom, ⋯ (or a long press) for its menu,
 * and its 标注 under it when it has one. [photo] null closes it.
 */
@Composable
internal fun PhotoViewer(
    photo: EssayBlock.Photo?,
    onDismiss: () -> Unit,
    onCaption: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    // Keeps the last photo on screen while the viewer animates away.
    var shown by remember { mutableStateOf(photo) }
    if (photo != null) shown = photo
    var editing by remember(photo?.id) { mutableStateOf(false) }
    var menu by remember(photo?.id) { mutableStateOf(false) }
    BackHandler(photo != null) { if (editing) editing = false else onDismiss() }

    AnimatedVisibility(
        photo != null,
        enter = fadeIn(LeafMotion.tool()) + scaleIn(LeafMotion.expressive(), initialScale = 0.92f),
        exit = fadeOut(LeafMotion.tool()) + scaleOut(LeafMotion.tool(), targetScale = 0.92f),
    ) {
        val current = shown ?: return@AnimatedVisibility
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ZoomablePicture(current, onLongPress = { menu = true })
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Disc(onDismiss) { Icon(Icons.Rounded.Close, "关闭", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                Box {
                    Disc({ menu = true }) { Icon(Icons.Rounded.MoreHoriz, "更多", tint = Color.White) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        PhotoMenuItems(
                            hasCaption = current.caption.isNotEmpty(),
                            onCaption = { menu = false; editing = true },
                            onSave = { menu = false; onSave() },
                            onDelete = { menu = false; onDelete() },
                        )
                    }
                }
            }
            CaptionArea(current.caption, editing, onCaption, onDone = { editing = false }, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun ZoomablePicture(photo: EssayBlock.Photo, onLongPress: () -> Unit) {
    var scale by remember(photo.id) { mutableFloatStateOf(1f) }
    var pan by remember(photo.id) { mutableStateOf(Offset.Zero) }
    val shownScale by animateFloatAsState(scale, LeafMotion.tool(), label = "zoom")
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(photo.id) {
                detectTransformGestures { _, move, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    pan = if (scale == 1f) Offset.Zero else pan + move
                }
            }
            .pointerInput(photo.id) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        pan = Offset.Zero
                    },
                    onLongPress = { onLongPress() },
                )
            },
    ) {
        PictureView(
            photo.picture,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = shownScale
                scaleY = shownScale
                translationX = pan.x
                translationY = pan.y
            },
            contentScale = ContentScale.Fit,
        )
    }
}

/** The 标注 under the photo: nothing when there is none, a field while it is being written. */
@Composable
private fun CaptionArea(caption: String, editing: Boolean, onCaption: (String) -> Unit, onDone: () -> Unit, modifier: Modifier) {
    if (caption.isEmpty() && !editing) return
    val focus = remember { FocusRequester() }
    LaunchedEffect(editing) { if (editing) focus.requestFocus() }
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (editing) {
            BasicTextField(
                caption,
                { onCaption(it.replace("\n", "")) },
                Modifier.fillMaxWidth().focusRequester(focus),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                cursorBrush = SolidColor(Color.White),
            )
            TextButton(onDone, Modifier.align(Alignment.End)) { Text("完成", color = Color.White) }
        } else {
            Text(caption, style = MaterialTheme.typography.bodyLarge, color = Color.White)
        }
    }
}

@Composable
private fun Disc(onClick: () -> Unit, icon: @Composable () -> Unit) {
    IconButton(onClick, Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))) { icon() }
}
