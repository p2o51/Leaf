package dev.chenwuyi.leaf.ui.essay

import dev.chenwuyi.leaf.data.EssayBlock

/**
 * A piece of the 写作台 (电脑模式) page: a run of text blocks edited together as one Markdown text, or a
 * block Leaf only shows (a photo, a file, a synced scribble, a rule, anything Notion holds more of than
 * Leaf can write back). Text runs sit between the shown blocks, so a photo never breaks the writing into
 * one field per paragraph, and the page as a whole is just the runs and the shown blocks in order.
 */
sealed interface DeskPart {
    val key: String

    data class Text(override val key: String, val blocks: List<EssayBlock.Paragraph>) : DeskPart
    data class Shown(val block: EssayBlock) : DeskPart {
        override val key: String get() = block.id
    }
}

/** One block parsed out of a run's text, and where its words sit in that text (for 来源 marks). */
data class DeskLine(val type: String, val text: String, val textStart: Int)

/** The run's text and, for each block in it, where that block's words start. */
data class DeskRendered(val text: String, val starts: List<Int>)

/**
 * Markdown in, Notion blocks out, for the 写作台. Pure, so the round trip is tested without Compose.
 *
 * A blank line ends a block, as in Markdown; a single line break stays inside the block (Notion keeps
 * it as a soft break). List items and headings start a block of their own even without a blank line,
 * so `- a` / `- b` on consecutive lines are two items. `#tag` without a space stays text: Leaf's
 * scribble tags are written that way.
 */
object DeskText {
    /** Block types the 写作台 edits as Markdown; everything else is shown, not edited. */
    val Editable = setOf("paragraph", "heading_1", "heading_2", "heading_3", "quote", "bulleted_list_item", "numbered_list_item")

    private val ListTypes = setOf("bulleted_list_item", "numbered_list_item")

    fun editable(block: EssayBlock): Boolean =
        block is EssayBlock.Paragraph && !block.fixed && block.type in Editable && roundTrips(block)

    /**
     * Whether the block reads back as itself from its Markdown. A paragraph whose words start with `# ` or
     * `- `, or a quote with a line starting `>`, would turn into another block (or lose a character) the
     * first time any block in its run is edited, so the 写作台 shows it, unedited, instead.
     */
    private fun roundTrips(block: EssayBlock.Paragraph): Boolean {
        if (block.text.isEmpty()) return true
        val lines = parse(prefix(block.type, 1) + block.text)
        return lines.size == 1 && lines[0].type == block.type && lines[0].text == block.text
    }

    /** The page as runs and shown blocks. A run always follows the last shown block, so there is somewhere to keep writing. */
    fun parts(blocks: List<EssayBlock>): List<DeskPart> {
        val out = mutableListOf<DeskPart>()
        var run = mutableListOf<EssayBlock.Paragraph>()
        var key = "run:start"
        for (b in blocks) {
            if (editable(b)) {
                run += b as EssayBlock.Paragraph
            } else {
                out += DeskPart.Text(key, run)
                out += DeskPart.Shown(b)
                run = mutableListOf()
                key = "run:after:${b.id}"
            }
        }
        out += DeskPart.Text(key, run)
        // An empty run between two shown blocks (or before the first) has nothing to edit; keep only the
        // last one, which is where new writing goes.
        return out.filterIndexed { i, p -> p !is DeskPart.Text || p.blocks.isNotEmpty() || i == out.lastIndex }
    }

    /** The page's blocks again, in order, from its parts. */
    fun blocks(parts: List<DeskPart>): List<EssayBlock> = parts.flatMap {
        when (it) {
            is DeskPart.Text -> it.blocks
            is DeskPart.Shown -> listOf(it.block)
        }
    }

    fun prefix(type: String, number: Int): String = when (type) {
        "heading_1" -> "# "
        "heading_2" -> "## "
        "heading_3" -> "### "
        "quote" -> "> "
        "bulleted_list_item" -> "- "
        "numbered_list_item" -> "$number. "
        else -> ""
    }

    fun render(blocks: List<EssayBlock.Paragraph>): DeskRendered {
        val sb = StringBuilder()
        val starts = ArrayList<Int>(blocks.size)
        var number = 0
        blocks.forEachIndexed { i, b ->
            if (i > 0) sb.append(if (b.type in ListTypes && blocks[i - 1].type in ListTypes) "\n" else "\n\n")
            number = if (b.type == "numbered_list_item") number + 1 else 0
            sb.append(prefix(b.type, number))
            starts += sb.length
            sb.append(b.text)
        }
        return DeskRendered(sb.toString(), starts)
    }

    private val Heading = Regex("^(#{1,3}) ")
    private val Bullet = Regex("^[-*+] ")
    private val Numbered = Regex("^\\d{1,3}[.)] ")
    private val Quote = Regex("^> ?")

    private fun startsBlock(line: String) = Heading.containsMatchIn(line) || Bullet.containsMatchIn(line) || Numbered.containsMatchIn(line)

    /** The blocks a run's text holds, in order, with where each one's words start in [text]. */
    fun parse(text: String): List<DeskLine> {
        val out = mutableListOf<DeskLine>()
        var type = ""
        var start = -1
        val words = StringBuilder()
        fun flush() {
            if (start >= 0) out += DeskLine(type, words.toString(), start)
            start = -1
            words.setLength(0)
        }
        var at = 0
        for (line in text.split('\n')) {
            val lineStart = at
            at += line.length + 1
            if (line.isBlank()) {
                flush()
                continue
            }
            if (start >= 0 && !startsBlock(line)) {
                // A soft break inside the block; a quote's later lines may repeat the marker.
                val m = if (type == "quote") Quote.find(line) else null
                words.append('\n').append(if (m != null) line.substring(m.value.length) else line)
                continue
            }
            flush()
            val (t, marker) = Heading.find(line)?.let { "heading_${it.groupValues[1].length}" to it.value.length }
                ?: Bullet.find(line)?.let { "bulleted_list_item" to it.value.length }
                ?: Numbered.find(line)?.let { "numbered_list_item" to it.value.length }
                ?: Quote.find(line)?.let { "quote" to it.value.length }
                ?: ("paragraph" to 0)
            type = t
            start = lineStart + marker
            words.append(line.substring(marker))
        }
        flush()
        return out
    }

    /**
     * The run's blocks after its text became [text]: unchanged blocks keep their ids, an edited one keeps
     * its id (so Notion updates it in place), new ones get [newId]. Returns the blocks and the ids that
     * are gone.
     */
    fun update(old: List<EssayBlock.Paragraph>, text: String, newId: () -> String): Pair<List<EssayBlock.Paragraph>, Set<String>> {
        val lines = parse(text)
        val pairs = match(old.map { it.type to it.text }, lines.map { it.type to it.text })
        val used = HashSet<Int>()
        val blocks = lines.mapIndexed { j, line ->
            val i = pairs[j]
            if (i != null) {
                used += i
                old[i].copy(text = line.text, type = line.type)
            } else {
                EssayBlock.Paragraph(newId(), line.text, line.type)
            }
        }
        val gone = old.indices.filter { it !in used }.map { old[it].id }.toSet()
        return blocks to gone
    }

    /**
     * For each new line, the old block it continues, or null for a new block. Equal lines are matched
     * first (longest common subsequence); in each gap between them the leftovers pair up in order, so a
     * paragraph that was edited keeps its block rather than becoming a delete plus a create.
     */
    fun <T> match(old: List<T>, new: List<T>): List<Int?> {
        val n = old.size
        val m = new.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (old[i] == new[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val result = arrayOfNulls<Int>(m)
        val anchors = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                old[i] == new[j] -> { anchors += i to j; i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> i++
                else -> j++
            }
        }
        anchors.forEach { (a, b) -> result[b] = a }
        var prevOld = -1
        var prevNew = -1
        for ((a, b) in anchors + (n to m)) {
            var x = prevOld + 1
            var y = prevNew + 1
            while (x < a && y < b) { result[y] = x; x++; y++ }
            prevOld = a
            prevNew = b
        }
        return result.toList()
    }
}

/** Sentence and paragraph spans for 专注, over a run's Markdown text. */
object DeskFocus {
    private fun endsSentence(text: String, i: Int): Boolean {
        val c = text[i]
        if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '…' || c == '\n') return true
        // A Latin full stop ends a sentence only before a space or the end, not inside 3.14 or e.g.
        return c == '.' && (i + 1 >= text.length || text[i + 1] == ' ' || text[i + 1] == '\n')
    }

    /** The sentence around [offset]: back to just after the previous end, on through its own end mark (and a closing quote). */
    fun sentence(text: String, offset: Int): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val at = offset.coerceIn(0, text.length)
        var a = at
        while (a > 0 && !endsSentence(text, a - 1)) a--
        while (a < text.length && text[a] == ' ') a++
        var b = at
        while (b < text.length && !endsSentence(text, b)) b++
        if (b < text.length && text[b] != '\n') b++
        while (b < text.length && text[b] in "”」』）)\"'") b++
        return a until b
    }

    /** The paragraph around [offset]: the block between blank lines (a list item is its own line). */
    fun paragraph(text: String, offset: Int): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val at = offset.coerceIn(0, text.length)
        val line = DeskText.parse(text).lastOrNull { it.textStart <= at }
        if (line == null) return 0 until 0
        val head = text.lastIndexOf('\n', line.textStart - 1) + 1
        return head until (line.textStart + line.text.length)
    }
}
