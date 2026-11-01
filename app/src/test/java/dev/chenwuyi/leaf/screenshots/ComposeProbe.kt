package dev.chenwuyi.leaf.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * What the newer Robolectric tests share: compose something, settle it, write its picture next to the
 * other shots, and read the semantics tree straight from the root views (the app has no ui-test
 * dependency). DrawerSettingsTest keeps its own copy of the same few lines.
 */
internal fun probe(name: String, content: @Composable () -> Unit): ComponentActivity {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    activity.setContent { content() }
    settleFrames()
    val view = activity.window.decorView
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    val dir = File("build/screenshots").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return activity
}

internal fun settleFrames() = repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400)) }

internal fun probeRoots(activity: ComponentActivity): List<ViewRootForTest> {
    fun walk(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { walk(v.getChildAt(it)) } else listOf(v)
    return walk(activity.window.decorView).filterIsInstance<ViewRootForTest>()
}

/** The merged tree, as TalkBack sees it, in drawing order. */
internal fun semanticsNodes(activity: ComponentActivity): List<SemanticsNode> =
    probeRoots(activity).flatMap { it.semanticsOwner.rootSemanticsNode.allNodes() }

private fun SemanticsNode.allNodes(): List<SemanticsNode> = listOf(this) + children.flatMap { it.allNodes() }

internal fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()

internal fun SemanticsNode.descriptions(): List<String> = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

internal fun SemanticsNode.clickLabel(): String? = config.getOrNull(SemanticsActions.OnClick)?.label

/** Taps the node through its click action, then lets the frame settle. */
internal fun SemanticsNode.tap() {
    val action = config.getOrNull(SemanticsActions.OnClick)?.action ?: error("not clickable: $this")
    action()
    settleFrames()
}
