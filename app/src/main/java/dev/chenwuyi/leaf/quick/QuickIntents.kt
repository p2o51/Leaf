package dev.chenwuyi.leaf.quick

import android.content.Context
import android.content.Intent
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.ui.capture.Capture

/** The ways into quick capture: the tile, the launcher shortcuts, the widget, sharing and selected text. */
object QuickIntents {
    const val ACTION_WRITE = "dev.chenwuyi.leaf.action.WRITE"
    const val ACTION_SPEAK = "dev.chenwuyi.leaf.action.SPEAK"

    fun write(context: Context) = Intent(context, QuickCaptureActivity::class.java).setAction(ACTION_WRITE)
    fun speak(context: Context) = Intent(context, QuickCaptureActivity::class.java).setAction(ACTION_SPEAK)

    fun capture(intent: Intent): Capture? = capture(
        action = intent.action,
        type = intent.type,
        text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString(),
        subject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
        stream = intent.streamUri()?.toString(),
    )

    /** Kept free of Android types so the rules can be tested without a device. */
    fun capture(action: String?, type: String?, text: String?, subject: String?, stream: String?): Capture? = when (action) {
        ACTION_WRITE -> Capture.Text()
        ACTION_SPEAK -> Capture.Voice
        Intent.ACTION_PROCESS_TEXT -> text?.trim()?.takeIf { it.isNotEmpty() }?.let { Capture.Text(it) }
        Intent.ACTION_SEND -> when {
            type?.startsWith("image/") == true && stream != null -> Capture.Photo(Picture.Local(stream), text?.trim().orEmpty())
            else -> sharedText(text, subject)?.let { Capture.Text(it) }
        }
        else -> null
    }

    /**
     * A shared web page usually comes as a title in the subject and a link in the text; keep both,
     * title first, unless one already contains the other.
     */
    fun sharedText(text: String?, subject: String?): String? {
        val body = text?.trim().orEmpty()
        val title = subject?.trim().orEmpty()
        val joined = when {
            title.isEmpty() || body.contains(title) -> body
            body.isEmpty() || title.contains(body) -> title
            else -> "$title\n$body"
        }
        return joined.takeIf { it.isNotEmpty() }
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): android.net.Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM)
}
