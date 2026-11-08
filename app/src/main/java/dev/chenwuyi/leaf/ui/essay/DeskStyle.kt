package dev.chenwuyi.leaf.ui.essay

/**
 * 文风检查, after iA Writer's Style Check but for Chinese: it marks words a careful writer would look at
 * twice and never rewrites them. Every rule is a word list or a fixed-size pattern, never a free regular
 * expression, so a check over a long essay stays well under a frame (iA limits its custom rules for the
 * same reason).
 */
enum class StyleRule(val label: String) {
    Filler("填充词：进行、其实、基本上…"),
    Degree("程度副词：非常、十分、极其…"),
    ManyDe("一句里「的」超过 3 个"),
    Long("长句：一句超过 60 字"),
    Punct("中文里夹了半角标点"),
}

/** [start] until [end] in the run's text. [Long] marks a whole sentence; the others mark the words. */
data class StyleHit(val start: Int, val end: Int, val rule: StyleRule)

object DeskStyle {
    val Fillers = listOf("某种程度上", "总的来说", "一般来说", "事实上", "可以说", "基本上", "其实", "进行", "的话")
    val Degrees = listOf("非常", "十分", "极其", "特别", "相当", "超级")
    private const val LongSentence = 60
    private const val DeLimit = 3
    private val HalfPunct = ",;:?!"

    private fun isHan(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿'

    fun check(text: String, rules: Set<StyleRule>): List<StyleHit> {
        if (text.isEmpty() || rules.isEmpty()) return emptyList()
        val hits = mutableListOf<StyleHit>()
        fun words(list: List<String>, rule: StyleRule) {
            for (w in list) {
                var i = text.indexOf(w)
                while (i >= 0) {
                    hits += StyleHit(i, i + w.length, rule)
                    i = text.indexOf(w, i + w.length)
                }
            }
        }
        if (StyleRule.Filler in rules) words(Fillers, StyleRule.Filler)
        if (StyleRule.Degree in rules) words(Degrees, StyleRule.Degree)
        if (StyleRule.ManyDe in rules || StyleRule.Long in rules) {
            var i = 0
            while (i < text.length) {
                val s = DeskFocus.sentence(text, i)
                if (s.isEmpty()) { i++; continue }
                val body = text.substring(s.first, s.last + 1)
                if (StyleRule.ManyDe in rules && body.count { it == '的' } > DeLimit) {
                    body.forEachIndexed { k, c -> if (c == '的') hits += StyleHit(s.first + k, s.first + k + 1, StyleRule.ManyDe) }
                }
                if (StyleRule.Long in rules && !body.startsWith("#") && body.count { !it.isWhitespace() } > LongSentence) {
                    hits += StyleHit(s.first, s.last + 1, StyleRule.Long)
                }
                i = s.last + 2
            }
        }
        if (StyleRule.Punct in rules) {
            for (k in text.indices) {
                if (text[k] !in HalfPunct) continue
                val before = k > 0 && isHan(text[k - 1])
                val after = k + 1 < text.length && isHan(text[k + 1])
                if (before || after) hits += StyleHit(k, k + 1, StyleRule.Punct)
            }
        }
        return hits.sortedBy { it.start }
    }
}

/**
 * 来源 (after iA Writer's Authorship): which words were typed here and which came in pasted, so 51 can see
 * at a glance what is their own. Kept on this device per essay, by block, and never written into Notion.
 */
enum class Origin(val code: Char) { Typed('T'), Pasted('P');
    companion object { fun of(code: Char) = entries.firstOrNull { it.code == code } ?: Typed }
}

/** A run of non-typed words inside one block's text. */
data class OriginSpan(val start: Int, val end: Int, val origin: Origin)

object DeskOrigins {
    /** The origins after an edit turned [before] into [after]; the inserted characters are [inserted]. */
    fun splice(origins: List<Origin>, before: String, after: String, inserted: Origin): List<Origin> {
        var p = 0
        while (p < before.length && p < after.length && before[p] == after[p]) p++
        var s = 0
        while (s < before.length - p && s < after.length - p && before[before.length - 1 - s] == after[after.length - 1 - s]) s++
        val added = after.length - p - s
        val keep = origins.take(p) + List(added) { inserted } + origins.drop(before.length - s)
        return if (keep.size == after.length) keep else List(after.length) { keep.getOrElse(it) { Origin.Typed } }
    }

    /** How many characters an edit inserted, to tell a paste from typing when the keyboard did not say. */
    fun insertedLength(before: String, after: String): Int {
        var p = 0
        while (p < before.length && p < after.length && before[p] == after[p]) p++
        var s = 0
        while (s < before.length - p && s < after.length - p && before[before.length - 1 - s] == after[after.length - 1 - s]) s++
        return after.length - p - s
    }

    /** Spans for one block whose words sit at [start] in a run whose characters have [origins]. */
    fun spans(origins: List<Origin>, start: Int, length: Int): List<OriginSpan> {
        val out = mutableListOf<OriginSpan>()
        var k = 0
        while (k < length) {
            val o = origins.getOrElse(start + k) { Origin.Typed }
            if (o == Origin.Typed) { k++; continue }
            var e = k
            while (e < length && origins.getOrElse(start + e) { Origin.Typed } == o) e++
            out += OriginSpan(k, e, o)
            k = e
        }
        return out
    }

    /** Origins for a run's text, from each block's stored spans laid at its start. */
    fun lay(length: Int, starts: List<Int>, spans: List<List<OriginSpan>>): List<Origin> {
        val out = MutableList(length) { Origin.Typed }
        starts.forEachIndexed { i, start ->
            for (span in spans.getOrElse(i) { emptyList() }) {
                for (k in span.start until span.end) if (start + k in 0 until length) out[start + k] = span.origin
            }
        }
        return out
    }

    /** One line per block: id, then start-end-code triples. Plain text, so it needs no JSON on the JVM tests. */
    fun encode(byBlock: Map<String, List<OriginSpan>>): String =
        byBlock.filterValues { it.isNotEmpty() }.entries.joinToString("\n") { (id, spans) ->
            id + "\t" + spans.joinToString(" ") { "${it.start}-${it.end}-${it.origin.code}" }
        }

    fun decode(text: String): Map<String, List<OriginSpan>> = text.lineSequence().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) return@mapNotNull null
        val spans = line.substring(tab + 1).split(' ').mapNotNull { part ->
            val bits = part.split('-')
            if (bits.size != 3) return@mapNotNull null
            val a = bits[0].toIntOrNull() ?: return@mapNotNull null
            val b = bits[1].toIntOrNull() ?: return@mapNotNull null
            OriginSpan(a, b, Origin.of(bits[2].firstOrNull() ?: 'T'))
        }
        line.substring(0, tab) to spans
    }.toMap()
}
