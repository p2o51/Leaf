package dev.chenwuyi.leaf.data

import java.text.Collator
import java.util.Locale

/** What home shows: everything, one colour, or one tag. Picked in the drawer. */
sealed interface HomeFilter {
    data object All : HomeFilter
    data class ByColor(val color: NotionColor) : HomeFilter
    data class ByTag(val name: String) : HomeFilter
}

/**
 * Tags come from two places, because Notion can only hold them on database rows: an essay's tags are its
 * 标签 multi-select, a scribble's are the #words in its text (a scribble is a block, and blocks have no
 * properties). Both read the same in the drawer, and the #words stay visible and searchable in Notion.
 */
object TagRules {
    // A tag runs from # to the next space or punctuation; letters in any script, digits, _ and - count.
    // Chinese needs no space before it ("灯光#东京"), but a # right after a Latin letter, digit or slash
    // is not one: "C#", a link's "/#token".
    private val hashTag = Regex("""(?<![A-Za-z0-9/&#])#([\p{L}\p{N}_\-]+)""")

    fun inText(text: String): List<String> = hashTag.findAll(text).map { it.groupValues[1] }.distinct().toList()

    fun of(scribble: Scribble): List<String> = inText(scribble.text)

    /** Every tag in use, each once, in dictionary order (pinyin order for Chinese). */
    fun all(scribbles: List<Scribble>, essays: List<Essay>): List<String> {
        val collator = Collator.getInstance(Locale.CHINA)
        return (scribbles.flatMap(::of) + essays.flatMap { it.tags }).distinct().sortedWith(collator)
    }

    /** The colors something is actually in, in Notion's order; plain (default) is not a filter. */
    fun colorsInUse(scribbles: List<Scribble>, essays: List<Essay>): List<NotionColor> {
        val used = scribbles.map { it.color }.toSet() + essays.map { it.color }
        return NotionColor.entries.filter { it != NotionColor.Default && it in used }
    }

    fun matches(scribble: Scribble, filter: HomeFilter): Boolean = when (filter) {
        HomeFilter.All -> true
        is HomeFilter.ByColor -> scribble.color == filter.color
        is HomeFilter.ByTag -> filter.name in of(scribble)
    }

    fun matches(essay: Essay, filter: HomeFilter): Boolean = when (filter) {
        HomeFilter.All -> true
        is HomeFilter.ByColor -> essay.color == filter.color
        is HomeFilter.ByTag -> filter.name in essay.tags
    }
}
