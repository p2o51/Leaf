package dev.chenwuyi.leaf.ui.essay

import dev.chenwuyi.leaf.data.Essay
import dev.chenwuyi.leaf.data.EssayBlock
import dev.chenwuyi.leaf.data.NotionColor
import dev.chenwuyi.leaf.data.Picture
import dev.chenwuyi.leaf.data.Scribble

/**
 * What the editor's 插入 panel can add, in Craft's order: text styles, lists, then media. [type] is the
 * Notion block type it becomes; [glyph] is the tile's big letter, as Craft draws H1, H2, P.
 */
enum class InsertKind(val label: String, val type: String, val glyph: String) {
    Paragraph("正文", "paragraph", "P"),
    Title("大标题", "heading_1", "H1"),
    Heading("标题", "heading_2", "H2"),
    Subheading("小标题", "heading_3", "H3"),
    Quote("引用", "quote", "❝"),
    Bullet("列表", "bulleted_list_item", "•"),
    Numbered("编号", "numbered_list_item", "1."),
    Photo("照片", "image", ""),
    Scribble("涂鸦", "paragraph", ""),
    ;

    companion object {
        val TextStyles = listOf(Paragraph, Title, Heading, Subheading, Quote)
        val Lists = listOf(Bullet, Numbered)
    }
}

/** Pure block edits behind the editor, kept apart from Compose so they can be tested. */
object EditorEdits {
    /** List items continue as the same kind on Enter; everything else continues as a paragraph. */
    private val Continuing = setOf("bulleted_list_item", "numbered_list_item", "to_do")

    /** [blocks] after the block [anchorId], or at the end when there is none (nothing focused). */
    fun insertAfter(essay: Essay, anchorId: String?, blocks: List<EssayBlock>): Essay {
        val at = essay.blocks.indexOfFirst { it.id == anchorId }.takeIf { it >= 0 }?.plus(1) ?: essay.blocks.size
        return essay.copy(blocks = essay.blocks.take(at) + blocks + essay.blocks.drop(at))
    }

    /**
     * New text for a text block. An Enter typed into it splits the block there, like Craft and Notion:
     * the result's second value is the new block to focus, or null when no split happened. Pasted lines
     * each become a block of their own. Enter on an empty list item ends the list instead, turning it
     * into a paragraph.
     */
    fun typed(essay: Essay, blockId: String, text: String, newId: () -> String): Pair<Essay, String?> {
        val block = essay.blocks.firstOrNull { it.id == blockId } as? EssayBlock.Paragraph ?: return essay to null
        val lines = text.split('\n')
        if (lines.size == 1) return essay.replace(block.copy(text = text)) to null
        if (lines.all { it.isEmpty() } && lines.size == 2 && block.type in Continuing) {
            return essay.replace(block.copy(text = "", type = "paragraph")) to null
        }
        val type = if (block.type in Continuing) block.type else "paragraph"
        val next = lines.drop(1).map { EssayBlock.Paragraph(newId(), it, type) }
        return insertAfter(essay.replace(block.copy(text = lines.first())), block.id, next) to next.last().id
    }

    /** A block for a picked 插入 kind; [Photo] and [Scribble] are built from what was picked instead. */
    fun blank(kind: InsertKind, newId: () -> String): EssayBlock.Paragraph = EssayBlock.Paragraph(newId(), "", kind.type)

    /**
     * A scribble put into an essay: a copy in its colour, the same as 复制到随笔 (synced blocks are M3).
     * A photo scribble brings its picture only while it is still a local file Leaf can upload.
     */
    fun fromScribble(scribble: Scribble, newId: () -> String): EssayBlock? = when (val photo = scribble.photo) {
        null -> EssayBlock.Paragraph(newId(), scribble.text, color = scribble.color)
        is Picture.Local -> EssayBlock.Photo(newId(), photo, scribble.text)
        else -> scribble.text.takeIf { it.isNotBlank() }?.let { EssayBlock.Paragraph(newId(), it, color = scribble.color) }
    }

    /** Scribbles worth offering in the picker, newest first. */
    fun pickable(scribbles: List<Scribble>): List<Scribble> =
        scribbles.filter { it.text.isNotBlank() || it.photo is Picture.Local }.sortedByDescending { it.createdAt }

    fun remove(essay: Essay, blockId: String): Essay = essay.copy(blocks = essay.blocks.filter { it.id != blockId })

    /** The block before [blockId], to move the cursor to when it is removed. */
    fun before(essay: Essay, blockId: String): String? {
        val at = essay.blocks.indexOfFirst { it.id == blockId }
        return if (at > 0) essay.blocks[at - 1].id else null
    }

    /** Turns a text block into another text kind (样式), keeping its words and colour. */
    fun restyle(essay: Essay, blockId: String, type: String): Essay {
        val block = essay.blocks.firstOrNull { it.id == blockId } as? EssayBlock.Paragraph ?: return essay
        return essay.replace(block.copy(type = type))
    }

    fun recolor(essay: Essay, blockId: String, color: NotionColor): Essay {
        val block = essay.blocks.firstOrNull { it.id == blockId } as? EssayBlock.Paragraph ?: return essay
        return essay.replace(block.copy(color = color))
    }

    /** The number a numbered item shows: its place in the run of numbered items it belongs to. */
    fun numberOf(essay: Essay, blockId: String): Int {
        val at = essay.blocks.indexOfFirst { it.id == blockId }
        if (at < 0) return 1
        var n = 1
        var i = at - 1
        while (i >= 0 && (essay.blocks[i] as? EssayBlock.Paragraph)?.type == "numbered_list_item") { n++; i-- }
        return n
    }

    private fun Essay.replace(block: EssayBlock): Essay = copy(blocks = blocks.map { if (it.id == block.id) block else it })

    /** Equal for the editor's purposes: what Room sends back after our own write differs only in time. */
    fun sameContent(a: Essay, b: Essay): Boolean = a.copy(editedAt = b.editedAt, storedPreview = b.storedPreview) == b
}
