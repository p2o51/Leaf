package dev.chenwuyi.leaf.quick

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import dev.chenwuyi.leaf.LeafApplication
import dev.chenwuyi.leaf.MainActivity
import dev.chenwuyi.leaf.R
import dev.chenwuyi.leaf.data.Scribble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The home-screen widget: the three newest scribbles and the two ways to add one. Plain RemoteViews,
 * so it needs no extra library; [WidgetUpdates] pushes a new copy whenever the wall changes.
 */
class LeafWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val container = (context.applicationContext as LeafApplication).container
        val pending = goAsync()
        container.scope.launch {
            try {
                render(context, manager, ids, WidgetLines.newest(container.repository.scribbles.first()))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, LeafWidget::class.java))

        fun render(context: Context, manager: AppWidgetManager, ids: IntArray, lines: List<String>) {
            if (ids.isEmpty()) return
            val views = views(context, lines)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        internal fun views(context: Context, lines: List<String>): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_wall)
            val rows = listOf(R.id.widget_line_1, R.id.widget_line_2, R.id.widget_line_3)
            rows.forEachIndexed { i, id ->
                val line = lines.getOrNull(i)
                views.setViewVisibility(id, if (line == null) View.GONE else View.VISIBLE)
                if (line != null) views.setTextViewText(id, line)
            }
            views.setViewVisibility(R.id.widget_empty, if (lines.isEmpty()) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(R.id.widget_lines, activity(context, 1, Intent(context, MainActivity::class.java)))
            views.setOnClickPendingIntent(R.id.widget_write, activity(context, 2, QuickIntents.write(context)))
            views.setOnClickPendingIntent(R.id.widget_speak, activity(context, 3, QuickIntents.speak(context)))
            return views
        }

        private fun activity(context: Context, code: Int, intent: Intent) = PendingIntent.getActivity(
            context,
            code,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Keeps placed widgets current while the app runs: every change to the wall, from typing or a pull. */
object WidgetUpdates {
    fun start(context: Context, scope: CoroutineScope, scribbles: Flow<List<Scribble>>) {
        val app = context.applicationContext
        scope.launch {
            scribbles.map { WidgetLines.newest(it) }.distinctUntilChanged().collect { lines ->
                val ids = LeafWidget.ids(app)
                if (ids.isNotEmpty()) LeafWidget.render(app, AppWidgetManager.getInstance(app), ids, lines)
            }
        }
    }
}

/** What the widget shows of the wall, kept apart so it can be tested. */
object WidgetLines {
    fun newest(scribbles: List<Scribble>, count: Int = 3): List<String> =
        scribbles.sortedByDescending { it.createdAt }.mapNotNull(::line).take(count)

    fun line(s: Scribble): String? {
        val text = s.text.trim().replace(Regex("\\s+"), " ")
        return when {
            s.photo != null -> if (text.isEmpty()) "照片" else "照片 · $text"
            text.isEmpty() -> null
            else -> text
        }
    }
}
