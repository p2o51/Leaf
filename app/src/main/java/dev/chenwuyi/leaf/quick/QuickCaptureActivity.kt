package dev.chenwuyi.leaf.quick

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.chenwuyi.leaf.LeafApplication
import dev.chenwuyi.leaf.data.voice.VoiceRecognizers
import dev.chenwuyi.leaf.ui.capture.Capture
import dev.chenwuyi.leaf.ui.capture.CaptureSheet
import dev.chenwuyi.leaf.ui.capture.VoiceSession
import dev.chenwuyi.leaf.ui.theme.LeafMotion
import dev.chenwuyi.leaf.ui.theme.LeafTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The capture sheet on its own, over whatever was on screen: opened from the tile, the launcher
 * shortcuts, the widget, sharing and selected text. It saves to the wall and goes away; Leaf itself
 * does not open.
 */
class QuickCaptureActivity : ComponentActivity() {
    private var capture by mutableStateOf<Capture?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        capture = QuickIntents.capture(intent) ?: return finish()
        val container = (application as LeafApplication).container
        setContent {
            LeafTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val voice = remember { VoiceSession(VoiceRecognizers.forScribbles(context), scope) }
                var saving by remember { mutableStateOf<Job?>(null) }
                DisposableEffect(Unit) { onDispose { voice.cancel() } }
                LaunchedEffect(capture) {
                    if (capture != Capture.Voice) voice.cancel()
                    if (capture == null) {
                        // A shared photo can only be read while this activity holds the grant, so the copy
                        // finishes before the activity does. A save first shows the sheet's tick; then the
                        // slide-out plays.
                        val saved = saving
                        saved?.join()
                        delay(if (saved != null) LeafMotion.SheetFinishMillis + SlideOutMillis else SlideOutMillis)
                        finish()
                    }
                }
                BackHandler(capture != null) { capture = null }
                CaptureSheet(
                    capture,
                    voice,
                    onDismiss = { capture = null },
                    onSave = { text, color, photo ->
                        // The sheet's own tick says 记下了, so there is no toast.
                        saving = container.scope.launch { container.repository.addScribble(text, color, photo) }
                        capture = null
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        QuickIntents.capture(intent)?.let { capture = it }
    }

    override fun finish() {
        super.finish()
        // The sheet already slid away; the window itself should not animate again.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

private const val SlideOutMillis = 260L
