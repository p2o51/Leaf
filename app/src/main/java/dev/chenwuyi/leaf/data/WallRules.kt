package dev.chenwuyi.leaf.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class WallSort(val label: String) { Newest("最新在前"), Oldest("最早在前"), Color("按颜色") }

data class WallGroup(val key: String, val label: String, val scribbles: List<Scribble>)

/** Pure rules behind the strip and the full wall, kept out of Compose so they can be unit tested. */
object WallRules {
    /**
     * Deals scribbles into [rows] rows for the home strip, always into the row that is currently
     * shortest, so the three rows end at about the same place. Width is estimated from text length.
     */
    fun stripRows(scribbles: List<Scribble>, rows: Int = 3): List<List<Scribble>> {
        val out = List(rows) { mutableListOf<Scribble>() }
        val widths = IntArray(rows)
        for (s in scribbles) {
            val row = widths.indices.minBy { widths[it] }
            out[row] += s
            widths[row] += estimatedWidth(s)
        }
        return out
    }

    fun estimatedWidth(s: Scribble): Int = if (s.photo != null) 96 else minOf(s.text.length, 18) * 14 + 40

    fun sorted(scribbles: List<Scribble>, sort: WallSort): List<Scribble> = when (sort) {
        WallSort.Newest -> scribbles.sortedByDescending { it.createdAt }
        WallSort.Oldest -> scribbles.sortedBy { it.createdAt }
        WallSort.Color -> scribbles.sortedWith(compareBy<Scribble> { it.color.ordinal }.thenByDescending { it.createdAt })
    }

    /** Day groups for time sorts, colour groups for the colour sort. */
    fun groups(scribbles: List<Scribble>, sort: WallSort, zone: ZoneId = ZoneId.systemDefault()): List<WallGroup> {
        val ordered = sorted(scribbles, sort)
        return if (sort == WallSort.Color) {
            ordered.groupBy { it.color }.map { (c, items) -> WallGroup("c-${c.apiName}", c.label, items) }
        } else {
            ordered.groupBy { it.createdAt.atZone(zone).toLocalDate() }
                .map { (day, items) -> WallGroup("d-$day", dayLabel(day), items) }
        }
    }

    fun dayLabel(day: LocalDate): String = "${day.monthValue}月${day.dayOfMonth}日"

    /**
     * 复制到随笔: copies scribbles to the end of an essay, oldest first, as paragraphs in the scribble's
     * colour, each followed by an empty paragraph to write into. With no essay, starts a new untitled one.
     * They are copies; linking them as Notion synced blocks comes with M3.
     */
    fun copyInto(essay: Essay?, scribbles: List<Scribble>, now: Instant, newId: () -> String): Essay {
        val added = scribbles.sortedBy { it.createdAt }.filter { it.text.isNotBlank() }.flatMap {
            listOf(EssayBlock.Paragraph(newId(), it.text, color = it.color), EssayBlock.Paragraph(newId(), ""))
        }
        val base = essay ?: Essay(newId(), "", emptyList(), NotionColor.Default, null, pinned = false, editedAt = now)
        return base.copy(blocks = base.blocks + added, editedAt = now)
    }

    fun search(scribbles: List<Scribble>, query: String): List<Scribble> {
        val q = query.trim()
        return if (q.isEmpty()) scribbles else scribbles.filter { it.text.contains(q, ignoreCase = true) }
    }

    fun searchEssays(essays: List<Essay>, query: String): List<Essay> {
        val q = query.trim()
        val hits = if (q.isEmpty()) essays else essays.filter { it.title.contains(q, true) || it.previewText.contains(q, true) }
        return hits.sortedWith(compareByDescending<Essay> { it.pinned }.thenByDescending { it.editedAt })
    }
}

/** Which panes show. Tablets start as one full-width list and split only while an essay is open. */
enum class PaneLayout { ListOnly, Split, EditorOnly }

fun paneLayout(widthDp: Int, essayOpen: Boolean): PaneLayout = when {
    !essayOpen -> PaneLayout.ListOnly
    widthDp >= 840 -> PaneLayout.Split
    else -> PaneLayout.EditorOnly
}
